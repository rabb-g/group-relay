package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.sh7411usa.jrelay.model.Member;

import java.util.ArrayList;
import java.util.List;

public class MemberRepository {

    private final DbHelper dbHelper;

    public MemberRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    public long insert(String phoneE164, String nickname, boolean isAdmin, String addedBy) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", phoneE164);
        cv.put("nickname", nickname);
        cv.put("is_admin", isAdmin ? 1 : 0);
        cv.put("is_muted", 0);
        cv.put("active", 1);
        cv.put("added_by", addedBy);
        cv.put("created_at", System.currentTimeMillis());
        return db.insertOrThrow(DbHelper.TABLE_MEMBERS, null, cv);
    }

    public Member findByPhone(String phoneE164) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MEMBERS, null, "phone_e164 = ?", new String[]{phoneE164}, null, null, null);
        Member m = null;
        if (c.moveToFirst()) {
            m = fromCursor(c);
        }
        c.close();
        return m;
    }

    public Member findById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MEMBERS, null, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        Member m = null;
        if (c.moveToFirst()) {
            m = fromCursor(c);
        }
        c.close();
        return m;
    }

    public Member findActiveByNickname(String nickname) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MEMBERS, null, "active = 1 AND nickname = ? COLLATE NOCASE",
                new String[]{nickname}, null, null, null);
        Member m = null;
        if (c.moveToFirst()) {
            m = fromCursor(c);
        }
        c.close();
        return m;
    }

    public List<Member> getActiveMembers() {
        return query("active = 1", null, "created_at ASC");
    }

    public List<Member> getActiveAdmins() {
        return query("active = 1 AND is_admin = 1", null, "created_at ASC");
    }

    public List<Member> getActiveRecipientsExcept(long excludeId) {
        return query("active = 1 AND is_muted = 0 AND id != ?", new String[]{String.valueOf(excludeId)}, "created_at ASC");
    }

    private List<Member> query(String selection, String[] args, String orderBy) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_MEMBERS, null, selection, args, null, null, orderBy);
        List<Member> list = new ArrayList<>();
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    public void setAdmin(long id, boolean isAdmin) {
        updateColumn(id, "is_admin", isAdmin ? 1 : 0);
    }

    public void setMuted(long id, boolean muted) {
        updateColumn(id, "is_muted", muted ? 1 : 0);
    }

    public void setNickname(long id, String nickname) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("nickname", nickname);
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void setPhone(long id, String phoneE164) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("phone_e164", phoneE164);
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void softRemove(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("active", 0);
        cv.put("removed_at", System.currentTimeMillis());
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Rejoins a previously soft-removed member under the same row (same phone number, unique in the table). */
    public void reactivate(long id, String nickname, String addedBy) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("nickname", nickname);
        cv.put("added_by", addedBy);
        cv.put("is_admin", 0);
        cv.put("is_muted", 0);
        cv.put("active", 1);
        cv.putNull("removed_at");
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Sets a custom send pace for this member, overriding the group default until cleared. */
    public void setRateLimitOverride(long id, int burstMin, int burstMax, int minWait, int maxWait, boolean initialDelay) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("rate_limit_custom", 1);
        cv.put("rate_burst_min", burstMin);
        cv.put("rate_burst_max", burstMax);
        cv.put("rate_min_wait", minWait);
        cv.put("rate_max_wait", maxWait);
        cv.put("rate_initial_delay", initialDelay ? 1 : 0);
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Reverts this member to the group's default send pacing. */
    public void clearRateLimitOverride(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("rate_limit_custom", 0);
        cv.putNull("rate_burst_min");
        cv.putNull("rate_burst_max");
        cv.putNull("rate_min_wait");
        cv.putNull("rate_max_wait");
        cv.putNull("rate_initial_delay");
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    private void updateColumn(long id, String column, int value) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(column, value);
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    private Member fromCursor(Cursor c) {
        Member m = new Member();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.phoneE164 = c.getString(c.getColumnIndexOrThrow("phone_e164"));
        m.nickname = c.getString(c.getColumnIndexOrThrow("nickname"));
        m.isAdmin = c.getInt(c.getColumnIndexOrThrow("is_admin")) != 0;
        m.isMuted = c.getInt(c.getColumnIndexOrThrow("is_muted")) != 0;
        m.active = c.getInt(c.getColumnIndexOrThrow("active")) != 0;
        m.addedBy = c.getString(c.getColumnIndexOrThrow("added_by"));
        m.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        int removedIdx = c.getColumnIndexOrThrow("removed_at");
        m.removedAt = c.isNull(removedIdx) ? null : c.getLong(removedIdx);
        m.rateLimitCustom = c.getInt(c.getColumnIndexOrThrow("rate_limit_custom")) != 0;
        m.rateBurstMin = getNullableInt(c, "rate_burst_min");
        m.rateBurstMax = getNullableInt(c, "rate_burst_max");
        m.rateMinWait = getNullableInt(c, "rate_min_wait");
        m.rateMaxWait = getNullableInt(c, "rate_max_wait");
        m.rateInitialDelay = getNullableInt(c, "rate_initial_delay");
        return m;
    }

    private Integer getNullableInt(Cursor c, String column) {
        int idx = c.getColumnIndexOrThrow(column);
        return c.isNull(idx) ? null : c.getInt(idx);
    }
}
