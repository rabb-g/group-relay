package com.sh7411usa.jrelay.sms;

import com.sh7411usa.jrelay.model.Member;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Composes the "sub-group roster" message: a plain-text list of a sub-group's members, sent so
 * that flip-phone members can save each other's contacts. See {@code docs/phase3-redesign.md},
 * "Inside a sub-group, nothing is prefaced with a name — and that is not fixable". Once a
 * sub-group is a real group-MMS thread, jRelay is not in the path of member-to-member replies
 * inside it, so it cannot prefix them with a nickname the way it does for relayed posts. On a
 * flip phone with the contact unsaved, an in-thread reply arrives as a bare string of digits. The
 * owner decided (2026-09-24) to mitigate this by sending each sub-group a one-time roster of its
 * own members so people can save the numbers.
 *
 * <p><b>Deliberately unwired.</b> This class has no caller yet. Sub-groups do not exist in the
 * codebase yet (Phase 3 is not built), and there is no sub-group boundary to roster. Composing
 * and sending a "roster" against today's data would mean the whole ~100-member group, publishing
 * everyone's number to everyone — exactly the harm the sub-group split exists to avoid. Do not
 * wire this into a command, a Settings action, or a lifecycle hook until sub-groups exist and the
 * call site can pass only that sub-group's members.
 *
 * <p><b>Must be re-sent on membership change.</b> A join, a removal, or an admin rebalance leaves
 * every other member holding a stale roster. A stale roster is worse than no roster: it attributes
 * a saved contact to whoever *used to* hold that slot, so a message from the wrong person reads as
 * coming from a neighbour the recipient trusts. Any future call site MUST re-send the roster to a
 * sub-group whenever that sub-group's member set changes, not only when it is first formed.
 *
 * <p>This class is intentionally Android-free (no {@code Context}, no resources, no SQLite) so it
 * can be unit-tested directly; all strings are passed in by the caller, which owns the localized
 * templates in {@code strings.xml}.
 */
public final class RosterComposer {

    private RosterComposer() {
    }

    /**
     * Builds the roster message body.
     *
     * @param headerTemplate a {@link String#format} template taking one argument, the group name
     *                       (e.g. {@code "%1$s roster:"}).
     * @param groupName      the sub-group's display name, substituted into {@code headerTemplate}.
     * @param footerText     a closing line telling members to save the contacts; appended as-is.
     * @param members        the sub-group's members. An empty or null list is not an error: this
     *                       can legitimately happen for a sub-group with no other members yet, and
     *                       the caller should still be able to compose (and decide whether to send)
     *                       a header-only message rather than crash.
     * @return the composed message body. For an empty or null {@code members}, this is just the
     *         formatted header (no member lines, no footer) — a footer telling people to "save the
     *         contacts below" would be misleading with nothing below it.
     */
    public static String compose(String headerTemplate, String groupName, String footerText,
            List<Member> members) {
        String header = String.format(Locale.US, headerTemplate, groupName);

        if (members == null || members.isEmpty()) {
            return header;
        }

        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(RosterComposer::sortKey));

        StringBuilder sb = new StringBuilder(header);
        for (Member member : sorted) {
            sb.append('\n').append(formatLine(member));
        }
        if (footerText != null && !footerText.isEmpty()) {
            sb.append('\n').append(footerText);
        }
        return sb.toString();
    }

    private static String sortKey(Member member) {
        String nickname = member.nickname;
        return nickname == null ? "" : nickname.toLowerCase(Locale.US);
    }

    /**
     * One roster line: "Nickname number". If the member has no usable number, the nickname is
     * still listed alone rather than the member being dropped — a missing entry would let a reader
     * wrongly conclude that person is not in the thread at all, which is worse than an ugly line.
     */
    private static String formatLine(Member member) {
        String nickname = member.nickname == null ? "" : member.nickname;
        String number = formatNumber(member.phoneE164);
        return number.isEmpty() ? nickname : nickname + " " + number;
    }

    /**
     * Formats a phone number for a flip-phone-readable roster line, e.g. {@code "848-207-4564"}.
     * For the common case (10-digit NANP number) the digits are grouped 3-3-4 with hyphens, which
     * reads far better on a small screen than E.164. If the result is not exactly 10 digits (an
     * international number, or malformed/missing data), grouping it would misrepresent the number,
     * so it is printed verbatim and ungrouped rather than guessing at a grouping that doesn't
     * apply. A null or empty {@code phoneE164} returns an empty string, which tells the caller to
     * list the nickname alone.
     *
     * <p>The "strip +1" step is deliberately duplicated from {@code MessageSalt.localDigits} rather
     * than called, and that is the whole reason this class has no project dependencies beyond the
     * {@link Member} POJO: {@code MessageSalt} references {@code Prefs}, which pulls in Android,
     * which would make this class impossible to compile and run outside the app. Five lines of
     * duplication buys the ability to execute this logic standalone and read its real output —
     * which is how a byte-level error was caught in the MMS PDU composer during the Phase 3 spike.
     * If this ever needs to change, change it in both places.
     */
    static String formatNumber(String phoneE164) {
        if (phoneE164 == null || phoneE164.isEmpty()) {
            return "";
        }
        String digits = phoneE164.startsWith("+") ? phoneE164.substring(1) : phoneE164;
        if (digits.length() == 11 && digits.charAt(0) == '1') {
            digits = digits.substring(1);
        }
        if (digits.length() != 10) {
            // Not a NANP number. Return the ORIGINAL, including any leading "+", rather than the
            // stripped digits: an international number without its "+" is not dialable, so
            // stripping it would hand someone a number they cannot call. Caught by running this
            // method standalone -- "+447700900123" was coming out as "447700900123".
            return phoneE164;
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6);
    }

    /**
     * Rough segment-count estimate for a composed roster message, so a caller can see the cost
     * before sending. This does not query the radio or any carrier API; it is a plain character
     * count against the standard SMS/MMS-text segment sizes:
     * <ul>
     *   <li>GSM-7 (Latin scripts): 160 chars in a single segment, 153 chars/segment once the
     *       message is long enough to require concatenation (each segment gives up 7 chars to the
     *       concatenation header).</li>
     *   <li>UCS-2 (Hebrew, Yiddish, and other non-GSM-7 scripts): 70 chars in a single segment, 67
     *       chars/segment once concatenated.</li>
     * </ul>
     * A nine-member roster is one of the very few places this app deliberately sends a
     * multi-segment message, so worked example (Hebrew/Yiddish, UCS-2): a header line plus nine
     * lines like {@code "שם 848-207-4564"} (~18 chars each) plus a footer runs to roughly
     * 30 + 9*18 + 40 ≈ 232 characters. 232 does not fit a single 70-char UCS-2 segment, so it
     * concatenates: {@code ceil(232 / 67) = 4} segments — matching the "~4 segments" estimate in
     * {@code docs/phase3-redesign.md} §2.
     *
     * @param message the composed message body.
     * @param ucs2    true if the body requires UCS-2 encoding (contains characters outside the
     *                GSM-7 alphabet, e.g. Hebrew or Yiddish text); false for GSM-7-safe content.
     * @return the estimated segment count, minimum 1 for a non-empty message (0 for an empty one).
     */
    public static int estimateSegments(String message, boolean ucs2) {
        if (message == null || message.isEmpty()) {
            return 0;
        }
        int length = message.length();
        int singleSegmentLimit = ucs2 ? 70 : 160;
        if (length <= singleSegmentLimit) {
            return 1;
        }
        int concatenatedSegmentSize = ucs2 ? 67 : 153;
        return (int) Math.ceil((double) length / concatenatedSegmentSize);
    }
}
