package com.sh7411usa.jrelay.sms;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.mms.MmsSender;
import com.sh7411usa.jrelay.util.MessageSalt;
import com.sh7411usa.jrelay.util.NotificationHelper;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RateLimitConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class SmsSendService extends Service {

    private static final String TAG = "SmsSendService";
    /** Cushion added past a held row's exact release time, so the wait doesn't wake a few millis early and find it still held. */
    private static final long HOLD_WAIT_MARGIN_MILLIS = 250L;
    /** Upper bound on one hold-wait sleep slice, so a newly enqueued COMMAND/REPLY row is never stuck behind the full window. */
    private static final long HOLD_WAIT_SLICE_MILLIS = 5_000L;
    /**
     * Upper bound on how long {@link #drainAll} will stay alive at an otherwise-empty exit point
     * waiting for outstanding sent-intent results before returning anyway, so a genuinely lost
     * result (radio never calls back) can never hang the drain forever. Deliberately NOT the same
     * constant as the staleness window a handed-off row is abandoned by: that answers "when do we
     * give up on this row," a multi-minute question; this answers "how long can a sent-intent
     * plausibly take to fire," and the telephony stack's own internal retry is seconds, not
     * minutes. Keep these two separate — unifying them re-introduces the failure this constant's
     * short value exists to avoid (see {@link #waitForOutstandingResults}).
     */
    private static final long OUTSTANDING_RESULTS_WAIT_MILLIS = 60 * 1000L;
    /**
     * How far back message_log history is kept before {@link MessageRepository#pruneOlderThan} is
     * applied to it, once per drain. 90 days: long enough that the dashboard's Recent Activity feed,
     * a member's activity feed, and {@code lastActivityForMember} still show a genuinely useful
     * window (a season of a group's real history) for a host reviewing "who's been active lately,"
     * while short enough that row growth stays bounded — at ~101 rows/relayed post, even a busy
     * group settles into roughly a constant-size table instead of the unbounded ~737k rows/year an
     * unpruned log accumulates. Costs: any activity older than 90 days is gone for good; a host who
     * wants a longer or shorter window has no way to change this without a code edit (no Prefs
     * setting added here — flagged separately as a possible follow-up).
     */
    private static final long MESSAGE_LOG_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000L;

    /**
     * True while a drain loop is running. drainAll() now only returns once the outbox is fully
     * empty (nothing pending, nothing held), so without this guard every start() call — there are
     * 13 call sites, one per inbound post/command — would spawn its own permanent burst/wait loop,
     * multiplying the effective send rate by however many are alive at once. Safe to suppress a
     * redundant start(): the one already running re-queries the outbox on every loop iteration, so
     * it will pick up whatever the suppressed call was for — except for the narrow race
     * {@link #RERUN} exists to close (see below). Always cleared unconditionally in the drain
     * thread's {@code finally}, so a crash can never strand it set.
     */
    private static final AtomicBoolean DRAINING = new AtomicBoolean(false);

    /**
     * Set when a start() is suppressed by an in-progress drain, consumed by that drain right after
     * it releases {@link #DRAINING}. Closes the gap between the drain's last "anything left?" check
     * and {@code DRAINING.set(false)}: a start() landing in that gap is suppressed (DRAINING is
     * still true) but its rows would otherwise never get drained until some unrelated later message
     * happened to arrive. Consuming it re-acquires and loops back into {@code drainAll()} on this
     * SAME worker thread, rather than re-entering through {@link #start(Context)} /
     * {@code startService}: this runs with no broadcast or activity cover, and on API 26+ a
     * background process is not permitted to call {@code startService} — it throws
     * {@link IllegalStateException} and would kill the relay mid-fan-out, exactly the outcome this
     * guarantee exists to prevent. Either the re-acquire CAS below succeeds and the same thread
     * drains again, or it fails because another drain has since started and will observe the same
     * work — either way nothing is silently stranded. No spin risk: only ever set by a real start()
     * call, and compareAndSet consumes it exactly once.
     */
    private static final AtomicBoolean RERUN = new AtomicBoolean(false);

    /**
     * The startId of the most recent onStartCommand call, suppressed or not. The running drain
     * stops the service against THIS id, not its own — stopping against its own id would tear the
     * service down (via stopSelf's "only if this is the most recent id" rule) while a later,
     * suppressed start's work is still being relied upon to have been folded into this same drain.
     */
    private static volatile int latestStartId;

    public static void start(Context context) {
        Intent intent = new Intent(context, SmsSendService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // Converts a total relay outage into one missed drain trigger: the enqueued rows are
            // still there and the next inbound message will start a drain. Logged at ERROR because
            // nothing else would ever surface it on an unattended phone.
            Log.e(TAG, "Could not start send service", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must be the very first thing done on every path, including the suppressed one below:
        // start() calls startForegroundService on API 26+, which requires startForeground() to be
        // reached within ~5 seconds or the system kills the app. Calling it again when already
        // foregrounded is harmless and just refreshes the notification.
        NotificationHelper.ensureSendChannel(this);
        startForeground(NotificationHelper.FOREGROUND_NOTIFICATION_ID,
                NotificationHelper.buildSendNotification(this, getString(R.string.notif_send_idle)));

        latestStartId = startId;
        if (!DRAINING.compareAndSet(false, true)) {
            // Do NOT stopSelf here: this id is always the most recent one delivered, so stopping
            // against it would tear the service down out from under the drain that's already
            // running. RERUN records that this start's work still needs to be observed.
            RERUN.set(true);
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                boolean again = true;
                // Runs resetOrphanedSending() on the first iteration only — "once per
                // drain-starting onStartCommand, before the loop" — but now INSIDE the same
                // try/finally that clears DRAINING, not before it. It used to run ahead of that
                // try: a throw there left DRAINING permanently true with no owner, since every
                // later start() would suppress into RERUN, which only this (now-dead) thread
                // could ever consume. Keeping it inside means the catch below and the finally's
                // DRAINING.set(false) both still fire if it throws.
                boolean first = true;
                while (again) {
                    try {
                        if (first) {
                            // A prior process death mid-drain leaves rows stuck in SENDING
                            // forever, since nothing else ever moves them back to PENDING.
                            int resetCount = new OutboxRepository(this).resetOrphanedSending();
                            if (resetCount > 0) {
                                Log.i(TAG, "Reset " + resetCount + " orphaned SENDING row(s) to PENDING");
                            }
                            // Once per drain-starting onStartCommand, same as resetOrphanedSending
                            // above: bulk housekeeping that must never run per-message (see
                            // MessageRepository#pruneOlderThan's javadoc). Runs here, on this
                            // background thread, before drainAll() ever draws a burst — not
                            // interleaved between bursts — so the DELETE's cost is paid once up
                            // front per drain instead of pushing back the pacing of real sends
                            // mid-burst.
                            long cutoff = System.currentTimeMillis() - MESSAGE_LOG_RETENTION_MILLIS;
                            int prunedCount = new MessageRepository(this).pruneOlderThan(cutoff);
                            if (prunedCount > 0) {
                                Log.i(TAG, "Pruned " + prunedCount + " message_log row(s) older than retention window");
                            }
                            first = false;
                        }
                        drainAll();
                    } catch (Throwable t) {
                        // Was previously uncaught: any SQLiteException (disk full, lock
                        // contention with the receiver thread), a notify() failure, or a throw
                        // from SentReceiver.handleFailure (called out of sendOne's catch) killed
                        // this whole thread mid-fan-out. START_NOT_STICKY means nothing restarts
                        // it, so the remaining recipients just sat there until an unrelated
                        // inbound SMS happened to trigger a fresh start(). Logged at ERROR since
                        // nothing else surfaces this on an unattended phone. Deliberately does
                        // NOT loop back into drainAll() immediately: a DB that just threw is
                        // likely to throw again on the very next call, and an immediate retry
                        // here would spin. Falling through to the same RERUN check every normal
                        // pass already uses means this only retries if a start() has genuinely
                        // arrived with new work (or already had one queued) — bounded by the same
                        // demand-driven re-entry as any other pass, not a busy loop.
                        Log.e(TAG, "Drain thread crashed; outbox will resume on next trigger", t);
                    } finally {
                        DRAINING.set(false);
                    }
                    // Re-check strictly after the clear, then re-acquire. Re-entering in this
                    // thread rather than via startService: a background app cannot call
                    // startService on API 26+, and this runs with no broadcast or activity cover.
                    again = RERUN.compareAndSet(true, false) && DRAINING.compareAndSet(false, true);
                }
            } finally {
                // Runs exactly once, last, with no drain outstanding — after the loop above has
                // already released DRAINING for good, so a start landing here starts (or joins)
                // a fresh drain rather than racing this stopSelf. stopSelfResult has the same
                // "only if this is the most recent id" semantics as stopSelf, but reports whether
                // it actually won, so we test first and demote only if we are really stopping.
                // It returns false only when a start lands AFTER this volatile read; a start
                // landing before it has already overwritten latestStartId, so we stop against
                // that newer id here and the new drain loses foreground standing until its own
                // start() re-promotes it. That remaining window is known and accepted: it's
                // microseconds wide, no message is lost or duplicated, and it self-heals on the
                // next start() — closing it fully would need a teardown handoff owned by whoever
                // holds DRAINING, out of scope for this fix.
                if (stopSelfResult(latestStartId)) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);
                }
            }
        }).start();
        return START_NOT_STICKY;
    }

    /**
     * Sending is global: one shared burst/wait/microspacing cadence drains the whole outbox, not
     * one per recipient. Before every burst is drawn, any RELAY rows whose coalescing hold has
     * elapsed are folded together by {@link #mergeReleasedRelayRows}; when nothing is sendable but
     * rows are still held, this loops back after sleeping until the earliest one releases, on this
     * same worker thread, instead of returning.
     */
    private void drainAll() {
        OutboxRepository outbox = new OutboxRepository(this);
        MessageRepository messageRepository = new MessageRepository(this);
        MemberRepository memberRepository = new MemberRepository(this);
        Prefs prefs = new Prefs(this);
        RateLimitConfig config = RateLimitConfig.groupDefault(prefs);
        Random random = new Random();
        SmsManager smsManager = SmsManager.getDefault();
        boolean shuffle = prefs.isDeliveryShuffleEnabled();

        try {
            boolean firstBurst = true;
            while (true) {
                if (prefs.isPaused()) {
                    return;
                }

                mergeReleasedRelayRows(outbox, prefs, smsManager);

                // countPending() includes held rows; countHolding() is how many of those are
                // currently held. When every pending row is held, nothing is actually sendable —
                // skip straight to the hold wait instead of computing a burst size and publishing
                // a "next burst" countdown that can't produce anything.
                int pending = outbox.countPending();
                int holding = outbox.countHolding();
                // Once per loop iteration, not per message: reuses the counts already read above,
                // no extra DB query. "Burst scheduled" mirrors the gate waitBeforeNextBurst checks
                // below, without needing burstSize itself.
                boolean burstScheduled = pending > holding
                        && config.staggeringEnabled && (!firstBurst || config.initialDelayEnabled);
                NotificationHelper.updateSendNotification(this, getString(
                        burstScheduled ? R.string.notif_send_status_burst : R.string.notif_send_status,
                        pending, holding));
                if (pending <= holding) {
                    if (holding <= 0) {
                        // Nothing pending, nothing held — but a result from an earlier send in
                        // this drain may still be in flight. Stay up until it lands (or the wait
                        // gives up) so this drain's own DRAINING/foreground standing is still
                        // held when SentReceiver's start() call needs it, per waitForOutstandingResults.
                        waitForOutstandingResults(outbox);
                        return;
                    }
                    waitForHold(outbox);
                    firstBurst = true;
                    continue;
                }

                int burstSize = nextBurstSize(outbox, config, random);
                if (config.staggeringEnabled && (!firstBurst || config.initialDelayEnabled)) {
                    waitBeforeNextBurst(outbox, config, random, burstSize);
                }
                firstBurst = false;

                List<OutboxRepository.OutboxItem> burst = outbox.takeBurst(burstSize, shuffle);
                if (burst.isEmpty()) {
                    if (outbox.countHolding() <= 0) {
                        // Same reasoning as the other "nothing pending, nothing held" exit above.
                        waitForOutstandingResults(outbox);
                        return;
                    }
                    waitForHold(outbox);
                    firstBurst = true;
                    continue;
                }

                sendBurst(smsManager, outbox, messageRepository, memberRepository, prefs, config, random, burst);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            SendQueueStatus.clear();
        }
    }

    /**
     * Folds RELAY rows whose coalescing hold has elapsed into as few outgoing bodies as possible,
     * per recipient, so the upcoming burst draw sees merged rows instead of individual ones.
     * No-op when the coalescing window is off: {@link OutboxRepository#takeReleasedRelayRows()} is
     * never even called, and coalescing itself never holds a row in that case. That does NOT mean
     * no rows can be held, though — {@code hold_until} is no longer coalescing's alone as of 5.4:
     * retry backoff sets it too, so a backed-off retry can hold a row for 30s-5min regardless of
     * this setting.
     */
    private void mergeReleasedRelayRows(OutboxRepository outbox, Prefs prefs, SmsManager smsManager) {
        if (prefs.getCoalesceWindowSeconds() == 0) {
            return;
        }
        List<OutboxRepository.OutboxItem> released = outbox.takeReleasedRelayRows();
        if (released.isEmpty()) {
            return;
        }

        Map<String, List<MessageMerger.Row>> byRecipient = new LinkedHashMap<>();
        for (OutboxRepository.OutboxItem item : released) {
            byRecipient.computeIfAbsent(item.phoneE164, k -> new ArrayList<>())
                    .add(new MessageMerger.Row(item.id, item.body));
        }

        int maxSegments = prefs.getMaxMergedSegments();
        // Counts segments on a salt-REPRESENTATIVE body, not the raw candidate: applyZwsp injects
        // U+200B, which is outside GSM-7 and forces the whole message to UCS-2 (70 chars/segment
        // instead of 160). Counting the unsalted body would under-count segments for any body that
        // ends up salted, so the cap the user configures wouldn't be the cap they actually get.
        // This is only ever used to size merge groups; the real send-time salt is applied fresh,
        // once, in sendOne — this call's random hex/zwsp output is discarded.
        MessageMerger.SegmentCounter counter =
                body -> smsManager.divideMessage(MessageSalt.applySendTime(prefs, body)).size();

        for (List<MessageMerger.Row> rows : byRecipient.values()) {
            List<MessageMerger.Merged> merges = MessageMerger.merge(rows, maxSegments, counter);
            for (MessageMerger.Merged merged : merges) {
                if (merged.mergedRowIds.isEmpty()) {
                    continue;   // nothing folded into this row; its hold has already elapsed, so
                                // it is already drawable and rewriting its body to itself would be
                                // a wasted write
                }
                // Single atomic write: applies the merge and skips it if the kept row (or any
                // merged-away row) is no longer PENDING, instead of a separate
                // update-then-mark-merged pair that a concurrent drainer could interleave with.
                outbox.applyMerge(merged.keepRowId, merged.body, merged.mergedRowIds);
            }
        }
    }

    /**
     * Sleeps on this worker thread until the earliest held row's hold elapses (plus a small
     * margin), in slices of at most {@link #HOLD_WAIT_SLICE_MILLIS} so the outer loop can re-check
     * for sendable rows. A COMMAND/REPLY row (or anything else) enqueued as PENDING-and-not-held
     * while we're waiting makes {@code countPending() > countHolding()} true again, and we return
     * immediately instead of sitting out the rest of the coalescing window. Caller has already
     * confirmed {@code countHolding() > 0}. Uses the same {@link Thread#sleep} +
     * propagate-on-interrupt pattern as {@link #waitBeforeNextBurst}: no new thread, no
     * alarm/scheduler, and an {@link InterruptedException} bails out of the wait and out of
     * {@link #drainAll} exactly as it already does for the inter-burst wait.
     */
    private void waitForHold(OutboxRepository outbox) throws InterruptedException {
        long deadline = outbox.earliestHoldUntil() + HOLD_WAIT_MARGIN_MILLIS;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            if (outbox.countPending() > outbox.countHolding()) {
                return;
            }
            Thread.sleep(Math.min(remaining, HOLD_WAIT_SLICE_MILLIS));
        }
    }

    /**
     * Sleeps on this worker thread, in slices of {@link #HOLD_WAIT_SLICE_MILLIS}, for as long as
     * {@link OutboxRepository#countAwaitingResults()} is nonzero, bounded by
     * {@link #OUTSTANDING_RESULTS_WAIT_MILLIS}. Called only at drainAll's "nothing pending, nothing
     * held" exits, right before the return each already has.
     * <p>
     * The point is to keep this drain's DRAINING hold (and the foreground service it implies) up
     * until any send this drain already started has actually resolved. A tail result — success,
     * permanent failure, or a retry requeue — reports through SentReceiver, which calls
     * {@link #start(Context)} to make sure the retried row gets picked up. If this drain has
     * already returned and the service demoted by then, that start() is a background
     * startForegroundService call, which throws on API 26+ and is swallowed, stranding the retry
     * until some unrelated trigger arrives. Staying up here means that call instead lands while
     * DRAINING is still true, so it is suppressed into {@link #RERUN} and consumed by the same
     * onStartCommand loop exactly as any other suppressed start — no change needed there.
     * <p>
     * Mirrors {@link #waitForHold}'s early-out immediately above: {@code countAwaitingResults()}
     * can include rows an earlier, already-dead process handed off and never resolved, not just
     * this drain's own in-flight sends, so it must not be trusted to fall to zero promptly. Live
     * traffic arriving mid-wait (real pending-and-not-held work) is what actually matters, and is
     * checked every slice; as soon as it shows up this returns immediately instead of sitting out
     * the rest of the wait, falling into drainAll's existing return — whichever start() enqueued
     * that work has already set {@link #RERUN}, so it re-enters drainAll on the same thread right
     * after, and any results still outstanding are covered on that next pass.
     */
    private void waitForOutstandingResults(OutboxRepository outbox) throws InterruptedException {
        long deadline = System.currentTimeMillis() + OUTSTANDING_RESULTS_WAIT_MILLIS;
        while (outbox.countAwaitingResults() > 0 && System.currentTimeMillis() < deadline) {
            if (outbox.countPending() > outbox.countHolding()) {
                return;   // real work arrived; RERUN re-enters drainAll and covers the results next pass
            }
            Thread.sleep(HOLD_WAIT_SLICE_MILLIS);
        }
    }

    private int nextBurstSize(OutboxRepository outbox, RateLimitConfig config, Random random) {
        switch (config.burstMode) {
            case FIXED:
                return Math.max(1, config.fixedBurstSize);
            case ALL_AT_ONCE:
                return Math.max(1, outbox.countPending());
            case RANDOM_RANGE:
            default:
                return randomBurstSize(config, random);
        }
    }

    private int randomBurstSize(RateLimitConfig config, Random random) {
        int min = Math.max(1, config.burstMin);
        int max = Math.max(min, config.burstMax);
        return min + (max > min ? random.nextInt(max - min + 1) : 0);
    }

    private void waitBeforeNextBurst(OutboxRepository outbox, RateLimitConfig config, Random random, int upcomingBurstSize)
            throws InterruptedException {
        int min = Math.max(0, config.minWaitSeconds);
        int max = Math.max(min, config.maxWaitSeconds);
        int waitSeconds = min + (max > min ? random.nextInt(max - min + 1) : 0);

        int pending = outbox.countPending();
        int displaySize = pending > 0 ? Math.min(upcomingBurstSize, pending) : upcomingBurstSize;
        SendQueueStatus.setNextBurst(System.currentTimeMillis() + waitSeconds * 1000L, displaySize);

        Thread.sleep(waitSeconds * 1000L);
    }

    private void sendBurst(SmsManager smsManager, OutboxRepository outbox, MessageRepository messageRepository,
                            MemberRepository memberRepository, Prefs prefs, RateLimitConfig config,
                            Random random, List<OutboxRepository.OutboxItem> burst) {
        for (int i = 0; i < burst.size(); i++) {
            sendOne(smsManager, outbox, memberRepository, prefs, burst.get(i));
            if (config.microspacingEnabled && i < burst.size() - 1) {
                MicroSpacer.waitMillis(microspacingDelayMillis(config, random));
            }
        }
    }

    /** Fractional-millisecond gap between two sends in the same burst; randomized between bounds when configured. */
    private double microspacingDelayMillis(RateLimitConfig config, Random random) {
        if (config.microspacingMode == Prefs.MicrospacingMode.RANDOM_RANGE) {
            int min = Math.max(0, config.microspacingMinMs);
            int max = Math.max(min, config.microspacingMaxMs);
            return min + random.nextDouble() * (max - min);
        }
        return Math.max(0, config.microspacingFixedMs);
    }

    /**
     * Hands the item off to the radio and records that it's awaiting a result — it no longer
     * judges success itself. "The SmsManager call didn't throw" is not delivery: carrier
     * rejection, radio-off, and no-service/rate-limit refusals all complete the send call
     * without throwing, so the real outcome now comes from the per-part sent {@link PendingIntent}
     * that {@link SentReceiver} observes, which owns markSent/requeueForRetry/markFailed and the
     * admin failure alert from here on.
     * <p>
     * Splits on segment count via {@link SmsManager#divideMessage}, not on character count: the
     * same API {@link #mergeReleasedRelayRows} already trusts for sizing. Hebrew and Yiddish text
     * — and any body {@link MessageSalt} has zero-width-salted — is UCS-2 at 70 chars/segment, not
     * GSM-7 at 160, so a length threshold let a 71-160 char Hebrew/Yiddish message take the
     * single-part branch, where {@code getSubmitPdu} cannot build it and the platform fires
     * {@code RESULT_ERROR_NULL_PDU}.
     * <p>
     * Mints one handoff token per call, shared by every part of this attempt, so a late result
     * from a superseded attempt (this row failed and was re-sent before all of its earlier parts
     * reported back) can never resolve the new attempt: {@link SentReceiver} checks the token
     * against the row, not just the row id.
     */
    private void sendOne(SmsManager smsManager, OutboxRepository outbox, MemberRepository memberRepository,
                          Prefs prefs, OutboxRepository.OutboxItem item) {
        if (item.subgroupId != null) {
            sendGroup(outbox, memberRepository, prefs, item);
            return;
        }
        long token = System.currentTimeMillis();
        try {
            // Send-time salt: applied only to rows flagged for it (post-upgrade RELAY rows), once,
            // right here at the SmsManager boundary. Rows with applySalt == false — every
            // non-RELAY row and every pre-upgrade row — go out exactly as stored, unchanged from
            // today. The salted body is never written back to item.body, so retries below re-salt
            // fresh and the failure log keeps the original stored text.
            String body = item.applySalt ? MessageSalt.applySendTime(prefs, item.body) : item.body;
            ArrayList<String> parts = smsManager.divideMessage(body);
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>(parts.size());
                for (int i = 0; i < parts.size(); i++) {
                    sentIntents.add(SentReceiver.create(this, item.id, i, token));
                }
                // Recorded before the send call so the row is known to be awaiting results even
                // if the process dies immediately after the radio accepts it.
                outbox.markHandedOff(item.id, parts.size(), token);
                smsManager.sendMultipartTextMessage(item.phoneE164, null, parts, sentIntents, null);
            } else {
                PendingIntent sentIntent = SentReceiver.create(this, item.id, 0, token);
                outbox.markHandedOff(item.id, 1, token);
                smsManager.sendTextMessage(item.phoneE164, null, body, sentIntent, null);
            }
        } catch (Exception e) {
            // The one failure path SentReceiver cannot observe: nothing reached the radio, so no
            // delivery intent will ever fire. markHandedOff(0) clears whatever parts_pending this
            // attempt already recorded above, so the row isn't left looking like it's still
            // awaiting results that will never arrive; then hand it to the same retry policy the
            // result path uses rather than duplicating it here.
            Log.e(TAG, "Failed to send SMS to " + item.phoneE164, e);
            outbox.markHandedOff(item.id, 0, token);
            SentReceiver.handleFailure(this, item, token, SentReceiver.RESULT_NOT_SENT);
        }
    }

    /**
     * Dispatches a group-MMS outbox row ({@code item.subgroupId != null}) via {@link MmsSender}.
     * Resolves the sub-group's members at SEND time, not enqueue time, so a member removed between
     * enqueue and drain never receives the message.
     * <p>
     * If the sub-group now has zero active members, this is a bug upstream (routing should never
     * have produced this row) — not a message to send. The row is resolved straight to FAILED,
     * without going through the retry policy, since re-sending to nobody can never succeed.
     */
    private void sendGroup(OutboxRepository outbox, MemberRepository memberRepository, Prefs prefs,
                            OutboxRepository.OutboxItem item) {
        long token = System.currentTimeMillis();
        List<Member> members = memberRepository.getSubgroupMembers(item.subgroupId);
        if (members.isEmpty()) {
            Log.e(TAG, "Sub-group " + item.subgroupId + " for outbox row " + item.id
                    + " has zero active members at send time; failing the row instead of sending to nobody");
            outbox.markHandedOff(item.id, 0, token);
            outbox.markFailed(item.id, token);
            return;
        }

        List<String> recipients = new ArrayList<>(members.size());
        for (Member m : members) {
            recipients.add(m.phoneE164);
        }

        String body = item.applySalt ? MessageSalt.applySendTime(prefs, item.body) : item.body;

        // Recorded before the send call, same contract as sendOne's individual path: MmsSentReceiver
        // matches a result back to this row via handed_off_at, so this must happen with the same
        // token passed to MmsSender.send, before the radio is ever invoked.
        outbox.markHandedOff(item.id, 1, token);
        MmsSender.send(this, item.id, token, recipients, body, resultCode -> {
            // Only reached for the cases MmsSentReceiver can never handle: empty recipient list
            // (already ruled out above) or a synchronous throw that never reached the radio.
            Log.e(TAG, "Failed to hand off group MMS for outbox row " + item.id + " (sub-group "
                    + item.subgroupId + "), resultCode=" + resultCode);
            outbox.markHandedOff(item.id, 0, token);
            SentReceiver.handleFailure(this, item, token, resultCode);
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
