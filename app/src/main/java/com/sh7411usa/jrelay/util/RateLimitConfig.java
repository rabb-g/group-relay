package com.sh7411usa.jrelay.util;

import com.sh7411usa.jrelay.model.Member;

/** Effective send-pacing settings for a member: their own override if set, otherwise the group default. */
public class RateLimitConfig {

    public final int burstMin;
    public final int burstMax;
    public final int minWaitSeconds;
    public final int maxWaitSeconds;
    public final boolean initialDelayEnabled;

    private RateLimitConfig(int burstMin, int burstMax, int minWaitSeconds, int maxWaitSeconds, boolean initialDelayEnabled) {
        this.burstMin = burstMin;
        this.burstMax = burstMax;
        this.minWaitSeconds = minWaitSeconds;
        this.maxWaitSeconds = maxWaitSeconds;
        this.initialDelayEnabled = initialDelayEnabled;
    }

    public static RateLimitConfig forMember(Prefs prefs, Member member) {
        if (member != null && member.rateLimitCustom) {
            return new RateLimitConfig(
                    member.rateBurstMin != null ? member.rateBurstMin : prefs.getBurstMin(),
                    member.rateBurstMax != null ? member.rateBurstMax : prefs.getBurstMax(),
                    member.rateMinWait != null ? member.rateMinWait : prefs.getMinWaitSeconds(),
                    member.rateMaxWait != null ? member.rateMaxWait : prefs.getMaxWaitSeconds(),
                    member.rateInitialDelay != null ? member.rateInitialDelay != 0 : prefs.isInitialDelayEnabled());
        }
        return groupDefault(prefs);
    }

    public static RateLimitConfig groupDefault(Prefs prefs) {
        return new RateLimitConfig(prefs.getBurstMin(), prefs.getBurstMax(),
                prefs.getMinWaitSeconds(), prefs.getMaxWaitSeconds(), prefs.isInitialDelayEnabled());
    }
}
