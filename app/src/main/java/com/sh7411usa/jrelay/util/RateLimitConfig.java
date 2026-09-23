package com.sh7411usa.jrelay.util;

/**
 * Send-pacing settings: global for the whole app (not configurable per member).
 *
 * <p>Every value is clamped HERE, on read, not only where Settings writes it. Clamping on save
 * alone would leave any out-of-range value already stored by an older build in force until someone
 * happened to re-open Settings and press Save - which on a dedicated relay phone may be never. The
 * ceilings exist because these fields had floors but no upper bound: a large enough wait put the
 * drain thread into a multi-year sleep that re-saving could not shorten (the config is snapshotted
 * once per drain), and a min of 0 with a max of Integer.MAX_VALUE overflowed the random-range
 * arithmetic and threw on the drain thread, which catches only InterruptedException.
 *
 * <p>These are sanity bounds, NOT a rate control. They cannot keep the line inside the CTIA band:
 * the inter-burst wait is skipped entirely when staggering is off, and BurstMode.ALL_AT_ONCE sizes
 * a burst from the pending count and never consults the burst ceiling at all. Pacing safety comes
 * from the configured values being sensible, not from these limits.
 */
public class RateLimitConfig {

    /** One hour. Long enough for any deliberate pacing; short enough that a drain cannot be wedged. */
    public static final int MAX_WAIT_SECONDS_CEILING = 3600;
    /** 10x the shipped default of 5. Bounds the value; see the class note - it is not a rate control. */
    public static final int BURST_SIZE_CEILING = 50;
    /** 10s. Keeps "micro" spacing genuinely short rather than becoming a second wait mechanism. */
    public static final int MICROSPACING_MS_CEILING = 10_000;

    /**
     * Shared by the read path here and by Settings' save path, so the two can never apply the
     * bounds differently. Public for that reason - SettingsActivity is in another package.
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

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
        this.burstMin = clamp(prefs.getBurstMin(), 1, BURST_SIZE_CEILING);
        this.burstMax = clamp(prefs.getBurstMax(), this.burstMin, BURST_SIZE_CEILING);
        this.minWaitSeconds = clamp(prefs.getMinWaitSeconds(), 0, MAX_WAIT_SECONDS_CEILING);
        this.maxWaitSeconds = clamp(prefs.getMaxWaitSeconds(), this.minWaitSeconds, MAX_WAIT_SECONDS_CEILING);
        this.initialDelayEnabled = prefs.isInitialDelayEnabled();
        this.staggeringEnabled = prefs.isStaggeringEnabled();
        this.burstMode = prefs.getBurstMode();
        this.fixedBurstSize = clamp(prefs.getFixedBurstSize(), 1, BURST_SIZE_CEILING);
        this.microspacingEnabled = prefs.isMicrospacingEnabled();
        this.microspacingMode = prefs.getMicrospacingMode();
        this.microspacingFixedMs = clamp(prefs.getMicrospacingFixedMs(), 0, MICROSPACING_MS_CEILING);
        this.microspacingMinMs = clamp(prefs.getMicrospacingMinMs(), 0, MICROSPACING_MS_CEILING);
        this.microspacingMaxMs = clamp(prefs.getMicrospacingMaxMs(), this.microspacingMinMs, MICROSPACING_MS_CEILING);
    }

    public static RateLimitConfig groupDefault(Prefs prefs) {
        return new RateLimitConfig(prefs);
    }
}
