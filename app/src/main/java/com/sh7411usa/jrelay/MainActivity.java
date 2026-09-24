package com.sh7411usa.jrelay;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.sh7411usa.jrelay.db.JoinRequestRepository;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.SendQueueStatus;
import com.sh7411usa.jrelay.sms.SmsCatchUp;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.sms.Watchdog;
import com.sh7411usa.jrelay.sms.mms.MmsIngestService;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.UiUtil;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {

    private static final long QUEUE_STATUS_TICK_MS = 1000;

    /**
     * How long MmsIngestService gets to come up after a start request before the forwarding banner
     * calls it stopped. The start is asynchronous, so isRunning() is still false straight after it.
     */
    private static final long SERVICE_START_GRACE_MS = 1500;

    /** The tick re-reads whether sub-groups exist only every this many ticks (~10 s). */
    private static final int SUBGROUP_RECHECK_TICKS = 10;

    private static final String[] CRITICAL_PERMISSIONS = {
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
    };

    /**
     * Needed only to read and forward inbound group MMS, so they count toward the "missing a
     * permission" banner only while group delivery is on. In SMS mode relaying works without them,
     * and a banner claiming the app "is not relaying messages" would be false.
     */
    private static final String[] GROUP_MMS_PERMISSIONS = {
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_MMS,
    };

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
    private TextView permissionWarningView;
    private TextView joinRequestsBannerView;
    private TextView forwardingHealthView;
    private TextView batteryBannerView;
    /** When MmsIngestService was last asked to start; the "stopped" verdict waits out the grace. */
    private long serviceStartRequestedAt;
    /** Set when a tap-to-restart did not bring the service up, so the banner adds battery advice. */
    private boolean restartFailed;
    /** Whether any sub-group exists, for the SMS-mode forwarding banner; see refreshHasSubgroups. */
    private boolean hasSubgroups;
    /** Ticks since hasSubgroups was last read from the database. */
    private int ticksSinceSubgroupCheck;
    private LinearLayout recentActivityContainer;
    private TextView recentActivityEmptyView;
    /** Recent-activity rows by record index; null where the row is not clickable. */
    private final List<View> recentActivityRows = new ArrayList<>();
    /** Row to refocus after the list is rebuilt, so Back from a member lands where the user was. */
    private int recentActivityFocusIndex = -1;

    private final Handler queueStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable queueStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshQueueStatus();
            if (++ticksSinceSubgroupCheck >= SUBGROUP_RECHECK_TICKS) {
                refreshHasSubgroups();
            }
            // Also here, not just in onResume, so the service dying while the screen is open shows.
            updateForwardingHealthBanner();
            queueStatusHandler.postDelayed(this, QUEUE_STATUS_TICK_MS);
        }
    };
    private final Runnable restartCheck = new Runnable() {
        @Override
        public void run() {
            restartFailed = !MmsIngestService.isRunning();
            updateForwardingHealthBanner();
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
        permissionWarningView = findViewById(R.id.warning_permission_missing);
        joinRequestsBannerView = findViewById(R.id.banner_join_requests);
        forwardingHealthView = findViewById(R.id.banner_forwarding_health);
        batteryBannerView = findViewById(R.id.banner_battery_optimisation);
        recentActivityContainer = findViewById(R.id.container_recent_activity);
        recentActivityEmptyView = findViewById(R.id.text_recent_activity_empty);
        applyTileColumns(findViewById(R.id.grid_stats_group));
        applyTileColumns(findViewById(R.id.grid_stats_messages));

        findViewById(R.id.button_options).setOnClickListener(this::showOptionsMenu);
        findViewById(R.id.button_membership).setOnClickListener(v ->
                startActivity(new Intent(this, MembershipActivity.class)));
        permissionWarningView.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        });
        joinRequestsBannerView.setOnClickListener(v ->
                startActivity(new Intent(this, MembershipActivity.class)));
        forwardingHealthView.setOnClickListener(v -> onForwardingHealthClicked());
        batteryBannerView.setOnClickListener(v -> requestBatteryExemption());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.isConsentAccepted()) {
            return;
        }
        updatePermissionWarning();
        updateJoinRequestsBanner();
        updateBatteryBanner();
        if (outboxRepository.countUnsent() > 0) {
            SmsSendService.start(this);
        }
        // Cheap catch-up scan; no-ops unless delivery mode is GROUP_MMS.
        requestIngestStart();
        Watchdog.schedule(this);
        // The relay's own number must never be a member (see purgeRelaySelfMember). Checked on
        // every resume so a mistaken add is undone as soon as the owner looks at the app.
        String purgedName = new CommandProcessor(this).purgeRelaySelfMember();
        if (purgedName != null) {
            Toast.makeText(this, getString(R.string.toast_relay_self_member_removed, purgedName),
                    Toast.LENGTH_LONG).show();
        }
        // Relays any text that arrived while the app was not running, instead of waiting for the
        // next watchdog run. Background thread; serialized with every other scan.
        SmsCatchUp.runAsync(this);
        refreshHasSubgroups();
        updateForwardingHealthBanner();
        refresh();
        queueStatusHandler.post(queueStatusTick);
    }

    /**
     * Fits the stat tiles to the screen: all three in one row on normal phones, two per row on
     * small ones, one per row on the narrowest flip phones so labels are never squeezed.
     */
    private void applyTileColumns(GridLayout grid) {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int columns = widthDp >= 360 ? 3 : widthDp >= 200 ? 2 : 1;
        grid.setColumnCount(columns);
    }

    private boolean anyDenied(String[] permissions) {
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private void updatePermissionWarning() {
        boolean missing = anyDenied(CRITICAL_PERMISSIONS)
                || (prefs.getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS
                        && anyDenied(GROUP_MMS_PERMISSIONS));
        permissionWarningView.setVisibility(missing ? View.VISIBLE : View.GONE);
    }

    private void updateJoinRequestsBanner() {
        int pending = new JoinRequestRepository(this).count();
        if (pending > 0) {
            joinRequestsBannerView.setText(getString(R.string.banner_join_requests, pending));
        }
        joinRequestsBannerView.setVisibility(pending > 0 ? View.VISIBLE : View.GONE);
    }

    private void requestIngestStart() {
        serviceStartRequestedAt = System.currentTimeMillis();
        MmsIngestService.start(this);
    }

    /**
     * Group forwarding once stopped with nothing on screen saying so. Shown when sub-groups exist
     * but group delivery is off, or when group delivery is on but the ingest service is not alive.
     */
    private void updateForwardingHealthBanner() {
        int textRes = 0;
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            restartFailed = false;
            if (hasSubgroups) {
                textRes = R.string.banner_forwarding_off;
            }
        } else if (MmsIngestService.isRunning()) {
            restartFailed = false;
        } else if (System.currentTimeMillis() - serviceStartRequestedAt >= SERVICE_START_GRACE_MS) {
            textRes = restartFailed
                    ? R.string.banner_forwarding_stopped_battery
                    : R.string.banner_forwarding_stopped;
        } else {
            // Still inside the start grace period; leave the banner as it is.
            return;
        }
        if (textRes != 0) {
            forwardingHealthView.setText(textRes);
        }
        forwardingHealthView.setVisibility(textRes != 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * Cached so the one-second tick does not query the database every second. Re-read on resume,
     * on refresh, and every {@link #SUBGROUP_RECHECK_TICKS} ticks, so a change made while this
     * screen is open still shows within about ten seconds.
     */
    private void refreshHasSubgroups() {
        hasSubgroups = !memberRepository.getDistinctSubgroupIds().isEmpty();
        ticksSinceSubgroupCheck = 0;
    }

    private void onForwardingHealthClicked() {
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        requestIngestStart();
        queueStatusHandler.removeCallbacks(restartCheck);
        queueStatusHandler.postDelayed(restartCheck, SERVICE_START_GRACE_MS);
    }

    private void updateBatteryBanner() {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryBannerView.setVisibility(exempt ? View.GONE : View.VISIBLE);
    }

    private void requestBatteryExemption() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (Exception e) {
            // Some builds lack the direct prompt; fall back to the full list.
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                // No battery settings screen on this device; nothing more to offer.
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        queueStatusHandler.removeCallbacks(queueStatusTick);
        queueStatusHandler.removeCallbacks(restartCheck);
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
        refreshHasSubgroups();

        long windowStart = new DailyLimitManager(this).currentWindowStart();
        statsMessagesTodayView.setText(String.valueOf(messageRepository.countSince(windowStart)));
        statsMessagesTotalView.setText(String.valueOf(messageRepository.countAll()));
        statsFailedTodayView.setText(String.valueOf(messageRepository.countFailedSince(windowStart)));

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
            return getString(R.string.dashboard_duration_ms, minutes, seconds);
        }
        return getString(R.string.dashboard_duration_s, seconds);
    }

    private void renderRecentActivity() {
        int focused = recentActivityRows.indexOf(recentActivityContainer.getFocusedChild());
        if (focused >= 0) {
            recentActivityFocusIndex = focused;
        }
        recentActivityRows.clear();
        recentActivityContainer.removeAllViews();
        List<MessageRecord> recent = messageRepository.getRecent(20);
        recentActivityEmptyView.setVisibility(recent.isEmpty() ? View.VISIBLE : View.GONE);
        if (recent.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < recent.size(); i++) {
            MessageRecord record = recent.get(i);
            View row = inflater.inflate(R.layout.row_message, recentActivityContainer, false);
            TextView bodyView = row.findViewById(R.id.text_message_body);
            TextView metaView = row.findViewById(R.id.text_message_meta);
            bodyView.setText(record.body);
            String date = DateUtils.formatDateTime(this, record.timestamp,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
            Member m = record.memberId != null ? memberRepository.findById(record.memberId) : null;
            metaView.setText(m != null
                    ? getString(R.string.dashboard_activity_meta, m.nickname, date)
                    : date);

            View focusableRow = null;
            if (record.memberId != null) {
                long memberId = record.memberId;
                final int index = i;
                focusableRow = row;
                row.setClickable(true);
                row.setFocusable(true);
                row.setBackgroundResource(R.drawable.focus_highlight);
                row.setOnClickListener(v -> {
                    recentActivityFocusIndex = index;
                    Intent intent = new Intent(MainActivity.this, MemberDetailActivity.class);
                    intent.putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId);
                    startActivity(intent);
                });
            }

            recentActivityRows.add(focusableRow);
            recentActivityContainer.addView(row);
            if (i < recent.size() - 1) {
                recentActivityContainer.addView(UiUtil.createDivider(this, R.color.divider));
            }
        }
        restoreRecentActivityFocus();
    }

    /** Refocuses the remembered row, or the nearest clickable one if it is gone. DPAD only. */
    private void restoreRecentActivityFocus() {
        int target = recentActivityFocusIndex;
        recentActivityFocusIndex = -1;
        if (target < 0 || recentActivityRows.isEmpty() || recentActivityContainer.isInTouchMode()) {
            return;
        }
        target = Math.min(target, recentActivityRows.size() - 1);
        for (int offset = 0; offset < recentActivityRows.size(); offset++) {
            for (int candidate : new int[] {target + offset, target - offset}) {
                if (candidate >= 0 && candidate < recentActivityRows.size()
                        && recentActivityRows.get(candidate) != null) {
                    recentActivityRows.get(candidate).requestFocus();
                    return;
                }
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
