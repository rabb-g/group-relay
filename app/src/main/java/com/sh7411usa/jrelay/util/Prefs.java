package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.sh7411usa.jrelay.R;

public class Prefs {

    public enum BurstMode { RANDOM_RANGE, FIXED, ALL_AT_ONCE }

    public enum MicrospacingMode { FIXED, RANDOM_RANGE }

    public enum SaltTimestampFormat { TIME_HHMMSS, UNIX_SECONDS, HEX_SECONDS, HEX_MILLIS }

    public enum SaltHexPosition { APPEND, PREPEND }

    public enum ThemeChoice { SYSTEM, LIGHT, DARK }

    public enum GroupMode { GROUP, ANNOUNCEMENT, REPLY }

    public enum JoinPolicy { OFF, ALLOW, REQUIRE_APPROVAL }

    /** Sentinel for {@link #getPauseUntilMillis()}: paused with no scheduled end, until manually resumed. */
    public static final long PAUSE_INDEFINITE = Long.MAX_VALUE;

    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_ENGLISH = "en";
    public static final String LANGUAGE_HEBREW = "iw";
    public static final String LANGUAGE_YIDDISH = "yi";

    private static final String PREFS_NAME = "jrelay_prefs";
    private static final String KEY_CONSENT_ACCEPTED = "consent_accepted";
    private static final String KEY_GROUP_NAME = "group_name";
    private static final String KEY_BURST_MIN = "burst_min";
    private static final String KEY_BURST_MAX = "burst_max";
    private static final String KEY_MIN_WAIT = "min_wait_seconds";
    private static final String KEY_MAX_WAIT = "max_wait_seconds";
    private static final String KEY_INITIAL_DELAY_ENABLED = "initial_delay_enabled";

    private static final String KEY_STAGGERING_ENABLED = "staggering_enabled";
    private static final String KEY_BURST_MODE = "burst_mode";
    private static final String KEY_FIXED_BURST_SIZE = "fixed_burst_size";
    private static final String KEY_MICROSPACING_ENABLED = "microspacing_enabled";
    private static final String KEY_MICROSPACING_MODE = "microspacing_mode";
    private static final String KEY_MICROSPACING_FIXED_MS = "microspacing_fixed_ms";
    private static final String KEY_MICROSPACING_MIN_MS = "microspacing_min_ms";
    private static final String KEY_MICROSPACING_MAX_MS = "microspacing_max_ms";
    private static final String KEY_DELIVERY_SHUFFLE_ENABLED = "delivery_shuffle_enabled";

    private static final String KEY_APPEND_SENDER_NUMBER_ENABLED = "append_sender_number_enabled";
    private static final String KEY_STRIP_PHONE_NUMBERS_ENABLED = "strip_phone_numbers_enabled";

    private static final String KEY_SALT_TIMESTAMP_ENABLED = "salt_timestamp_enabled";
    private static final String KEY_SALT_TIMESTAMP_FORMAT = "salt_timestamp_format";
    private static final String KEY_SALT_HEX_ENABLED = "salt_hex_enabled";
    private static final String KEY_SALT_HEX_POSITION = "salt_hex_position";
    private static final String KEY_SALT_ZWSP_ENABLED = "salt_zwsp_enabled";

    private static final String KEY_RETRY_LIMIT = "retry_limit";
    private static final String KEY_FAILURE_ALERT_THRESHOLD = "failure_alert_threshold";

    private static final String KEY_GROUP_DAILY_LIMIT_ENABLED = "group_daily_limit_enabled";
    private static final String KEY_GROUP_DAILY_LIMIT_VALUE = "group_daily_limit_value";
    private static final String KEY_GROUP_DAILY_LIMIT_BONUS = "group_daily_limit_bonus";
    private static final String KEY_GROUP_DAILY_LIMIT_BONUS_WINDOW_START = "group_daily_limit_bonus_window_start";
    private static final String KEY_DEFAULT_INDIVIDUAL_LIMIT_SEED = "default_individual_limit_seed";
    private static final String KEY_DAILY_LIMIT_RESET_MINUTE_OF_DAY = "daily_limit_reset_minute_of_day";

    private static final String KEY_LANGUAGE_CODE = "language_code";
    private static final String KEY_THEME_CHOICE = "theme_choice";

    private static final String KEY_ADDED_REPORTING_ENABLED = "added_reporting_enabled";

    // These gate GROUP-WIDE announcements only — the member directly affected by an
    // action is always notified regardless of these settings.
    // NOTE: notify_member_left / notify_name_changed / notify_member_removed /
    // notify_group_renamed default to false, which CHANGES existing behaviour for
    // anyone upgrading (these announcements previously always fired). This is the
    // owner's explicit intent: at ~100 members these broadcasts were burning through
    // a ~1,000/day SMS ceiling on messages that carry no real conversation.
    private static final String KEY_NOTIFY_MEMBER_LEFT = "notify_member_left";
    private static final String KEY_NOTIFY_NAME_CHANGED = "notify_name_changed";
    private static final String KEY_NOTIFY_MEMBER_REMOVED = "notify_member_removed";
    private static final String KEY_NOTIFY_GROUP_RENAMED = "notify_group_renamed";
    private static final String KEY_NOTIFY_MODE_CHANGED = "notify_mode_changed";

    private static final String KEY_GROUP_MODE = "group_mode";
    private static final String KEY_JOIN_POLICY = "join_policy";
    private static final String KEY_PAUSE_UNTIL_MILLIS = "pause_until_millis";

    private static final String KEY_REPLY_WINDOW_HOURS = "reply_window_hours";
    private static final String KEY_COPY_REPLIES_TO_ADMINS = "copy_replies_to_admins";
    private static final String KEY_BARE_KEYWORDS_ENABLED = "bare_keywords_enabled";

    private static final String KEY_COALESCE_WINDOW_SECONDS = "coalesce_window_seconds";
    private static final String KEY_MAX_MERGED_SEGMENTS = "max_merged_segments";

    private static final String KEY_MAX_MEMBERS = "max_members";
    private static final String KEY_GROUP_FULL_MESSAGE = "group_full_message";

    private static final String DEFAULT_GROUP_NAME = "jRelay";
    private static final int DEFAULT_BURST_MIN = 3;
    private static final int DEFAULT_BURST_MAX = 5;
    private static final int DEFAULT_MIN_WAIT = 3;
    private static final int DEFAULT_MAX_WAIT = 8;

    private static final int DEFAULT_FIXED_BURST_SIZE = 4;
    private static final int DEFAULT_MICROSPACING_FIXED_MS = 350;
    private static final int DEFAULT_MICROSPACING_MIN_MS = 150;
    private static final int DEFAULT_MICROSPACING_MAX_MS = 500;

    private static final int DEFAULT_RETRY_LIMIT = 1;
    private static final int DEFAULT_FAILURE_ALERT_THRESHOLD = 3;

    private static final int DEFAULT_GROUP_DAILY_LIMIT_VALUE = 100;
    private static final int DEFAULT_INDIVIDUAL_LIMIT_SEED = 10;

    private static final int DEFAULT_REPLY_WINDOW_HOURS = 24;

    private static final int DEFAULT_COALESCE_WINDOW_SECONDS = 45;
    private static final int DEFAULT_MAX_MERGED_SEGMENTS = 3;

    private final Context appContext;
    private final SharedPreferences prefs;

    public Prefs(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isConsentAccepted() {
        return prefs.getBoolean(KEY_CONSENT_ACCEPTED, false);
    }

    public void setConsentAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_CONSENT_ACCEPTED, accepted).apply();
    }

    public String getGroupName() {
        return prefs.getString(KEY_GROUP_NAME, DEFAULT_GROUP_NAME);
    }

    public void setGroupName(String name) {
        prefs.edit().putString(KEY_GROUP_NAME, name).apply();
    }

    public void resetGroupName() {
        setGroupName(DEFAULT_GROUP_NAME);
    }

    public int getBurstMin() {
        return prefs.getInt(KEY_BURST_MIN, DEFAULT_BURST_MIN);
    }

    public void setBurstMin(int size) {
        prefs.edit().putInt(KEY_BURST_MIN, size).apply();
    }

    public int getBurstMax() {
        return prefs.getInt(KEY_BURST_MAX, DEFAULT_BURST_MAX);
    }

    public void setBurstMax(int size) {
        prefs.edit().putInt(KEY_BURST_MAX, size).apply();
    }

    public int getMinWaitSeconds() {
        return prefs.getInt(KEY_MIN_WAIT, DEFAULT_MIN_WAIT);
    }

    public void setMinWaitSeconds(int seconds) {
        prefs.edit().putInt(KEY_MIN_WAIT, seconds).apply();
    }

    public int getMaxWaitSeconds() {
        return prefs.getInt(KEY_MAX_WAIT, DEFAULT_MAX_WAIT);
    }

    public void setMaxWaitSeconds(int seconds) {
        prefs.edit().putInt(KEY_MAX_WAIT, seconds).apply();
    }

    public boolean isInitialDelayEnabled() {
        return prefs.getBoolean(KEY_INITIAL_DELAY_ENABLED, true);
    }

    public void setInitialDelayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_INITIAL_DELAY_ENABLED, enabled).apply();
    }

    public boolean isStaggeringEnabled() {
        return prefs.getBoolean(KEY_STAGGERING_ENABLED, true);
    }

    public void setStaggeringEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_STAGGERING_ENABLED, enabled).apply();
    }

    public BurstMode getBurstMode() {
        return parseEnum(prefs.getString(KEY_BURST_MODE, null), BurstMode.class, BurstMode.RANDOM_RANGE);
    }

    public void setBurstMode(BurstMode mode) {
        prefs.edit().putString(KEY_BURST_MODE, mode.name()).apply();
    }

    public int getFixedBurstSize() {
        return prefs.getInt(KEY_FIXED_BURST_SIZE, DEFAULT_FIXED_BURST_SIZE);
    }

    public void setFixedBurstSize(int size) {
        prefs.edit().putInt(KEY_FIXED_BURST_SIZE, size).apply();
    }

    public boolean isMicrospacingEnabled() {
        return prefs.getBoolean(KEY_MICROSPACING_ENABLED, true);
    }

    public void setMicrospacingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MICROSPACING_ENABLED, enabled).apply();
    }

    public MicrospacingMode getMicrospacingMode() {
        return parseEnum(prefs.getString(KEY_MICROSPACING_MODE, null), MicrospacingMode.class, MicrospacingMode.FIXED);
    }

    public void setMicrospacingMode(MicrospacingMode mode) {
        prefs.edit().putString(KEY_MICROSPACING_MODE, mode.name()).apply();
    }

    public int getMicrospacingFixedMs() {
        return prefs.getInt(KEY_MICROSPACING_FIXED_MS, DEFAULT_MICROSPACING_FIXED_MS);
    }

    public void setMicrospacingFixedMs(int ms) {
        prefs.edit().putInt(KEY_MICROSPACING_FIXED_MS, ms).apply();
    }

    public int getMicrospacingMinMs() {
        return prefs.getInt(KEY_MICROSPACING_MIN_MS, DEFAULT_MICROSPACING_MIN_MS);
    }

    public void setMicrospacingMinMs(int ms) {
        prefs.edit().putInt(KEY_MICROSPACING_MIN_MS, ms).apply();
    }

    public int getMicrospacingMaxMs() {
        return prefs.getInt(KEY_MICROSPACING_MAX_MS, DEFAULT_MICROSPACING_MAX_MS);
    }

    public void setMicrospacingMaxMs(int ms) {
        prefs.edit().putInt(KEY_MICROSPACING_MAX_MS, ms).apply();
    }

    public boolean isDeliveryShuffleEnabled() {
        return prefs.getBoolean(KEY_DELIVERY_SHUFFLE_ENABLED, true);
    }

    public void setDeliveryShuffleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DELIVERY_SHUFFLE_ENABLED, enabled).apply();
    }

    public boolean isAppendSenderNumberEnabled() {
        return prefs.getBoolean(KEY_APPEND_SENDER_NUMBER_ENABLED, false);
    }

    public void setAppendSenderNumberEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APPEND_SENDER_NUMBER_ENABLED, enabled).apply();
    }

    public boolean isStripPhoneNumbersEnabled() {
        return prefs.getBoolean(KEY_STRIP_PHONE_NUMBERS_ENABLED, false);
    }

    public void setStripPhoneNumbersEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_STRIP_PHONE_NUMBERS_ENABLED, enabled).apply();
    }

    public boolean isSaltTimestampEnabled() {
        return prefs.getBoolean(KEY_SALT_TIMESTAMP_ENABLED, false);
    }

    public void setSaltTimestampEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SALT_TIMESTAMP_ENABLED, enabled).apply();
    }

    public SaltTimestampFormat getSaltTimestampFormat() {
        return parseEnum(prefs.getString(KEY_SALT_TIMESTAMP_FORMAT, null), SaltTimestampFormat.class, SaltTimestampFormat.TIME_HHMMSS);
    }

    public void setSaltTimestampFormat(SaltTimestampFormat format) {
        prefs.edit().putString(KEY_SALT_TIMESTAMP_FORMAT, format.name()).apply();
    }

    public boolean isSaltHexEnabled() {
        return prefs.getBoolean(KEY_SALT_HEX_ENABLED, false);
    }

    public void setSaltHexEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SALT_HEX_ENABLED, enabled).apply();
    }

    public SaltHexPosition getSaltHexPosition() {
        return parseEnum(prefs.getString(KEY_SALT_HEX_POSITION, null), SaltHexPosition.class, SaltHexPosition.APPEND);
    }

    public void setSaltHexPosition(SaltHexPosition position) {
        prefs.edit().putString(KEY_SALT_HEX_POSITION, position.name()).apply();
    }

    public boolean isSaltZwspEnabled() {
        return prefs.getBoolean(KEY_SALT_ZWSP_ENABLED, false);
    }

    public void setSaltZwspEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SALT_ZWSP_ENABLED, enabled).apply();
    }

    public int getRetryLimit() {
        return prefs.getInt(KEY_RETRY_LIMIT, DEFAULT_RETRY_LIMIT);
    }

    public void setRetryLimit(int limit) {
        prefs.edit().putInt(KEY_RETRY_LIMIT, limit).apply();
    }

    public int getFailureAlertThreshold() {
        return prefs.getInt(KEY_FAILURE_ALERT_THRESHOLD, DEFAULT_FAILURE_ALERT_THRESHOLD);
    }

    public void setFailureAlertThreshold(int threshold) {
        prefs.edit().putInt(KEY_FAILURE_ALERT_THRESHOLD, threshold).apply();
    }

    public boolean isGroupDailyLimitEnabled() {
        return prefs.getBoolean(KEY_GROUP_DAILY_LIMIT_ENABLED, false);
    }

    public void setGroupDailyLimitEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GROUP_DAILY_LIMIT_ENABLED, enabled).apply();
    }

    public int getGroupDailyLimitValue() {
        return prefs.getInt(KEY_GROUP_DAILY_LIMIT_VALUE, DEFAULT_GROUP_DAILY_LIMIT_VALUE);
    }

    public void setGroupDailyLimitValue(int value) {
        prefs.edit().putInt(KEY_GROUP_DAILY_LIMIT_VALUE, value).apply();
    }

    public int getGroupDailyLimitBonus() {
        return prefs.getInt(KEY_GROUP_DAILY_LIMIT_BONUS, 0);
    }

    public void setGroupDailyLimitBonus(int bonus) {
        prefs.edit().putInt(KEY_GROUP_DAILY_LIMIT_BONUS, bonus).apply();
    }

    public long getGroupDailyLimitBonusWindowStart() {
        return prefs.getLong(KEY_GROUP_DAILY_LIMIT_BONUS_WINDOW_START, 0L);
    }

    public void setGroupDailyLimitBonusWindowStart(long windowStartMillis) {
        prefs.edit().putLong(KEY_GROUP_DAILY_LIMIT_BONUS_WINDOW_START, windowStartMillis).apply();
    }

    public int getDefaultIndividualLimitSeed() {
        return prefs.getInt(KEY_DEFAULT_INDIVIDUAL_LIMIT_SEED, DEFAULT_INDIVIDUAL_LIMIT_SEED);
    }

    public void setDefaultIndividualLimitSeed(int value) {
        prefs.edit().putInt(KEY_DEFAULT_INDIVIDUAL_LIMIT_SEED, value).apply();
    }

    /** Minutes after local midnight when the daily group/individual limits reset. Default 0 = midnight. */
    public int getDailyLimitResetMinuteOfDay() {
        return prefs.getInt(KEY_DAILY_LIMIT_RESET_MINUTE_OF_DAY, 0);
    }

    public void setDailyLimitResetMinuteOfDay(int minuteOfDay) {
        prefs.edit().putInt(KEY_DAILY_LIMIT_RESET_MINUTE_OF_DAY, minuteOfDay).apply();
    }

    /** One of LANGUAGE_SYSTEM / LANGUAGE_ENGLISH / LANGUAGE_HEBREW / LANGUAGE_YIDDISH. */
    public String getLanguageCode() {
        return prefs.getString(KEY_LANGUAGE_CODE, LANGUAGE_SYSTEM);
    }

    public void setLanguageCode(String code) {
        prefs.edit().putString(KEY_LANGUAGE_CODE, code).apply();
    }

    public ThemeChoice getThemeChoice() {
        return parseEnum(prefs.getString(KEY_THEME_CHOICE, null), ThemeChoice.class, ThemeChoice.SYSTEM);
    }

    public void setThemeChoice(ThemeChoice choice) {
        prefs.edit().putString(KEY_THEME_CHOICE, choice.name()).apply();
    }

    /** Whether adding a member (via #add or Add Member) sends the usual welcome/broadcast texts. CSV import always asks separately. */
    public boolean isAddedReportingEnabled() {
        return prefs.getBoolean(KEY_ADDED_REPORTING_ENABLED, true);
    }

    public void setAddedReportingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ADDED_REPORTING_ENABLED, enabled).apply();
    }

    /** Whether a member leaving the group sends a group-wide announcement. */
    public boolean isNotifyMemberLeftEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_MEMBER_LEFT, false);
    }

    public void setNotifyMemberLeftEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_MEMBER_LEFT, enabled).apply();
    }

    /** Whether a member's nickname change sends a group-wide announcement. */
    public boolean isNotifyNameChangedEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_NAME_CHANGED, false);
    }

    public void setNotifyNameChangedEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_NAME_CHANGED, enabled).apply();
    }

    /** Whether removing a member sends a group-wide announcement. */
    public boolean isNotifyMemberRemovedEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_MEMBER_REMOVED, false);
    }

    public void setNotifyMemberRemovedEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_MEMBER_REMOVED, enabled).apply();
    }

    /** Whether renaming the group sends a group-wide announcement. */
    public boolean isNotifyGroupRenamedEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_GROUP_RENAMED, false);
    }

    public void setNotifyGroupRenamedEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_GROUP_RENAMED, enabled).apply();
    }

    /**
     * Whether a group-mode change sends a group-wide announcement. Defaults to true,
     * unlike the other four notify_* settings: in Reply Mode a plain reply reaches only
     * the last poster, and members must prefix #all to reach everyone. If nobody is
     * told the mode changed, their next message silently goes to one person instead of
     * the whole group — this announcement is the only thing preventing that.
     */
    public boolean isNotifyModeChangedEnabled() {
        return prefs.getBoolean(KEY_NOTIFY_MODE_CHANGED, true);
    }

    public void setNotifyModeChangedEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFY_MODE_CHANGED, enabled).apply();
    }

    public GroupMode getGroupMode() {
        return parseEnum(prefs.getString(KEY_GROUP_MODE, null), GroupMode.class, GroupMode.GROUP);
    }

    public void setGroupMode(GroupMode mode) {
        prefs.edit().putString(KEY_GROUP_MODE, mode.name()).apply();
    }

    /** Hours a member's incoming reply is accepted after the group's last outgoing message. 0 = no limit. */
    public int getReplyWindowHours() {
        return prefs.getInt(KEY_REPLY_WINDOW_HOURS, DEFAULT_REPLY_WINDOW_HOURS);
    }

    public void setReplyWindowHours(int hours) {
        prefs.edit().putInt(KEY_REPLY_WINDOW_HOURS, hours).apply();
    }

    public boolean isCopyRepliesToAdmins() {
        return prefs.getBoolean(KEY_COPY_REPLIES_TO_ADMINS, false);
    }

    public void setCopyRepliesToAdmins(boolean on) {
        prefs.edit().putBoolean(KEY_COPY_REPLIES_TO_ADMINS, on).apply();
    }

    public boolean isBareKeywordsEnabled() {
        return prefs.getBoolean(KEY_BARE_KEYWORDS_ENABLED, true);
    }

    public void setBareKeywordsEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_BARE_KEYWORDS_ENABLED, on).apply();
    }

    /** Seconds to hold a relayed post so near-simultaneous posts merge into one SMS. 0 = off. */
    public int getCoalesceWindowSeconds() {
        return prefs.getInt(KEY_COALESCE_WINDOW_SECONDS, DEFAULT_COALESCE_WINDOW_SECONDS);
    }

    public void setCoalesceWindowSeconds(int seconds) {
        prefs.edit().putInt(KEY_COALESCE_WINDOW_SECONDS, seconds).apply();
    }

    /** True when the coalescing window is on. Single owner of the on/off question: the enqueuer and the send loop must agree. */
    public boolean isCoalescingEnabled() {
        return getCoalesceWindowSeconds() > 0;
    }

    /** Cap on how many SMS segments one merged message may occupy. */
    public int getMaxMergedSegments() {
        return prefs.getInt(KEY_MAX_MERGED_SEGMENTS, DEFAULT_MAX_MERGED_SEGMENTS);
    }

    public void setMaxMergedSegments(int segments) {
        prefs.edit().putInt(KEY_MAX_MERGED_SEGMENTS, segments).apply();
    }

    public JoinPolicy getJoinPolicy() {
        return parseEnum(prefs.getString(KEY_JOIN_POLICY, null), JoinPolicy.class, JoinPolicy.OFF);
    }

    public void setJoinPolicy(JoinPolicy policy) {
        prefs.edit().putString(KEY_JOIN_POLICY, policy.name()).apply();
    }

    /** 0 = not paused. {@link #PAUSE_INDEFINITE} = paused with no scheduled end. Otherwise an epoch-millis resume time. */
    public long getPauseUntilMillis() {
        return prefs.getLong(KEY_PAUSE_UNTIL_MILLIS, 0L);
    }

    public void setPauseUntilMillis(long untilMillis) {
        prefs.edit().putLong(KEY_PAUSE_UNTIL_MILLIS, untilMillis).apply();
    }

    /** True while all SMS sending and incoming processing should be suppressed. */
    public boolean isPaused() {
        long until = getPauseUntilMillis();
        if (until == 0) {
            return false;
        }
        return until == PAUSE_INDEFINITE || System.currentTimeMillis() < until;
    }

    /** Maximum number of members allowed in the group. 0 = unlimited. */
    public int getMaxMembers() {
        return prefs.getInt(KEY_MAX_MEMBERS, 0);
    }

    public void setMaxMembers(int max) {
        prefs.edit().putInt(KEY_MAX_MEMBERS, max).apply();
    }

    /** Message sent to someone trying to join a full group. Falls back to the localised default when unset or blank. */
    public String getGroupFullMessage() {
        String stored = prefs.getString(KEY_GROUP_FULL_MESSAGE, null);
        if (stored == null || stored.trim().isEmpty()) {
            return appContext.getString(R.string.tpl_group_full_default);
        }
        return stored;
    }

    public void setGroupFullMessage(String message) {
        prefs.edit().putString(KEY_GROUP_FULL_MESSAGE, message).apply();
    }

    private <E extends Enum<E>> E parseEnum(String stored, Class<E> type, E defaultValue) {
        if (stored == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }
}
