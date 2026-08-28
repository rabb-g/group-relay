package com.sh7411usa.jrelay.util;

import android.content.Context;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.model.Member;

import java.util.Calendar;

/**
 * Two independent daily quotas on relayed messages: a shared group pool (checked first, takes
 * precedence) and an optional per-member cap. Both reset at the same configurable time of day
 * ({@link Prefs#getDailyLimitResetMinuteOfDay()}, default midnight). Usage is derived from
 * {@code message_log} (no denormalized counters); each side's "bonus" (added by #override /
 * the Override button) is lazily zeroed here once its stored window no longer matches the
 * current one, so no background job is needed to reset anything at rollover.
 */
public class DailyLimitManager {

    public static class Status {
        public final boolean enabled;
        public final int used;
        public final int limit;
        public final long resetAtMillis;

        Status(boolean enabled, int used, int limit, long resetAtMillis) {
            this.enabled = enabled;
            this.used = used;
            this.limit = limit;
            this.resetAtMillis = resetAtMillis;
        }

        public int remaining() {
            return enabled ? Math.max(0, limit - used) : Integer.MAX_VALUE;
        }

        public boolean isExhausted() {
            return enabled && remaining() <= 0;
        }
    }

    private final Prefs prefs;
    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;

    public DailyLimitManager(Context context) {
        Context appContext = context.getApplicationContext();
        prefs = new Prefs(appContext);
        memberRepository = new MemberRepository(appContext);
        messageRepository = new MessageRepository(appContext);
    }

    /** Start of the current reset window: the most recent reset-time instant at or before now. */
    public long currentWindowStart() {
        Calendar cal = Calendar.getInstance();
        int resetMinute = prefs.getDailyLimitResetMinuteOfDay();
        cal.set(Calendar.HOUR_OF_DAY, resetMinute / 60);
        cal.set(Calendar.MINUTE, resetMinute % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayReset = cal.getTimeInMillis();
        if (todayReset > System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, -1);
            todayReset = cal.getTimeInMillis();
        }
        return todayReset;
    }

    public long nextResetAtMillis() {
        return currentWindowStart() + 24L * 60 * 60 * 1000;
    }

    public Status groupStatus() {
        long windowStart = currentWindowStart();
        int bonus = lazyGroupBonus(windowStart);
        int used = messageRepository.countRelayedSince(windowStart);
        int limit = prefs.getGroupDailyLimitValue() + bonus;
        return new Status(prefs.isGroupDailyLimitEnabled(), used, limit, nextResetAtMillis());
    }

    public Status memberStatus(Member member) {
        long windowStart = currentWindowStart();
        int bonus = lazyMemberBonus(member, windowStart);
        int used = messageRepository.countRelayedForMemberSince(member.id, windowStart);
        int limit = (member.dailyLimitValue != null ? member.dailyLimitValue : 0) + bonus;
        return new Status(member.dailyLimitCustom, used, limit, nextResetAtMillis());
    }

    /** Adds one message of headroom to the group pool for the current window. */
    public void overrideGroup() {
        long windowStart = currentWindowStart();
        int bonus = lazyGroupBonus(windowStart) + 1;
        prefs.setGroupDailyLimitBonus(bonus);
        prefs.setGroupDailyLimitBonusWindowStart(windowStart);
    }

    /** Adds one message of headroom to a member's individual cap. Returns false if they have no custom cap. */
    public boolean overrideMember(Member member) {
        if (!member.dailyLimitCustom) {
            return false;
        }
        long windowStart = currentWindowStart();
        int bonus = lazyMemberBonus(member, windowStart) + 1;
        memberRepository.setDailyLimitBonusState(member.id, bonus, windowStart);
        return true;
    }

    /** Seeds every (or every non-customized) active member's individual cap from one number. */
    public void bulkSetIndividualLimit(int value, boolean onlyNonCustomized) {
        memberRepository.bulkSetDailyLimit(value, onlyNonCustomized);
    }

    private int lazyGroupBonus(long windowStart) {
        if (prefs.getGroupDailyLimitBonusWindowStart() != windowStart) {
            prefs.setGroupDailyLimitBonus(0);
            prefs.setGroupDailyLimitBonusWindowStart(windowStart);
            return 0;
        }
        return prefs.getGroupDailyLimitBonus();
    }

    private int lazyMemberBonus(Member member, long windowStart) {
        if (member.dailyLimitBonusWindowStart != windowStart) {
            memberRepository.setDailyLimitBonusState(member.id, 0, windowStart);
            return 0;
        }
        return member.dailyLimitBonus;
    }
}
