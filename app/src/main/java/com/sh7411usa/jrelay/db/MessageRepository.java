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

    public long log(Long memberId, String direction, String category, String body) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (memberId != null) {
            cv.put("member_id", memberId);
        }
        cv.put("direction", direction);
        cv.put("category", category);
        cv.put("body", body);
        cv.put("timestamp", System.currentTimeMillis());
        return db.insert(DbHelper.TABLE_MESSAGE_LOG, null, cv);
    }

    /** One logged message by row id, or null when it no longer exists. Used to resolve a reply's target post. */
    public MessageRecord getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MESSAGE_LOG, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        MessageRecord r = null;
        if (c.moveToFirst()) {
            r = fromCursor(c);
        }
        c.close();
        return r;
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

    /**
     * One member's own activity: what they sent, plus messages addressed to them specifically
     * (direct messages, replies, admin and system notices, command answers).
     *
     * <p>Excludes the internal "RELAYED" quota marker (see {@link #getRecent}), and also every
     * {@code OUT}/{@code RELAY} row. Each group post is logged once per recipient, under the
     * recipient's id, so that per-member delivery counts work. Without this filter a member's
     * activity screen listed every post anyone made to the group, since each one had been
     * delivered to them. {@link #getRecent} already filters the same rows for the dashboard feed.
     */
    public List<MessageRecord> getRecentForMember(long memberId, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MESSAGE_LOG, null,
                "member_id = ? AND category != 'RELAYED' AND NOT (direction = 'OUT' AND category = 'RELAY')",
                new String[]{String.valueOf(memberId)}, null, null, "timestamp DESC", String.valueOf(limit));
        List<MessageRecord> list = new ArrayList<>();
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    /**
     * A member's messages in one direction. Excludes the internal "RELAYED" quota marker (see
     * {@link #getRecent}): without this, every relayed post from a member logs both a "RELAY" row
     * and a "RELAYED" marker row in direction IN, so {@code countForMember(id, "IN")} counted each
     * relayed post twice.
     */
    public int countForMember(long memberId, String direction) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ? AND direction = ? AND category != 'RELAYED'",
                new String[]{String.valueOf(memberId), direction});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /**
     * A member's activity of either direction since a cutoff. Excludes the "RELAYED" marker (see
     * {@link #getRecent}) but, like the pre-existing behavior, still mixes IN and OUT rows - so a
     * member who only ever receives messages still counts as "active" here. Callers that need a
     * member's own posting activity (e.g. an activity-level indicator) should use
     * {@link #countInboundForMemberSince} instead.
     */
    public int countForMemberSince(long memberId, long sinceTimestamp) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE member_id = ? AND timestamp >= ? AND category != 'RELAYED'",
                new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /**
     * A member's own inbound activity since a cutoff - messages the member sent in, not
     * messages relayed to them. Excludes the "RELAYED" marker (see {@link #getRecent}) for the
     * same double-counting reason as {@link #countForMember}. Use this instead of
     * {@link #countForMemberSince} wherever "active" should mean "this member posted", not
     * "this member had any traffic logged against them".
     */
    public int countInboundForMemberSince(long memberId, long sinceTimestamp) {
        return countWhere("member_id = ? AND direction = 'IN' AND timestamp >= ? AND category != 'RELAYED'",
                new String[]{String.valueOf(memberId), String.valueOf(sinceTimestamp)});
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

    /** Messages logged since a cutoff, group-wide. Excludes the "RELAYED" marker (see {@link #getRecent}). */
    public int countSince(long sinceTimestamp) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE timestamp >= ? AND category != 'RELAYED'", new String[]{String.valueOf(sinceTimestamp)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /** All-time message count, group-wide. Excludes the "RELAYED" marker (see {@link #getRecent}). */
    public int countAll() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_MESSAGE_LOG +
                " WHERE category != 'RELAYED'", null);
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

    /**
     * Permanently deletes message_log rows older than {@code cutoffTimestamp}. Returns the number
     * of rows deleted. Backed by the {@code idx_message_log_timestamp} index (see
     * {@link DbHelper}), so this is a single indexed range delete, not a table scan.
     *
     * <p>This is a bulk maintenance operation, not part of the per-message write path: {@link #log}
     * runs on every inbound/outbound message (~101 rows per relayed post), so calling a prune from
     * there would turn one cheap insert into a delete scan on every single message. Call this only
     * from an infrequent, off-the-hot-path trigger - e.g. once a day from a background job or
     * app-open check - never from {@link #log} or any per-message code path.
     */
    public int pruneOlderThan(long cutoffTimestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(DbHelper.TABLE_MESSAGE_LOG, "timestamp < ?", new String[]{String.valueOf(cutoffTimestamp)});
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
