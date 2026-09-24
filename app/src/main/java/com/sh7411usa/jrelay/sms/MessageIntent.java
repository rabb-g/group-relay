package com.sh7411usa.jrelay.sms;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the small set of message shapes Reply Mode needs to recognize: an explicit "post to
 * everyone" prefix (either {@code #all} or {@code all:}), the bare keywords that carriers expect
 * to work without a leading {@code #} (e.g. {@code STOP}, {@code HELP}), and whether a reply still
 * falls inside a post's reply window. Pure parsing/arithmetic only — no Android APIs — so it can
 * run in a plain JVM unit test.
 */
public final class MessageIntent {

    /**
     * The two accepted "post to everyone" spellings: `#all` followed by whitespace, or the bare word
     * `all` immediately followed by a colon. DOTALL so a multi-line post still matches as one body.
     */
    private static final Pattern POST_PREFIX_PATTERN =
            Pattern.compile("(?i)^(?:#all\\s+|all:)(.*)$", Pattern.DOTALL);

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /** Any run of whitespace that includes a newline, carriage return, tab, or other control character. */
    private static final Pattern MULTILINE_WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final int MAX_NICKNAME_LENGTH = 32;

    private MessageIntent() {
    }

    /**
     * Collapses a relayed member body to a single line: every run of whitespace (including
     * newlines, carriage returns and tabs) becomes one space, and the result is trimmed. This is
     * the anti-forgery guard for the relay path (audit 2.3): every relayed post is rendered as
     * "%1$s: %2$s", so if the body itself could contain a newline, a member could plant a second
     * "line" reading like "[Admin]: ..." that is structurally indistinguishable from a genuine
     * merged post. Collapsing to one line means the sender's own nickname is always the first
     * thing recipients see, and nothing after it can masquerade as a new attribution line.
     * Only apply this to a member's relayed body — never to command arguments, admin broadcasts,
     * or anything composed by the app itself.
     */
    public static String sanitizeRelayBody(String body) {
        if (body == null) {
            return "";
        }
        return MULTILINE_WHITESPACE_RUN.matcher(body.trim()).replaceAll(" ").trim();
    }

    /**
     * True when `nickname` is safe to store and to render inside "%1$s: ..." relay/attribution
     * prefixes (audit 2.3). Rejects: empty/whitespace-only; anything containing a newline, tab, or
     * other control character; anything containing ':' (the attribution-line separator itself);
     * anything whose first non-space character is '[', '@', or '#' (these are exactly the leading
     * characters the app's own system prefixes use — "[Admin]", "@admin", "@system" — so banning
     * them as the *first* character defeats impersonation without having to enumerate homoglyphs:
     * "[Аdmin]" with a Cyrillic А still starts with '['); and anything over 32 characters.
     */
    public static boolean isValidNickname(String nickname) {
        if (nickname == null) {
            return false;
        }
        String trimmed = nickname.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NICKNAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < nickname.length(); i++) {
            char c = nickname.charAt(i);
            if (c == ':' || Character.isISOControl(c)) {
                return false;
            }
        }
        char first = trimmed.charAt(0);
        return first != '[' && first != '@' && first != '#';
    }

    /**
     * Neutralizes a nickname already stored before {@link #isValidNickname} existed, so it can't
     * still forge an attribution line at render time. Collapses embedded newlines/tabs the same
     * way {@link #sanitizeRelayBody} does, then strips any leading '[', '@' or '#' characters
     * (repeatedly, in case of e.g. "[[Admin]"), falling back to a neutral label if nothing is left.
     */
    public static String sanitizeNicknameForRender(String nickname) {
        String collapsed = sanitizeRelayBody(nickname);
        int start = 0;
        while (start < collapsed.length()
                && (collapsed.charAt(start) == '[' || collapsed.charAt(start) == '@' || collapsed.charAt(start) == '#')) {
            start++;
        }
        String stripped = collapsed.substring(start).trim();
        return stripped.isEmpty() ? "Member" : stripped;
    }

    /** Returns the body with an explicit post prefix removed, or null when `trimmed` is not an explicit post. */
    public static String stripPostPrefix(String trimmed) {
        if (trimmed == null) {
            return null;
        }
        Matcher matcher = POST_PREFIX_PATTERN.matcher(trimmed);
        return matcher.matches() ? blankToNull(matcher.group(1).trim()) : null;
    }

    /** Returns the canonical #command for a bare keyword, or null when it is not one. */
    public static String canonicalBareKeyword(String trimmed) {
        if (trimmed == null) {
            return null;
        }
        String upper = trimmed.trim().toUpperCase(Locale.ROOT);
        switch (upper) {
            case "STOP":
            case "UNSUBSCRIBE":
            case "CANCEL":
            case "QUIT":
            case "END":
                return "#stop";
            case "HELP":
                return "#commands";
            case "MUTE":
                return "#mute";
            case "UNMUTE":
                return "#unmute";
            default:
                return null;
        }
    }

    /** True when a post made at postTimestamp can still be replied to at `now`. windowHours <= 0 means no limit. */
    public static boolean isWithinReplyWindow(long postTimestamp, long now, int windowHours) {
        if (windowHours <= 0) {
            return true;
        }
        long windowMillis = windowHours * MILLIS_PER_HOUR;
        return now - postTimestamp <= windowMillis;
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
