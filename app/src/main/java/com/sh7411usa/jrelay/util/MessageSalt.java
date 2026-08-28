package com.sh7411usa.jrelay.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Content transforms applied to a relayed member message right before it's enqueued: stripping
 * any phone numbers the sender typed, appending the sender's own number for traceability, and
 * "salting" the body (timestamp / random hex / zero-width spaces) so that otherwise-identical
 * messages don't look byte-for-byte identical to the carrier.
 */
public final class MessageSalt {

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+\\d{1,3}[-.\\s]?)?(?:\\(\\d{3}\\)[-.\\s]?|\\d{3}[-.\\s]?)\\d{3}[-.\\s]?\\d{4}");
    private static final Pattern EXTRA_SPACES = Pattern.compile("\\s{2,}");
    private static final Random RANDOM = new Random();

    private MessageSalt() {
    }

    /** Applies every enabled transform, in a fixed order, to a formatted relay message body. */
    public static String applyAll(Prefs prefs, String senderE164, String body) {
        String result = body;
        if (prefs.isStripPhoneNumbersEnabled()) {
            result = stripPhoneNumbers(result);
        }
        if (prefs.isAppendSenderNumberEnabled()) {
            result = appendSenderNumber(senderE164, result);
        }
        if (prefs.isSaltTimestampEnabled()) {
            result = appendTimestampSalt(result, prefs.getSaltTimestampFormat());
        }
        if (prefs.isSaltHexEnabled()) {
            result = applyHexSalt(result, prefs.getSaltHexPosition());
        }
        if (prefs.isSaltZwspEnabled()) {
            result = applyZwsp(result);
        }
        return result;
    }

    public static String stripPhoneNumbers(String body) {
        String stripped = PHONE_PATTERN.matcher(body).replaceAll("");
        return EXTRA_SPACES.matcher(stripped).replaceAll(" ").trim();
    }

    public static String appendSenderNumber(String senderE164, String body) {
        return body + " " + localDigits(senderE164);
    }

    /** Bare 10-digit local number (no "+1"), e.g. "2345678910". */
    public static String localDigits(String phoneE164) {
        if (phoneE164 == null) {
            return "";
        }
        String digits = phoneE164.startsWith("+") ? phoneE164.substring(1) : phoneE164;
        if (digits.length() == 11 && digits.charAt(0) == '1') {
            return digits.substring(1);
        }
        return digits;
    }

    public static String appendTimestampSalt(String body, Prefs.SaltTimestampFormat format) {
        return body + " [" + formatTimestamp(format, System.currentTimeMillis()) + "]";
    }

    private static String formatTimestamp(Prefs.SaltTimestampFormat format, long nowMillis) {
        switch (format) {
            case UNIX_SECONDS:
                return String.valueOf(nowMillis / 1000L);
            case HEX_SECONDS:
                return Long.toHexString(nowMillis / 1000L);
            case HEX_MILLIS:
                return Long.toHexString(nowMillis);
            case TIME_HHMMSS:
            default:
                return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(nowMillis));
        }
    }

    public static String applyHexSalt(String body, Prefs.SaltHexPosition position) {
        String hex = String.format(Locale.US, "%03X", RANDOM.nextInt(0x1000));
        return position == Prefs.SaltHexPosition.PREPEND ? "[" + hex + "] " + body : body + " [" + hex + "]";
    }

    private static final char ZERO_WIDTH_SPACE = 0x200B;

    /** Randomly injects U+200B (zero-width space) at some word boundaries so the body is byte-unique per send. */
    public static String applyZwsp(String body) {
        String[] words = body.split(" ");
        if (words.length <= 1) {
            return body;
        }
        StringBuilder sb = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            sb.append(' ');
            if (RANDOM.nextBoolean()) {
                sb.append(ZERO_WIDTH_SPACE);
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }
}
