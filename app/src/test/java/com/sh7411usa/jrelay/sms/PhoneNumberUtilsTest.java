package com.sh7411usa.jrelay.sms;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PhoneNumberUtilsTest {

    private static final String EXPECTED = "+12345678910";

    @Test
    public void normalize_acceptsAllDocumentedUsFormats() {
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("+12345678910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("+1 (234) 567-8910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("1-234-567-8910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("234-567-8910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("2345678910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("(234) 567-8910"));
    }

    @Test
    public void normalize_rejectsGarbageInput() {
        assertNull(PhoneNumberUtils.normalize("12345"));
        assertNull(PhoneNumberUtils.normalize("not a number"));
        assertNull(PhoneNumberUtils.normalize(""));
    }

    @Test
    public void splitLeadingNumberAndRest_handlesHyphenatedFormats() {
        assertArrayEquals(
                new String[]{"234-567-8910", "John Doe"},
                PhoneNumberUtils.splitLeadingNumberAndRest("234-567-8910 John Doe"));
        assertArrayEquals(
                new String[]{"1-234-567-8910", "John Doe"},
                PhoneNumberUtils.splitLeadingNumberAndRest("1-234-567-8910 John Doe"));
    }

    @Test
    public void splitLeadingNumberAndRest_handlesSpaceInsideNumber() {
        assertArrayEquals(
                new String[]{"+1 (234) 567-8910", "John Doe"},
                PhoneNumberUtils.splitLeadingNumberAndRest("+1 (234) 567-8910 John Doe"));
    }

    @Test
    public void splitTrailingNumberAndRest_handlesNicknameFirst() {
        assertArrayEquals(
                new String[]{"234-567-8910", "User"},
                PhoneNumberUtils.splitTrailingNumberAndRest("User 234-567-8910"));
        assertArrayEquals(
                new String[]{"1-234-567-8910", "John Doe"},
                PhoneNumberUtils.splitTrailingNumberAndRest("John Doe 1-234-567-8910"));
    }

    @Test
    public void splitTrailingNumberAndRest_handlesSpaceInsideNumber() {
        assertArrayEquals(
                new String[]{"+1 (234) 567-8910", "John Doe"},
                PhoneNumberUtils.splitTrailingNumberAndRest("John Doe +1 (234) 567-8910"));
    }

    // ---- normalizeStrict ----

    @Test
    public void normalizeStrict_acceptsBareTenDigitNumber() {
        assertEquals("+15551234567", PhoneNumberUtils.normalizeStrict("5551234567"));
    }

    @Test
    public void normalizeStrict_acceptsFormattedNumberWithParensAndSpaces() {
        assertEquals("+15551234567", PhoneNumberUtils.normalizeStrict("+1 (555) 123-4567"));
    }

    // Duplicate-member fix (finding 2.11): Arabic-Indic digits must normalize to the SAME
    // string as their ASCII equivalent, or the same person creates two distinct DB rows and
    // receives every relayed message twice.
    @Test
    public void normalizeStrict_arabicIndicDigitsMatchAsciiEquivalent() {
        String arabicIndic = PhoneNumberUtils.normalizeStrict("٢٣٤٥٦٧٨٩١٠");
        String ascii = PhoneNumberUtils.normalizeStrict("2345678910");
        assertEquals(ascii, arabicIndic);
        assertEquals("+12345678910", arabicIndic);
    }

    @Test
    public void normalizeStrict_rejectsNumberWithTrailingWords() {
        assertNull(PhoneNumberUtils.normalizeStrict("5551234567 hi there"));
    }

    @Test
    public void normalizeStrict_rejectsNumberEmbeddedInSentence() {
        assertNull(PhoneNumberUtils.normalizeStrict("call 234-567-8910 now"));
    }

    @Test
    public void normalizeStrict_rejectsLettersOnly() {
        assertNull(PhoneNumberUtils.normalizeStrict("VERIZON"));
    }

    @Test
    public void normalizeStrict_rejectsShortCode() {
        assertNull(PhoneNumberUtils.normalizeStrict("12345"));
    }

    @Test
    public void normalizeStrict_rejectsEmptyString() {
        assertNull(PhoneNumberUtils.normalizeStrict(""));
    }

    @Test
    public void normalizeStrict_rejectsNull() {
        assertNull(PhoneNumberUtils.normalizeStrict(null));
    }
}
