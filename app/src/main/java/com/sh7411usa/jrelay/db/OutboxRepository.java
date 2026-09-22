package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OutboxRepository {

    /** Outbox category for a relayed group post: the only kind that is held for coalescing and salted at send. */
    public static final String CATEGORY_RELAY = "RELAY";

    public static class OutboxItem {
        public long id;
        public Long memberId;
        public String phoneE164;
        public String body;
        public int attempts;
        public String category;
        public boolean applySalt;
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

    /** Messages still waiting to go out: not yet claimed for sending, or currently mid-send. */
    public int countUnsent() {
        return (int) queryScalar("COUNT(*)", "status IN ('PENDING', 'SENDING')", null);
    }

    public int countPending() {
        return (int) queryScalar("COUNT(*)", "status = 'PENDING'", null);
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
        return item;
    }

    private void updateStatus(long id, String status) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
    }
}
