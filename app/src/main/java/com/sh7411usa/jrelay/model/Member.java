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

    /** When true, dailyLimitValue caps this member's own relayed messages per day, independent of the group pool. */
    public boolean dailyLimitCustom;
    public Integer dailyLimitValue;
    public int dailyLimitBonus;
    public long dailyLimitBonusWindowStart;

    /** Cumulative sends that exhausted retries since this member's last successful send. */
    public int failedCount;

    /** message_log id of the most recent post relayed to this member, for Reply Mode targeting. Null if none yet. */
    public Long lastPostReceivedId;

    /**
     * Stable, admin-assigned sub-group for group-MMS routing (phase 3). Null means unassigned -
     * the member is not in any sub-group and is unaffected by sub-group sending, which must
     * remain a fully safe, fully-functional state. Never conflate with 0: the column has no
     * DEFAULT, so every row that existed before this field was added is NULL, not 0, and must be
     * read that way (see MemberRepository#fromCursor).
     */
    public Long subgroupId;
}
