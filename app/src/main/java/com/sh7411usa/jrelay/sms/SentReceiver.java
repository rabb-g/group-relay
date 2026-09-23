package com.sh7411usa.jrelay.sms;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.util.Prefs;

/**
 * Receives the per-part send result Android broadcasts back for each {@link PendingIntent} handed
 * to {@code SmsManager} (5.3). Before this, {@code SmsSendService.sendOne} assumed success
 * whenever the SMS API call didn't throw - carrier rejection and "radio off / no service" were
 * invisible to that check, so the retry counter and admin failure alerts fired on the wrong
 * things.
 *
 * <p>A send can now fail two different ways: Android broadcasts a non-OK result code here in
 * {@link #onReceive}, or {@code SmsManager} throws synchronously in {@code sendOne} - in which
 * case the message never reached the radio and no intent will ever fire, so {@code onReceive} is
 * never invoked for it at all. {@link #handleFailure} is the single owner of the retry-vs-fail
 * policy for both cases; only the "is this failure new or a duplicate part result" dedup differs
 * between the two entry points, since only the broadcast path can race multiple parts of the same
 * row.
 */
public class SentReceiver extends BroadcastReceiver {

    private static final String TAG = "SentReceiver";
    private static final String ACTION = "com.sh7411usa.jrelay.SMS_SENT";
    private static final String EXTRA_ROW_ID = "row_id";
    /** Identifies one send attempt, shared across all parts SmsSendService.sendOne dispatches for it. */
    private static final String EXTRA_TOKEN = "token";

    /**
     * No delivery result is possible: {@code SmsManager} threw synchronously in
     * {@code SmsSendService.sendOne}, so the message never reached the radio and no
     * {@link PendingIntent} for it will ever fire. Passed to {@link #handleFailure} directly from
     * that catch block so the thrown case is distinguishable from a real broadcast result code,
     * both in the log and wherever the activity feed surfaces the outcome.
     *
     * <p>Deliberately far from the platform's result codes: {@code Activity.RESULT_OK} is -1 and
     * the {@code SmsManager.RESULT_ERROR_*} constants are small positives, so a sentinel in either
     * range would be misread as a real platform outcome - in particular, -1 would collide with
     * RESULT_OK and a never-sent message would be stored and displayed as delivered.
     */
    public static final int RESULT_NOT_SENT = -1000;

    /**
     * A fixed request code is safe here: PendingIntent identity (with {@code FLAG_UPDATE_CURRENT})
     * is the tuple (request code, action, data, type, component, categories) - NOT extras - and
     * every intent below already gets its own {@code data} Uri, so the request code carries no
     * uniqueness burden. It is not, in particular, used as a per-process counter: an
     * in-memory counter reset by a process restart previously left every intent issued by the new
     * process colliding with request code 1 from the old one, and since {@code data} used to be
     * identical (null) across all of them too, {@code FLAG_UPDATE_CURRENT} silently rewrote an
     * old, still-outstanding PendingIntent's extras instead of creating a new record - two
     * different sends then shared one system-held token. See {@link #create}'s data Uri for the
     * actual fix, which holds across process restarts because it depends on nothing in memory.
     */
    private static final int REQUEST_CODE = 0;

    /**
     * Builds the PendingIntent Android will fire with this part's delivery result. {@code token}
     * identifies one send attempt - {@code SmsSendService.sendOne} mints one and shares it across
     * every part of that attempt's message - so a result can be matched back to the attempt it
     * came from, not just the row.
     *
     * <p>The Intent's component is set explicitly to this receiver so only this app can ever
     * receive the broadcast, regardless of how it's declared in the manifest.
     *
     * <p>Each part needs a PendingIntent that is not {@code equal()} to any other part's, or
     * Android collapses them into one and only a single result is ever delivered for the whole
     * multipart send - and, since PendingIntents are held by the system and outlive this process,
     * "any other part's" includes ones issued by an earlier, now-dead process. PendingIntent
     * equality does not look at extras, so packing {@code rowId}/{@code token} into extras alone
     * would not make two parts' PendingIntents distinct, and an in-memory request-code counter
     * doesn't survive a process restart either (see {@link #REQUEST_CODE}). What does survive is
     * putting the identifying values in the Intent's {@code data}: two intents with different data
     * are never {@code equal()} regardless of request code or which process created them, which is
     * why a fixed {@link #REQUEST_CODE} is fine.
     *
     * <p>{@code FLAG_IMMUTABLE} means the platform will not merge its own {@code errorCode} extra
     * into the returned Intent when it fires - accepted, since {@code getResultCode()} alone
     * carries everything {@link #onReceive} needs.
     */
    public static PendingIntent create(Context context, long rowId, int partIndex, long token) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(ACTION);
        intent.setClass(appContext, SentReceiver.class);
        intent.setData(Uri.parse("jrelay://sent/" + rowId + "/" + partIndex + "/" + token));
        intent.putExtra(EXTRA_ROW_ID, rowId);
        intent.putExtra(EXTRA_TOKEN, token);

        return PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Maps a delivery result code to its explanatory string resource. Separated from
     * {@link #describe} so it can be unit-tested without Android: this mapping is exactly where a
     * wrong or colliding code would silently mislabel a send - {@link #RESULT_NOT_SENT} briefly
     * collided with {@code Activity.RESULT_OK} during development of this feature, which would
     * have rendered a message that never reached the radio as "Delivered to carrier" - so it's
     * worth pinning with tests. An unrecognised code (an OEM-specific one the constants below
     * don't cover) deliberately falls through to {@code send_result_unknown} rather than being
     * guessed at and mislabelled as one of the known reasons.
     */
    static int stringResFor(int resultCode) {
        if (resultCode == Activity.RESULT_OK) {
            return R.string.send_result_ok;
        } else if (resultCode == RESULT_NOT_SENT) {
            return R.string.send_result_not_sent;
        } else if (resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE) {
            return R.string.send_result_generic;
        } else if (resultCode == SmsManager.RESULT_ERROR_RADIO_OFF) {
            return R.string.send_result_radio_off;
        } else if (resultCode == SmsManager.RESULT_ERROR_NO_SERVICE) {
            return R.string.send_result_no_service;
        } else if (resultCode == SmsManager.RESULT_ERROR_NULL_PDU) {
            return R.string.send_result_null_pdu;
        } else if (resultCode == SmsManager.RESULT_ERROR_LIMIT_EXCEEDED) {
            return R.string.send_result_limit_exceeded;
        } else if (resultCode == SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED) {
            return R.string.send_result_short_code_not_allowed;
        }
        return R.string.send_result_unknown;
    }

    /**
     * Human-readable reason for a delivery result code, for the activity feed (via the FAILED
     * message-log row) and the WARN log - one mapping ({@link #stringResFor}), used both places,
     * so they can never disagree. The unknown-code resource is the only one that takes an
     * argument, so it's the only case that needs the raw code supplied.
     */
    public static String describe(Context context, int resultCode) {
        int stringRes = stringResFor(resultCode);
        if (stringRes == R.string.send_result_unknown) {
            return context.getString(stringRes, resultCode);
        }
        return context.getString(stringRes);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        long rowId = intent.getLongExtra(EXTRA_ROW_ID, -1);
        if (rowId < 0) {
            return;
        }
        long token = intent.getLongExtra(EXTRA_TOKEN, -1);

        Context appContext = context.getApplicationContext();
        OutboxRepository outbox = new OutboxRepository(appContext);
        OutboxRepository.OutboxItem item = outbox.findById(rowId);
        if (item == null) {
            // Row no longer exists (or never did) - nothing to record against.
            return;
        }

        int resultCode = getResultCode();
        if (resultCode == Activity.RESULT_OK) {
            handleSuccess(outbox, new MemberRepository(appContext), item, token);
            return;
        }

        if (outbox.recordSendFailure(item.id, token, resultCode)) {
            handleFailure(appContext, item, resultCode);
        }
        // false: either already resolved by another part's result (a multi-part message must not
        // be counted as more than one failure), or this result belongs to a prior attempt whose
        // token no longer matches the row's current one - a late result from attempt N must not
        // be applied against attempt N+1, which is already in flight or already resolved. Either
        // way, nothing further happens for this broadcast.
    }

    /** Mirrors sendOne's old success path: all parts in -> SENT, and the member's failure streak resets. */
    private static void handleSuccess(OutboxRepository outbox, MemberRepository memberRepository,
                                       OutboxRepository.OutboxItem item, long token) {
        int remaining = outbox.recordPartSent(item.id, token);
        if (remaining != 0) {
            // -1: already resolved, or a late/stale result (duplicate part, or from a prior
            // attempt whose token doesn't match) - ignore either way.
            //  >0: other parts of this message are still outstanding - nothing more to do yet.
            return;
        }
        outbox.markSent(item.id);
        if (item.memberId != null) {
            Member member = memberRepository.findById(item.memberId);
            if (member != null) {
                memberRepository.resetFailedCount(member.id);
            }
        }
    }

    /**
     * Applies the retry policy to a failed send, from either failure path: a non-OK delivery
     * result (only after {@link OutboxRepository#recordSendFailure} has confirmed this is the
     * first part of the row to report failure), or a synchronous throw in
     * {@code SmsSendService#sendOne} - which fires no intent at all, so this is the only way that
     * case is ever handled. Under the retry limit the row is requeued and a drain kicked off; at
     * the limit it is marked FAILED, logged, and counted toward the member's failure alert. This
     * is a straight port of sendOne's original retry-vs-fail block - now the single owner of that
     * policy, called from both places instead of being duplicated.
     */
    public static void handleFailure(Context context, OutboxRepository.OutboxItem item, int resultCode) {
        Context appContext = context.getApplicationContext();
        String reason = describe(appContext, resultCode);

        // WARN, not ERROR: an individual carrier rejection is expected background noise for an
        // SMS relay, but on an unattended phone this log line is the only place it's surfaced at
        // all, so it needs to be findable and to say why, not just that it happened. Uses
        // describe() rather than its own label logic so the log and the activity feed never
        // disagree about what a given resultCode means.
        Log.w(TAG, "Send failed for outbox row " + item.id + " (" + item.phoneE164
                + "), resultCode=" + resultCode + " (" + reason + ")");

        OutboxRepository outbox = new OutboxRepository(appContext);
        Prefs prefs = new Prefs(appContext);

        int attempts = item.attempts + 1;
        int maxAttempts = Math.max(1, prefs.getRetryLimit() + 1);
        if (attempts < maxAttempts) {
            outbox.requeueForRetry(item.id, attempts);
            SmsSendService.start(appContext);
            return;
        }

        outbox.markFailed(item.id);
        // tpl_failed_with_reason ("%1$s -- %2$s") folds the reason into this FAILED marker row
        // only - the delivered message text itself lives in its own log rows, untouched. This is
        // the only place the activity feed learns *why* a send failed, so it has to carry it.
        new MessageRepository(appContext).log(item.memberId, "OUT", "FAILED",
                appContext.getString(R.string.tpl_failed_with_reason, item.body, reason));
        if (item.memberId != null) {
            MemberRepository memberRepository = new MemberRepository(appContext);
            Member member = memberRepository.findById(item.memberId);
            if (member != null) {
                int failedCount = memberRepository.incrementFailedCount(member.id);
                int threshold = prefs.getFailureAlertThreshold();
                if (threshold > 0 && failedCount % threshold == 0) {
                    new CommandProcessor(appContext).alertAdminsOfFailures(member, failedCount);
                }
            }
        }
    }
}
