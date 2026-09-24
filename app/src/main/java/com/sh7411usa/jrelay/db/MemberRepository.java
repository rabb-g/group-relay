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

    /**
     * Count of active admins, matching the same predicate as {@link #getActiveAdmins()}. Used to
     * refuse removing/demoting the last admin over SMS (CommandProcessor#handleStop,
     * #handleRemove), since re-adding a removed admin does not restore admin status and setAdmin()
     * is only reachable from the app UI.
     */
    public int countActiveAdmins() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + DbHelper.TABLE_MEMBERS + " WHERE active = 1 AND is_admin = 1", null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /**
     * Count of active members, matching the same {@code active = 1} predicate as the other
     * active-member queries. Backs the group capacity check (CommandProcessor#handleJoinRequest,
     * #handleAdd) against {@code Prefs.getMaxMembers()}.
     */
    public int countActiveMembers() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + DbHelper.TABLE_MEMBERS + " WHERE active = 1", null);
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /**
     * THE RULE FOR EVERY QUERY THAT FEEDS A SEND PATH: send-eligibility is
     * {@code active = 1 AND is_muted = 0}, plus whatever scope that particular send needs.
     * Four queries in this class encode some version of it and they are NOT interchangeable:
     *
     * <ul>
     *   <li>{@link #getActiveRecipientsExcept} - active, unmuted, not the sender. The SMS fan-out.</li>
     *   <li>{@link #getSubgroupMembers} - active, unmuted, in one sub-group. The group-MMS fan-out.</li>
     *   <li>{@link #getUnassignedActiveUnmutedMembers} - active, unmuted, no sub-group. The
     *       individual half of a group-mode post.</li>
     *   <li>{@link #getUnassignedActiveMembers} - active, ANY mute state, no sub-group.
     *       <b>Deliberately unfiltered, and NOT for sending.</b> The admin assignment screen needs
     *       to see muted members or they become unassignable.</li>
     * </ul>
     *
     * <p>If you are adding a fifth query for a new send path, it needs {@code is_muted = 0}. This
     * is not hypothetical: the group-MMS fan-out shipped in review with that clause missing,
     * because it was written against the {@code active = 1} queries that already existed, and
     * enabling group mode would have resumed sending to every member who had asked to be muted.
     * Mute is a promise to a person. Copy from a sibling that sends, never from the admin one.
     *
     * <p>Once a fifth or sixth send query exists, extract the predicate to a shared constant
     * rather than repeating it again - four is about where the repetition is still cheaper than
     * the machinery.
     */
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

    /**
     * The ONLY place admin status can be granted anywhere in the app - there is no SMS command for
     * it. That makes demoting the last active admin unrecoverable without physical access to the
     * relay phone, so callers must check {@link #countActiveAdmins()} before passing false for an
     * existing admin. See the note on {@link #softRemove} - the same invariant, not enforced here.
     */
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

    /**
     * INVARIANT THIS METHOD DOES NOT ENFORCE: the group must never reach zero active admins. Once
     * it does, every admin-gated command refuses everyone permanently and there is no way back by
     * text - {@link #reactivate} resets is_admin to 0, and {@link #setAdmin} is reachable only from
     * the app UI on the relay handset. Every caller must therefore check
     * {@link #countActiveAdmins()} first; as of 5.5 both do (CommandProcessor#handleStop and
     * #removeMember, the latter reached only via the guarded #handleRemove and the guarded UI).
     * A new caller that skips that check can brick the group, so add the guard there too - or move
     * it in here, which requires this method to report refusal so callers stop sending the
     * "you left the group" notices that follow a successful removal.
     */
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
        cv.putNull("last_post_received_id");
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Sets a custom per-day relay cap for this member, independent of the shared group pool. A null value means unlimited. */
    public void setDailyLimitOverride(long id, Integer value) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_custom", 1);
        if (value != null) {
            cv.put("daily_limit_value", value);
        } else {
            cv.putNull("daily_limit_value");
        }
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Reverts this member to drawing only from the shared group pool (no personal cap). */
    public void clearDailyLimitOverride(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_custom", 0);
        cv.putNull("daily_limit_value");
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Writes `value` as every active member's custom daily cap (enabling it), or only for members without one yet. */
    public void bulkSetDailyLimit(int value, boolean onlyNonCustomized) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_custom", 1);
        cv.put("daily_limit_value", value);
        String selection = onlyNonCustomized ? "active = 1 AND daily_limit_custom = 0" : "active = 1";
        db.update(DbHelper.TABLE_MEMBERS, cv, selection, null);
    }

    /** Raw setter for this member's today-bonus (extra allowance added via #override) and the window it belongs to. */
    public void setDailyLimitBonusState(long id, int bonus, long windowStart) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("daily_limit_bonus", bonus);
        cv.put("daily_limit_bonus_window_start", windowStart);
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Increments this member's cumulative failed-send count and returns the new value. */
    public int incrementFailedCount(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("UPDATE " + DbHelper.TABLE_MEMBERS + " SET failed_count = failed_count + 1 WHERE id = ?",
                new Object[]{id});
        Member updated = findById(id);
        return updated != null ? updated.failedCount : 0;
    }

    public void resetFailedCount(long id) {
        updateColumn(id, "failed_count", 0);
    }

    /**
     * Records the same post id on every member in `memberIds` in one statement, or clears the column
     * for them when `postLogId` is null. Clearing is what app-originated admin messages use, so a
     * member's next plain reply falls through to the admin route instead of targeting a stale post.
     * The per-member alternative issues one implicit transaction per row; this commits once per chunk.
     * No-op on a null/empty list.
     */
    public void setLastPostReceivedIdForAll(List<Long> memberIds, Long postLogId) {
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (postLogId != null) {
            cv.put("last_post_received_id", postLogId);
        } else {
            cv.putNull("last_post_received_id");
        }
        int chunkSize = 500;
        for (int start = 0; start < memberIds.size(); start += chunkSize) {
            List<Long> chunk = memberIds.subList(start, Math.min(start + chunkSize, memberIds.size()));
            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[chunk.size()];
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) {
                    placeholders.append(',');
                }
                placeholders.append('?');
                args[i] = String.valueOf(chunk.get(i));
            }
            db.update(DbHelper.TABLE_MEMBERS, cv, "id IN (" + placeholders + ")", args);
        }
    }

    /**
     * Assigns (or, with {@code subgroupId == null}, unassigns) a member to a sub-group for
     * phase-3 group-MMS routing. This does not send anything - see the class-level note on
     * {@link DbHelper} for what a caller must do before and after calling this. In particular:
     * once a sub-group's roster has been sent by MMS, that member's phone number is visible to
     * the other members of the group permanently, and moving them to a different sub_group id
     * later does not undo it - it only starts routing future sends elsewhere.
     */
    public void assignSubgroup(long id, Long subgroupId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (subgroupId != null) {
            cv.put("subgroup_id", subgroupId);
        } else {
            cv.putNull("subgroup_id");
        }
        db.update(DbHelper.TABLE_MEMBERS, cv, "id = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Members of one sub-group who are eligible to actually receive a send: {@code active = 1},
     * same as every other send-eligible query in this class, and (since D1 of the 5.9
     * group-MMS-fan-out review) {@code is_muted = 0} - this is the query {@code SmsSendService}
     * resolves at send time to build the group MMS recipient list, so a muted member excluded
     * here is a muted member jRelay itself never sends to. It cannot, however, stop that member
     * from seeing the thread at all: once any group MMS has gone out to a sub-group, every
     * recipient's handset holds a single MMS conversation with every other recipient in it, and a
     * muted member who was already in that thread before being muted still receives whatever
     * their groupmates reply directly into it. jRelay only controls what IT sends; that part of
     * the promise "muted means nothing more reaches you" cannot be kept under group delivery, and
     * this method deliberately does not try to imply otherwise.
     *
     * <p>The only other caller this query had (MembershipActivity, for admin roster/assignment
     * screens) does not exist - {@code getSubgroupMembers} is called exclusively from the
     * send-time recipient resolution, so narrowing it here does not need a separate unmuted
     * variant the way {@link #getUnassignedActiveMembers()} does below.
     */
    public List<Member> getSubgroupMembers(long subgroupId) {
        return query("active = 1 AND is_muted = 0 AND subgroup_id = ?",
                new String[]{String.valueOf(subgroupId)}, "created_at ASC");
    }

    /**
     * Every active member of a sub-group, <b>including muted ones</b> — unlike
     * {@link #getSubgroupMembers(long)}, which excludes them because it answers "who should
     * receive this relayed post?".
     *
     * <p>This answers a different question: "who is in this thread?". Under group MMS a muted
     * member is still a participant of the real MMS thread and can still write into it; muting
     * only governs what jRelay relays. So for anything about the composition of the group —
     * planning a merge, or composing the roster that tells people which number is whose — the
     * muted member must be counted and listed, or the roster would omit somebody whose messages
     * the others will nonetheless see, which is the exact confusion the roster exists to remove.
     *
     * <p>It also keeps merge planning consistent with {@link #countMembersPerSubgroup()}, which
     * counts every active member; sizing a group by one query and listing it by another that
     * filters differently is how a merge would silently overfill its destination.
     */
    public List<Member> getActiveSubgroupMembers(long subgroupId) {
        return query("active = 1 AND subgroup_id = ?",
                new String[]{String.valueOf(subgroupId)}, "created_at ASC");
    }

    /**
     * Distinct sub-group ids currently in use by an active member. Filtered to {@code active = 1}
     * so a sub-group whose only members have all been removed does not show up as a group still
     * needing a send target; NULL (unassigned) is excluded since it is not a sub-group id.
     */
    public List<Long> getDistinctSubgroupIds() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT DISTINCT subgroup_id FROM " + DbHelper.TABLE_MEMBERS +
                " WHERE active = 1 AND subgroup_id IS NOT NULL ORDER BY subgroup_id ASC", null);
        List<Long> ids = new ArrayList<>();
        while (c.moveToNext()) {
            ids.add(c.getLong(0));
        }
        c.close();
        return ids;
    }

    /**
     * Active member count per sub-group, keyed by subgroup_id. Used to size sub-groups (the
     * redesign's target is ~9 per group) and to find the smallest group for a new join. Filtered
     * to {@code active = 1} for the same reason as {@link #getDistinctSubgroupIds()} - a removed
     * member should not count toward a group's size. Unassigned members (NULL) are excluded; use
     * {@link #getUnassignedActiveMembers()} for those.
     */
    public java.util.Map<Long, Integer> countMembersPerSubgroup() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT subgroup_id, COUNT(*) FROM " + DbHelper.TABLE_MEMBERS +
                " WHERE active = 1 AND subgroup_id IS NOT NULL GROUP BY subgroup_id", null);
        java.util.Map<Long, Integer> counts = new java.util.LinkedHashMap<>();
        while (c.moveToNext()) {
            counts.put(c.getLong(0), c.getInt(1));
        }
        c.close();
        return counts;
    }

    /**
     * Active members with no sub-group assignment - the pool an admin assignment or auto-balance
     * step draws from. Filtered to {@code active = 1} because a removed member should never be
     * newly assigned to a sub-group.
     */
    public List<Member> getUnassignedActiveMembers() {
        return query("active = 1 AND subgroup_id IS NULL", null, "created_at ASC");
    }

    /**
     * Same rows as {@link #getUnassignedActiveMembers()}, minus muted members - the variant for a
     * send path (CommandProcessor#postToSubgroups' individual-member half) rather than an admin
     * roster/assignment screen. Kept separate rather than adding {@code is_muted = 0} to the
     * shared query: {@link #getUnassignedActiveMembers()}'s other caller, MembershipActivity's
     * sub-group assignment planner, must still see a muted member as needing a sub-group
     * assignment like anyone else - mute and sub-group membership are independent, and hiding a
     * muted member from the planner would leave them permanently unassigned with no way for an
     * admin to fix it from that screen.
     */
    public List<Member> getUnassignedActiveUnmutedMembers() {
        return query("active = 1 AND is_muted = 0 AND subgroup_id IS NULL", null, "created_at ASC");
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
        m.dailyLimitCustom = c.getInt(c.getColumnIndexOrThrow("daily_limit_custom")) != 0;
        m.dailyLimitValue = getNullableInt(c, "daily_limit_value");
        m.dailyLimitBonus = c.getInt(c.getColumnIndexOrThrow("daily_limit_bonus"));
        m.dailyLimitBonusWindowStart = c.getLong(c.getColumnIndexOrThrow("daily_limit_bonus_window_start"));
        m.failedCount = c.getInt(c.getColumnIndexOrThrow("failed_count"));
        int lastPostIdx = c.getColumnIndexOrThrow("last_post_received_id");
        m.lastPostReceivedId = c.isNull(lastPostIdx) ? null : c.getLong(lastPostIdx);
        int subgroupIdx = c.getColumnIndexOrThrow("subgroup_id");
        m.subgroupId = c.isNull(subgroupIdx) ? null : c.getLong(subgroupIdx);
        return m;
    }

    private Integer getNullableInt(Cursor c, String column) {
        int idx = c.getColumnIndexOrThrow(column);
        return c.isNull(idx) ? null : c.getInt(idx);
    }
}
