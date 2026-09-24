package com.sh7411usa.jrelay.sms;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhoneNumberUtils {

    /**
     * Matches a leading phone number in any of the common US formats the app accepts
     * (some of which contain internal spaces, e.g. "+1 (234) 567-8910"), followed by
     * whitespace and the remaining text (a nickname).
     */
    private static final String NUMBER_ALTERNATION =
            "\\+1\\s?\\(\\d{3}\\)\\s?\\d{3}-?\\d{4}" +
                    "|\\+1\\d{10}" +
                    "|1-\\d{3}-\\d{3}-\\d{4}" +
                    "|\\(\\d{3}\\)\\s?\\d{3}-?\\d{4}" +
                    "|\\d{3}-\\d{3}-\\d{4}" +
                    "|\\d{10}" +
                    "|\\+\\d{8,15}";

    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile(
            "^(" + NUMBER_ALTERNATION + ")\\s+(.+)$");

    /** Same number formats, but as the last token instead of the first (e.g. "John Doe 234-567-8910"). */
    private static final Pattern TRAILING_NUMBER_PATTERN = Pattern.compile(
            "^(.+?)\\s+(" + NUMBER_ALTERNATION + ")$");

    /**
     * Tolerant normalizer: harvests every digit found ANYWHERE in the input and ignores
     * everything else (letters, punctuation, extra words). Use this only when the caller
     * already knows the input is a phone-number field with nothing else in it (e.g. a
     * dedicated "phone" column from CSV import, or a value already isolated by
     * {@link #splitLeadingNumberAndRest}/{@link #splitTrailingNumberAndRest}).
     *
     * Do NOT use this to test whether a piece of free-form user text (a command argument,
     * a nickname field, arbitrary SMS body text) "is" a phone number — because it ignores
     * non-digit characters, "5551234567 hi there" and "call 234-567-8910 now" both normalize
     * successfully, which is how a trailing/embedded number can be mistaken for the whole
     * input (see the #to-command and nickname-shadowing bugs this caused). For that kind of
     * "is this whole string a phone number" check, use {@link #normalizeStrict}.
     */
    public static String normalize(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String trimmed = rawInput.trim();
        boolean hasPlus = trimmed.startsWith("+");
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // Character.digit() folds any Unicode decimal digit (e.g. Arabic-Indic ٢) to its
            // ASCII value, unlike Character.isDigit()+append(c), which would keep the original
            // non-ASCII code point and silently produce a different normalized string for the
            // same logical number (duplicate-member bug, finding 2.11). Using digit() instead
            // of restricting to '0'-'9' also means we still recognize the number rather than
            // dropping those digits and losing it entirely.
            int value = Character.digit(c, 10);
            if (value >= 0) {
                digits.append((char) ('0' + value));
            }
        }
        String d = digits.toString();

        if (hasPlus) {
            if (d.length() >= 11 && d.length() <= 15) {
                return "+" + d;
            }
            return null;
        }

        if (d.length() == 11 && d.charAt(0) == '1') {
            return "+" + d;
        }
        if (d.length() == 10) {
            return "+1" + d;
        }
        return null;
    }

    /** Last four digits only, for logs. Null or four characters or fewer becomes "****". */
    public static String mask(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return "***" + phone.substring(phone.length() - 4);
    }

    /** Characters permitted in a phone number besides digits, for {@link #normalizeStrict}. */
    private static final String STRICT_ALLOWED_NON_DIGITS = "+()-. \t";

    /**
     * Strict normalizer: returns non-null ONLY when the ENTIRE trimmed input is a plausible
     * phone number and nothing else — digits plus {@code + ( ) - . } and whitespace, no
     * letters, no trailing/leading words. Use this whenever you need to decide whether a
     * whole string IS a phone number, as opposed to extracting one that's embedded in a
     * longer string. In particular: prefer this over {@link #normalize} for validating
     * command arguments or nickname-vs-number disambiguation, so that inputs like
     * "5551234567 hi there" or a nickname that merely contains digits are correctly rejected
     * instead of silently accepted as a number.
     */
    public static String normalizeStrict(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.digit(c, 10) < 0 && STRICT_ALLOWED_NON_DIGITS.indexOf(c) < 0) {
                return null;
            }
        }
        return normalize(trimmed);
    }

    /**
     * Splits a "<number> <nickname>" argument string into the raw number token and the
     * remaining nickname, tolerating spaces inside the number itself. Returns null if no
     * recognizable phone number is found at the start of the text.
     */
    public static String[] splitLeadingNumberAndRest(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = LEADING_NUMBER_PATTERN.matcher(text.trim());
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return null;
    }

    /**
     * Same as {@link #splitLeadingNumberAndRest}, but for a "<nickname> <number>" argument
     * string (number last instead of first). Returns {number, nickname}, or null if no
     * recognizable phone number is found at the end of the text.
     */
    public static String[] splitTrailingNumberAndRest(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = TRAILING_NUMBER_PATTERN.matcher(text.trim());
        if (matcher.matches()) {
            return new String[]{matcher.group(2), matcher.group(1)};
        }
        return null;
    }
}
