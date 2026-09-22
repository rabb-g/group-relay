package com.sh7411usa.jrelay;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.SendQueueStatus;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.UiUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends BaseActivity {

    private static final long QUEUE_STATUS_TICK_MS = 1000;

    private Prefs prefs;
    private MemberRepository memberRepository;
    private MessageRepository messageRepository;
    private OutboxRepository outboxRepository;
    private CommandProcessor commandProcessor;

    private TextView groupNameView;
    private TextView announcementBadgeView;
    private TextView replyBadgeView;
    private TextView pausedBadgeView;
    private TextView statsMembersView;
    private TextView statsAdminsView;
    private TextView statsMutedView;
    private TextView statsMessagesTodayView;
    private TextView statsMessagesTotalView;
    private TextView statsFailedTodayView;
    private TextView queueCountView;
    private TextView sendingBadgeView;
    private TextView nextBurstView;
    private LinearLayout recentActivityContainer;

    private final Handler queueStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable queueStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshQueueStatus();
            queueStatusHandler.postDelayed(this, QUEUE_STATUS_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (!prefs.isConsentAccepted()) {
            startActivity(new Intent(this, ConsentActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        memberRepository = new MemberRepository(this);
        messageRepository = new MessageRepository(this);
        outboxRepository = new OutboxRepository(this);
        commandProcessor = new CommandProcessor(this);

        groupNameView = findViewById(R.id.text_group_name);
        announcementBadgeView = findViewById(R.id.badge_announcement_mode);
        replyBadgeView = findViewById(R.id.badge_reply_mode);
        pausedBadgeView = findViewById(R.id.badge_service_paused);
        statsMembersView = findViewById(R.id.text_stats_members);
        statsAdminsView = findViewById(R.id.text_stats_admins);
        statsMutedView = findViewById(R.id.text_stats_muted);
        statsMessagesTodayView = findViewById(R.id.text_stats_messages_today);
        statsMessagesTotalView = findViewById(R.id.text_stats_messages_total);
        statsFailedTodayView = findViewById(R.id.text_stats_failed_today);
        queueCountView = findViewById(R.id.text_queue_count);
        sendingBadgeView = findViewById(R.id.badge_sending);
        nextBurstView = findViewById(R.id.text_next_burst);
        recentActivityContainer = findViewById(R.id.container_recent_activity);

        findViewById(R.id.button_options).setOnClickListener(this::showOptionsMenu);
        findViewById(R.id.button_membership).setOnClickListener(v ->
                startActivity(new Intent(this, MembershipActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.isConsentAccepted()) {
            return;
        }
        refresh();
        queueStatusHandler.post(queueStatusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        queueStatusHandler.removeCallbacks(queueStatusTick);
    }

    private void showOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_options_menu, popup.getMenu());

        // Offer the two modes that are not currently active; the active mode has no menu entry.
        Prefs.GroupMode currentMode = prefs.getGroupMode();
        Prefs.GroupMode modeA = null;
        Prefs.GroupMode modeB = null;
        for (Prefs.GroupMode mode : Prefs.GroupMode.values()) {
            if (mode == currentMode) {
                continue;
            }
            if (modeA == null) {
                modeA = mode;
            } else {
                modeB = mode;
            }
        }
        final Prefs.GroupMode targetA = modeA;
        final Prefs.GroupMode targetB = modeB;
        popup.getMenu().findItem(R.id.menu_switch_mode_a).setTitle(modeSwitchTitleRes(targetA));
        popup.getMenu().findItem(R.id.menu_switch_mode_b).setTitle(modeSwitchTitleRes(targetB));

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_set_group_name) {
                showRenameDialog();
                return true;
            } else if (id == R.id.menu_add_member) {
                startActivity(new Intent(this, AddMemberActivity.class));
                return true;
            } else if (id == R.id.menu_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            } else if (id == R.id.menu_send_to_group) {
                startActivity(new Intent(this, SendGroupMessageActivity.class));
                return true;
            } else if (id == R.id.menu_switch_mode_a) {
                switchGroupMode(targetA);
                return true;
            } else if (id == R.id.menu_switch_mode_b) {
                switchGroupMode(targetB);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private int modeSwitchTitleRes(Prefs.GroupMode mode) {
        if (mode == Prefs.GroupMode.GROUP) {
            return R.string.menu_switch_to_group;
        } else if (mode == Prefs.GroupMode.ANNOUNCEMENT) {
            return R.string.menu_switch_to_announcement;
        }
        return R.string.menu_switch_to_reply;
    }

    private void switchGroupMode(Prefs.GroupMode newMode) {
        commandProcessor.setGroupMode(newMode, getString(R.string.default_added_by_admin), -1);
        refreshQueueStatus();
    }

    private void refresh() {
        List<Member> members = memberRepository.getActiveMembers();
        int adminCount = 0;
        int mutedCount = 0;
        for (Member m : members) {
            if (m.isAdmin) {
                adminCount++;
            }
            if (m.isMuted) {
                mutedCount++;
            }
        }
        statsMembersView.setText(String.valueOf(members.size()));
        statsAdminsView.setText(String.valueOf(adminCount));
        statsMutedView.setText(String.valueOf(mutedCount));

        long startOfDay = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        statsMessagesTodayView.setText(String.valueOf(messageRepository.countSince(startOfDay)));
        statsMessagesTotalView.setText(String.valueOf(messageRepository.countAll()));
        statsFailedTodayView.setText(String.valueOf(messageRepository.countFailedSince(startOfDay)));

        refreshQueueStatus();
        renderRecentActivity();
    }

    private void refreshQueueStatus() {
        groupNameView.setText(prefs.getGroupName());
        Prefs.GroupMode mode = prefs.getGroupMode();
        announcementBadgeView.setVisibility(mode == Prefs.GroupMode.ANNOUNCEMENT ? View.VISIBLE : View.GONE);
        replyBadgeView.setVisibility(mode == Prefs.GroupMode.REPLY ? View.VISIBLE : View.GONE);
        pausedBadgeView.setVisibility(prefs.isPaused() ? View.VISIBLE : View.GONE);
        queueCountView.setText(String.valueOf(outboxRepository.countUnsent()));

        long nextBurstAt = SendQueueStatus.getNextBurstAtMillis();
        int holding = outboxRepository.countHolding();
        if (nextBurstAt > 0) {
            long remainingMs = Math.max(0, nextBurstAt - System.currentTimeMillis());
            String burstStatus = getString(R.string.stats_next_burst,
                    formatDuration(remainingMs), SendQueueStatus.getNextBurstSize());
            if (holding > 0) {
                burstStatus += getString(R.string.queue_holding_suffix, holding);
            }
            nextBurstView.setText(burstStatus);
            nextBurstView.setVisibility(View.VISIBLE);
            sendingBadgeView.setVisibility(View.VISIBLE);
        } else if (holding > 0) {
            nextBurstView.setText(getString(R.string.queue_holding_only, holding));
            nextBurstView.setVisibility(View.VISIBLE);
            sendingBadgeView.setVisibility(View.GONE);
        } else {
            nextBurstView.setVisibility(View.GONE);
            sendingBadgeView.setVisibility(View.GONE);
        }
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private void renderRecentActivity() {
        recentActivityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecent(20);
        if (recent.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_activity_yet);
            recentActivityContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < recent.size(); i++) {
            MessageRecord record = recent.get(i);
            View row = inflater.inflate(R.layout.row_message, recentActivityContainer, false);
            TextView bodyView = row.findViewById(R.id.text_message_body);
            TextView metaView = row.findViewById(R.id.text_message_meta);
            bodyView.setText(record.body);
            String memberLabel = "";
            if (record.memberId != null) {
                Member m = memberRepository.findById(record.memberId);
                if (m != null) {
                    memberLabel = m.nickname + " • ";
                }
            }
            metaView.setText(memberLabel + DateFormat.format("MMM d, h:mm a", record.timestamp));

            if (record.memberId != null) {
                long memberId = record.memberId;
                row.setClickable(true);
                row.setFocusable(true);
                row.setBackgroundResource(R.drawable.focus_highlight);
                row.setOnClickListener(v -> {
                    Intent intent = new Intent(MainActivity.this, MemberDetailActivity.class);
                    intent.putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId);
                    startActivity(intent);
                });
            }

            recentActivityContainer.addView(row);
            if (i < recent.size() - 1) {
                recentActivityContainer.addView(UiUtil.createDivider(this, R.color.divider));
            }
        }
    }

    private void showRenameDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        EditText input = dialogView.findViewById(R.id.edit_text_input);
        input.setText(prefs.getGroupName());
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit_group_name)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty() && !newName.equals(prefs.getGroupName())) {
                        commandProcessor.changeGroupName(newName, getString(R.string.default_added_by_admin), -1);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
