package com.sh7411usa.jrelay.model;

public class Member {
    public long id;
    public String phoneE164;
    public String nickname;
    public boolean isAdmin;
    public boolean isMuted;
    public boolean active;
    public String addedBy;
    public long createdAt;
    public Long removedAt;

    /** When true, the rate* fields below override the group's default send pacing for this member. */
    public boolean rateLimitCustom;
    public Integer rateBurstMin;
    public Integer rateBurstMax;
    public Integer rateMinWait;
    public Integer rateMaxWait;
    public Integer rateInitialDelay;

    /** When true, dailyLimitValue caps this member's own relayed messages per day, independent of the group pool. */
    public boolean dailyLimitCustom;
    public Integer dailyLimitValue;
    public int dailyLimitBonus;
    public long dailyLimitBonusWindowStart;

    /** Cumulative sends that exhausted retries since this member's last successful send. */
    public int failedCount;
}
