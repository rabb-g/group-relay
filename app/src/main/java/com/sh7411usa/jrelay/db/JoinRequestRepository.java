package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Pending {@code #join Name} requests under JoinPolicy.REQUIRE_APPROVAL, so the owner can approve
 * or decline them in the app instead of only by texting {@code #add}. One row per phone number: a
 * repeat #join replaces the earlier name and time. A row is removed when the request is approved
 * (by the app or by an admin's {@code #add}, both of which go through CommandProcessor#addMember)
 * or declined in the app.
 */
public class JoinRequestRepository {

    public static final class JoinRequest {
        public final String phoneE164;
        public final String nickname;
        public final long requestedAt;

        public JoinRequest(String phoneE164, String nickname, long requestedAt) {
            this.phoneE164 = phoneE164;
            this.nickname = nickname;
            this.requestedAt = requestedAt;
        }
    }

    private final DbHelper dbHelper;

    public JoinRequestRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    /** INSERT OR REPLACE: a repeat #join from the same number updates the name and time, never duplicates. */
    public void upsert(String phoneE164, String nickname) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", phoneE164);
        cv.put("nickname", nickname);
        cv.put("requested_at", System.currentTimeMillis());
        db.insertWithOnConflict(DbHelper.TABLE_JOIN_REQUESTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Oldest first. */
    public List<JoinRequest> getAll() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_JOIN_REQUESTS,
                new String[]{"phone_e164", "nickname", "requested_at"},
                null, null, null, null, "requested_at ASC");
        List<JoinRequest> result = new ArrayList<>();
        while (c.moveToNext()) {
            result.add(new JoinRequest(c.getString(0), c.getString(1), c.getLong(2)));
        }
        c.close();
        return result;
    }

    public int count() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DbHelper.TABLE_JOIN_REQUESTS, null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    public void delete(String phoneE164) {
        // A null bind arg throws in SQLiteProgram; a null phone can't have a row anyway.
        if (phoneE164 == null) {
            return;
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DbHelper.TABLE_JOIN_REQUESTS, "phone_e164 = ?", new String[]{phoneE164});
    }
}
