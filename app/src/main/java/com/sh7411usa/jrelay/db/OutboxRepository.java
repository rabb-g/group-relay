package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outbox holds TWO row shapes, and {@code subgroup_id} is the discriminant - never the phone
 * number.
 *
 * <ul>
 *   <li><b>Individual row</b> ({@code subgroup_id IS NULL}): one recipient, addressed by
 *       {@code member_id} and {@code phone_e164}. Every row before v5.9 is one of these, and every
 *       row is one of these while delivery mode is SMS.</li>
 *   <li><b>Group row</b> ({@code subgroup_id} set): one group MMS to a whole sub-group. It has no
 *       single recipient, so {@code phone_e164} is an empty-string sentinel purely because the
 *       column is NOT NULL, and {@code member_id} is null. The recipient list is resolved from the
 *       sub-group at SEND time, not stored here.</li>
 * </ul>
 *
 * <p><b>Read {@code subgroupId} to tell them apart. Do not infer the row shape from an empty
 * phone.</b> The sentinel is a schema fill-in, not a second source of truth, and treating it as
 * one would mean two places had to agree about what an empty phone means. Anything that reads
 * {@code phoneE164} must establish it is an individual row first - on a group row that field is
 * meaningless, not merely blank.
 *
 * <p>The same care applies to anything counted per row: a group row is ONE pending message and up
 * to nine deliveries. {@code countPending} and friends count rows, which is the honest number for
 * carrier metering and an understatement of how many people are waiting.
 */
public class OutboxRepository {

    /** Outbox category for a relayed group post: the only kind that is held for coalescing and salted at send. */
    public static final String CATEGORY_RELAY = "RELAY";

    /**
     * Once a row has been SENDING (awaiting a delivery result) for longer than this, the result
     * is never arriving and {@link #resetOrphanedSending()} treats the row as stranded.
     */
    private static final long AWAITING_RESULT_STALE_MILLIS = 10 * 60 * 1000L;

    /**
     * Result code recorded by {@link #recordPartSent(long, long)} for a successful part. Matches
     * {@code android.app.Activity.RESULT_OK}, the default resultCode delivered to a sent
     * PendingIntent when the radio does not explicitly set an error code.
     */
    private static final int SEND_RESULT_OK = -1;

    public static class OutboxItem {
        public long id;
        public Long memberId;
        public String phoneE164;
        public String body;
        public int attempts;
        public String category;
        public boolean applySalt;
        /** NULL for an ordinary per-recipient SMS row. Non-null identifies the sub-group this
         *  group-MMS row is addressed to; the sender uses this to decide how to dispatch the row. */
        public Long subgroupId;
    }

    private final DbHelper dbHelper;

    public OutboxRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    /**
     * Intermediate status a row occupies between a failed delivery result (or a synchronous throw
     * that never reached the radio) being recorded and the retry-vs-fail policy being applied to
     * it. Distinct from every other status on purpose: {@code takeBurst} only ever draws
     * {@code PENDING} rows, so a row parked here cannot be reclaimed and sent again, and
     * {@code resetOrphanedSending} only ever matches {@code SENDING}, so a row parked here cannot
     * be reset back to PENDING out from under {@link SentReceiver#handleFailure}. The row leaves
     * this status only via {@link #requeueForRetry} or {@link #markFailed}.
     */
    private static final String STATUS_SEND_FAILED = "SEND_FAILED";

    /** Existing 3-arg form kept as a delegate: category null, no hold, no send-time salt. */
    public void enqueue(Long memberId, String phoneE164, String body) {
        enqueue(memberId, phoneE164, body, null, 0L, false);
    }

    /**
     * holdUntilMillis 0 = send immediately. applySalt true = salt at send time, not enqueue time.
     */
    public void enqueue(Long memberId, String phoneE164, String body, String category,
                         long holdUntilMillis, boolean applySalt) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (memberId != null) {
            cv.put("member_id", memberId);
        }
        cv.put("phone_e164", phoneE164);
        cv.put("body", body);
        cv.put("enqueued_at", System.currentTimeMillis());
        cv.put("status", "PENDING");
        if (category != null) {
            cv.put("category", category);
        } else {
            cv.putNull("category");
        }
        cv.put("hold_until", holdUntilMillis);
        cv.put("apply_salt", applySalt ? 1 : 0);
        db.insert(DbHelper.TABLE_OUTBOX, null, cv);
    }

    /**
     * Enqueues one group-MMS row addressed to {@code subgroupId} instead of a single recipient.
     * {@code member_id} is left null (no single member owns this row) and {@code phone_e164} -
     * NOT NULL in the schema - is set to {@code ""}, a sentinel that can never collide with a real
     * E.164 number: for a group row, {@code subgroup_id} is the sole address and the sender must
     * resolve the sub-group's actual recipient list itself, never read {@code phoneE164} for one.
     *
     * <p>Deliberately not routed through {@link #enqueue}'s existing signature so a future caller
     * cannot accidentally construct a group row with a stray non-empty phone number, or an
     * individual row with a non-null subgroupId - the two shapes stay mutually exclusive by
     * construction.
     */
    public void enqueueGroup(long subgroupId, String body, String category,
                              long holdUntilMillis, boolean applySalt) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", "");
        cv.put("body", body);
        cv.put("enqueued_at", System.currentTimeMillis());
        cv.put("status", "PENDING");
        if (category != null) {
            cv.put("category", category);
        } else {
            cv.putNull("category");
        }
        cv.put("hold_until", holdUntilMillis);
        cv.put("apply_salt", applySalt ? 1 : 0);
        cv.put("subgroup_id", subgroupId);
        db.insert(DbHelper.TABLE_OUTBOX, null, cv);
    }

    /**
     * Atomically selects up to `limit` pending rows whose hold has expired and flips them to
     * SENDING so overlapping drains cannot double-send. Sending is global (not per-recipient), so
     * `shuffle` optionally randomizes which pending rows are chosen instead of always taking the
     * oldest first.
     */
    public List<OutboxItem> takeBurst(int limit, boolean shuffle) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<OutboxItem> selected;
        db.beginTransaction();
        try {
            Cursor c = db.query(DbHelper.TABLE_OUTBOX, null, "status = ? AND hold_until <= ?",
                    new String[]{"PENDING", String.valueOf(System.currentTimeMillis())},
                    null, null, "enqueued_at ASC");
            List<OutboxItem> pending = new ArrayList<>();
            while (c.moveToNext()) {
                pending.add(mapCursor(c));
            }
            c.close();

            if (shuffle) {
                Collections.shuffle(pending);
            }
            selected = new ArrayList<>(pending.subList(0, Math.min(limit, pending.size())));

            for (OutboxItem item : selected) {
                ContentValues cv = new ContentValues();
                cv.put("status", "SENDING");
                db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(item.id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return selected;
    }

    /**
     * Returns rows stranded in SENDING back to PENDING so they are retried. Returns how many rows
     * were reset.
     *
     * <p>Historically a row was only ever SENDING for the few milliseconds a drain spent inside
     * {@code sendOne}, so any SENDING row seen at process start was assumed to be the remains of a
     * drain that was killed, and every SENDING row was reset unconditionally. Since 5.3,
     * {@code sendOne} hands a claimed row to the radio via {@link #markHandedOff(long, int, long)} and
     * leaves it SENDING while it awaits an asynchronous delivery result ({@code parts_pending > 0},
     * with {@code handed_off_at} recording when it was handed off) - that row is legitimately
     * SENDING, sometimes for many seconds, often after the drain thread itself has already exited.
     * Resetting it to PENDING would let {@code takeBurst} claim and send it a second time while the
     * original send may still be in flight, so this method now resets a SENDING row only when:
     * <ul>
     *   <li>{@code parts_pending = 0} - claimed by {@code takeBurst} but killed before ever reaching
     *       the radio, so no delivery result can ever arrive for it. This covers both a kill before
     *       {@code markHandedOff} ever ran, and a kill after a synchronous {@code SmsManager} throw
     *       called {@code markHandedOff(id, 0, token)} but before the row could be moved on to
     *       {@link #STATUS_SEND_FAILED} - since 5.7 that is the only other way {@code parts_pending}
     *       reaches 0, so any row still SENDING with {@code parts_pending = 0} is provably one of
     *       these two "killed before it could move on" cases, never a row whose outcome is mid-write
     *       elsewhere; see {@link #recordPartSent} and {@link #recordSendFailure}, or
     *   <li>{@code parts_pending > 0} but {@code handed_off_at} is older than
     *       {@link #AWAITING_RESULT_STALE_MILLIS} - a delivery result that has not arrived within
     *       that window is never arriving (radio crash, lost broadcast, etc.), so the row is
     *       genuinely stranded rather than still in flight, or
     *   <li>{@code parts_pending > 0} but {@code handed_off_at} predates the current boot - a
     *       {@code PendingIntent} does not survive a reboot, so a hand-off from before the last
     *       boot can never receive a result. Unlike the staleness case above this row is provably
     *       dead rather than merely old, so it is reset immediately without waiting out the
     *       staleness window.
     * </ul>
     * A row with {@code parts_pending > 0} and a recent {@code handed_off_at} is left untouched
     * even though it is SENDING, because it may still receive a real result; resetting it would
     * double-send.
     *
     * <p>The two branches disagree on {@code attempts} on purpose. A {@code parts_pending = 0} row
     * was claimed by {@code takeBurst} but killed before {@code markHandedOff} ever ran - no part
     * was ever handed to the radio, so nothing was actually attempted and the retry must not be
     * charged against the row's attempt limit. A {@code parts_pending > 0} row genuinely reached
     * the radio and simply never got a result back; that is a real, if inconclusive, attempt, and
     * if it were free to retry forever a row that always strands (rather than always failing
     * cleanly) would retry unboundedly instead of eventually hitting the attempt limit like every
     * other kind of failure.
     *
     * <p>Safe to call only when no drain can have rows currently claimed. {@code takeBurst} claims
     * a whole burst into SENDING in one transaction and then sends those rows one at a time, a
     * window of seconds; resetting during that window flips a live burst back to PENDING and it
     * gets sent twice. Exactly two conditions establish that no drain is running:
     * <ol>
     *   <li>Immediately after winning the {@code SmsSendService.DRAINING} compare-and-set - no
     *       other drain is running by definition. This is what {@code SmsSendService}'s drain
     *       thread relies on.
     *   <li>While {@code Prefs.isPaused()} is true - {@code drainAll} checks the pause flag before
     *       ever claiming a burst, so no row can legitimately be SENDING. This is what
     *       {@code BootReceiver} relies on.
     * </ol>
     * A new caller must establish one of these two conditions itself, not assume it: all app
     * components share one process, so "a drain probably isn't running" is not safe.
     */
    public int resetOrphanedSending() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long staleBefore = System.currentTimeMillis() - AWAITING_RESULT_STALE_MILLIS;
        long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();

        ContentValues neverHandedOffCv = new ContentValues();
        neverHandedOffCv.put("status", "PENDING");
        int neverHandedOff = db.update(DbHelper.TABLE_OUTBOX, neverHandedOffCv,
                "status = ? AND parts_pending = 0", new String[]{"SENDING"});

        // parts_pending is zeroed here, not just status: this row is being declared dead, so it
        // must stop matching recordSendFailure's/recordPartSent's WHERE clause
        // (id = ? AND status = 'SENDING' AND parts_pending > 0 AND handed_off_at = ?) for the
        // abandoned attempt. Leaving parts_pending > 0 intact let takeBurst reclaim the row
        // (writing only status back to SENDING) while the old handed_off_at token was still
        // sitting there - so a late result from the original, abandoned attempt could still
        // satisfy that WHERE clause during the reclaimed attempt's SENDING window, and
        // recordSendFailure's true triggered a requeue of a row that was mid-send again: a
        // duplicate send.
        SQLiteStatement strandedStmt = db.compileStatement(
                "UPDATE " + DbHelper.TABLE_OUTBOX
                        + " SET status = 'PENDING', attempts = attempts + 1, parts_pending = 0"
                        + " WHERE status = 'SENDING' AND parts_pending > 0"
                        + " AND (handed_off_at <= ? OR handed_off_at < ?)");
        int strandedAwaitingResult;
        try {
            strandedStmt.bindLong(1, staleBefore);
            strandedStmt.bindLong(2, bootTime);
            strandedAwaitingResult = strandedStmt.executeUpdateDelete();
        } finally {
            strandedStmt.close();
        }

        return neverHandedOff + strandedAwaitingResult;
    }

    /**
     * Messages still waiting to go out: not yet claimed for sending, currently mid-send, or a
     * failed result recorded but the retry-vs-fail policy not yet applied ({@link
     * #STATUS_SEND_FAILED} - it still might become a retried PENDING row, so it counts as unsent
     * the same as a mid-send row does).
     */
    public int countUnsent() {
        return (int) queryScalar("COUNT(*)",
                "status IN ('PENDING', 'SENDING', '" + STATUS_SEND_FAILED + "')", null);
    }

    /**
     * Unsent group rows addressed to any of the given sub-groups, using the same definition of
     * "unsent" as {@link #countUnsent()}.
     *
     * <p>Exists to gate a sub-group merge. {@code SmsSendService#sendGroup} resolves a group row's
     * recipients at SEND time from the sub-group's current membership, not at enqueue time. So if
     * sub-group A is folded into B while a post for A is still queued, that row later finds A
     * empty and is failed — the members who moved miss the post whenever B's copy has already
     * gone, and the failure raises an admin alert that is itself an outgoing SMS. Refusing the
     * merge until both ends have drained is far cheaper than any of that.
     */
    public int countUnsentForSubgroups(java.util.Collection<Long> subgroupIds) {
        if (subgroupIds == null || subgroupIds.isEmpty()) {
            return 0;
        }
        StringBuilder placeholders = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (Long id : subgroupIds) {
            placeholders.append(placeholders.length() == 0 ? "?" : ",?");
            args.add(String.valueOf(id));
        }
        return (int) queryScalar("COUNT(*)",
                "status IN ('PENDING', 'SENDING', '" + STATUS_SEND_FAILED + "') AND subgroup_id IN ("
                        + placeholders + ")",
                args.toArray(new String[0]));
    }

    public int countPending() {
        return (int) queryScalar("COUNT(*)", "status = 'PENDING'", null);
    }

    /**
     * Rows handed to the radio recently enough that a delivery result could still plausibly
     * arrive. Bounded to the same staleness window {@link #resetOrphanedSending()} uses to give
     * up on a row: an unbounded count would include orphans that a reset deliberately spares
     * while they're still inside that window, and a caller waiting for this count to reach zero
     * would then wait on rows it has already decided will never resolve.
     */
    public int countAwaitingResults() {
        return (int) queryScalar("COUNT(*)",
                "status = ? AND parts_pending > 0 AND handed_off_at > ?",
                new String[]{"SENDING",
                        String.valueOf(System.currentTimeMillis() - AWAITING_RESULT_STALE_MILLIS)});
    }

    /** Pending rows still inside their hold window, for the dashboard's "holding N". */
    public int countHolding() {
        return (int) queryScalar("COUNT(*)", "status = ? AND hold_until > ?",
                new String[]{"PENDING", String.valueOf(System.currentTimeMillis())});
    }

    /** Earliest hold_until among still-held pending rows, or 0 when nothing is held. */
    public long earliestHoldUntil() {
        return queryScalar("MIN(hold_until)", "status = ? AND hold_until > ?",
                new String[]{"PENDING", String.valueOf(System.currentTimeMillis())});
    }

    /**
     * Marks a row FAILED - the retry limit has been exhausted. Guarded on
     * {@code id AND handed_off_at = ? AND status IN ('SEND_FAILED', 'SENDING')} - see
     * {@link #requeueForRetry} for why both statuses are matched here. If the row has since been
     * reclaimed into a newer attempt under a different token, zero rows match and the call is
     * silently dropped instead of stamping FAILED over a live, unrelated attempt.
     */
    public void markFailed(long id, long token) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", "FAILED");
        db.update(DbHelper.TABLE_OUTBOX, cv,
                "id = ? AND handed_off_at = ? AND status IN ('" + STATUS_SEND_FAILED + "', 'SENDING')",
                new String[]{String.valueOf(id), String.valueOf(token)});
    }

    /**
     * Records a hand-off to the radio. `token` identifies this attempt; results carrying a
     * different token are stale. Also called with `parts = 0` to clear the awaiting state.
     */
    public void markHandedOff(long id, int parts, long token) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("parts_pending", parts);
        cv.put("handed_off_at", token);
        db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /**
     * One part reported success. Atomically decrements the outstanding count and returns how many
     * remain, or -1 when this row is no longer awaiting results (already resolved, a late
     * duplicate result, or a result from a stale attempt whose `token` no longer matches the row's
     * `handed_off_at`). Callers must treat -1 as "ignore this result".
     *
     * <p>When the decrement reaches 0 this call writes {@code status = 'SENT'} itself, inside the
     * same transaction, instead of leaving the row in SENDING for a later separate {@code markSent}
     * call to close out (the pre-5.7 design). That older design left a real window - however
     * short - where the row sat at {@code status = 'SENDING', parts_pending = 0}: exactly the
     * state {@link #resetOrphanedSending()}'s first branch treats as "claimed but never reached the
     * radio" and resets to PENDING. A drain running that reset concurrently with this call could
     * catch the row in that window and flip it back to PENDING, and {@code takeBurst} would then
     * send it again even though it had already been delivered. Folding the SENT write into this
     * transaction means no other statement ever observes the row at {@code parts_pending = 0}
     * while it is still SENDING - it goes from "awaiting" straight to "SENT" atomically.
     */
    public int recordPartSent(long id, long token) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("last_result", SEND_RESULT_OK);
            int rows = db.update(DbHelper.TABLE_OUTBOX, cv,
                    "id = ? AND status = ? AND parts_pending > 0 AND handed_off_at = ?",
                    new String[]{String.valueOf(id), "SENDING", String.valueOf(token)});
            if (rows == 0) {
                db.setTransactionSuccessful();
                return -1;
            }

            db.execSQL("UPDATE " + DbHelper.TABLE_OUTBOX
                            + " SET parts_pending = parts_pending - 1 WHERE id = ?",
                    new Object[]{id});

            int remaining = -1;
            Cursor c = db.rawQuery("SELECT parts_pending FROM " + DbHelper.TABLE_OUTBOX + " WHERE id = ?",
                    new String[]{String.valueOf(id)});
            if (c.moveToFirst()) {
                remaining = c.getInt(0);
            }
            c.close();

            if (remaining == 0) {
                // Plain id-match update is safe here: the row-owning update above, in this same
                // transaction, already confirmed this call holds the live SENDING attempt for
                // `token` - nothing else can have touched the row since (SQLite serializes writers
                // through this same transaction), so no further status/token guard is needed.
                ContentValues doneCv = new ContentValues();
                doneCv.put("status", "SENT");
                db.update(DbHelper.TABLE_OUTBOX, doneCv, "id = ?", new String[]{String.valueOf(id)});
            }

            db.setTransactionSuccessful();
            return remaining;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * A part reported failure. Atomically clears the awaiting state, stores `resultCode`, and
     * returns true if this call is the one that resolved the row. Returns false when another
     * result already resolved it, or when `token` no longer matches the row's `handed_off_at`
     * (a stale result from a previous attempt), so a multi-part message reports its failure
     * exactly once and a stale result is silently ignored.
     *
     * <p>Moves the row's status to {@link #STATUS_SEND_FAILED} in the same write that clears
     * {@code parts_pending} - not just {@code parts_pending}, as before 5.7. Leaving status at
     * SENDING here left the row at {@code parts_pending = 0, status = 'SENDING'} for the whole
     * span of {@code SentReceiver.handleFailure}, which is exactly the state
     * {@link #resetOrphanedSending()}'s first branch resets to PENDING (without bumping
     * {@code attempts}); a drain running that reset concurrently could reclaim the row via
     * {@code takeBurst} while the original failure was still being processed, and the recipient
     * got the message twice. Moving status here closes that window: {@code resetOrphanedSending}
     * only ever matches {@code status = 'SENDING'}, so a row parked in SEND_FAILED cannot be
     * touched by it, and {@code takeBurst} only ever draws {@code status = 'PENDING'}, so it
     * cannot reclaim it either. The row leaves SEND_FAILED only via {@link #requeueForRetry} or
     * {@link #markFailed}, both of which this same token must also match.
     */
    public boolean recordSendFailure(long id, long token, int resultCode) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("status", STATUS_SEND_FAILED);
            cv.put("parts_pending", 0);
            // last_result has no Java reader (the failure reason reaches the UI via
            // tpl_failed_with_reason instead) - it's kept write-only, deliberately, so the raw
            // radio result code is still visible when inspecting the database directly to
            // diagnose a delivery problem. Do not remove this write in a future cleanup pass.
            cv.put("last_result", resultCode);
            int rows = db.update(DbHelper.TABLE_OUTBOX, cv,
                    "id = ? AND status = ? AND parts_pending > 0 AND handed_off_at = ?",
                    new String[]{String.valueOf(id), "SENDING", String.valueOf(token)});
            db.setTransactionSuccessful();
            return rows > 0;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Applies one merge atomically: rewrites the kept row's body, clears its hold, and marks the
     * folded rows MERGED - all in one transaction, and only for rows still PENDING. A row another
     * drain already claimed is left untouched and the whole merge is abandoned, so its text is
     * never both merged into another body and sent on its own. Returns true when applied.
     */
    public boolean applyMerge(long keepRowId, String mergedBody, List<Long> mergedRowIds) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues keepCv = new ContentValues();
            keepCv.put("body", mergedBody);
            keepCv.put("hold_until", 0);
            int keepRows = db.update(DbHelper.TABLE_OUTBOX, keepCv, "id = ? AND status = ?",
                    new String[]{String.valueOf(keepRowId), "PENDING"});
            if (keepRows == 0) {
                return false;
            }

            for (Long mergedId : mergedRowIds) {
                ContentValues mergedCv = new ContentValues();
                mergedCv.put("status", "MERGED");
                int mergedRows = db.update(DbHelper.TABLE_OUTBOX, mergedCv, "id = ? AND status = ?",
                        new String[]{String.valueOf(mergedId), "PENDING"});
                if (mergedRows == 0) {
                    return false;
                }
            }

            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /** One outbox row by id, or null when it no longer exists. */
    public OutboxItem findById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_OUTBOX, null, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        OutboxItem item = null;
        if (c.moveToFirst()) {
            item = mapCursor(c);
        }
        c.close();
        return item;
    }

    /**
     * Released pending RELAY-category rows, grouped-by-recipient upstream. Oldest first.
     *
     * <p>Excludes group-MMS rows ({@code subgroup_id IS NOT NULL}) even though nothing currently
     * enqueues one under {@link #CATEGORY_RELAY}. {@code applyMerge}, fed from this method's
     * result, folds several rows into one by rewriting the kept row's body and keys the merge on
     * {@code phone_e164} upstream - a group row's {@code phone_e164} is the empty-string sentinel
     * (see {@link #enqueueGroup}), not a real recipient, so merging it with anything by that key
     * is meaningless at best and would silently fold two different sub-groups' messages together
     * at worst. This filter makes that exclusion structural instead of relying on every future
     * enqueue call site to remember never to pass {@code CATEGORY_RELAY} for a group row.
     */
    public List<OutboxItem> takeReleasedRelayRows() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<OutboxItem> items = new ArrayList<>();
        Cursor c = db.query(DbHelper.TABLE_OUTBOX, null,
                "status = ? AND category = ? AND hold_until <= ? AND subgroup_id IS NULL",
                new String[]{"PENDING", CATEGORY_RELAY, String.valueOf(System.currentTimeMillis())},
                null, null, "enqueued_at ASC");
        while (c.moveToNext()) {
            items.add(mapCursor(c));
        }
        c.close();
        return items;
    }

    /**
     * Sends this item back to PENDING with its attempt count bumped, held until `holdUntilMillis`
     * so a retry can be backed off. Pass 0 to retry at the next opportunity, which is the old
     * behavior. The hold reuses the same `hold_until` column and the same `takeBurst` filter the
     * coalescing window uses, so a backed-off row is simply invisible to bursts until it is due.
     *
     * <p>Guarded on {@code id AND handed_off_at = ? AND status IN ('SEND_FAILED', 'SENDING')}.
     * {@code SentReceiver.handleFailure} has exactly two callers, and by the time either reaches
     * this method the row is in one of exactly two states, both legitimately resolvable:
     * <ul>
     *   <li>{@link #STATUS_SEND_FAILED} - the broadcast path: {@link #recordSendFailure} already
     *       moved the row here, atomically with recording the outcome, before calling
     *       {@code handleFailure}.
     *   <li>{@code SENDING} - the synchronous-throw path: {@code SmsSendService#sendOne}'s catch
     *       block calls {@code markHandedOff(id, 0, token)} (which does not change status) and then
     *       calls {@code handleFailure} directly, in the same synchronous call, with no other
     *       write able to interleave.
     * </ul>
     * `token` is the attempt this failure belongs to either way. Without this guard, a late
     * failure result for an attempt that has since been superseded - the row reclaimed into a
     * newer SENDING attempt under a new token - would stamp PENDING directly over that live
     * attempt; the newer attempt's real result then finds the row no longer in the state it
     * expects and is discarded as stale, and the row gets sent again - a duplicate delivery,
     * repeating until `attempts` exhausts. Zero rows matched means the row has moved on (or is a
     * live, unrelated SENDING attempt under a different token) and this call is silently dropped
     * instead, the same contract {@link #markFailed} implements.
     */
    public void requeueForRetry(long id, long token, int attempts, long holdUntilMillis) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", "PENDING");
        cv.put("attempts", attempts);
        cv.put("hold_until", holdUntilMillis);
        db.update(DbHelper.TABLE_OUTBOX, cv,
                "id = ? AND handed_off_at = ? AND status IN ('" + STATUS_SEND_FAILED + "', 'SENDING')",
                new String[]{String.valueOf(id), String.valueOf(token)});
    }

    /** Runs a single-value aggregate query over the outbox, returning 0 when there is no row or the value is NULL. */
    private long queryScalar(String selectExpr, String selection, String[] args) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT " + selectExpr + " FROM " + DbHelper.TABLE_OUTBOX
                + (selection == null ? "" : " WHERE " + selection), args);
        long value = 0;
        if (c.moveToFirst() && !c.isNull(0)) {
            value = c.getLong(0);
        }
        c.close();
        return value;
    }

    private OutboxItem mapCursor(Cursor c) {
        OutboxItem item = new OutboxItem();
        item.id = c.getLong(c.getColumnIndexOrThrow("id"));
        int memberIdx = c.getColumnIndexOrThrow("member_id");
        item.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
        item.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
        item.body = c.getString(c.getColumnIndexOrThrow("body"));
        item.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
        item.category = c.getString(c.getColumnIndexOrThrow("category"));
        item.applySalt = c.getInt(c.getColumnIndexOrThrow("apply_salt")) != 0;
        int subgroupIdx = c.getColumnIndexOrThrow("subgroup_id");
        item.subgroupId = c.isNull(subgroupIdx) ? null : c.getLong(subgroupIdx);
        return item;
    }
}
