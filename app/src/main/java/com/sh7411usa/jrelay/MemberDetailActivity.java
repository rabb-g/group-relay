package com.sh7411usa.jrelay;

import android.app.Activity;
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
import com.sh7411usa.jrelay.util.Prefs;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MemberDetailActivity extends Activity {

    public static final String EXTRA_MEMBER_ID = "extra_member_id";

    private MemberRepository memberRepository;
    private MessageRepository messageRepository;
    private CommandProcessor commandProcessor;
    private Prefs prefs;
    private long memberId;
    private Member member;

    private TextView nicknameView;
    private TextView numberView;
    private TextView statsView;
    private LinearLayout activityContainer;
    private Button adminButton;
    private Button muteButton;

    private CheckBox customRateLimitCheckbox;
    private LinearLayout rateLimitFieldsContainer;
    private EditText rateBurstMinInput;
    private EditText rateBurstMaxInput;
    private EditText rateMinWaitInput;
    private EditText rateMaxWaitInput;
    private CheckBox rateInitialDelayCheckbox;

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
        statsView = findViewById(R.id.text_stats);
        activityContainer = findViewById(R.id.container_member_activity);
        adminButton = findViewById(R.id.button_toggle_admin);
        muteButton = findViewById(R.id.button_toggle_mute);

        customRateLimitCheckbox = findViewById(R.id.checkbox_custom_rate_limit);
        rateLimitFieldsContainer = findViewById(R.id.container_rate_limit_fields);
        rateBurstMinInput = findViewById(R.id.edit_member_burst_min);
        rateBurstMaxInput = findViewById(R.id.edit_member_burst_max);
        rateMinWaitInput = findViewById(R.id.edit_member_min_wait);
        rateMaxWaitInput = findViewById(R.id.edit_member_max_wait);
        rateInitialDelayCheckbox = findViewById(R.id.checkbox_member_initial_delay);

        adminButton.setOnClickListener(v -> {
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

        customRateLimitCheckbox.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) ->
                rateLimitFieldsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE));
        findViewById(R.id.button_save_rate_limit).setOnClickListener(v -> saveRateLimit());
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

        StringBuilder stats = new StringBuilder();
        stats.append(getString(R.string.label_member_since, DateFormat.format("MMM d, yyyy", member.createdAt))).append("\n");
        if (lastActivity > 0) {
            stats.append(getString(R.string.label_last_activity, DateFormat.format("MMM d, yyyy h:mm a", lastActivity))).append("\n");
        }
        stats.append(getString(R.string.label_messages_sent, sentCount)).append("\n");
        stats.append(getString(R.string.label_messages_received, receivedCount)).append("\n");
        stats.append(getString(R.string.label_activity_level, activityLevelLabel(recentCount)));
        statsView.setText(stats.toString());

        populateRateLimitFields();
        renderActivity();
    }

    private void populateRateLimitFields() {
        customRateLimitCheckbox.setChecked(member.rateLimitCustom);
        rateLimitFieldsContainer.setVisibility(member.rateLimitCustom ? View.VISIBLE : View.GONE);

        rateBurstMinInput.setText(String.valueOf(member.rateBurstMin != null ? member.rateBurstMin : prefs.getBurstMin()));
        rateBurstMaxInput.setText(String.valueOf(member.rateBurstMax != null ? member.rateBurstMax : prefs.getBurstMax()));
        rateMinWaitInput.setText(String.valueOf(member.rateMinWait != null ? member.rateMinWait : prefs.getMinWaitSeconds()));
        rateMaxWaitInput.setText(String.valueOf(member.rateMaxWait != null ? member.rateMaxWait : prefs.getMaxWaitSeconds()));
        rateInitialDelayCheckbox.setChecked(member.rateInitialDelay != null ? member.rateInitialDelay != 0 : prefs.isInitialDelayEnabled());
    }

    private void saveRateLimit() {
        if (!customRateLimitCheckbox.isChecked()) {
            memberRepository.clearRateLimitOverride(member.id);
            refresh();
            Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
            return;
        }

        int burstMin = Math.max(1, parseOrDefault(rateBurstMinInput, prefs.getBurstMin()));
        int burstMax = Math.max(burstMin, parseOrDefault(rateBurstMaxInput, prefs.getBurstMax()));
        int minWait = Math.max(0, parseOrDefault(rateMinWaitInput, prefs.getMinWaitSeconds()));
        int maxWait = Math.max(minWait, parseOrDefault(rateMaxWaitInput, prefs.getMaxWaitSeconds()));

        memberRepository.setRateLimitOverride(member.id, burstMin, burstMax, minWait, maxWait,
                rateInitialDelayCheckbox.isChecked());
        refresh();
        Toast.makeText(this, R.string.rate_limit_member_saved, Toast.LENGTH_SHORT).show();
    }

    private int parseOrDefault(EditText input, int defaultValue) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String activityLevelLabel(int recentCount) {
        if (recentCount >= 20) {
            return getString(R.string.activity_level_high);
        } else if (recentCount >= 5) {
            return getString(R.string.activity_level_medium);
        } else if (recentCount >= 1) {
            return getString(R.string.activity_level_low);
        }
        return getString(R.string.activity_level_none);
    }

    private void renderActivity() {
        activityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecentForMember(member.id, 20);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (MessageRecord record : recent) {
            View row = inflater.inflate(R.layout.row_message, activityContainer, false);
            TextView bodyView = row.findViewById(R.id.text_message_body);
            TextView metaView = row.findViewById(R.id.text_message_meta);
            bodyView.setText(record.body);
            metaView.setText(DateFormat.format("MMM d, h:mm a", record.timestamp));
            activityContainer.addView(row);
        }
    }

    private void confirmRemove() {
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
