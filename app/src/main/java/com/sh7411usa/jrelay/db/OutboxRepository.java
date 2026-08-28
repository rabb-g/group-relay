package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
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

    /** Atomically selects up to `limit` pending rows and flips them to SENDING so overlapping drains cannot double-send. */
    public List<OutboxItem> takeBurst(int limit) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<OutboxItem> list = new ArrayList<>();
        db.beginTransaction();
        try {
            Cursor c = db.query(DbHelper.TABLE_OUTBOX, null, "status = ?", new String[]{"PENDING"},
                    null, null, "enqueued_at ASC", String.valueOf(limit));
            while (c.moveToNext()) {
                OutboxItem item = new OutboxItem();
                item.id = c.getLong(c.getColumnIndexOrThrow("id"));
                int memberIdx = c.getColumnIndexOrThrow("member_id");
                item.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
                item.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
                item.body = c.getString(c.getColumnIndexOrThrow("body"));
                item.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
                list.add(item);
            }
            c.close();
            for (OutboxItem item : list) {
                ContentValues cv = new ContentValues();
                cv.put("status", "SENDING");
                db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(item.id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return list;
    }

    /** Distinct recipient member ids with at least one pending (not yet claimed) message. */
    public List<Long> getDistinctPendingMemberIds() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT DISTINCT member_id FROM " + DbHelper.TABLE_OUTBOX +
                " WHERE status = 'PENDING'", null);
        List<Long> ids = new ArrayList<>();
        while (c.moveToNext()) {
            ids.add(c.isNull(0) ? null : c.getLong(0));
        }
        c.close();
        return ids;
    }

    /** Atomically selects up to `limit` pending rows for one recipient and flips them to SENDING. */
    public List<OutboxItem> takeBurstForMember(Long memberId, int limit) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<OutboxItem> list = new ArrayList<>();
        db.beginTransaction();
        try {
            String selection = "status = 'PENDING' AND " + (memberId != null ? "member_id = " + memberId : "member_id IS NULL");
            Cursor c = db.query(DbHelper.TABLE_OUTBOX, null, selection, null,
                    null, null, "enqueued_at ASC", String.valueOf(limit));
            while (c.moveToNext()) {
                OutboxItem item = new OutboxItem();
                item.id = c.getLong(c.getColumnIndexOrThrow("id"));
                int memberIdx = c.getColumnIndexOrThrow("member_id");
                item.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
                item.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
                item.body = c.getString(c.getColumnIndexOrThrow("body"));
                item.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
                list.add(item);
            }
            c.close();
            for (OutboxItem item : list) {
                ContentValues cv = new ContentValues();
                cv.put("status", "SENDING");
                db.update(DbHelper.TABLE_OUTBOX, cv, "id = ?", new String[]{String.valueOf(item.id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return list;
    }

    public int countPendingForMember(Long memberId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String selection = "status = 'PENDING' AND " + (memberId != null ? "member_id = " + memberId : "member_id IS NULL");
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_OUTBOX + " WHERE " + selection, null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
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
