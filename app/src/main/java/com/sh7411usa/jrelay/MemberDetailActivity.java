package com.sh7411usa.jrelay;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.UiUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MemberDetailActivity extends BaseActivity {

    public static final String EXTRA_MEMBER_ID = "extra_member_id";

    private MemberRepository memberRepository;
    private MessageRepository messageRepository;
    private CommandProcessor commandProcessor;
    private Prefs prefs;
    private long memberId;
    private Member member;

    private TextView nicknameView;
    private TextView numberView;
    private TextView memberMetaView;
    private TextView statSentView;
    private TextView statReceivedView;
    private TextView statTodayView;
    private TextView statActivityLevelView;
    private LinearLayout activityContainer;
    private Button adminButton;
    private Button muteButton;

    private CheckBox customDailyLimitCheckbox;
    private LinearLayout dailyLimitFieldsContainer;
    private CheckBox dailyLimitUnlimitedCheckbox;
    private LinearLayout dailyLimitValueContainer;
    private EditText dailyLimitValueInput;
    private TextView dailyLimitUsageView;
    private Button overrideDailyLimitButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_member_detail);

        memberRepository = new MemberRepository(this);
        messageRepository = new MessageRepository(this);
        commandProcessor = new CommandProcessor(this);
        prefs = new Prefs(this);
        memberId = getIntent().getLongExtra(EXTRA_MEMBER_ID, -1);

        nicknameView = findViewById(R.id.text_nickname);
        numberView = findViewById(R.id.text_number);
        memberMetaView = findViewById(R.id.text_member_meta);
        statSentView = findViewById(R.id.text_stat_sent);
        statReceivedView = findViewById(R.id.text_stat_received);
        statTodayView = findViewById(R.id.text_stat_today);
        statActivityLevelView = findViewById(R.id.text_stat_activity_level);
        activityContainer = findViewById(R.id.container_member_activity);
        adminButton = findViewById(R.id.button_toggle_admin);
        muteButton = findViewById(R.id.button_toggle_mute);

        customDailyLimitCheckbox = findViewById(R.id.checkbox_custom_daily_limit);
        dailyLimitFieldsContainer = findViewById(R.id.container_daily_limit_fields);
        dailyLimitUnlimitedCheckbox = findViewById(R.id.checkbox_daily_limit_unlimited);
        dailyLimitValueContainer = findViewById(R.id.container_daily_limit_value);
        dailyLimitValueInput = findViewById(R.id.edit_daily_limit_value);
        dailyLimitUsageView = findViewById(R.id.text_daily_limit_usage);
        overrideDailyLimitButton = findViewById(R.id.button_override_daily_limit);

        adminButton.setOnClickListener(v -> {
            if (member.isAdmin && memberRepository.countActiveAdmins() <= 1) {
                Toast.makeText(this, R.string.error_last_admin, Toast.LENGTH_SHORT).show();
                return;
            }
            memberRepository.setAdmin(member.id, !member.isAdmin);
            refresh();
        });
        muteButton.setOnClickListener(v -> {
            memberRepository.setMuted(member.id, !member.isMuted);
            refresh();
        });
        findViewById(R.id.button_remove).setOnClickListener(v -> confirmRemove());
        findViewById(R.id.button_send_dm).setOnClickListener(v -> showDmDialog());
        findViewById(R.id.button_edit_nickname).setOnClickListener(v -> showEditNicknameDialog());
        findViewById(R.id.button_edit_number).setOnClickListener(v -> showEditNumberDialog());
        findViewById(R.id.button_export_contact).setOnClickListener(v -> exportContact());
        findViewById(R.id.button_call).setOnClickListener(v -> callMember());
        findViewById(R.id.button_text).setOnClickListener(v -> textMember());

        customDailyLimitCheckbox.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                dailyLimitFieldsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE));
        dailyLimitUnlimitedCheckbox.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                dailyLimitValueContainer.setVisibility(isChecked ? View.GONE : View.VISIBLE));
        findViewById(R.id.button_save_daily_limit).setOnClickListener(v -> saveDailyLimit());
        overrideDailyLimitButton.setOnClickListener(v -> overrideDailyLimit());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        member = memberRepository.findById(memberId);
        if (member == null || !member.active) {
            finish();
            return;
        }
        nicknameView.setText(member.nickname);
        numberView.setText(member.phoneE164);
        adminButton.setText(member.isAdmin ? R.string.action_revoke_admin : R.string.action_make_admin);
        muteButton.setText(member.isMuted ? R.string.action_unmute : R.string.action_mute);

        int sentCount = messageRepository.countForMember(member.id, "IN");
        int receivedCount = messageRepository.countForMember(member.id, "OUT");
        long lastActivity = messageRepository.lastActivityForMember(member.id);
        long sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        int recentCount = messageRepository.countForMemberSince(member.id, sevenDaysAgo);
        long startOfDay = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        int todayCount = messageRepository.countForMemberSince(member.id, startOfDay);

        if (lastActivity > 0) {
            memberMetaView.setText(getString(R.string.label_member_meta_with_activity,
                    DateFormat.format("MMM d, yyyy", member.createdAt), DateFormat.format("MMM d, h:mm a", lastActivity)));
        } else {
            memberMetaView.setText(getString(R.string.label_member_meta, DateFormat.format("MMM d, yyyy", member.createdAt)));
        }

        statSentView.setText(String.valueOf(sentCount));
        statReceivedView.setText(String.valueOf(receivedCount));
        statTodayView.setText(String.valueOf(todayCount));
        setActivityLevel(recentCount);

        populateDailyLimitFields();
        renderActivity();
    }

    private void setActivityLevel(int recentCount) {
        int labelRes;
        int colorRes;
        if (recentCount >= 20) {
            labelRes = R.string.activity_level_high;
            colorRes = R.color.success;
        } else if (recentCount >= 5) {
            labelRes = R.string.activity_level_medium;
            colorRes = R.color.primary;
        } else if (recentCount >= 1) {
            labelRes = R.string.activity_level_low;
            colorRes = R.color.warning;
        } else {
            labelRes = R.string.activity_level_none;
            colorRes = R.color.text_secondary;
        }
        statActivityLevelView.setText(labelRes);
        statActivityLevelView.setTextColor(getColor(colorRes));
    }

    private void populateDailyLimitFields() {
        customDailyLimitCheckbox.setChecked(member.dailyLimitCustom);
        dailyLimitFieldsContainer.setVisibility(member.dailyLimitCustom ? View.VISIBLE : View.GONE);

        boolean unlimited = member.dailyLimitCustom && member.dailyLimitValue == null;
        dailyLimitUnlimitedCheckbox.setChecked(unlimited);
        dailyLimitValueContainer.setVisibility(unlimited ? View.GONE : View.VISIBLE);
        dailyLimitValueInput.setText(String.valueOf(
                member.dailyLimitValue != null ? member.dailyLimitValue : prefs.getDefaultIndividualLimitSeed()));

        if (!member.dailyLimitCustom) {
            dailyLimitUsageView.setText(R.string.daily_limit_not_customized);
            overrideDailyLimitButton.setVisibility(View.GONE);
            return;
        }

        DailyLimitManager.Status status = new DailyLimitManager(this).memberStatus(member);
        dailyLimitUsageView.setText(status.unlimited
                ? getString(R.string.tpl_daily_limit_usage_unlimited, status.used)
                : getString(R.string.tpl_daily_limit_usage, status.used, status.limit));
        overrideDailyLimitButton.setVisibility(status.isExhausted() ? View.VISIBLE : View.GONE);
    }

    private void saveDailyLimit() {
        if (!customDailyLimitCheckbox.isChecked()) {
            memberRepository.clearDailyLimitOverride(member.id);
            refresh();
            Toast.makeText(this, R.string.daily_limit_saved, Toast.LENGTH_SHORT).show();
            return;
        }

        if (dailyLimitUnlimitedCheckbox.isChecked()) {
            memberRepository.setDailyLimitOverride(member.id, null);
        } else {
            int value = Math.max(0, parseOrDefault(dailyLimitValueInput, prefs.getDefaultIndividualLimitSeed()));
            memberRepository.setDailyLimitOverride(member.id, value);
        }
        refresh();
        Toast.makeText(this, R.string.daily_limit_saved, Toast.LENGTH_SHORT).show();
    }

    private void overrideDailyLimit() {
        new DailyLimitManager(this).overrideMember(member);
        refresh();
        Toast.makeText(this, R.string.daily_limit_override_done, Toast.LENGTH_SHORT).show();
    }

    private int parseOrDefault(EditText input, int defaultValue) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void renderActivity() {
        activityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecentForMember(member.id, 20);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < recent.size(); i++) {
            MessageRecord record = recent.get(i);
            View row = inflater.inflate(R.layout.row_message, activityContainer, false);
            TextView bodyView = row.findViewById(R.id.text_message_body);
            TextView metaView = row.findViewById(R.id.text_message_meta);
            bodyView.setText(record.body);
            metaView.setText(DateFormat.format("MMM d, h:mm a", record.timestamp));
            activityContainer.addView(row);
            if (i < recent.size() - 1) {
                activityContainer.addView(UiUtil.createDivider(this, R.color.divider));
            }
        }
    }

    private void confirmRemove() {
        if (member.isAdmin && memberRepository.countActiveAdmins() <= 1) {
            Toast.makeText(this, R.string.error_last_admin, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_remove_title)
                .setMessage(getString(R.string.confirm_remove_message, member.nickname))
                .setPositiveButton(R.string.action_remove, (dialog, which) -> {
                    commandProcessor.removeMember(member, getString(R.string.default_added_by_admin));
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDmDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_send_dm)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String message = input.getText().toString().trim();
                    if (!message.isEmpty()) {
                        commandProcessor.sendDirectMessage(member, message);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showEditNicknameDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        input.setText(member.nickname);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_edit_nickname)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String nickname = input.getText().toString().trim();
                    if (!nickname.isEmpty() && !nickname.equals(member.nickname)) {
                        commandProcessor.renameMemberFromApp(member, nickname, getString(R.string.default_added_by_admin));
                        refresh();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showEditNumberDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        input.setText(member.phoneE164);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_edit_number)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String normalized = PhoneNumberUtils.normalize(input.getText().toString());
                    if (normalized != null) {
                        memberRepository.setPhone(member.id, normalized);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void exportContact() {
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
        intent.putExtra(ContactsContract.Intents.Insert.NAME, member.nickname);
        intent.putExtra(ContactsContract.Intents.Insert.PHONE, member.phoneE164);
        startActivity(intent);
    }

    private void callMember() {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + member.phoneE164));
        startActivity(intent);
    }

    private void textMember() {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + member.phoneE164));
        startActivity(intent);
    }
}
