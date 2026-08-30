package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.sh7411usa.jrelay.model.MessageRecord;

import java.util.ArrayList;
import java.util.List;

public class MessageRepository {

    private final DbHelper dbHelper;

    public MessageRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public void log(Long memberId, String direction, String category, String body) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (memberId != null) {
            cv.put("member_id", memberId);
        }
        cv.put("direction", direction);
        cv.put("category", category);
        cv.put("body", body);
        cv.put("timestamp", System.currentTimeMillis());
        db.insert(DbHelper.TABLE_MESSAGE_LOG, null, cv);
    }

    /**
     * Recent activity across the whole group, collapsed to one row per event: excludes the internal
     * "RELAYED" quota marker (a duplicate of the "RELAY" row already logged for the same message)
     * and the per-recipient "OUT"/"RELAY" fan-out copies (one would otherwise show up per member a
     * relay was delivered to) -- callers only see the single incoming message and who sent it.
     */
    public List<MessageRecord> getRecent(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MESSAGE_LOG, null,
                "category != 'RELAYED' AND NOT (direction = 'OUT' AND category = 'RELAY')", null,
                null, null, "timestamp DESC", String.valueOf(limit));
        List<MessageRecord> list = new ArrayList<>();
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    /** One member's recent activity; excludes the internal "RELAYED" quota marker (see {@link #getRecent}). */
    public List<MessageRecord> getRecentForMember(long memberId, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MESSAGE_LOG, null, "member_id = ? AND category != 'RELAYED'",
                new String[]{String.valueOf(memberId)}, null, null, "timestamp DESC", String.valueOf(limit));
        List<MessageRecord> list = new ArrayList<>();
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    public int countForMember(long memberId, String direction) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ? AND direction = ?", new String[]{String.valueOf(memberId), direction});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public int countForMemberSince(long memberId, long sinceTimestamp) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ? AND timestamp >= ?", new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public long lastActivityForMember(long memberId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT MAX(timestamp) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ?", new String[]{String.valueOf(memberId)});
        long ts = 0;
        if (c.moveToFirst() && !c.isNull(0)) {
            ts = c.getLong(0);
        }
        c.close();
        return ts;
    }

    /**
     * Messages actually relayed to the group today (the shared group daily pool's usage). Counts
     * the dedicated "RELAYED" marker logged only on the success path in
     * {@code CommandProcessor.relayPlainMessage} -- deliberately not the general "RELAY" category,
     * which is logged for every inbound message (including commands and quota-blocked ones) and
     * would otherwise double-count or count messages that were never actually relayed.
     */
    public int countRelayedSince(long sinceTimestamp) {
        return countWhere("category = 'RELAYED' AND direction = 'IN' AND timestamp >= ?",
                new String[]{String.valueOf(sinceTimestamp)});
    }

    /** One member's own relayed messages today (that member's individual daily pool usage). */
    public int countRelayedForMemberSince(long memberId, long sinceTimestamp) {
        return countWhere("member_id = ? AND category = 'RELAYED' AND direction = 'IN' AND timestamp >= ?",
                new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
    }

    /** Sends that exhausted their retry limit today, for the dashboard's "Failed Today" tile. */
    public int countFailedSince(long sinceTimestamp) {
        return countWhere("category = 'FAILED' AND timestamp >= ?", new String[]{String.valueOf(sinceTimestamp)});
    }

    private int countWhere(String selection, String[] args) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG + " WHERE " + selection, args);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public int countSince(long sinceTimestamp) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE timestamp >= ?", new String[]{String.valueOf(sinceTimestamp)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public int countAll() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG, null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /** Permanently erases message history only (members/outbox untouched). Used by "Clear History". */
    public void deleteAll() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DbHelper.TABLE_MESSAGE_LOG, null, null);
    }

    /** Rough on-disk size estimate for the message log, for the "Clear History" button label. */
    public long estimateStorageBytes() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(body)), 0) FROM " + DbHelper.TABLE_MESSAGE_LOG, null);
        long bytes = 0;
        if (c.moveToFirst()) {
            long rowCount = c.getLong(0);
            long bodyBytes = c.getLong(1);
            bytes = bodyBytes + rowCount * 40L;
        }
        c.close();
        return bytes;
    }

    private MessageRecord fromCursor(Cursor c) {
        MessageRecord r = new MessageRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        int memberIdx = c.getColumnIndexOrThrow("member_id");
        r.memberId = c.isNull(memberIdx) ? null : c.getLong(memberIdx);
        r.direction = c.getString(c.getColumnIndexOrThrow("direction"));
        r.category = c.getString(c.getColumnIndexOrThrow("category"));
        r.body = c.getString(c.getColumnIndexOrThrow("body"));
        r.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
        return r;
    }
}
