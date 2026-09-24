package com.sh7411usa.jrelay;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.sms.SubgroupPlanner;
import com.sh7411usa.jrelay.util.CsvUtil;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.UiUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class MembershipActivity extends BaseActivity {

    private static final int REQUEST_EXPORT_CSV = 1001;
    private static final int REQUEST_IMPORT_CSV = 1002;

    private MemberRepository memberRepository;
    private CommandProcessor commandProcessor;
    private LinearLayout container;
    private EditText searchInput;
    private TextView subgroupSizesView;
    private String currentQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_membership);
        memberRepository = new MemberRepository(this);
        commandProcessor = new CommandProcessor(this);
        container = findViewById(R.id.container_members);
        searchInput = findViewById(R.id.edit_search);
        subgroupSizesView = findViewById(R.id.text_subgroup_sizes);

        findViewById(R.id.button_add_member).setOnClickListener(v ->
                startActivity(new Intent(this, AddMemberActivity.class)));
        findViewById(R.id.button_membership_options).setOnClickListener(this::showOptionsMenu);
        findViewById(R.id.button_suggest_subgroups).setOnClickListener(v -> suggestSubgroupAssignments());
        findViewById(R.id.button_send_rosters).setOnClickListener(v -> sendSubgroupRosters());
        findViewById(R.id.button_merge_subgroups).setOnClickListener(v -> mergeSmallSubgroups());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentQuery = s.toString();
                renderMembers();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderMembers();
    }

    private void renderSubgroupSizes() {
        Map<Long, Integer> counts = memberRepository.countMembersPerSubgroup();
        if (counts.isEmpty()) {
            subgroupSizesView.setText(R.string.subgroup_sizes_none);
            return;
        }
        // TreeMap for a stable, ascending display order regardless of the repository's map type.
        TreeMap<Long, Integer> sorted = new TreeMap<>(counts);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(getString(R.string.subgroup_size_line, e.getKey(), e.getValue()));
        }
        subgroupSizesView.setText(sb.toString());
    }

    /**
     * Runs {@link SubgroupPlanner} over the currently-unassigned members and shows the proposal
     * for the admin to confirm. Applies via {@link MemberRepository#assignSubgroup} only when the
     * admin taps apply; the planner never touches the database itself, and neither does this
     * method on cancel.
     */
    private void suggestSubgroupAssignments() {
        List<Member> unassigned = memberRepository.getUnassignedActiveMembers();
        List<Long> unassignedIds = new ArrayList<>();
        for (Member m : unassigned) {
            unassignedIds.add(m.id);
        }

        Map<Long, Integer> sizesByLongId = memberRepository.countMembersPerSubgroup();
        Map<Integer, Integer> sizes = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : sizesByLongId.entrySet()) {
            sizes.put(e.getKey().intValue(), e.getValue());
        }

        int targetSize = new Prefs(this).getSubgroupTargetSize();
        SubgroupPlanner.Plan plan = SubgroupPlanner.planFor(unassignedIds, sizes, targetSize);
        String summary = SubgroupPlanner.summarize(plan);

        if (plan.assignments.isEmpty()) {
            // Nothing to apply, but the admin pressed the button and deserves to know why
            // (advisoryNotes explains it) rather than seeing nothing happen.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.subgroup_suggest_dialog_title)
                    .setMessage(summary)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        String message = getString(R.string.warning_subgroup_visibility) + "\n\n" + summary;
        new AlertDialog.Builder(this)
                .setTitle(R.string.subgroup_suggest_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.subgroup_suggest_apply_action, (dialog, which) -> {
                    java.util.LinkedHashSet<Long> affected = new java.util.LinkedHashSet<>();
                    for (SubgroupPlanner.Assignment a : plan.assignments) {
                        memberRepository.assignSubgroup(a.memberId, (long) a.subgroupId);
                        affected.add((long) a.subgroupId);
                    }
                    // One roster per sub-group that actually changed, not one per sub-group.
                    rosterAndDrain(affected);
                    renderMembers();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Queues one roster per sub-group, each listing only that sub-group's own members. Confirmed
     * first because it is real outgoing traffic — roughly four segments per sub-group in Hebrew —
     * and because the admin, not the app, decides when the membership has settled enough to be
     * worth publishing. See {@link CommandProcessor#sendSubgroupRosters()}.
     */
    /**
     * Queues a roster for each of the given sub-groups and kicks the send service, so an automatic
     * re-roster leaves the queue the same way a manual one does rather than waiting for the next
     * inbound message to drain it.
     *
     * <p>Takes the affected ids rather than re-rostering everything: only the threads whose
     * membership actually changed can see the change, and re-sending to the rest would multiply
     * the cost of a one-person edit by the number of sub-groups.
     */
    private void rosterAndDrain(java.util.Collection<Long> subgroupIds) {
        CommandProcessor processor = new CommandProcessor(this);
        for (Long subgroupId : subgroupIds) {
            processor.sendRosterFor(subgroupId);
        }
        SmsSendService.start(this);
    }

    /**
     * Proposes folding any sub-group that has shrunk to {@link SubgroupPlanner#MERGE_THRESHOLD} or
     * fewer members into another sub-group with room, and re-rosters both ends on confirmation.
     *
     * <p>Deliberately a button rather than something that fires on every departure. A merge costs
     * a fresh roster to everyone involved and a new thread to everyone who moved, so rebalancing
     * each time a member leaves would cost far more traffic than a thin group ever wastes. The
     * admin decides when a group has actually got too small to be worth keeping.
     */
    private void mergeSmallSubgroups() {
        Map<Integer, List<Long>> subgroupMembers = new TreeMap<>();
        for (Long subgroupId : memberRepository.getDistinctSubgroupIds()) {
            List<Long> ids = new ArrayList<>();
            for (Member m : memberRepository.getActiveSubgroupMembers(subgroupId)) {
                ids.add(m.id);
            }
            subgroupMembers.put(subgroupId.intValue(), ids);
        }

        SubgroupPlanner.Plan plan = SubgroupPlanner.planMerges(
                subgroupMembers, new Prefs(this).getSubgroupTargetSize());
        String summary = SubgroupPlanner.summarize(plan);

        if (plan.assignments.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.merge_dialog_title)
                    .setMessage(summary.isEmpty() ? getString(R.string.merge_none) : summary)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.merge_dialog_title)
                .setMessage(getString(R.string.warning_subgroup_visibility) + "\n\n" + summary)
                .setPositiveButton(R.string.merge_apply_action, (dialog, which) -> {
                    java.util.LinkedHashSet<Long> affected = new java.util.LinkedHashSet<>();
                    for (SubgroupPlanner.Assignment a : plan.assignments) {
                        // The source group the member is leaving is re-rostered too: its remaining
                        // members keep a list naming people who are no longer in their thread.
                        Member before = memberRepository.findById(a.memberId);
                        if (before != null && before.subgroupId != null) {
                            affected.add(before.subgroupId);
                        }
                        memberRepository.assignSubgroup(a.memberId, (long) a.subgroupId);
                        affected.add((long) a.subgroupId);
                    }
                    rosterAndDrain(affected);
                    renderMembers();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void sendSubgroupRosters() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_send_rosters)
                .setMessage(R.string.warning_subgroup_visibility)
                .setPositiveButton(R.string.action_send_rosters, (dialog, which) -> {
                    int queued = new CommandProcessor(this).sendSubgroupRosters();
                    if (queued == 0) {
                        Toast.makeText(this, R.string.roster_none, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, getString(R.string.roster_queued, queued),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Lets the admin assign, move, or clear one member's sub-group. Never applies a change
     *  without a confirmation step, and that confirmation always carries the number-visibility
     *  warning for any change that places the member into a group. */
    private void showSubgroupAssignDialog(Member member) {
        List<Long> existingIds = memberRepository.getDistinctSubgroupIds();
        Map<Long, Integer> sizes = memberRepository.countMembersPerSubgroup();

        List<CharSequence> options = new ArrayList<>();
        List<Long> optionIds = new ArrayList<>();
        for (Long id : existingIds) {
            if (member.subgroupId != null && member.subgroupId.equals(id)) {
                continue;
            }
            int count = sizes.containsKey(id) ? sizes.get(id) : 0;
            options.add(getString(R.string.subgroup_option_existing, id, count));
            optionIds.add(id);
        }
        options.add(getString(R.string.subgroup_option_new));
        optionIds.add(null);

        boolean canClear = member.subgroupId != null;
        if (canClear) {
            options.add(getString(R.string.subgroup_option_clear));
        }

        CharSequence[] items = options.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.subgroup_assign_dialog_title, member.nickname))
                .setItems(items, (dialog, which) -> {
                    if (canClear && which == items.length - 1) {
                        Long vacated = member.subgroupId;
                        memberRepository.assignSubgroup(member.id, null);
                        // The group they just left still holds a roster naming them, so its
                        // members would keep a saved contact for somebody no longer in their
                        // thread. Re-roster the group that was vacated; there is no destination
                        // to re-roster here because the member is now unassigned.
                        if (vacated != null) {
                            rosterAndDrain(java.util.Collections.singletonList(vacated));
                        }
                        Toast.makeText(this, getString(R.string.subgroup_cleared_toast, member.nickname),
                                Toast.LENGTH_SHORT).show();
                        renderMembers();
                        return;
                    }
                    Long chosen = optionIds.get(which);
                    long targetGroupId = chosen != null ? chosen : nextSubgroupId(existingIds);
                    confirmAndAssign(member, targetGroupId);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private long nextSubgroupId(List<Long> existingIds) {
        long max = 0;
        for (Long id : existingIds) {
            if (id > max) {
                max = id;
            }
        }
        return max + 1;
    }

    /** The single choke point for actually placing a member into a sub-group: always shows the
     *  irreversible-number-visibility warning first and only writes on explicit confirmation. */
    private void confirmAndAssign(Member member, long subgroupId) {
        String message = getString(R.string.warning_subgroup_visibility) + "\n\n"
                + getString(R.string.subgroup_confirm_assign_detail, member.nickname, subgroupId);
        new AlertDialog.Builder(this)
                .setTitle(R.string.subgroup_confirm_assign_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // Captured BEFORE the write: on a move (A -> B) both ends change, and once
                    // assignSubgroup has run there is no record of where the member came from.
                    Long vacated = member.subgroupId;
                    memberRepository.assignSubgroup(member.id, subgroupId);
                    // Re-roster the destination immediately. Everyone already in that thread holds
                    // a list without this person on it, so until it is re-sent the newcomer posts
                    // as a bare string of digits -- the exact problem the roster exists to solve,
                    // reappearing for whoever joined last. The group they LEFT is re-rostered too,
                    // for the mirror-image reason: its list still names somebody who is no longer
                    // in that thread. Only these two sub-groups are touched; the rest cannot see
                    // this change and must not pay for it.
                    java.util.LinkedHashSet<Long> affected = new java.util.LinkedHashSet<>();
                    affected.add(subgroupId);
                    if (vacated != null && vacated != subgroupId) {
                        affected.add(vacated);
                    }
                    rosterAndDrain(affected);
                    Toast.makeText(this, getString(R.string.subgroup_assigned_toast, member.nickname, subgroupId),
                            Toast.LENGTH_SHORT).show();
                    renderMembers();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Converts a dp value to px. No existing helper in {@link UiUtil} covers this (it only has
     *  the divider factory), so a local, standard-form conversion is used rather than adding a
     *  third copy elsewhere. */
    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void showOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.membership_options_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_export_csv) {
                startExport();
                return true;
            } else if (id == R.id.menu_import_csv) {
                startImport();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "jrelay_members.csv");
        startActivityForResult(intent, REQUEST_EXPORT_CSV);
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, REQUEST_IMPORT_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_CSV) {
            exportCsv(uri);
        } else if (requestCode == REQUEST_IMPORT_CSV) {
            promptImportReportingMode(uri);
        }
    }

    private void promptImportReportingMode(Uri uri) {
        CharSequence[] options = {
                getString(R.string.csv_import_report_usual),
                getString(R.string.csv_import_report_streamlined),
                getString(R.string.csv_import_report_none)
        };
        CommandProcessor.ImportReportingMode[] modes = {
                CommandProcessor.ImportReportingMode.USUAL,
                CommandProcessor.ImportReportingMode.STREAMLINED,
                CommandProcessor.ImportReportingMode.NONE
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.csv_import_report_title)
                .setItems(options, (dialog, which) -> importCsv(uri, modes[which]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void exportCsv(Uri uri) {
        List<Member> members = memberRepository.getActiveMembers();
        try (OutputStream out = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write("phone,nickname\n");
            for (Member m : members) {
                writer.write(CsvUtil.escapeField(m.phoneE164) + "," + CsvUtil.escapeField(m.nickname) + "\n");
            }
            writer.flush();
            Toast.makeText(this, getString(R.string.export_success, members.size()), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void importCsv(Uri uri, CommandProcessor.ImportReportingMode mode) {
        List<String[]> toImport = new ArrayList<>();
        Set<String> stagedPhones = new HashSet<>();
        int skipped = 0;
        boolean firstLine = true;

        int maxMembers = new Prefs(this).getMaxMembers();
        int runningCount = maxMembers > 0 ? memberRepository.countActiveMembers() : 0;
        boolean hitLimit = false;

        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> fields = CsvUtil.parseLine(line);
                String normalized = fields.size() >= 1 ? PhoneNumberUtils.normalize(fields.get(0).trim()) : null;
                String nickname = fields.size() >= 2 ? fields.get(1).trim() : "";

                if (normalized == null) {
                    boolean wasFirstLine = firstLine;
                    firstLine = false;
                    if (wasFirstLine) {
                        continue;
                    }
                    skipped++;
                    continue;
                }
                firstLine = false;

                if (nickname.isEmpty()) {
                    skipped++;
                    continue;
                }
                Member existing = memberRepository.findByPhone(normalized);
                if ((existing != null && existing.active) || stagedPhones.contains(normalized)) {
                    skipped++;
                    continue;
                }
                if (maxMembers > 0 && runningCount >= maxMembers) {
                    hitLimit = true;
                    skipped++;
                    continue;
                }
                stagedPhones.add(normalized);
                toImport.add(new String[]{normalized, nickname});
                runningCount++;
            }
            int imported = commandProcessor.importMembers(toImport, getString(R.string.default_added_by_admin), mode);
            Toast.makeText(this, getString(R.string.import_summary, imported, skipped), Toast.LENGTH_LONG).show();
            if (hitLimit) {
                Toast.makeText(this, getString(R.string.tpl_group_full_admin, maxMembers, maxMembers), Toast.LENGTH_LONG).show();
            }
            renderMembers();
        } catch (IOException e) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void renderMembers() {
        container.removeAllViews();
        renderSubgroupSizes();
        List<Member> allMembers = memberRepository.getActiveMembers();

        String query = currentQuery.trim();
        String queryLower = query.toLowerCase(Locale.US);
        String queryDigits = digitsOnly(query);
        boolean adminMode = queryLower.contains("admin");

        List<Member> filtered = new ArrayList<>();
        for (Member m : allMembers) {
            if (query.isEmpty() || matchesQuery(m, queryLower, queryDigits, adminMode)) {
                filtered.add(m);
            }
        }

        if (filtered.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(allMembers.isEmpty() ? R.string.no_members_yet : R.string.no_search_results);
            container.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < filtered.size(); i++) {
            Member member = filtered.get(i);
            View row = inflater.inflate(R.layout.row_member, container, false);
            TextView nicknameView = row.findViewById(R.id.text_member_nickname);
            TextView phoneView = row.findViewById(R.id.text_member_phone);
            TextView badgeView = row.findViewById(R.id.text_member_badge);

            setHighlightedText(nicknameView, member.nickname, queryLower);
            setHighlightedPhone(phoneView, member.phoneE164, queryDigits);

            StringBuilder badge = new StringBuilder();
            if (member.isAdmin) {
                badge.append(getString(R.string.admin_badge));
            }
            if (member.isMuted) {
                if (badge.length() > 0) {
                    badge.append(" ");
                }
                badge.append(getString(R.string.muted_badge));
            }
            if (badge.length() == 0) {
                badgeView.setVisibility(View.GONE);
            } else {
                badgeView.setVisibility(View.VISIBLE);
                badgeView.setBackgroundResource(member.isAdmin ? R.drawable.bg_badge_admin : R.drawable.bg_badge_muted);
                badgeView.setTextColor(getColor(member.isAdmin ? R.color.primary : R.color.warning));
                if (adminMode && member.isAdmin) {
                    setHighlightedText(badgeView, badge.toString(), "admin");
                } else {
                    badgeView.setText(badge.toString());
                }
            }

            TextView subgroupBadge = new TextView(this);
            subgroupBadge.setId(View.generateViewId());
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMarginStart(dp(8));
            subgroupBadge.setLayoutParams(badgeParams);
            subgroupBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            subgroupBadge.setTextSize(12);
            subgroupBadge.setClickable(true);
            subgroupBadge.setFocusable(true);
            subgroupBadge.setBackgroundResource(R.drawable.focus_highlight);
            if (member.subgroupId != null) {
                subgroupBadge.setText(getString(R.string.subgroup_row_badge, member.subgroupId));
                subgroupBadge.setBackgroundResource(R.drawable.bg_badge_live);
                subgroupBadge.setTextColor(getColor(R.color.success));
            } else {
                subgroupBadge.setText(R.string.subgroup_row_unassigned_badge);
                subgroupBadge.setTextColor(getColor(R.color.text_secondary));
            }
            subgroupBadge.setOnClickListener(v -> showSubgroupAssignDialog(member));
            ((LinearLayout) row).addView(subgroupBadge);

            long memberId = member.id;
            row.setOnClickListener(v -> {
                Intent intent = new Intent(MembershipActivity.this, MemberDetailActivity.class);
                intent.putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId);
                startActivity(intent);
            });
            container.addView(row);

            if (i < filtered.size() - 1) {
                container.addView(UiUtil.createDivider(this, R.color.divider));
            }
        }
    }

    private boolean matchesQuery(Member member, String queryLower, String queryDigits, boolean adminMode) {
        if (adminMode && member.isAdmin) {
            return true;
        }
        if (!queryLower.isEmpty() && member.nickname.toLowerCase(Locale.US).contains(queryLower)) {
            return true;
        }
        if (!queryDigits.isEmpty() && digitsOnly(member.phoneE164).contains(queryDigits)) {
            return true;
        }
        return false;
    }

    private String digitsOnly(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void setHighlightedText(TextView view, String text, String queryLower) {
        if (queryLower == null || queryLower.isEmpty()) {
            view.setText(text);
            return;
        }
        int start = text.toLowerCase(Locale.US).indexOf(queryLower);
        if (start < 0) {
            view.setText(text);
            return;
        }
        SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new BackgroundColorSpan(getColor(R.color.search_highlight)),
                start, start + queryLower.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(spannable);
    }

    /** phoneE164 is always "+" followed only by digits, so a digit-index match maps to char-index + 1. */
    private void setHighlightedPhone(TextView view, String phoneE164, String queryDigits) {
        if (queryDigits == null || queryDigits.isEmpty()) {
            view.setText(phoneE164);
            return;
        }
        int digitStart = digitsOnly(phoneE164).indexOf(queryDigits);
        if (digitStart < 0) {
            view.setText(phoneE164);
            return;
        }
        int charStart = digitStart + 1;
        int charEnd = charStart + queryDigits.length();
        SpannableString spannable = new SpannableString(phoneE164);
        spannable.setSpan(new BackgroundColorSpan(getColor(R.color.search_highlight)),
                charStart, charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(spannable);
    }
}
