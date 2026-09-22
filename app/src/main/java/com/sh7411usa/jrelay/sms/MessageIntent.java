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

    private MessageIntent() {
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
