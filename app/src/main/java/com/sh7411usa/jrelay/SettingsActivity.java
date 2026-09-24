package com.sh7411usa.jrelay;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.DbHelper;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RateLimitConfig;
import com.sh7411usa.jrelay.util.RateLimitSettings;

import java.util.List;
import java.util.Locale;
import java.util.Random;

public class SettingsActivity extends BaseActivity {

    private static final String[] LANGUAGE_CODES = {
            Prefs.LANGUAGE_SYSTEM, Prefs.LANGUAGE_ENGLISH, Prefs.LANGUAGE_HEBREW, Prefs.LANGUAGE_YIDDISH
    };

    /** Index 0 ("Not Paused") is handled separately; indexes 1-5 map one-to-one here. */
    private static final long[] PAUSE_DURATIONS_MS = {
            0L, 10_000L, 60_000L, 3_600_000L, 86_400_000L, Prefs.PAUSE_INDEFINITE
    };

    private static final long PAUSE_STATUS_TICK_MS = 1000;

    /**
     * Pacing ceilings live on {@link RateLimitConfig}, which also applies them when the drain
     * READS these values - clamping only here, on save, would leave any out-of-range value already
     * stored by an older build in force until someone happened to re-open this screen and press
     * Save. Defined in one place so the two can never drift; see that class for why each bound is
     * what it is, and for why they are sanity bounds rather than a rate control.
     */
    private static final int MAX_WAIT_SECONDS_CEILING = RateLimitConfig.MAX_WAIT_SECONDS_CEILING;
    private static final int BURST_SIZE_CEILING = RateLimitConfig.BURST_SIZE_CEILING;
    private static final int MICROSPACING_MS_CEILING = RateLimitConfig.MICROSPACING_MS_CEILING;

    private Prefs prefs;
    private MessageRepository messageRepository;
    private MemberRepository memberRepository;

    // Pause Service
    private Spinner pauseSpinner;
    private TextView pauseStatusView;
    private final AdapterView.OnItemSelectedListener pauseSpinnerListener = new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            applyPauseSelection(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    };
    private final Handler pauseStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable pauseStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshPauseStatus();
            pauseStatusHandler.postDelayed(this, PAUSE_STATUS_TICK_MS);
        }
    };

    // Group Mode
    private Spinner groupModeSpinner;

    // Reply Mode
    private EditText replyWindowHoursInput;
    private CheckBox copyRepliesToAdminsCheckbox;

    // Commands
    private CheckBox acceptBareKeywordsCheckbox;

    // Staggering / Burst / Microspacing
    private CheckBox staggeringEnabledCheckbox;
    private EditText minWaitInput;
    private EditText maxWaitInput;
    private CheckBox initialDelayCheckbox;
    private Spinner burstModeSpinner;
    private View burstRandomContainer;
    private View burstFixedContainer;
    private EditText burstMinInput;
    private EditText burstMaxInput;
    private EditText fixedBurstSizeInput;
    private CheckBox microspacingEnabledCheckbox;
    private Spinner microspacingModeSpinner;
    private View microspacingFixedContainer;
    private View microspacingRandomContainer;
    private EditText microspacingFixedMsInput;
    private EditText microspacingMinMsInput;
    private EditText microspacingMaxMsInput;

    // Coalescing window
    private EditText coalesceWindowSecondsInput;
    private EditText maxMergedSegmentsInput;

    // Delivery shuffle
    private CheckBox deliveryShuffleCheckbox;

    // Group Notices
    private CheckBox addedReportingEnabledCheckbox;
    private CheckBox notifyMemberLeftCheckbox;
    private CheckBox notifyNameChangedCheckbox;
    private CheckBox notifyMemberRemovedCheckbox;
    private CheckBox notifyGroupRenamedCheckbox;
    private CheckBox notifyModeChangedCheckbox;

    // Join requests
    private Spinner joinPolicySpinner;

    // Group Capacity
    private EditText maxMembersInput;
    private EditText groupFullMessageInput;
    private TextView capacityStatusView;

    // Message content / Salting
    private CheckBox appendSenderNumberCheckbox;
    private CheckBox stripPhoneNumbersCheckbox;
    private CheckBox saltTimestampCheckbox;
    private Spinner saltTimestampFormatSpinner;
    private CheckBox saltHexCheckbox;
    private Spinner saltHexPositionSpinner;
    private CheckBox saltZwspCheckbox;

    // Failures & retries
    private EditText retryLimitInput;
    private EditText failureAlertThresholdInput;

    // Group daily limit
    private EditText dailyLimitResetTimeInput;
    private CheckBox groupDailyLimitEnabledCheckbox;
    private EditText groupDailyLimitValueInput;
    private TextView groupDailyLimitStatusView;
    private EditText defaultIndividualLimitInput;

    // System limit
    private EditText systemMaxCountInput;
    private EditText systemIntervalInput;
    private TextView systemPermissionNotice;
    private Button systemSaveButton;

    // Language / Theme
    private Spinner languageSpinner;
    private Spinner themeSpinner;

    private Button clearHistoryButton;
    private TextView appVersionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        messageRepository = new MessageRepository(this);
        memberRepository = new MemberRepository(this);

        bindViews();
        populateFromPrefs();
        wireListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSystemSettings();
        updateClearHistoryButtonLabel();
        refreshGroupDailyLimitStatus();
        refreshCapacityStatus();
        pauseStatusHandler.post(pauseStatusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseStatusHandler.removeCallbacks(pauseStatusTick);
    }

    private void bindViews() {
        pauseSpinner = findViewById(R.id.spinner_pause);
        pauseStatusView = findViewById(R.id.text_pause_status);
        groupModeSpinner = findViewById(R.id.spinner_group_mode);
        appVersionView = findViewById(R.id.text_app_version);

        replyWindowHoursInput = findViewById(R.id.edit_reply_window_hours);
        copyRepliesToAdminsCheckbox = findViewById(R.id.checkbox_copy_replies_to_admins);

        acceptBareKeywordsCheckbox = findViewById(R.id.checkbox_accept_bare_keywords);

        staggeringEnabledCheckbox = findViewById(R.id.checkbox_staggering_enabled);
        minWaitInput = findViewById(R.id.edit_min_wait);
        maxWaitInput = findViewById(R.id.edit_max_wait);
        initialDelayCheckbox = findViewById(R.id.checkbox_initial_delay);
        burstModeSpinner = findViewById(R.id.spinner_burst_mode);
        burstRandomContainer = findViewById(R.id.container_burst_random);
        burstFixedContainer = findViewById(R.id.container_burst_fixed);
        burstMinInput = findViewById(R.id.edit_burst_min);
        burstMaxInput = findViewById(R.id.edit_burst_max);
        fixedBurstSizeInput = findViewById(R.id.edit_fixed_burst_size);
        microspacingEnabledCheckbox = findViewById(R.id.checkbox_microspacing_enabled);
        microspacingModeSpinner = findViewById(R.id.spinner_microspacing_mode);
        microspacingFixedContainer = findViewById(R.id.container_microspacing_fixed);
        microspacingRandomContainer = findViewById(R.id.container_microspacing_random);
        microspacingFixedMsInput = findViewById(R.id.edit_microspacing_fixed_ms);
        microspacingMinMsInput = findViewById(R.id.edit_microspacing_min_ms);
        microspacingMaxMsInput = findViewById(R.id.edit_microspacing_max_ms);

        // Equivalent to android:maxLength, applied in code since this class doesn't own the
        // layout file. Sized to the digit count of each field's ceiling above so the UI can't
        // even accept absurd values like "2147483647" in the first place; the Math.min clamps
        // in savePacingSettings() remain the real guarantee regardless of what's typed.
        applyMaxDigits(minWaitInput, MAX_WAIT_SECONDS_CEILING);
        applyMaxDigits(maxWaitInput, MAX_WAIT_SECONDS_CEILING);
        applyMaxDigits(burstMinInput, BURST_SIZE_CEILING);
        applyMaxDigits(burstMaxInput, BURST_SIZE_CEILING);
        applyMaxDigits(fixedBurstSizeInput, BURST_SIZE_CEILING);
        applyMaxDigits(microspacingFixedMsInput, MICROSPACING_MS_CEILING);
        applyMaxDigits(microspacingMinMsInput, MICROSPACING_MS_CEILING);
        applyMaxDigits(microspacingMaxMsInput, MICROSPACING_MS_CEILING);

        coalesceWindowSecondsInput = findViewById(R.id.edit_coalesce_window_seconds);
        maxMergedSegmentsInput = findViewById(R.id.edit_max_merged_segments);

        deliveryShuffleCheckbox = findViewById(R.id.checkbox_delivery_shuffle);

        addedReportingEnabledCheckbox = findViewById(R.id.checkbox_added_reporting_enabled);
        notifyMemberLeftCheckbox = findViewById(R.id.checkbox_notify_member_left);
        notifyNameChangedCheckbox = findViewById(R.id.checkbox_notify_name_changed);
        notifyMemberRemovedCheckbox = findViewById(R.id.checkbox_notify_member_removed);
        notifyGroupRenamedCheckbox = findViewById(R.id.checkbox_notify_group_renamed);
        notifyModeChangedCheckbox = findViewById(R.id.checkbox_notify_mode_changed);
        joinPolicySpinner = findViewById(R.id.spinner_join_policy);

        maxMembersInput = findViewById(R.id.edit_max_members);
        groupFullMessageInput = findViewById(R.id.edit_group_full_message);
        capacityStatusView = findViewById(R.id.text_capacity_status);

        appendSenderNumberCheckbox = findViewById(R.id.checkbox_append_sender_number);
        stripPhoneNumbersCheckbox = findViewById(R.id.checkbox_strip_phone_numbers);
        saltTimestampCheckbox = findViewById(R.id.checkbox_salt_timestamp);
        saltTimestampFormatSpinner = findViewById(R.id.spinner_salt_timestamp_format);
        saltHexCheckbox = findViewById(R.id.checkbox_salt_hex);
        saltHexPositionSpinner = findViewById(R.id.spinner_salt_hex_position);
        saltZwspCheckbox = findViewById(R.id.checkbox_salt_zwsp);

        retryLimitInput = findViewById(R.id.edit_retry_limit);
        failureAlertThresholdInput = findViewById(R.id.edit_failure_alert_threshold);

        dailyLimitResetTimeInput = findViewById(R.id.edit_daily_limit_reset_time);
        groupDailyLimitEnabledCheckbox = findViewById(R.id.checkbox_group_daily_limit_enabled);
        groupDailyLimitValueInput = findViewById(R.id.edit_group_daily_limit_value);
        groupDailyLimitStatusView = findViewById(R.id.text_group_daily_limit_status);
        defaultIndividualLimitInput = findViewById(R.id.edit_default_individual_limit);

        systemMaxCountInput = findViewById(R.id.edit_system_max_count);
        systemIntervalInput = findViewById(R.id.edit_system_interval);
        systemPermissionNotice = findViewById(R.id.text_system_permission_notice);
        systemSaveButton = findViewById(R.id.button_system_save);

        languageSpinner = findViewById(R.id.spinner_language);
        themeSpinner = findViewById(R.id.spinner_theme);

        clearHistoryButton = findViewById(R.id.button_clear_history);
    }

    /** Caps an EditText's input length to the digit count of {@code ceiling} (android:maxLength equivalent, in code). */
    private void applyMaxDigits(EditText input, int ceiling) {
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(String.valueOf(ceiling).length())});
    }

    private void populateFromPrefs() {
        pauseSpinner.setSelection(0);
        refreshPauseStatus();
        groupModeSpinner.setSelection(prefs.getGroupMode().ordinal());
        appVersionView.setText(getVersionLabel());

        replyWindowHoursInput.setText(String.valueOf(prefs.getReplyWindowHours()));
        copyRepliesToAdminsCheckbox.setChecked(prefs.isCopyRepliesToAdmins());

        acceptBareKeywordsCheckbox.setChecked(prefs.isBareKeywordsEnabled());

        staggeringEnabledCheckbox.setChecked(prefs.isStaggeringEnabled());
        minWaitInput.setText(String.valueOf(prefs.getMinWaitSeconds()));
        maxWaitInput.setText(String.valueOf(prefs.getMaxWaitSeconds()));
        initialDelayCheckbox.setChecked(prefs.isInitialDelayEnabled());

        burstModeSpinner.setSelection(prefs.getBurstMode().ordinal());
        burstMinInput.setText(String.valueOf(prefs.getBurstMin()));
        burstMaxInput.setText(String.valueOf(prefs.getBurstMax()));
        fixedBurstSizeInput.setText(String.valueOf(prefs.getFixedBurstSize()));
        updateBurstModeVisibility(prefs.getBurstMode());

        microspacingEnabledCheckbox.setChecked(prefs.isMicrospacingEnabled());
        microspacingModeSpinner.setSelection(prefs.getMicrospacingMode().ordinal());
        microspacingFixedMsInput.setText(String.valueOf(prefs.getMicrospacingFixedMs()));
        microspacingMinMsInput.setText(String.valueOf(prefs.getMicrospacingMinMs()));
        microspacingMaxMsInput.setText(String.valueOf(prefs.getMicrospacingMaxMs()));
        updateMicrospacingModeVisibility(prefs.getMicrospacingMode());

        coalesceWindowSecondsInput.setText(String.valueOf(prefs.getCoalesceWindowSeconds()));
        maxMergedSegmentsInput.setText(String.valueOf(prefs.getMaxMergedSegments()));

        deliveryShuffleCheckbox.setChecked(prefs.isDeliveryShuffleEnabled());

        addedReportingEnabledCheckbox.setChecked(prefs.isAddedReportingEnabled());
        notifyMemberLeftCheckbox.setChecked(prefs.isNotifyMemberLeftEnabled());
        notifyNameChangedCheckbox.setChecked(prefs.isNotifyNameChangedEnabled());
        notifyMemberRemovedCheckbox.setChecked(prefs.isNotifyMemberRemovedEnabled());
        notifyGroupRenamedCheckbox.setChecked(prefs.isNotifyGroupRenamedEnabled());
        notifyModeChangedCheckbox.setChecked(prefs.isNotifyModeChangedEnabled());
        joinPolicySpinner.setSelection(prefs.getJoinPolicy().ordinal());

        maxMembersInput.setText(String.valueOf(prefs.getMaxMembers()));
        groupFullMessageInput.setText(prefs.getGroupFullMessage());
        refreshCapacityStatus();

        appendSenderNumberCheckbox.setChecked(prefs.isAppendSenderNumberEnabled());
        stripPhoneNumbersCheckbox.setChecked(prefs.isStripPhoneNumbersEnabled());
        saltTimestampCheckbox.setChecked(prefs.isSaltTimestampEnabled());
        saltTimestampFormatSpinner.setSelection(prefs.getSaltTimestampFormat().ordinal());
        saltHexCheckbox.setChecked(prefs.isSaltHexEnabled());
        saltHexPositionSpinner.setSelection(prefs.getSaltHexPosition().ordinal());
        saltZwspCheckbox.setChecked(prefs.isSaltZwspEnabled());

        retryLimitInput.setText(String.valueOf(prefs.getRetryLimit()));
        failureAlertThresholdInput.setText(String.valueOf(prefs.getFailureAlertThreshold()));

        dailyLimitResetTimeInput.setText(formatMinuteOfDay(prefs.getDailyLimitResetMinuteOfDay()));
        groupDailyLimitEnabledCheckbox.setChecked(prefs.isGroupDailyLimitEnabled());
        groupDailyLimitValueInput.setText(String.valueOf(prefs.getGroupDailyLimitValue()));
        defaultIndividualLimitInput.setText(String.valueOf(prefs.getDefaultIndividualLimitSeed()));

        int languageIndex = indexOf(LANGUAGE_CODES, prefs.getLanguageCode());
        languageSpinner.setSelection(languageIndex >= 0 ? languageIndex : 0);
        themeSpinner.setSelection(prefs.getThemeChoice().ordinal());
    }

    private void wireListeners() {
        // AdapterView fires onItemSelected once automatically after the first layout pass, using
        // whatever listener is attached by then — regardless of whether that happens before or
        // after populateFromPrefs()'s setSelection(0) call above. Left unguarded, that spurious
        // callback invokes applyPauseSelection(0), which silently cancels an active pause just
        // from opening this screen. Deferring attachment via post() (same technique
        // resetPauseSpinnerToIdle uses below) means the listener isn't attached yet when that
        // automatic callback fires, so it's a no-op instead of a cancellation.
        pauseSpinner.post(() -> pauseSpinner.setOnItemSelectedListener(pauseSpinnerListener));
        findViewById(R.id.button_save_group_mode).setOnClickListener(v -> saveGroupMode());
        findViewById(R.id.button_save_reply_mode).setOnClickListener(v -> saveReplyModeSettings());
        findViewById(R.id.button_save_commands).setOnClickListener(v -> saveCommandsSettings());
        findViewById(R.id.button_save).setOnClickListener(v -> savePacingSettings());
        findViewById(R.id.button_save_reporting).setOnClickListener(v -> saveReportingSettings());
        findViewById(R.id.button_save_notices).setOnClickListener(v -> saveNoticesSettings());
        findViewById(R.id.button_save_capacity).setOnClickListener(v -> saveCapacitySettings());
        findViewById(R.id.button_save_content).setOnClickListener(v -> saveContentSettings());
        findViewById(R.id.button_save_failures).setOnClickListener(v -> saveFailureSettings());
        findViewById(R.id.button_save_group_limit).setOnClickListener(v -> saveGroupLimitSettings());
        findViewById(R.id.button_apply_to_all).setOnClickListener(v -> applyIndividualLimit(false));
        findViewById(R.id.button_apply_to_non_customized).setOnClickListener(v -> applyIndividualLimit(true));
        systemSaveButton.setOnClickListener(v -> saveSystemSettings());
        clearHistoryButton.setOnClickListener(v -> confirmClearHistory());
        findViewById(R.id.button_disband_group).setOnClickListener(v -> confirmDisbandGroup());
        findViewById(R.id.button_view_license).setOnClickListener(v ->
                startActivity(new Intent(this, LicenseActivity.class)));
        systemPermissionNotice.setOnClickListener(v -> copyAdbCommandToClipboard());

        burstModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateBurstModeVisibility(Prefs.BurstMode.values()[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        microspacingModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateMicrospacingModeVisibility(Prefs.MicrospacingMode.values()[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newCode = LANGUAGE_CODES[position];
                if (!newCode.equals(prefs.getLanguageCode())) {
                    prefs.setLanguageCode(newCode);
                    restartApp();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Prefs.ThemeChoice newChoice = Prefs.ThemeChoice.values()[position];
                if (newChoice != prefs.getThemeChoice()) {
                    prefs.setThemeChoice(newChoice);
                    restartApp();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** Position 0 resumes (if paused); positions 1-5 pause starting now, for a duration or indefinitely. */
    private void applyPauseSelection(int position) {
        if (position == 0) {
            if (prefs.getPauseUntilMillis() != 0) {
                prefs.setPauseUntilMillis(0);
                SmsSendService.start(this);
            }
        } else {
            long duration = PAUSE_DURATIONS_MS[position];
            long until = duration == Prefs.PAUSE_INDEFINITE ? Prefs.PAUSE_INDEFINITE : System.currentTimeMillis() + duration;
            prefs.setPauseUntilMillis(until);
        }
        refreshPauseStatus();
        resetPauseSpinnerToIdle();
    }

    /** Springs the spinner back to "Not Paused" after applying a real selection, without re-triggering the listener. */
    private void resetPauseSpinnerToIdle() {
        pauseSpinner.setOnItemSelectedListener(null);
        pauseSpinner.setSelection(0);
        pauseSpinner.post(() -> pauseSpinner.setOnItemSelectedListener(pauseSpinnerListener));
    }

    private void refreshPauseStatus() {
        long until = prefs.getPauseUntilMillis();
        if (until == 0 || (until != Prefs.PAUSE_INDEFINITE && System.currentTimeMillis() >= until)) {
            pauseStatusView.setText(R.string.pause_status_not_paused);
        } else if (until == Prefs.PAUSE_INDEFINITE) {
            pauseStatusView.setText(R.string.pause_status_indefinite);
        } else {
            long remainingMs = Math.max(0, until - System.currentTimeMillis());
            pauseStatusView.setText(getString(R.string.pause_status_timed, formatPauseDuration(remainingMs)));
        }
    }

    private String formatPauseDuration(long millis) {
        long totalSeconds = millis / 1000;
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        if (minutes < 60) {
            return minutes + "m " + (totalSeconds % 60) + "s";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        long days = hours / 24;
        return days + "d " + (hours % 24) + "h";
    }

    private void saveGroupMode() {
        Prefs.GroupMode newMode = Prefs.GroupMode.values()[groupModeSpinner.getSelectedItemPosition()];
        if (newMode != prefs.getGroupMode()) {
            new CommandProcessor(this).setGroupMode(newMode, getString(R.string.default_added_by_admin), -1);
        }
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveReplyModeSettings() {
        prefs.setReplyWindowHours(Math.max(0, parseOrDefault(replyWindowHoursInput, prefs.getReplyWindowHours())));
        prefs.setCopyRepliesToAdmins(copyRepliesToAdminsCheckbox.isChecked());
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveCommandsSettings() {
        prefs.setBareKeywordsEnabled(acceptBareKeywordsCheckbox.isChecked());
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private String getVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
            return getString(R.string.tpl_app_version, info.versionName, code);
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void updateBurstModeVisibility(Prefs.BurstMode mode) {
        burstRandomContainer.setVisibility(mode == Prefs.BurstMode.RANDOM_RANGE ? View.VISIBLE : View.GONE);
        burstFixedContainer.setVisibility(mode == Prefs.BurstMode.FIXED ? View.VISIBLE : View.GONE);
    }

    private void updateMicrospacingModeVisibility(Prefs.MicrospacingMode mode) {
        microspacingFixedContainer.setVisibility(mode == Prefs.MicrospacingMode.FIXED ? View.VISIBLE : View.GONE);
        microspacingRandomContainer.setVisibility(mode == Prefs.MicrospacingMode.RANDOM_RANGE ? View.VISIBLE : View.GONE);
    }

    private void savePacingSettings() {
        prefs.setStaggeringEnabled(staggeringEnabledCheckbox.isChecked());
        int min = parseOrDefault(minWaitInput, prefs.getMinWaitSeconds());
        int max = parseOrDefault(maxWaitInput, prefs.getMaxWaitSeconds());
        prefs.setMinWaitSeconds(RateLimitConfig.clamp(min, 0, MAX_WAIT_SECONDS_CEILING));
        prefs.setMaxWaitSeconds(RateLimitConfig.clamp(max, prefs.getMinWaitSeconds(), MAX_WAIT_SECONDS_CEILING));
        prefs.setInitialDelayEnabled(initialDelayCheckbox.isChecked());

        prefs.setBurstMode(Prefs.BurstMode.values()[burstModeSpinner.getSelectedItemPosition()]);
        int burstMin = parseOrDefault(burstMinInput, prefs.getBurstMin());
        int burstMax = parseOrDefault(burstMaxInput, prefs.getBurstMax());
        prefs.setBurstMin(RateLimitConfig.clamp(burstMin, 1, BURST_SIZE_CEILING));
        prefs.setBurstMax(RateLimitConfig.clamp(burstMax, prefs.getBurstMin(), BURST_SIZE_CEILING));
        prefs.setFixedBurstSize(RateLimitConfig.clamp(
                parseOrDefault(fixedBurstSizeInput, prefs.getFixedBurstSize()), 1, BURST_SIZE_CEILING));

        prefs.setMicrospacingEnabled(microspacingEnabledCheckbox.isChecked());
        prefs.setMicrospacingMode(Prefs.MicrospacingMode.values()[microspacingModeSpinner.getSelectedItemPosition()]);
        prefs.setMicrospacingFixedMs(RateLimitConfig.clamp(
                parseOrDefault(microspacingFixedMsInput, prefs.getMicrospacingFixedMs()), 0, MICROSPACING_MS_CEILING));
        int microMin = RateLimitConfig.clamp(
                parseOrDefault(microspacingMinMsInput, prefs.getMicrospacingMinMs()), 0, MICROSPACING_MS_CEILING);
        int microMax = parseOrDefault(microspacingMaxMsInput, prefs.getMicrospacingMaxMs());
        prefs.setMicrospacingMinMs(microMin);
        prefs.setMicrospacingMaxMs(RateLimitConfig.clamp(microMax, microMin, MICROSPACING_MS_CEILING));

        prefs.setCoalesceWindowSeconds(
                Math.min(600, Math.max(0, parseOrDefault(coalesceWindowSecondsInput, prefs.getCoalesceWindowSeconds()))));
        prefs.setMaxMergedSegments(Math.max(1, parseOrDefault(maxMergedSegmentsInput, prefs.getMaxMergedSegments())));

        prefs.setDeliveryShuffleEnabled(deliveryShuffleCheckbox.isChecked());

        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveReportingSettings() {
        prefs.setJoinPolicy(Prefs.JoinPolicy.values()[joinPolicySpinner.getSelectedItemPosition()]);
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveNoticesSettings() {
        prefs.setAddedReportingEnabled(addedReportingEnabledCheckbox.isChecked());
        prefs.setNotifyMemberLeftEnabled(notifyMemberLeftCheckbox.isChecked());
        prefs.setNotifyNameChangedEnabled(notifyNameChangedCheckbox.isChecked());
        prefs.setNotifyMemberRemovedEnabled(notifyMemberRemovedCheckbox.isChecked());
        prefs.setNotifyGroupRenamedEnabled(notifyGroupRenamedCheckbox.isChecked());
        prefs.setNotifyModeChangedEnabled(notifyModeChangedCheckbox.isChecked());
        Toast.makeText(this, R.string.notices_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveCapacitySettings() {
        int maxMembers = Math.max(0, parseOrDefault(maxMembersInput, prefs.getMaxMembers()));
        prefs.setMaxMembers(maxMembers);
        maxMembersInput.setText(String.valueOf(maxMembers));
        prefs.setGroupFullMessage(groupFullMessageInput.getText().toString().trim());
        refreshCapacityStatus();
        Toast.makeText(this, R.string.capacity_saved, Toast.LENGTH_SHORT).show();
    }

    private void refreshCapacityStatus() {
        int activeCount = memberRepository.countActiveMembers();
        capacityStatusView.setText(getString(R.string.capacity_status, activeCount));
    }

    private void saveContentSettings() {
        prefs.setAppendSenderNumberEnabled(appendSenderNumberCheckbox.isChecked());
        prefs.setStripPhoneNumbersEnabled(stripPhoneNumbersCheckbox.isChecked());
        prefs.setSaltTimestampEnabled(saltTimestampCheckbox.isChecked());
        prefs.setSaltTimestampFormat(Prefs.SaltTimestampFormat.values()[saltTimestampFormatSpinner.getSelectedItemPosition()]);
        prefs.setSaltHexEnabled(saltHexCheckbox.isChecked());
        prefs.setSaltHexPosition(Prefs.SaltHexPosition.values()[saltHexPositionSpinner.getSelectedItemPosition()]);
        prefs.setSaltZwspEnabled(saltZwspCheckbox.isChecked());
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveFailureSettings() {
        prefs.setRetryLimit(Math.max(0, parseOrDefault(retryLimitInput, prefs.getRetryLimit())));
        prefs.setFailureAlertThreshold(Math.max(1, parseOrDefault(failureAlertThresholdInput, prefs.getFailureAlertThreshold())));
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveGroupLimitSettings() {
        Integer resetMinute = parseMinuteOfDay(dailyLimitResetTimeInput.getText().toString().trim());
        prefs.setDailyLimitResetMinuteOfDay(resetMinute != null ? resetMinute : prefs.getDailyLimitResetMinuteOfDay());
        dailyLimitResetTimeInput.setText(formatMinuteOfDay(prefs.getDailyLimitResetMinuteOfDay()));

        prefs.setGroupDailyLimitEnabled(groupDailyLimitEnabledCheckbox.isChecked());
        prefs.setGroupDailyLimitValue(Math.max(0, parseOrDefault(groupDailyLimitValueInput, prefs.getGroupDailyLimitValue())));
        prefs.setDefaultIndividualLimitSeed(Math.max(0, parseOrDefault(defaultIndividualLimitInput, prefs.getDefaultIndividualLimitSeed())));

        refreshGroupDailyLimitStatus();
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private void applyIndividualLimit(boolean onlyNonCustomized) {
        int value = Math.max(0, parseOrDefault(defaultIndividualLimitInput, prefs.getDefaultIndividualLimitSeed()));
        prefs.setDefaultIndividualLimitSeed(value);

        int affected = 0;
        for (Member m : memberRepository.getActiveMembers()) {
            if (!onlyNonCustomized || !m.dailyLimitCustom) {
                affected++;
            }
        }
        new DailyLimitManager(this).bulkSetIndividualLimit(value, onlyNonCustomized);
        Toast.makeText(this, getString(R.string.individual_limit_applied, affected), Toast.LENGTH_SHORT).show();
    }

    private void refreshGroupDailyLimitStatus() {
        DailyLimitManager.Status status = new DailyLimitManager(this).groupStatus();
        if (!status.enabled) {
            groupDailyLimitStatusView.setText(R.string.group_daily_limit_status_disabled);
            return;
        }
        String resetLabel = DateFormat.format("h:mm a", status.resetAtMillis).toString();
        groupDailyLimitStatusView.setText(getString(R.string.tpl_group_daily_limit_status, status.used, status.limit, resetLabel));
    }

    private String formatMinuteOfDay(int minuteOfDay) {
        return String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private Integer parseMinuteOfDay(String text) {
        String[] parts = text.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private void restartApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void loadSystemSettings() {
        boolean hasPermission = RateLimitSettings.hasPermission(this);
        int maxCount = RateLimitSettings.readMaxCount(this);
        long intervalMs = RateLimitSettings.readIntervalMs(this);

        systemMaxCountInput.setText(maxCount >= 0 ? String.valueOf(maxCount) : "");
        systemIntervalInput.setText(intervalMs >= 0 ? String.valueOf(intervalMs / 60000L) : "");
        systemMaxCountInput.setHint(maxCount >= 0 ? "" : getString(R.string.rate_limit_value_unavailable));
        systemIntervalInput.setHint(intervalMs >= 0 ? "" : getString(R.string.rate_limit_value_unavailable));

        systemMaxCountInput.setEnabled(hasPermission);
        systemIntervalInput.setEnabled(hasPermission);
        systemSaveButton.setEnabled(hasPermission);
        systemPermissionNotice.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
        systemPermissionNotice.setText(getString(R.string.rate_limit_permission_missing) + "\n" + RateLimitSettings.ADB_GRANT_COMMAND);
    }

    private void copyAdbCommandToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("adb command", RateLimitSettings.ADB_GRANT_COMMAND));
        Toast.makeText(this, R.string.toast_adb_command_copied, Toast.LENGTH_SHORT).show();
    }

    private void saveSystemSettings() {
        int maxCount = parseOrDefault(systemMaxCountInput, -1);
        int intervalMinutes = parseOrDefault(systemIntervalInput, -1);
        if (maxCount >= 0) {
            RateLimitSettings.writeMaxCount(this, maxCount);
        }
        if (intervalMinutes >= 0) {
            RateLimitSettings.writeIntervalMs(this, intervalMinutes * 60000L);
        }
        loadSystemSettings();
    }

    private int parseOrDefault(EditText input, int defaultValue) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void updateClearHistoryButtonLabel() {
        String size = formatSize(messageRepository.estimateStorageBytes());
        clearHistoryButton.setText(getString(R.string.action_clear_history, size));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return "~" + bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return "~" + (bytes / 1024) + " KB";
        }
        return String.format(Locale.US, "~%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_warning)
                .setPositiveButton(R.string.action_continue, (dialog, which) -> clearHistory())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void clearHistory() {
        messageRepository.deleteAll();
        Toast.makeText(this, R.string.clear_history_done, Toast.LENGTH_LONG).show();
        updateClearHistoryButtonLabel();
    }

    private void confirmDisbandGroup() {
        int pin = 1000 + new Random().nextInt(9000);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_disband_confirm, null);
        TextView pinView = dialogView.findViewById(R.id.text_disband_pin);
        EditText pinInput = dialogView.findViewById(R.id.edit_disband_pin);
        pinView.setText(String.valueOf(pin));

        new AlertDialog.Builder(this)
                .setTitle(R.string.disband_group_title)
                .setMessage(R.string.disband_group_warning)
                .setView(dialogView)
                .setPositiveButton(R.string.action_disband_group, (dialog, which) -> {
                    if (pinInput.getText().toString().trim().equals(String.valueOf(pin))) {
                        disbandGroup();
                    } else {
                        Toast.makeText(this, R.string.disband_pin_mismatch, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void disbandGroup() {
        DbHelper.getInstance(this).wipeAllData();
        prefs.resetGroupName();
        Toast.makeText(this, R.string.disband_group_done, Toast.LENGTH_LONG).show();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
