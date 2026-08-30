package com.sh7411usa.jrelay.util;

/** Send-pacing settings: global for the whole app (not configurable per member). */
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

    private RateLimitConfig(Prefs prefs) {
        this.burstMin = prefs.getBurstMin();
        this.burstMax = prefs.getBurstMax();
        this.minWaitSeconds = prefs.getMinWaitSeconds();
        this.maxWaitSeconds = prefs.getMaxWaitSeconds();
        this.initialDelayEnabled = prefs.isInitialDelayEnabled();
        this.staggeringEnabled = prefs.isStaggeringEnabled();
        this.burstMode = prefs.getBurstMode();
        this.fixedBurstSize = prefs.getFixedBurstSize();
        this.microspacingEnabled = prefs.isMicrospacingEnabled();
        this.microspacingMode = prefs.getMicrospacingMode();
        this.microspacingFixedMs = prefs.getMicrospacingFixedMs();
        this.microspacingMinMs = prefs.getMicrospacingMinMs();
        this.microspacingMaxMs = prefs.getMicrospacingMaxMs();
    }

    public static RateLimitConfig groupDefault(Prefs prefs) {
        return new RateLimitConfig(prefs);
    }
}
