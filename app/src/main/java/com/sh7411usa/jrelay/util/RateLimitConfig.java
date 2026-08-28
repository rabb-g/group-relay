package com.sh7411usa.jrelay.util;

import com.sh7411usa.jrelay.model.Member;

/**
 * Effective send-pacing settings for a member. Burst size range, inter-burst wait range, and
 * initial-delay each fall back to a member's own override if set, otherwise the group default.
 * Burst mode, microspacing, and staggering on/off are always the group setting (not currently
 * member-overridable).
 */
public class RateLimitConfig {

    public final int burstMin;
    public final int burstMax;
    public final int minWaitSeconds;
    public final int maxWaitSeconds;
    public final boolean initialDelayEnabled;

    public final boolean staggeringEnabled;
    public final Prefs.BurstMode burstMode;
    public final int fixedBurstSize;
    public final boolean microspacingEnabled;
    public final Prefs.MicrospacingMode microspacingMode;
    public final int microspacingFixedMs;
    public final int microspacingMinMs;
    public final int microspacingMaxMs;

    private RateLimitConfig(int burstMin, int burstMax, int minWaitSeconds, int maxWaitSeconds, boolean initialDelayEnabled,
                             Prefs prefs) {
        this.burstMin = burstMin;
        this.burstMax = burstMax;
        this.minWaitSeconds = minWaitSeconds;
        this.maxWaitSeconds = maxWaitSeconds;
        this.initialDelayEnabled = initialDelayEnabled;
        this.staggeringEnabled = prefs.isStaggeringEnabled();
        this.burstMode = prefs.getBurstMode();
        this.fixedBurstSize = prefs.getFixedBurstSize();
        this.microspacingEnabled = prefs.isMicrospacingEnabled();
        this.microspacingMode = prefs.getMicrospacingMode();
        this.microspacingFixedMs = prefs.getMicrospacingFixedMs();
        this.microspacingMinMs = prefs.getMicrospacingMinMs();
        this.microspacingMaxMs = prefs.getMicrospacingMaxMs();
    }

    public static RateLimitConfig forMember(Prefs prefs, Member member) {
        if (member != null && member.rateLimitCustom) {
            return new RateLimitConfig(
                    member.rateBurstMin != null ? member.rateBurstMin : prefs.getBurstMin(),
                    member.rateBurstMax != null ? member.rateBurstMax : prefs.getBurstMax(),
                    member.rateMinWait != null ? member.rateMinWait : prefs.getMinWaitSeconds(),
                    member.rateMaxWait != null ? member.rateMaxWait : prefs.getMaxWaitSeconds(),
                    member.rateInitialDelay != null ? member.rateInitialDelay != 0 : prefs.isInitialDelayEnabled(),
                    prefs);
        }
        return groupDefault(prefs);
    }

    public static RateLimitConfig groupDefault(Prefs prefs) {
        return new RateLimitConfig(prefs.getBurstMin(), prefs.getBurstMax(),
                prefs.getMinWaitSeconds(), prefs.getMaxWaitSeconds(), prefs.isInitialDelayEnabled(), prefs);
    }
}
