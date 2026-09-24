package com.sh7411usa.jrelay.sms;

import android.content.Context;
import android.text.format.DateFormat;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.model.MessageRecord;
import com.sh7411usa.jrelay.util.DailyLimitManager;
import com.sh7411usa.jrelay.util.MessageSalt;
import com.sh7411usa.jrelay.util.NotificationHelper;
import com.sh7411usa.jrelay.util.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CommandProcessor {

    private final Context context;
    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final OutboxRepository outboxRepository;
    private final Prefs prefs;

    public CommandProcessor(Context context) {
        this.context = context.getApplicationContext();
        memberRepository = new MemberRepository(this.context);
        messageRepository = new MessageRepository(this.context);
        outboxRepository = new OutboxRepository(this.context);
        prefs = new Prefs(this.context);
    }

    /** Entry point for an inbound SMS from an already-normalized sender number. */
    public void handleIncoming(String senderE164, String body) {
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
        int count = 0;
        for (String[] pair : phoneNicknamePairs) {
            Member newMember = insertOrReactivateMember(pair[0], pair[1], addedByLabel);
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
        if (nickname.isEmpty()) {
            nickname = MessageSalt.localDigits(senderE164);
        } else if (!MessageIntent.isValidNickname(nickname)) {
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

        if (policy == Prefs.JoinPolicy.ALLOW) {
            selfJoin(senderE164, nickname);
        } else {
            requestJoinApproval(senderE164, nickname);
        }
    }

    /** A non-member added themselves via #join with JoinPolicy.ALLOW. */
    public void selfJoin(String normalizedPhone, String nickname) {
        Member newMember = insertOrReactivateMember(normalizedPhone, nickname, nickname);
        if (prefs.isAddedReportingEnabled()) {
            String groupName = prefs.getGroupName();
            String welcome = withReplyHint(context.getString(R.string.tpl_added_you_self, groupName));
            enqueue(newMember, welcome, "SYSTEM");
            broadcastExcept(newMember.id, context.getString(R.string.tpl_joined_other, nickname));
        }
        SmsSendService.start(context);
    }

    /** JoinPolicy.REQUIRE_APPROVAL: no membership is created; admins get a ready-to-forward #add command. */
    private void requestJoinApproval(String senderE164, String nickname) {
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
     */
    public void broadcastToGroup(String body) {
        String formatted = context.getString(R.string.tpl_dm_prefix, body);
        List<Member> members = memberRepository.getActiveMembers();
        List<Long> recipientIds = new ArrayList<>(members.size());
        for (Member m : members) {
            enqueue(m, formatted, "ADMIN");
            recipientIds.add(m.id);
        }
        memberRepository.setLastPostReceivedIdForAll(recipientIds, null);
        SmsSendService.start(context);
    }

    /** An explicit #all / all: post. Same Announcement-mode gate as a plain relay, otherwise always posts to the group. */
    private void handleExplicitPost(Member sender, String body) {
        if (prefs.getGroupMode() == Prefs.GroupMode.ANNOUNCEMENT && !sender.isAdmin) {
            sendToAdminsOnly(sender, body);
            return;
        }
        postToGroup(sender, body);
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
        postToGroup(sender, body);
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

    private void postToGroup(Member sender, String body) {
        DailyLimitManager limitManager = new DailyLimitManager(context);

        DailyLimitManager.Status groupStatus = limitManager.groupStatus();
        if (groupStatus.isExhausted()) {
            reply(sender, context.getString(R.string.tpl_group_limit_blocked,
                    groupStatus.used, groupStatus.limit, resetTimeLabel(groupStatus.resetAtMillis)));
            return;
        }

        DailyLimitManager.Status memberStatus = limitManager.memberStatus(sender);
        if (memberStatus.isExhausted()) {
            reply(sender, context.getString(R.string.tpl_individual_limit_blocked,
                    memberStatus.used, memberStatus.limit, resetTimeLabel(memberStatus.resetAtMillis)));
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
        broadcastExcept(sender.id, formatted, OutboxRepository.CATEGORY_RELAY, postLogId);
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
