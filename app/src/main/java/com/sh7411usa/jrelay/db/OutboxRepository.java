package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OutboxRepository {

    public static class OutboxItem {
        public long id;
        public Long memberId;
        public String phoneE164;
        public String body;
        public int attempts;
    }

    private final DbHelper dbHelper;

    public OutboxRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public void enqueue(Long memberId, String phoneE164, String body) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (memberId != null) {
            cv.put("member_id", memberId);
        }
        cv.put("phone_e164", phoneE164);
        cv.put("body", body);
        cv.put("enqueued_at", System.currentTimeMillis());
        cv.put("status", "PENDING");
        db.insert(DbHelper.TABLE_OUTBOX, null, cv);
    }

    /**
     * Atomically selects up to `limit` pending rows and flips them to SENDING so overlapping
     * drains cannot double-send. Sending is global (not per-recipient), so `shuffle` optionally
     * randomizes which pending rows are chosen instead of always taking the oldest first.
     */
    public List<OutboxItem> takeBurst(int limit, boolean shuffle) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<OutboxItem> selected;
        db.beginTransaction();
        try {
            Cursor c = db.query(DbHelper.TABLE_OUTBOX, null, "status = ?", new String[]{"PENDING"},
                    null, null, "enqueued_at ASC");
            List<OutboxItem> pending = new ArrayList<>();
            while (c.moveToNext()) {
                OutboxItem item = new OutboxItem();
                item.id = c.getLong(c.getColumnIndexOrThrow("id"));
                int memberIdx = c.getColumnIndexOrThrow("member_id");
                item.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
                item.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
                item.body = c.getString(c.getColumnIndexOrThrow("body"));
                item.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
                pending.add(item);
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
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_OUTBOX +
                " WHERE status IN ('PENDING', 'SENDING')", null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public int countPending() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_OUTBOX +
                " WHERE status = 'PENDING'", null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public void markSent(long id) {
        updateStatus(id, "SENT");
    }

    public void markFailed(long id) {
        updateStatus(id, "FAILED");
    }

    /** Sends this item back to PENDING (so it's picked up by a later burst) with its attempt count bumped. */
    public void requeueForRetry(long id, int attempts) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", "PENDING");
        cv.put("attempts", attempts);
        db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    private void updateStatus(long id, String status) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(id)});
    }
}
