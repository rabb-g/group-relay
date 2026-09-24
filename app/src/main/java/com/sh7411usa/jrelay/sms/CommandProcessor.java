package com.sh7411usa.jrelay.sms;

import android.content.Context;
import android.text.format.DateFormat;
import android.util.Log;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.JoinRequestRepository;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.MessageSalt;
import com.sh7411usa.jrelay.util.NotificationHelper;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RelayIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class CommandProcessor {

    private static final String TAG = "CommandProcessor";

    /** How far back the self-loop breaker looks for an outbox row this inbound text could be an echo of. */
    private static final long ECHO_WINDOW_MILLIS = 10 * 60 * 1000L;
    /** Allowance past the receive time, for clock disagreement between the provider and the outbox. */
    private static final long ECHO_WINDOW_SLACK_MILLIS = 60 * 1000L;

    private final Context context;
    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final OutboxRepository outboxRepository;
    private final JoinRequestRepository joinRequestRepository;
    private final Prefs prefs;

    public CommandProcessor(Context context) {
        this.context = context.getApplicationContext();
        memberRepository = new MemberRepository(this.context);
        messageRepository = new MessageRepository(this.context);
        outboxRepository = new OutboxRepository(this.context);
        joinRequestRepository = new JoinRequestRepository(this.context);
        prefs = new Prefs(this.context);
    }

    /** Entry point for an inbound SMS from an already-normalized sender number, received just now. */
    public void handleIncoming(String senderE164, String body) {
        handleIncoming(senderE164, body, System.currentTimeMillis());
    }

    /**
     * Same, for a text received at {@code receivedAtMillis} rather than now. SmsCatchUp passes the
     * provider's receive time, which can be up to 48 hours ago, so the self-echo check looks at
     * what the relay sent around THAT time instead of in the last few minutes.
     */
    public void handleIncoming(String senderE164, String body, long receivedAtMillis) {
        if (prefs.isPaused()) {
            return;
        }

        String trimmed = body == null ? "" : body.trim();

        // A whitespace-only or empty inbound body (a pocket-send, or an SmsReceiver PDU whose parts
        // all decoded to a null body) matches no command and would otherwise fall through to
        // relayPlainMessage() -> postToGroup(), broadcasting an empty "Nickname: " post to the whole
        // group and burning a daily-quota slot. Bail out before anything is logged, queued or replied to.
        if (trimmed.isEmpty()) {
            return;
        }

        if (isSelfEcho(senderE164, trimmed, receivedAtMillis)) {
            return;
        }

        // #join is the one command a non-member can use, so it's checked before the member lookup below.
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("#join")) {
            handleJoinRequest(senderE164, trimmed);
            SmsSendService.start(context);
            return;
        }

        Member sender = memberRepository.findByPhone(senderE164);
        if (sender == null || !sender.active) {
            return;
        }

        messageRepository.log(sender.id, "IN", "RELAY", body);

        String effective = trimmed;
        if (prefs.isBareKeywordsEnabled()) {
            String canonical = MessageIntent.canonicalBareKeyword(trimmed);
            if (canonical != null) {
                effective = canonical;
            }
        }

        // Explicit-post prefix (#all / all:) is checked before the "#" dispatch below so #all is
        // never mistaken for an unknown command, and it applies in every group mode.
        String postBody = MessageIntent.stripPostPrefix(effective);
        if (postBody != null) {
            if (!sender.isMuted) {
                handleExplicitPost(sender, postBody);
            }
        } else if (effective.startsWith("#")) {
            handleCommand(sender, effective);
        } else if (!sender.isMuted) {
            relayPlainMessage(sender, effective);
        }

        SmsSendService.start(context);
    }

    /**
     * Self-loop guards, checked before anything is logged, answered or relayed. If the relay's own
     * number is ever a member, every post is sent to it as an individual SMS, Android hands that
     * SMS straight back to the relay, and without these checks it is broadcast again as a new post
     * from that "member" - on every post, forever. Either guard alone breaks the loop:
     *
     * <ul>
     *   <li><b>Own number</b>: a text from the relay's own number is never a real post. Cheap, but
     *       only works when {@link RelayIdentity} knows the number (often it can't).</li>
     *   <li><b>Echo</b>: the relay sent this exact text (ignoring MessageSalt's send-time salt) to
     *       this same number in the last {@link #ECHO_WINDOW_MILLIS}. Needs no own-number knowledge,
     *       because the looped copy is by construction identical to a row just sent to it.</li>
     * </ul>
     *
     * <p>Why the echo rule is safe for real members: a person never sends the relay a text
     * identical to one the relay sent them minutes ago, except by copy-pasting a post back. That
     * copy-paste is dropped. This is deliberate: re-posting someone else's message verbatim adds
     * nothing the group hasn't just seen, while a loop sends every group a duplicate of every post.
     * Occasionally dropping an echo is always preferred over ever looping.
     */
    private boolean isSelfEcho(String senderE164, String trimmedBody, long receivedAtMillis) {
        if (RelayIdentity.isOwnNumber(context, senderE164)) {
            Log.w(TAG, "Dropped inbound text from the relay's own number");
            return true;
        }
        String normalized = MessageSalt.normalizeEchoBody(trimmedBody);
        // An echo is received after its row went out, so the window ends at the receive time. The
        // slack covers the provider's receive time and our own clock disagreeing slightly. On the
        // live path receivedAtMillis is now, so the upper bound excludes nothing.
        long windowStart = receivedAtMillis - ECHO_WINDOW_MILLIS;
        long windowEnd = receivedAtMillis + ECHO_WINDOW_SLACK_MILLIS;
        if (outboxRepository.sentSameBodyRecently(senderE164, normalized, windowStart, windowEnd)) {
            Log.w(TAG, "Dropped inbound text that echoes a message the relay just sent to the same number");
            return true;
        }
        return false;
    }

    private void handleCommand(Member sender, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("#commands")) {
            handleCommandsList(sender);
        } else if (lower.equals("#help")) {
            // Bare "HELP" is a carrier opt-out convention (see MessageIntent.canonicalBareKeyword) and
            // deliberately still maps to #commands; #help answers a different question ("how do I
            // phrase a message right now?" vs. "what commands exist?") — don't unify the two later.
            handleHelp(sender);
        } else if (lower.equals("#mute")) {
            memberRepository.setMuted(sender.id, true);
            reply(sender, context.getString(R.string.tpl_muted_confirm));
        } else if (lower.equals("#unmute")) {
            memberRepository.setMuted(sender.id, false);
            reply(sender, context.getString(R.string.tpl_unmuted_confirm));
        } else if (lower.equals("#stop")) {
            handleStop(sender);
        } else if (lower.equals("#list")) {
            handleList(sender);
        } else if (lower.startsWith("#name")) {
            handleNameChange(sender, text);
        } else if (lower.startsWith("#admin")) {
            handleAdminMessage(sender, text);
        } else if (lower.startsWith("#add")) {
            handleAdd(sender, text);
        } else if (lower.startsWith("#remove")) {
            handleRemove(sender, text);
        } else if (lower.startsWith("#topic")) {
            handleTopicChange(sender, text);
        } else if (lower.equals("#to") || (lower.startsWith("#to") && Character.isWhitespace(lower.charAt(3)))) {
            // Requires "#to" to be the whole command or followed by whitespace, so "#topic" (checked
            // above) and other "#to*" words like "#tomorrow"/"#total" don't get misdispatched here.
            handleToCommand(sender, text);
        } else if (lower.equals("#limits")) {
            handleLimits(sender);
        } else if (lower.startsWith("#override")) {
            handleOverride(sender, text);
        } else if (lower.equals("#mode")) {
            handleModeQuery(sender);
        } else if (lower.startsWith("#mode")) {
            handleModeChange(sender, text);
        } else {
            reply(sender, context.getString(R.string.tpl_unknown_command));
        }
    }

    private void handleCommandsList(Member requester) {
        StringBuilder sb = new StringBuilder(context.getString(R.string.tpl_commands_list_common));
        if (requester.isAdmin) {
            sb.append(context.getString(R.string.tpl_commands_list_admin_extra));
        }
        if (prefs.getGroupMode() == Prefs.GroupMode.REPLY) {
            sb.append(context.getString(R.string.tpl_commands_list_reply_extra));
        }
        reply(requester, sb.toString());
    }

    /** #help: mode-aware guidance on how to phrase a message right now, for every member (not admin-gated). */
    private void handleHelp(Member requester) {
        // Mute takes precedence over mode: while muted, the mode is irrelevant to this member — nothing
        // reaches them and nothing they send goes out, whichever mode the group is in.
        if (requester.isMuted) {
            reply(requester, context.getString(R.string.tpl_help_muted));
            return;
        }
        reply(requester, context.getString(helpStringRes(prefs.getGroupMode())));
    }

    private int helpStringRes(Prefs.GroupMode mode) {
        switch (mode) {
            case ANNOUNCEMENT:
                return R.string.tpl_help_announcement;
            case REPLY:
                return R.string.tpl_help_reply;
            case GROUP:
            default:
                return R.string.tpl_help_group;
        }
    }

    private void handleStop(Member sender) {
        if (sender.isAdmin && memberRepository.countActiveAdmins() == 1) {
            // The last admin can't remove themselves over SMS: setAdmin() is only reachable from the
            // app UI (MemberDetailActivity), and reactivate() always resets is_admin to 0, so an empty
            // admin set is unrecoverable without physically holding the handset.
            reply(sender, context.getString(R.string.tpl_last_admin_refused));
            return;
        }
        memberRepository.softRemove(sender.id);
        String groupName = prefs.getGroupName();
        reply(sender, context.getString(R.string.tpl_removed_you, groupName));
        if (prefs.isNotifyMemberLeftEnabled()) {
            broadcastExcept(sender.id, context.getString(R.string.tpl_left_other, sender.nickname));
        }
    }

    private void handleList(Member requester) {
        List<Member> members = memberRepository.getActiveMembers();
        StringBuilder sb = new StringBuilder();
        sb.append(prefs.getGroupName()).append("\n\n");
        boolean first = true;
        for (Member m : members) {
            if (!first) {
                sb.append("\n");
            }
            first = false;
            sb.append(m.nickname);
            if (m.id == requester.id) {
                sb.append(" ").append(context.getString(R.string.tpl_list_you));
            }
            if (m.isAdmin) {
                sb.append(" ").append(context.getString(R.string.tpl_list_admin));
            }
        }
        reply(requester, sb.toString());
    }

    private void handleNameChange(Member sender, String text) {
        String newNickname = stripLeadingWord(text).trim();
        if (newNickname.isEmpty()) {
            reply(sender, context.getString(R.string.error_empty_nickname));
            return;
        }
        // Audit 2.3: a nickname is rendered verbatim as the attribution prefix of every relayed
        // post ("%1$s: ..."), so an unvalidated #name can forge "[Admin]"/"@system"-style lines
        // just as effectively as a body-injection newline. See MessageIntent.isValidNickname.
        if (!MessageIntent.isValidNickname(newNickname)) {
            reply(sender, context.getString(R.string.error_invalid_nickname));
            return;
        }
        if (newNickname.equals(sender.nickname)) {
            reply(sender, context.getString(R.string.tpl_name_changed_confirm, newNickname));
            return;
        }
        String oldNickname = sender.nickname;
        memberRepository.setNickname(sender.id, newNickname);
        reply(sender, context.getString(R.string.tpl_name_changed_confirm, newNickname));
        if (prefs.isNotifyNameChangedEnabled()) {
            broadcastExcept(sender.id, context.getString(R.string.tpl_name_changed_self_other, oldNickname, newNickname));
        }
    }

    /** Renames a member from the app UI and notifies the rest of the group. changedByLabel is typically "An Admin". */
    public void renameMemberFromApp(Member target, String newNickname, String changedByLabel) {
        String oldNickname = target.nickname;
        memberRepository.setNickname(target.id, newNickname);
        enqueue(target, context.getString(R.string.tpl_name_changed_confirm, newNickname), "SYSTEM");
        if (prefs.isNotifyNameChangedEnabled()) {
            broadcastExcept(target.id, context.getString(R.string.tpl_name_changed_admin_other, changedByLabel, oldNickname, newNickname));
        }
        SmsSendService.start(context);
    }

    private void handleAdminMessage(Member sender, String text) {
        String message = stripLeadingWord(text).trim();
        if (message.isEmpty()) {
            return;
        }
        sendToAdminsOnly(sender, message);
    }

    /** Formats `message` as coming from `sender` and delivers it only to admins (used by #admin and Announcement mode). */
    private void sendToAdminsOnly(Member sender, String message) {
        String formatted = context.getString(R.string.tpl_admin_relay_prefix, sender.nickname, message);
        List<Member> admins = memberRepository.getActiveAdmins();
        for (Member admin : admins) {
            if (admin.id == sender.id || admin.isMuted) {
                continue;
            }
            enqueue(admin, formatted, "ADMIN");
        }
        NotificationHelper.showAdminMessage(context, formatted);
    }

    private void handleAdd(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String rest = stripLeadingWord(text).trim();
        String[] parts = PhoneNumberUtils.splitLeadingNumberAndRest(rest);
        if (parts == null) {
            parts = PhoneNumberUtils.splitTrailingNumberAndRest(rest);
        }
        if (parts == null) {
            reply(sender, context.getString(R.string.tpl_invalid_number));
            return;
        }
        String normalized = PhoneNumberUtils.normalize(parts[0]);
        String nickname = parts[1].trim();
        if (normalized == null) {
            reply(sender, context.getString(R.string.tpl_invalid_number));
            return;
        }
        if (nickname.isEmpty()) {
            reply(sender, context.getString(R.string.error_empty_nickname));
            return;
        }
        if (RelayIdentity.isOwnNumber(context, normalized)) {
            reply(sender, context.getString(R.string.tpl_add_own_relay_number));
            return;
        }
        Member existing = memberRepository.findByPhone(normalized);
        if (existing != null && existing.active) {
            reply(sender, context.getString(R.string.error_duplicate_number));
            return;
        }
        int maxMembers = prefs.getMaxMembers();
        if (maxMembers > 0) {
            int activeCount = memberRepository.countActiveMembers();
            if (activeCount >= maxMembers) {
                reply(sender, context.getString(R.string.tpl_group_full_admin, activeCount, maxMembers));
                return;
            }
        }
        addMember(normalized, nickname, sender.nickname);
    }

    private Member insertOrReactivateMember(String normalizedPhone, String nickname, String addedBy) {
        return insertOrReactivateMember(normalizedPhone, nickname, addedBy, RelayIdentity.ownNumber(context));
    }

    /**
     * The one place a member row is created or revived, so the one place the relay's own number is
     * refused: as a member it sends every post to itself (see isSelfEcho). Returns null, having
     * written nothing, for the own number. {@code ownNumber} is {@link RelayIdentity#ownNumber},
     * passed in so a CSV import reads the SIM once rather than once per row.
     */
    private Member insertOrReactivateMember(String normalizedPhone, String nickname, String addedBy,
                                            String ownNumber) {
        if (ownNumber != null && ownNumber.equals(PhoneNumberUtils.normalize(normalizedPhone))) {
            Log.w(TAG, "Refused to add the relay's own number as a member");
            return null;
        }
        Member existing = memberRepository.findByPhone(normalizedPhone);
        long id;
        if (existing != null && !existing.active) {
            memberRepository.reactivate(existing.id, nickname, addedBy);
            id = existing.id;
        } else {
            id = memberRepository.insert(normalizedPhone, nickname, false, addedBy);
        }
        return memberRepository.findById(id);
    }

    /**
     * Adds a member and, if member-added reporting is enabled, sends the standard welcome/broadcast
     * messages. addedByLabel is either an admin's nickname or "An Admin". CSV import uses
     * {@link #importMembers} instead, which always asks the caller for a reporting mode rather than
     * following this toggle.
     */
    public void addMember(String normalizedPhone, String nickname, String addedByLabel) {
        Member newMember = insertOrReactivateMember(normalizedPhone, nickname, addedByLabel);
        if (newMember == null) {
            // The relay's own number: refused, and its pending request (if any) left alone.
            return;
        }
        // Every approval path lands here (an admin's texted #add and the app's Approve button), so
        // clearing the pending request here keeps the in-app list from showing someone already added.
        joinRequestRepository.delete(normalizedPhone);
        if (prefs.isAddedReportingEnabled()) {
            String groupName = prefs.getGroupName();
            String welcome = withReplyHint(context.getString(R.string.tpl_added_you, addedByLabel, groupName));
            enqueue(newMember, welcome, "SYSTEM");
            broadcastExcept(newMember.id, context.getString(R.string.tpl_added_other, addedByLabel, nickname));
        }
        SmsSendService.start(context);
    }

    /**
     * Adds every (phone, nickname) pair as a member in one go (CSV import), with an explicit
     * reporting style chosen by the caller for this import: USUAL notifies exactly as an individual
     * #add would per row, STREAMLINED tells only the new members individually plus one combined
     * notice to everyone who was already a member, NONE adds everyone silently. Returns how many
     * rows were added.
     */
    public int importMembers(List<String[]> phoneNicknamePairs, String addedByLabel, ImportReportingMode mode) {
        List<Member> preExistingMembers = memberRepository.getActiveMembers();
        String ownNumber = RelayIdentity.ownNumber(context);
        int count = 0;
        for (String[] pair : phoneNicknamePairs) {
            Member newMember = insertOrReactivateMember(pair[0], pair[1], addedByLabel, ownNumber);
            if (newMember == null) {
                continue;
            }
            count++;
            if (mode == ImportReportingMode.USUAL) {
                String groupName = prefs.getGroupName();
                enqueue(newMember, withReplyHint(context.getString(R.string.tpl_added_you, addedByLabel, groupName)), "SYSTEM");
                broadcastExcept(newMember.id, context.getString(R.string.tpl_added_other, addedByLabel, pair[1]));
            } else if (mode == ImportReportingMode.STREAMLINED) {
                String groupName = prefs.getGroupName();
                enqueue(newMember, withReplyHint(context.getString(R.string.tpl_added_you, addedByLabel, groupName)), "SYSTEM");
            }
        }
        if (mode == ImportReportingMode.STREAMLINED && count > 0) {
            String message = context.getString(R.string.tpl_csv_import_streamlined_notice, addedByLabel, count);
            for (Member m : preExistingMembers) {
                if (!m.isMuted) {
                    enqueue(m, message, "SYSTEM");
                }
            }
        }
        SmsSendService.start(context);
        return count;
    }

    public enum ImportReportingMode { USUAL, STREAMLINED, NONE }

    private void handleRemove(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String rest = stripLeadingWord(text).trim();
        if (rest.isEmpty()) {
            return;
        }
        Member target = resolveMemberTolerant(rest);
        if (target == null) {
            return;
        }
        if (target.isAdmin && memberRepository.countActiveAdmins() == 1) {
            // Same unrecoverable-empty-admin-set hazard as handleStop: refuse rather than let an
            // admin remove the last admin (including themselves) with no way back except the app UI.
            reply(sender, context.getString(R.string.tpl_last_admin_refused));
            return;
        }
        removeMember(target, sender.nickname);
    }

    /**
     * Removes this relay phone's own number from the member list, if it is on it. Silent on
     * purpose: no "you were removed" text and no notice to the group, because any text addressed
     * to this number is a text the relay sends to itself.
     *
     * <p>The relay can never legitimately be a member. As one it receives a copy of every post,
     * gets that copy back as an inbound text "from a member", and relays it to everyone again.
     * That loop reached production on 2026-09-24, twice. As an ADMIN it is worse: every join
     * request and admin alert is texted to it and then broadcast to the whole group.
     *
     * <p>This deliberately bypasses the last-admin protection in the UI. That guard is what trapped
     * the owner: the relay's own number was the only admin, so Remove was refused, and Revoke
     * Admin was refused too, leaving no way to fix it from the app. A group with no admin is
     * recoverable (any member can be made admin in the app); a relay that texts itself is not.
     *
     * @return the removed member's nickname (never null, possibly empty), or null if nobody was
     *         removed.
     */
    public String purgeRelaySelfMember() {
        String own = RelayIdentity.ownNumber(context);
        if (own == null) {
            return null;
        }
        Member self = memberRepository.findByPhone(own);
        if (self == null || !self.active) {
            return null;
        }
        // softRemove also clears is_admin.
        memberRepository.softRemove(self.id);
        Log.w(TAG, "Removed this relay phone's own number from members (member id " + self.id + ")");
        return self.nickname == null ? "" : self.nickname;
    }

    /** Removes a member and sends the standard notice/broadcast messages. removedByLabel is either an admin's nickname or "An Admin". */
    public void removeMember(Member target, String removedByLabel) {
        memberRepository.softRemove(target.id);
        String groupName = prefs.getGroupName();
        enqueue(target, context.getString(R.string.tpl_removed_you, groupName), "SYSTEM");
        if (prefs.isNotifyMemberRemovedEnabled()) {
            broadcastExcept(target.id, context.getString(R.string.tpl_removed_other, removedByLabel, target.nickname));
        }
        SmsSendService.start(context);
    }

    private void handleTopicChange(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String newName = stripLeadingWord(text).trim();
        if (newName.isEmpty()) {
            return;
        }
        changeGroupName(newName, sender.nickname, sender.id);
    }

    /**
     * Changes the group name and notifies members. changedByLabel is either an admin's nickname
     * or "An Admin". excludeId skips that member (the acting admin, who already knows); pass -1
     * (no matching member id) to notify every active member, e.g. when changed from the app.
     */
    public void changeGroupName(String newName, String changedByLabel, long excludeId) {
        prefs.setGroupName(newName);
        if (prefs.isNotifyGroupRenamedEnabled()) {
            String message = context.getString(R.string.tpl_group_name_changed, changedByLabel, newName);
            broadcastExcept(excludeId, message, "SYSTEM");
        }
        SmsSendService.start(context);
    }

    private void handleLimits(Member requester) {
        if (!requester.isAdmin) {
            reply(requester, context.getString(R.string.tpl_unauthorized));
            return;
        }
        DailyLimitManager.Status status = new DailyLimitManager(context).groupStatus();
        if (!status.enabled) {
            reply(requester, context.getString(R.string.tpl_limits_disabled));
            return;
        }
        reply(requester, context.getString(R.string.tpl_limits_status,
                status.used, status.limit, status.remaining(), resetTimeLabel(status.resetAtMillis)));
    }

    private void handleOverride(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String rest = stripLeadingWord(text).trim();
        DailyLimitManager limitManager = new DailyLimitManager(context);
        if (rest.isEmpty()) {
            limitManager.overrideGroup();
            reply(sender, context.getString(R.string.tpl_override_group_done));
            return;
        }

        Member target = resolveMemberTolerant(rest);
        if (target == null) {
            return;
        }

        if (!limitManager.overrideMember(target)) {
            reply(sender, context.getString(R.string.tpl_override_member_no_limit, target.nickname));
            return;
        }
        reply(sender, context.getString(R.string.tpl_override_member_done, target.nickname));
    }

    private String resetTimeLabel(long resetAtMillis) {
        return DateFormat.format("h:mm a", resetAtMillis).toString();
    }

    private void handleModeQuery(Member requester) {
        reply(requester, context.getString(modeStatusStringRes(prefs.getGroupMode())));
    }

    private void handleModeChange(Member sender, String text) {
        if (!sender.isAdmin) {
            reply(sender, context.getString(R.string.tpl_unauthorized));
            return;
        }
        String arg = stripLeadingWord(text).trim().toLowerCase(Locale.ROOT);
        Prefs.GroupMode newMode;
        if (arg.equals("announcement")) {
            newMode = Prefs.GroupMode.ANNOUNCEMENT;
        } else if (arg.equals("group")) {
            newMode = Prefs.GroupMode.GROUP;
        } else if (arg.equals("reply")) {
            newMode = Prefs.GroupMode.REPLY;
        } else {
            reply(sender, context.getString(R.string.tpl_mode_invalid));
            return;
        }

        setGroupMode(newMode, sender.nickname, sender.id);
        reply(sender, context.getString(modeStatusStringRes(newMode)));
    }

    /**
     * Changes the group mode and notifies members. changedByLabel is either an admin's nickname or
     * "An Admin". excludeId skips that member (the acting admin, who already knows via their own
     * reply); pass -1 to notify every active member, e.g. when changed from the app's Settings
     * screen or dashboard.
     */
    public void setGroupMode(Prefs.GroupMode newMode, String changedByLabel, long excludeId) {
        prefs.setGroupMode(newMode);
        if (prefs.isNotifyModeChangedEnabled()) {
            int changeNoticeRes;
            switch (newMode) {
                case ANNOUNCEMENT:
                    changeNoticeRes = R.string.tpl_mode_changed_announcement;
                    break;
                case REPLY:
                    changeNoticeRes = R.string.tpl_mode_changed_reply;
                    break;
                case GROUP:
                default:
                    changeNoticeRes = R.string.tpl_mode_changed_group;
                    break;
            }
            String changeNotice = context.getString(changeNoticeRes, changedByLabel);
            broadcastExcept(excludeId, changeNotice, "SYSTEM");
        }
        SmsSendService.start(context);
    }

    private int modeStatusStringRes(Prefs.GroupMode mode) {
        switch (mode) {
            case ANNOUNCEMENT:
                return R.string.tpl_mode_status_announcement;
            case REPLY:
                return R.string.tpl_mode_status_reply;
            case GROUP:
            default:
                return R.string.tpl_mode_status_group;
        }
    }

    private void handleJoinRequest(String senderE164, String text) {
        Member existing = memberRepository.findByPhone(senderE164);
        if (existing != null && existing.active) {
            reply(existing, context.getString(R.string.tpl_join_already_member));
            return;
        }

        messageRepository.log(null, "IN", "SYSTEM", text);

        Prefs.JoinPolicy policy = prefs.getJoinPolicy();
        if (policy == Prefs.JoinPolicy.OFF) {
            return;
        }

        String nickname = stripLeadingWord(text).trim();
        if (!nickname.isEmpty() && !MessageIntent.isValidNickname(nickname)) {
            // Audit 2.3: same forgery risk as #name — a nickname is rendered verbatim as the
            // attribution prefix of every relayed post. #join is the one command a non-member can
            // send, so there's no Member row yet to reply(); use replyToNumber instead.
            replyToNumber(senderE164, context.getString(R.string.error_invalid_nickname));
            return;
        }

        int maxMembers = prefs.getMaxMembers();
        if (maxMembers > 0 && memberRepository.countActiveMembers() >= maxMembers) {
            // Refuse before either path below, so a full group doesn't spam admins with join
            // requests they can't approve (REQUIRE_APPROVAL) or silently overfill (ALLOW).
            replyToNumber(senderE164, prefs.getGroupFullMessage());
            return;
        }

        // A name is required (owner decision, 2026-09-24). A bare "#join" used to fall back to the
        // last digits of the number, and every post from that member then arrived as "4564: ..." --
        // unreadable to a hundred neighbours. Reply once with instructions instead of adding them.
        // Checked AFTER the capacity check on purpose: when the group is full, telling someone to
        // resend with a name only to refuse them on the second try would waste two texts.
        if (nickname.isEmpty()) {
            replyToNumber(senderE164, context.getString(R.string.tpl_join_name_required));
            return;
        }

        if (policy == Prefs.JoinPolicy.ALLOW) {
            selfJoin(senderE164, nickname);
        } else {
            requestJoinApproval(senderE164, nickname);
        }
    }

    /** A non-member added themselves via #join with JoinPolicy.ALLOW. */
    public void selfJoin(String normalizedPhone, String nickname) {
        Member newMember = insertOrReactivateMember(normalizedPhone, nickname, nickname);
        if (newMember == null) {
            return;
        }
        if (prefs.isAddedReportingEnabled()) {
            String groupName = prefs.getGroupName();
            String welcome = withReplyHint(context.getString(R.string.tpl_added_you_self, groupName));
            enqueue(newMember, welcome, "SYSTEM");
            broadcastExcept(newMember.id, context.getString(R.string.tpl_joined_other, nickname));
        }
        SmsSendService.start(context);
    }

    public enum ApproveResult { ADDED, ALREADY_MEMBER, GROUP_FULL, OWN_NUMBER }

    /** Approve a pending request from the app. Same effect as an admin texting "#add <phone> <name>". */
    public ApproveResult approveJoinRequest(String phoneE164, String nickname) {
        if (RelayIdentity.isOwnNumber(context, phoneE164)) {
            // Never approvable, so drop the request rather than leave it in the list.
            joinRequestRepository.delete(phoneE164);
            return ApproveResult.OWN_NUMBER;
        }
        Member existing = memberRepository.findByPhone(phoneE164);
        if (existing != null && existing.active) {
            // Added some other way since they asked (CSV import, Members screen); the request is stale.
            joinRequestRepository.delete(phoneE164);
            return ApproveResult.ALREADY_MEMBER;
        }
        int maxMembers = prefs.getMaxMembers();
        if (maxMembers > 0 && memberRepository.countActiveMembers() >= maxMembers) {
            // Keep the request: once someone leaves or the cap is raised it can still be approved,
            // and the requester was already told their request was sent.
            return ApproveResult.GROUP_FULL;
        }
        addMember(phoneE164, nickname, context.getString(R.string.join_approved_by_app));
        return ApproveResult.ADDED;
    }

    /** Decline: remove the request. Deliberately sends NO text (saves a message; silence is how SMS approval already declines). */
    public void declineJoinRequest(String phoneE164) {
        joinRequestRepository.delete(phoneE164);
    }

    /**
     * JoinPolicy.REQUIRE_APPROVAL: no membership is created; the request is stored for the in-app
     * list and admins get a ready-to-forward #add command.
     */
    private void requestJoinApproval(String senderE164, String nickname) {
        // Stored first so the in-app list has it even if an admin text or the notification fails.
        joinRequestRepository.upsert(senderE164, nickname);
        String suggestedCommand = "#add " + senderE164 + " " + nickname;
        String adminMessage = context.getString(R.string.tpl_join_request_admin, nickname, senderE164, suggestedCommand);
        List<Member> admins = memberRepository.getActiveAdmins();
        for (Member admin : admins) {
            if (admin.isMuted) {
                continue;
            }
            enqueue(admin, adminMessage, "SYSTEM");
        }
        NotificationHelper.showAdminMessage(context, adminMessage);
        replyToNumber(senderE164, context.getString(R.string.tpl_join_request_sent));
    }

    /** Replies to a phone number that isn't (yet) a member, so it can't go through enqueue(Member, ...). */
    private void replyToNumber(String phoneE164, String message) {
        outboxRepository.enqueue(null, phoneE164, message);
        messageRepository.log(null, "OUT", "SYSTEM", message);
    }

    /** Notifies admins that a member has repeatedly failed to receive messages, e.g. their number may be bad. */
    public void alertAdminsOfFailures(Member target, int failedCount) {
        String formatted = context.getString(R.string.tpl_failure_alert, target.nickname, failedCount);
        List<Member> admins = memberRepository.getActiveAdmins();
        for (Member admin : admins) {
            // An alert about an admin's own failing number must never be routed back through that
            // same admin's failure counter: enqueue() below stamps the outbox row with the admin's
            // member_id, so a failed delivery of this very alert would increment the counter that
            // triggers it, turning one bad admin number into a self-sustaining alert storm. Mirrors
            // sendToAdminsOnly's identical sender-exclusion guard above.
            if (admin.id == target.id || admin.isMuted) {
                continue;
            }
            enqueue(admin, formatted, "ADMIN");
        }
        NotificationHelper.showAdminMessage(context, formatted);
        SmsSendService.start(context);
    }

    /**
     * D3 (5.9 review): notifies admins that a group-MMS send failed. Not yet wired to anything -
     * {@code SentReceiver.handleFailure} only ever calls the per-member
     * {@link #alertAdminsOfFailures} path, gated on {@code item.memberId != null}, which is always
     * null for a group-MMS outbox row (see {@code OutboxRepository#enqueueGroup}), so today a
     * failed group send trips no admin alert at all. Wiring this up requires a branch in
     * {@code SentReceiver.handleFailure} - when {@code item.memberId == null && item.subgroupId !=
     * null}, call this instead of the member-failure-count path - which is out of scope here: this
     * class does not own {@code SentReceiver.java}. Left in place, documented, for whoever does.
     *
     * <p>Per-member failure counting (the existing model) is the wrong shape for this case on
     * purpose, not just by omission: {@code incrementFailedCount}/{@code getFailureAlertThreshold}
     * are keyed to one member's number repeatedly failing - evidence that number itself is bad. A
     * group MMS failure is a property of the SEND (one thread, one radio call, one result), not of
     * any one recipient in it - the same failure hits every member of the sub-group identically,
     * so counting it against a per-member streak would blame nine people for one failed thread and
     * could trip nine separate alerts (or, worse, silently ride on whichever one member's counter
     * happens to already be near a divisible-by-threshold value) for a single event. The right
     * model is per-thread: alert once, immediately, for the sub-group itself, independent of any
     * member's individual failure history - which is what this method does.
     */
    public void alertAdminsOfGroupFailure(long subgroupId) {
        String formatted = context.getString(R.string.tpl_group_failure_alert, subgroupId);
        List<Member> admins = memberRepository.getActiveAdmins();
        for (Member admin : admins) {
            if (admin.isMuted) {
                continue;
            }
            enqueue(admin, formatted, "ADMIN");
        }
        NotificationHelper.showAdminMessage(context, formatted);
        SmsSendService.start(context);
    }

    /**
     * Sends a one-off admin direct message to a member, regardless of their mute state. Clears
     * their reply-target pointer: this message didn't come from another member's post, so a Reply
     * Mode reply to it should fall through to the admin route rather than silently targeting
     * whoever they last replied to.
     */
    public void sendDirectMessage(Member target, String body) {
        String formatted = context.getString(R.string.tpl_dm_prefix, body);
        enqueue(target, formatted, "DM");
        memberRepository.setLastPostReceivedIdForAll(Collections.singletonList(target.id), null);
        SmsSendService.start(context);
    }

    /**
     * Sends an admin message to every active member, regardless of mute state. Clears every
     * recipient's reply-target pointer for the same reason as {@link #sendDirectMessage}.
     *
     * <p>In GROUP_MMS delivery each sub-group gets one group row instead of one text per member;
     * see {@link #planBroadcastRecipients} for who goes where. In SMS delivery every active member
     * gets their own text, as always.
     */
    public void broadcastToGroup(String body) {
        String formatted = context.getString(R.string.tpl_dm_prefix, body);
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            List<Member> members = memberRepository.getActiveMembers();
            List<Long> recipientIds = new ArrayList<>(members.size());
            for (Member m : members) {
                enqueue(m, formatted, "ADMIN");
                recipientIds.add(m.id);
            }
            memberRepository.setLastPostReceivedIdForAll(recipientIds, null);
            SmsSendService.start(context);
            return;
        }

        BroadcastPlan plan = planBroadcastRecipients();
        List<Long> recipientIds = new ArrayList<>();
        for (Map.Entry<Long, List<Member>> group : plan.bySubgroup.entrySet()) {
            // Salted like postToSubgroups: the same body is going to every sub-group thread.
            outboxRepository.enqueueGroup(group.getKey(), formatted, "ADMIN", 0L, true);
            for (Member m : group.getValue()) {
                messageRepository.log(m.id, "OUT", "ADMIN", formatted);
                recipientIds.add(m.id);
            }
        }
        for (Member m : plan.individuals) {
            enqueue(m, formatted, "ADMIN");
            recipientIds.add(m.id);
        }
        memberRepository.setLastPostReceivedIdForAll(recipientIds, null);
        SmsSendService.start(context);
    }

    /**
     * What {@link #broadcastToGroup} would send right now, as
     * {@code {groupMessages, individualTexts, peopleReached}}. In SMS delivery that is no group
     * messages and one text per active member. Built from the same {@link #planBroadcastRecipients}
     * the send uses, so the number an admin is shown cannot drift from what actually goes out.
     */
    public int[] planBroadcast() {
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            int active = memberRepository.countActiveMembers();
            return new int[]{0, active, active};
        }
        BroadcastPlan plan = planBroadcastRecipients();
        int reached = plan.individuals.size();
        for (List<Member> group : plan.bySubgroup.values()) {
            reached += group.size();
        }
        return new int[]{plan.bySubgroup.size(), plan.individuals.size(), reached};
    }

    /** GROUP_MMS recipients of an admin broadcast, split into group rows and individual texts. */
    private static final class BroadcastPlan {
        /** Sub-group id to the members its group row reaches, in sub-group id order. */
        final Map<Long, List<Member>> bySubgroup = new TreeMap<>();
        /** Members the group rows do not reach, each sent their own text. */
        final List<Member> individuals = new ArrayList<>();
    }

    /**
     * Splits the active members for a GROUP_MMS admin broadcast. Each active member lands in
     * exactly one bucket, from one pass over one {@code getActiveMembers()} read:
     * <ul>
     *   <li>in a sub-group and unmuted: that sub-group's group row;</li>
     *   <li>otherwise (unassigned, or muted): an individual text.</li>
     * </ul>
     *
     * <p>Why nobody is sent it twice and nobody is missed: the two conditions are complements, so
     * the buckets partition the active set. The group row is resolved at send time by
     * {@link MemberRepository#getSubgroupMembers}, i.e. {@code active AND unmuted AND subgroup_id
     * = id} - exactly the first bucket for that id - so it never reaches a muted member or an
     * unassigned one, which are the individual bucket. A sub-group whose members are all muted
     * gets no group row at all (its key is never created); its members are all individuals.
     * Muted members still get admin broadcasts, as they always have in SMS delivery.
     *
     * <p>The one gap is the usual enqueue-vs-send race: a member muted, moved or removed between
     * this call and the send is resolved by the send, as for every other group row.
     */
    private BroadcastPlan planBroadcastRecipients() {
        BroadcastPlan plan = new BroadcastPlan();
        for (Member m : memberRepository.getActiveMembers()) {
            if (m.subgroupId != null && !m.isMuted) {
                List<Member> group = plan.bySubgroup.get(m.subgroupId);
                if (group == null) {
                    group = new ArrayList<>();
                    plan.bySubgroup.put(m.subgroupId, group);
                }
                group.add(m);
            } else {
                plan.individuals.add(m);
            }
        }
        return plan;
    }

    /** An explicit #all / all: post. Same Announcement-mode gate as a plain relay, otherwise always posts to the group. */
    private void handleExplicitPost(Member sender, String body) {
        if (prefs.getGroupMode() == Prefs.GroupMode.ANNOUNCEMENT && !sender.isAdmin) {
            sendToAdminsOnly(sender, body);
            return;
        }
        // Direct 1:1 SMS path: nobody has seen this yet except the poster, so no sub-group is
        // pre-excluded from the relay set. See postToGroup's alreadyDeliveredSubgroupId javadoc.
        postToGroup(sender, body, null);
    }

    private void relayPlainMessage(Member sender, String body) {
        Prefs.GroupMode mode = prefs.getGroupMode();
        if (mode == Prefs.GroupMode.ANNOUNCEMENT && !sender.isAdmin) {
            sendToAdminsOnly(sender, body);
            return;
        }
        if (mode == Prefs.GroupMode.REPLY) {
            handleReply(sender, body);
            return;
        }
        // Direct 1:1 SMS path: same as handleExplicitPost, nothing pre-excluded.
        postToGroup(sender, body, null);
    }

    /**
     * A message a member wrote inside the group-MMS thread of sub-group {@code threadSubgroupId}.
     * A separate unit (the inbound group-MMS reader) calls this for every message a member posts
     * in their sub-group thread; this bridges ("relays") it onward to every other sub-group plus
     * unassigned members, exactly like a relayed post from a direct SMS, except the thread's own
     * sub-group is excluded — its members already saw the message peer-to-peer, in the thread it
     * was written in, before jRelay ever saw it.
     */
    public void handleThreadMessage(Member sender, long threadSubgroupId, String body) {
        if (prefs.isPaused()) {
            return;
        }
        // Bridging only makes sense in GROUP_MMS delivery: in SMS mode there is no sub-group
        // thread to have "already seen" the message, so bridging here would send every one of the
        // thread's own members an individual SMS duplicating what they just read in the thread.
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            return;
        }
        if (sender == null || !sender.active) {
            return;
        }
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        // Log the inbound message the same way handleIncoming does for a direct message, so the
        // dashboard/message history shows real in-thread conversation, not just relayed copies.
        messageRepository.log(sender.id, "IN", "RELAY", body);

        if (sender.isMuted) {
            // Muted means jRelay does not relay this member's posts, in-thread or direct.
            return;
        }
        // Bare keywords ("stop", "help", ...) are deliberately NOT treated as commands here, even
        // when the bare-keywords setting is on. In a conversation between neighbours "stop" is an
        // ordinary word, so it is bridged like any other message. Only an explicit '#' command is
        // held back (owner decision, 2026-09-24).
        if (trimmed.startsWith("#")) {
            // Commands must be texted directly to the relay. Executing a command typed inside a
            // group-MMS thread — visible to every other member of that thread — is out of scope
            // and would let e.g. #remove or #mode be triggered in front of an audience; just drop it.
            return;
        }
        if (prefs.getGroupMode() == Prefs.GroupMode.ANNOUNCEMENT && !sender.isAdmin) {
            // The thread's peers already saw it peer-to-peer; Announcement mode means only admins'
            // posts reach the rest of the group. Don't sendToAdminsOnly here either — that would
            // cost extra texts for a message the sender's own thread already delivered.
            return;
        }
        // Reply mode has no meaning for an in-thread message: it has no 1:1 reply target (it was
        // written to a group thread, not to jRelay), so it's always treated as a group post here,
        // same as a direct-SMS post in Group mode.
        postToGroup(sender, trimmed, threadSubgroupId);
    }

    /**
     * In Reply Mode, welcome texts gain a sentence explaining #all; every other mode gets the text
     * unchanged. Shared by #add, #join self-adds and CSV import so a member's first message never
     * depends on which path added them.
     */
    private String withReplyHint(String welcome) {
        if (prefs.getGroupMode() == Prefs.GroupMode.REPLY) {
            return welcome + context.getString(R.string.tpl_welcome_reply_hint);
        }
        return welcome;
    }

    /**
     * Shared landing point for every path that turns a member's message into a relayed group
     * post: a direct 1:1 SMS to the relay (relayPlainMessage / handleExplicitPost, both pass
     * {@code alreadyDeliveredSubgroupId = null} — nobody but the poster has seen it) and an
     * in-thread group-MMS message (handleThreadMessage, which passes the thread's own sub-group
     * id, since that sub-group's members already saw it peer-to-peer and must not get it twice).
     *
     * <p>Note: when the poster IS in a sub-group and posted directly (SMS, not in-thread), their
     * own sub-group now receives the post as a group MMS same as everyone else's, and the poster
     * — a participant of that MMS thread — gets their own post echoed back to them. That's
     * accepted as-is (one extra MMS, and it doubles as a delivery confirmation) rather than built
     * around: there is no way to exclude one participant from a group MMS without a separate
     * thread, which does not exist here.
     */
    private void postToGroup(Member sender, String body, Long alreadyDeliveredSubgroupId) {
        DailyLimitManager limitManager = new DailyLimitManager(context);

        // A non-null alreadyDeliveredSubgroupId means this post came from inside a group thread,
        // not a direct text. On that path an exhausted cap drops the bridge SILENTLY. Replying would
        // send one 1:1 "limit reached" SMS for every in-thread message from any of ~100 members,
        // unbounded, at exactly the moment the carrier budget is already spent -- and the writer's
        // own group has seen the message anyway. A direct text still gets the reply, as before:
        // there the sender has no other sign that nothing went out.
        boolean replyOnLimit = alreadyDeliveredSubgroupId == null;

        DailyLimitManager.Status groupStatus = limitManager.groupStatus();
        if (groupStatus.isExhausted()) {
            if (replyOnLimit) {
                reply(sender, context.getString(R.string.tpl_group_limit_blocked,
                        groupStatus.used, groupStatus.limit, resetTimeLabel(groupStatus.resetAtMillis)));
            }
            return;
        }

        DailyLimitManager.Status memberStatus = limitManager.memberStatus(sender);
        if (memberStatus.isExhausted()) {
            if (replyOnLimit) {
                reply(sender, context.getString(R.string.tpl_individual_limit_blocked,
                        memberStatus.used, memberStatus.limit, resetTimeLabel(memberStatus.resetAtMillis)));
            }
            return;
        }

        // Audit 2.3: collapse the body to one line and neutralize an unsafe stored nickname before
        // this ever reaches the "%1$s: %2$s" relay prefix, so an injected newline or a "[Admin]"-style
        // nickname can't forge a second attribution line. This is the shared landing point for both
        // relay entries in non-Reply-mode: a plain message (relayPlainMessage) and an #all-prefixed
        // post (handleExplicitPost) both call postToGroup().
        String safeNickname = MessageIntent.sanitizeNicknameForRender(sender.nickname);
        String safeBody = MessageIntent.sanitizeRelayBody(body);
        String formatted = context.getString(R.string.tpl_relay_prefix, safeNickname, safeBody);
        formatted = MessageSalt.applyEnqueueTime(prefs, sender.phoneE164, formatted);
        long postLogId = messageRepository.log(sender.id, "IN", "RELAYED", body);

        // DeliveryMode.SMS -> exactly today's behaviour, unchanged: one row per recipient via
        // broadcastExcept, same as before this branch existed. GROUP_MMS is the only new path.
        if (prefs.getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS) {
            postToSubgroups(sender, formatted, postLogId, alreadyDeliveredSubgroupId);
        } else {
            broadcastExcept(sender.id, formatted, OutboxRepository.CATEGORY_RELAY, postLogId);
        }
    }

    /**
     * GROUP_MMS fan-out for a relayed post: one group-MMS row per eligible sub-group, plus an
     * ordinary individual row for every active, unmuted member with no sub-group assignment.
     * {@link SubgroupRouter#routePost} returns both halves precisely so neither can be dropped —
     * the unassigned half is who silently stops receiving anything if it's ever skipped, since
     * today every member is unassigned.
     *
     * <p>{@code alreadyDeliveredSubgroupId} is the one sub-group (if any) whose members have
     * already seen this message some other way and must not receive the relayed copy — the
     * thread's own sub-group for an in-thread post (see {@link #handleThreadMessage}), or
     * {@code null} for a direct 1:1 SMS post, where no sub-group has seen it yet. It is passed
     * straight through as {@link SubgroupRouter#routePost}'s {@code posterSubgroupId} argument.
     * This used to always be {@code sender.subgroupId}, on the theory that a direct SMS poster's
     * own sub-group had "already seen it peer-to-peer" — wrong for a direct SMS (nobody but the
     * poster has seen it; the caller must actually be excluding based on delivery, not membership).
     *
     * <p>D1 (5.9 review): the unassigned half is built from
     * {@link MemberRepository#getUnassignedActiveUnmutedMembers()}, not the unfiltered
     * {@code getUnassignedActiveMembers()} - a muted member must not resume receiving relayed
     * posts just because delivery mode switched to group MMS, same as the SMS path's
     * {@link MemberRepository#getActiveRecipientsExcept}. The sub-group half is filtered the same
     * way inside {@link MemberRepository#getSubgroupMembers(long)} itself, at send time.
     *
     * <p>D2/D4 (5.9 review): each sub-group's actual (unmuted) membership is logged into
     * {@code message_log} one row per recipient here at enqueue time - mirroring what
     * {@link #enqueue} already does for the individual half and for the plain SMS fan-out
     * ({@link #broadcastExcept}) - so the dashboard feed and per-member history are not blank for
     * group traffic. This does not add an extra outbox row (still exactly one
     * {@code enqueueGroup} row per sub-group); it only restores the one message_log row per
     * recipient the SMS path already wrote, bounding the added growth to the same order of
     * magnitude group mode would have cost under individual SMS. Their {@code last_post_received_id}
     * is stamped too, in the same call, so Reply Mode can resolve them as a target - the gap
     * {@link SmsSendService}'s send-time re-resolution can reintroduce (a member muted/removed
     * between enqueue and send) is the same enqueue-vs-send race the individual half already
     * accepts.
     */
    /**
     * Sends every sub-group a roster of its own members, as one group message into that
     * sub-group's own thread. See {@link RosterComposer} for why this exists: inside a sub-group
     * thread jRelay is not in the path of member-to-member replies, so it cannot prefix them with
     * a nickname, and on a flip phone an unsaved contact arrives as a bare string of digits.
     *
     * <p>A sub-group only ever receives its own list, never the whole ~100-member group — that
     * boundary is the entire privacy argument for splitting into sub-groups in the first place, so
     * the members passed to the composer come from {@link MemberRepository#getSubgroupMembers}
     * per id and are never pooled.
     *
     * <p>Salting is deliberately off. {@link MessageSalt} exists to keep identical bodies from
     * looking like bulk traffic, but each sub-group's roster is already a different body, and the
     * zero-width space it inserts would force the whole message to UCS-2 — halving the segment
     * size of what is already the longest message this app sends (~4 segments in Hebrew).
     *
     * <p>Rows are enqueued under {@code "SYSTEM"}, not {@code CATEGORY_RELAY}: a roster is not a
     * relayed post, it must not appear in the relay feed as one, and
     * {@link OutboxRepository#takeReleasedRelayRows} must never consider it for coalescing.
     * {@code holdUntil} is 0 so rosters are not held behind the coalescing window — they are
     * admin-triggered and expected to go out now.
     *
     * <p><b>Caller's obligation:</b> a roster goes stale the moment that sub-group's membership
     * changes - everyone still holds a list without the newcomer on it, so that person posts as
     * bare digits. Re-send after any join or rebalance.
     *
     * @return the number of sub-groups a roster was queued for. Empty sub-groups are skipped
     *         rather than sent a header-only message.
     */
    public int sendSubgroupRosters() {
        int queued = 0;
        for (Long subgroupId : memberRepository.getDistinctSubgroupIds()) {
            if (sendRosterFor(subgroupId)) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * Queues the roster for one sub-group. This is the form the automatic paths use, so that
     * adding a member to sub-group 3 costs one roster to sub-group 3 and nothing at all to the
     * other nine -- re-rostering every group on every membership edit would cost roughly ten times
     * the traffic for a change that only one thread can see.
     *
     * <p>Membership comes from {@link MemberRepository#getActiveSubgroupMembers}, which includes
     * muted members: they are participants of the real MMS thread whatever jRelay relays, so
     * omitting them would leave exactly the unidentifiable number the roster exists to name.
     *
     * @return true if a roster was queued; false for a sub-group with no active members, which is
     *         skipped rather than sent a header with nothing under it.
     */
    public boolean sendRosterFor(long subgroupId) {
        List<Member> members = memberRepository.getActiveSubgroupMembers(subgroupId);
        if (members.isEmpty()) {
            return false;
        }
        String body = RosterComposer.compose(
                context.getString(R.string.roster_header),
                prefs.getGroupName(),
                context.getString(R.string.roster_footer),
                members);
        // Replace, don't pile up: a roster still queued for this group lists who was in it when
        // it was written, and this one supersedes it.
        outboxRepository.deletePendingRosters(subgroupId);
        outboxRepository.enqueueGroup(subgroupId, body, "SYSTEM", 0L, false);
        return true;
    }

    private void postToSubgroups(Member sender, String formatted, long postLogId, Long alreadyDeliveredSubgroupId) {
        Set<Long> activeSubgroupIds = new LinkedHashSet<>(memberRepository.getDistinctSubgroupIds());
        List<Member> unassignedMembers = memberRepository.getUnassignedActiveUnmutedMembers();
        List<Long> unassignedIds = new ArrayList<>(unassignedMembers.size());
        for (Member m : unassignedMembers) {
            unassignedIds.add(m.id);
        }

        SubgroupRouter.OutboundPlan plan = SubgroupRouter.routePost(
                sender.id, alreadyDeliveredSubgroupId, activeSubgroupIds, unassignedIds);

        long holdUntil = prefs.isCoalescingEnabled()
                ? System.currentTimeMillis() + prefs.getCoalesceWindowSeconds() * 1000L
                : 0L;

        List<Long> postRecipientIds = new ArrayList<>();
        for (Long subgroupId : plan.subgroupIdsToRelay) {
            outboxRepository.enqueueGroup(subgroupId, formatted, OutboxRepository.CATEGORY_RELAY, holdUntil, true);
            List<Member> subgroupMembers = memberRepository.getSubgroupMembers(subgroupId);
            for (Member m : subgroupMembers) {
                messageRepository.log(m.id, "OUT", OutboxRepository.CATEGORY_RELAY, formatted);
                postRecipientIds.add(m.id);
            }
        }

        // The individual-members half: anyone with no sub-group cannot receive a group MMS at all.
        // Wired explicitly here rather than dropped, exactly because the router returns it
        // separately so a caller cannot miss it without visibly ignoring part of the result.
        for (Long memberId : plan.individualMemberIds) {
            Member m = memberRepository.findById(memberId);
            if (m == null) {
                continue;
            }
            enqueue(m, formatted, OutboxRepository.CATEGORY_RELAY);
            postRecipientIds.add(m.id);
        }
        if (!postRecipientIds.isEmpty()) {
            memberRepository.setLastPostReceivedIdForAll(postRecipientIds, postLogId);
        }
    }

    /**
     * Reply Mode: a plain message with no #all/#to prefix. Delivered to the sender's last-received
     * post's author only, if that target is still eligible; otherwise routed to admins with a
     * "no target" notice back to the sender (mirrors #admin / Announcement-mode routing).
     */
    private void handleReply(Member sender, String body) {
        Member target = resolveReplyTarget(sender);
        if (target == null) {
            sendToAdminsOnly(sender, body);
            reply(sender, context.getString(R.string.tpl_reply_no_target));
            return;
        }
        deliverReply(sender, target, body);
    }

    /**
     * Resolves `sender`'s last-received post to a still-eligible reply target: the post's log row
     * must still exist, its author must still be an active member other than the sender, and the
     * post must still be within the configured reply window. Returns null otherwise.
     */
    private Member resolveReplyTarget(Member sender) {
        Long postId = sender.lastPostReceivedId;
        if (postId == null) {
            return null;
        }
        MessageRecord record = messageRepository.getById(postId);
        if (record == null || record.memberId == null) {
            return null;
        }
        Member author = memberRepository.findById(record.memberId);
        if (author == null || !author.active || author.id == sender.id) {
            return null;
        }
        if (!MessageIntent.isWithinReplyWindow(record.timestamp, System.currentTimeMillis(), prefs.getReplyWindowHours())) {
            return null;
        }
        return author;
    }

    /**
     * Delivers a resolved Reply Mode message to exactly one target (skipped if muted, same as
     * sendToAdminsOnly skips muted admins), with no daily-limit charge and no "RELAYED" log entry
     * (mirrors #admin / Announcement routing). Optionally copies it to admins.
     */
    private void deliverReply(Member sender, Member target, String body) {
        // Audit 2.3: this is the relay path's other convergence point — the plain-message entry
        // (relayPlainMessage) reaches here in Reply Mode. Sanitize once and reuse for both the
        // target delivery and the admin copy below, so neither can be bypassed independently.
        String safeSenderNickname = MessageIntent.sanitizeNicknameForRender(sender.nickname);
        String safeBody = MessageIntent.sanitizeRelayBody(body);
        if (!target.isMuted) {
            enqueue(target, context.getString(R.string.tpl_reply_prefix, safeSenderNickname, safeBody), "REPLY");
        }
        if (prefs.isCopyRepliesToAdmins()) {
            copyReplyToAdmins(sender.id, safeSenderNickname, target, safeBody);
        }
    }

    /** Copies a delivered reply to active admins, skipping muted admins and the sender/target so nobody gets it twice. */
    private void copyReplyToAdmins(long senderId, String senderNickname, Member target, String body) {
        String formatted = context.getString(R.string.tpl_reply_copy_admin, senderNickname, target.nickname, body);
        List<Member> admins = memberRepository.getActiveAdmins();
        for (Member admin : admins) {
            if (admin.isMuted || admin.id == senderId || admin.id == target.id) {
                continue;
            }
            enqueue(admin, formatted, "ADMIN");
        }
    }

    /** #to <nickname> <text>: Reply Mode only, explicit-target reply. */
    private void handleToCommand(Member sender, String text) {
        if (prefs.getGroupMode() != Prefs.GroupMode.REPLY) {
            reply(sender, context.getString(R.string.tpl_to_wrong_mode));
            return;
        }
        String rest = text.substring(3).trim();
        if (rest.isEmpty()) {
            reply(sender, context.getString(R.string.tpl_to_usage));
            return;
        }

        // Nicknames may contain spaces: try progressively longer word-prefixes of `rest` as the
        // candidate nickname and keep the longest one that resolves to an active member. Split on
        // \s+ (not a literal space) so a newline after the nickname, e.g. "#to Bob\nmessage", still
        // separates it from the message.
        String[] words = rest.split("\\s+");
        String bestCandidate = null;
        Member bestMatch = null;
        int bestWordCount = 0;
        StringBuilder candidateBuilder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                candidateBuilder.append(' ');
            }
            candidateBuilder.append(words[i]);
            String candidate = candidateBuilder.toString();
            Member match = resolveMemberTolerant(candidate);
            if (match != null) {
                bestCandidate = candidate;
                bestMatch = match;
                bestWordCount = i + 1;
            }
        }

        if (bestMatch == null) {
            reply(sender, context.getString(R.string.tpl_to_not_found, rest));
            return;
        }

        StringBuilder messageBuilder = new StringBuilder();
        for (int i = bestWordCount; i < words.length; i++) {
            if (messageBuilder.length() > 0) {
                messageBuilder.append(' ');
            }
            messageBuilder.append(words[i]);
        }
        String message = messageBuilder.toString().trim();
        if (message.isEmpty()) {
            reply(sender, context.getString(R.string.tpl_to_usage));
            return;
        }
        if (bestMatch.id == sender.id) {
            reply(sender, context.getString(R.string.tpl_to_not_found, bestCandidate));
            return;
        }

        deliverReply(sender, bestMatch, message);
    }

    /**
     * Resolves a user-typed member reference the tolerant way #remove, #override and #to all share:
     * a strict phone-number match first, falling back to an active-member nickname match. Returns
     * null when nothing matches.
     *
     * <p>Phone is checked first (not nickname first) using {@link PhoneNumberUtils#normalizeStrict},
     * which only accepts a candidate that IS a phone number end-to-end — no embedded/trailing digits
     * bleeding in from surrounding text (audit 4.2a: that looseness in the old {@code normalize()}
     * fallback let {@code #to 5551234567 hi there} keep matching longer and longer word-prefixes
     * until the whole message was consumed as "nickname", leaving nothing to deliver). Checking
     * phone first also closes the nickname-shadowing hole: if nickname were tried first, a member
     * whose NICKNAME happens to be a phone-shaped string (e.g. "5551234567") would shadow the real
     * owner of that number for every command — most dangerously {@code #remove}, which would then
     * remove the wrong member. With phone-first, a strictly-numeric candidate always resolves to
     * whoever actually owns that number (if anyone), never to a same-shaped nickname.
     *
     * <p>Trade-off: a member whose nickname is itself a bare phone-shaped string can no longer be
     * targeted by that nickname if a DIFFERENT member owns the matching number — the number match
     * wins. That's judged the safer failure mode: it fails closed (targets the real number owner,
     * or nobody) rather than failing open onto whichever member happened to pick a numeric-looking
     * nickname. Admins can still avoid the collision by not assigning phone-shaped nicknames.
     */
    private Member resolveMemberTolerant(String candidate) {
        String normalized = PhoneNumberUtils.normalizeStrict(candidate);
        if (normalized != null) {
            Member byPhone = memberRepository.findByPhone(normalized);
            if (byPhone != null && byPhone.active) {
                return byPhone;
            }
        }
        return memberRepository.findActiveByNickname(candidate);
    }

    private void broadcastExcept(long excludeId, String message) {
        broadcastExcept(excludeId, message, "SYSTEM", null);
    }

    private void broadcastExcept(long excludeId, String message, String category) {
        broadcastExcept(excludeId, message, category, null);
    }

    private void broadcastExcept(long excludeId, String message, String category, Long postLogId) {
        List<Member> recipients = memberRepository.getActiveRecipientsExcept(excludeId);
        boolean trackPost = postLogId != null && OutboxRepository.CATEGORY_RELAY.equals(category);
        List<Long> postRecipientIds = trackPost ? new ArrayList<>(recipients.size()) : null;
        for (Member m : recipients) {
            enqueue(m, message, category);
            if (trackPost) {
                postRecipientIds.add(m.id);
            }
        }
        if (trackPost) {
            memberRepository.setLastPostReceivedIdForAll(postRecipientIds, postLogId);
        }
    }

    private void reply(Member recipient, String message) {
        enqueue(recipient, message, "COMMAND");
    }

    private void enqueue(Member recipient, String message, String category) {
        long holdUntil = 0L;
        boolean applySalt = false;
        if (OutboxRepository.CATEGORY_RELAY.equals(category)) {
            holdUntil = prefs.isCoalescingEnabled()
                    ? System.currentTimeMillis() + prefs.getCoalesceWindowSeconds() * 1000L
                    : 0L;
            applySalt = true;
        }
        outboxRepository.enqueue(recipient.id, recipient.phoneE164, message, category, holdUntil, applySalt);
        messageRepository.log(recipient.id, "OUT", category, message);
    }

    private String stripLeadingWord(String text) {
        // Split on any whitespace run (not just a literal space) so a newline after the command
        // word, e.g. "#admin\nmessage", still separates it from the argument instead of yielding ""
        // and silently dropping the member's message with no reply at all. Mirrors
        // handleToCommand's rest.split("\\s+") and MessageIntent.stripPostPrefix.
        String[] parts = text.split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }
}
