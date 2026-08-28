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
}
