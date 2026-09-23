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
        public Integer lastResult;
    }

    private final DbHelper dbHelper;

    public OutboxRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

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
     *       the radio, so no delivery result can ever arrive for it, or
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

        SQLiteStatement strandedStmt = db.compileStatement(
                "UPDATE " + DbHelper.TABLE_OUTBOX
                        + " SET status = 'PENDING', attempts = attempts + 1"
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

    /** Messages still waiting to go out: not yet claimed for sending, or currently mid-send. */
    public int countUnsent() {
        return (int) queryScalar("COUNT(*)", "status IN ('PENDING', 'SENDING')", null);
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

    public void markSent(long id) {
        updateStatus(id, "SENT");
    }

    public void markFailed(long id) {
        updateStatus(id, "FAILED");
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
     */
    public boolean recordSendFailure(long id, long token, int resultCode) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("parts_pending", 0);
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

    /** The most recent delivery result code for a row, or null when it has none. */
    public Integer lastResult(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT last_result FROM " + DbHelper.TABLE_OUTBOX + " WHERE id = ?",
                new String[]{String.valueOf(id)});
        Integer result = null;
        if (c.moveToFirst() && !c.isNull(0)) {
            result = c.getInt(0);
        }
        c.close();
        return result;
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

    /** Released pending RELAY-category rows, grouped-by-recipient upstream. Oldest first. */
    public List<OutboxItem> takeReleasedRelayRows() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<OutboxItem> items = new ArrayList<>();
        Cursor c = db.query(DbHelper.TABLE_OUTBOX, null,
                "status = ? AND category = ? AND hold_until <= ?",
                new String[]{"PENDING", CATEGORY_RELAY, String.valueOf(System.currentTimeMillis())},
                null, null, "enqueued_at ASC");
        while (c.moveToNext()) {
            items.add(mapCursor(c));
        }
        c.close();
        return items;
    }

    /** Sends this item back to PENDING (so it's picked up by a later burst) with its attempt count bumped. */
    public void requeueForRetry(long id, int attempts) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", "PENDING");
        cv.put("attempts", attempts);
        db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
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
        int lastResultIdx = c.getColumnIndexOrThrow("last_result");
        item.lastResult = c.isNull(lastResultIdx) ? null : c.getInt(lastResultIdx);
        return item;
    }

    private void updateStatus(long id, String status) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
    }
}
