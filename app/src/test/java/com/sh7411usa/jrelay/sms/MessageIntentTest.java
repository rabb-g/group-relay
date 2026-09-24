package com.sh7411usa.jrelay.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MessageIntentTest {

    // ---- stripPostPrefix ----

    @Test
    public void stripPostPrefix_hashAllWithSpace() {
        assertEquals("hi", MessageIntent.stripPostPrefix("#all hi"));
    }

    @Test
    public void stripPostPrefix_hashAllMixedCaseWithExtraSpaces() {
        assertEquals("hi", MessageIntent.stripPostPrefix("#All   hi"));
    }

    @Test
    public void stripPostPrefix_colonAllWithSpace() {
        assertEquals("hi", MessageIntent.stripPostPrefix("all: hi"));
    }

    @Test
    public void stripPostPrefix_colonAllUpperCaseNoSpace() {
        assertEquals("hi", MessageIntent.stripPostPrefix("ALL:hi"));
    }

    @Test
    public void stripPostPrefix_colonAllTrimsTrailingWhitespaceAndKeepsInternalBody() {
        assertEquals("hi there", MessageIntent.stripPostPrefix("All:  hi there "));
    }

    @Test
    public void stripPostPrefix_rejectsSpaceBeforeColon() {
        assertNull(MessageIntent.stripPostPrefix("all : hi"));
    }

    @Test
    public void stripPostPrefix_rejectsLongerHashWord() {
        assertNull(MessageIntent.stripPostPrefix("#allen hi"));
    }

    @Test
    public void stripPostPrefix_rejectsHashAllWithNoBody() {
        assertNull(MessageIntent.stripPostPrefix("#all"));
    }

    @Test
    public void stripPostPrefix_rejectsHashAllWithOnlyWhitespaceBody() {
        assertNull(MessageIntent.stripPostPrefix("#all   "));
    }

    @Test
    public void stripPostPrefix_rejectsColonAllWithNoBody() {
        assertNull(MessageIntent.stripPostPrefix("all:"));
    }

    @Test
    public void stripPostPrefix_rejectsPrefixNotAtStart() {
        assertNull(MessageIntent.stripPostPrefix("hi all: there"));
    }

    @Test
    public void stripPostPrefix_rejectsEmptyString() {
        assertNull(MessageIntent.stripPostPrefix(""));
    }

    @Test
    public void stripPostPrefix_rejectsNull() {
        assertNull(MessageIntent.stripPostPrefix(null));
    }

    // These four pin Pattern.DOTALL: without it, "." stops at a newline and a real multi-line
    // SMS post (or a multi-line non-post) would be parsed differently, so a future refactor that
    // silently drops DOTALL must fail here instead of silently misrouting posts as private replies.

    @Test
    public void stripPostPrefix_keepsMultiLineBody() {
        assertEquals("Meeting tonight\nNew Hope Shul",
                MessageIntent.stripPostPrefix("#all Meeting tonight\nNew Hope Shul"));
    }

    @Test
    public void stripPostPrefix_colonAllKeepsMultiLineBody() {
        assertEquals("Meeting tonight\n7pm",
                MessageIntent.stripPostPrefix("all:Meeting tonight\n7pm"));
    }

    @Test
    public void stripPostPrefix_newlineSeparatorAfterHashAll() {
        assertEquals("Meeting tonight", MessageIntent.stripPostPrefix("#all\nMeeting tonight"));
    }

    // ---- canonicalBareKeyword ----

    @Test
    public void canonicalBareKeyword_stopSynonymsMapToHashStop() {
        assertEquals("#stop", MessageIntent.canonicalBareKeyword("STOP"));
        assertEquals("#stop", MessageIntent.canonicalBareKeyword("UNSUBSCRIBE"));
        assertEquals("#stop", MessageIntent.canonicalBareKeyword("CANCEL"));
        assertEquals("#stop", MessageIntent.canonicalBareKeyword("QUIT"));
        assertEquals("#stop", MessageIntent.canonicalBareKeyword("END"));
        assertEquals("#stop", MessageIntent.canonicalBareKeyword("stop"));
    }

    @Test
    public void canonicalBareKeyword_helpMapsToHashCommands() {
        assertEquals("#commands", MessageIntent.canonicalBareKeyword("HELP"));
        assertEquals("#commands", MessageIntent.canonicalBareKeyword("help"));
    }

    @Test
    public void canonicalBareKeyword_muteAndUnmute() {
        assertEquals("#mute", MessageIntent.canonicalBareKeyword("MUTE"));
        assertEquals("#unmute", MessageIntent.canonicalBareKeyword("UNMUTE"));
    }

    @Test
    public void canonicalBareKeyword_rejectsPhraseContainingKeyword() {
        assertNull(MessageIntent.canonicalBareKeyword("stop by later"));
        assertNull(MessageIntent.canonicalBareKeyword("please stop"));
    }

    @Test
    public void canonicalBareKeyword_rejectsEmptyAndNull() {
        assertNull(MessageIntent.canonicalBareKeyword(""));
        assertNull(MessageIntent.canonicalBareKeyword(null));
    }

    @Test
    public void canonicalBareKeyword_rejectsMultiLineMessageStartingWithKeyword() {
        assertNull(MessageIntent.canonicalBareKeyword("STOP\nplease"));
    }

    // ---- isWithinReplyWindow ----

    @Test
    public void isWithinReplyWindow_zeroWindowMeansUnlimited() {
        assertTrue(MessageIntent.isWithinReplyWindow(0L, Long.MAX_VALUE / 2, 0));
    }

    @Test
    public void isWithinReplyWindow_negativeWindowMeansUnlimited() {
        assertTrue(MessageIntent.isWithinReplyWindow(0L, Long.MAX_VALUE / 2, -1));
    }

    @Test
    public void isWithinReplyWindow_23HoursWithin24HourWindowIsTrue() {
        long postTimestamp = 0L;
        long now = 23L * 3_600_000L;
        assertTrue(MessageIntent.isWithinReplyWindow(postTimestamp, now, 24));
    }

    @Test
    public void isWithinReplyWindow_25HoursWithin24HourWindowIsFalse() {
        long postTimestamp = 0L;
        long now = 25L * 3_600_000L;
        assertFalse(MessageIntent.isWithinReplyWindow(postTimestamp, now, 24));
    }

    @Test
    public void isWithinReplyWindow_exact24HourBoundaryIsTrue() {
        long postTimestamp = 0L;
        long now = 24L * 3_600_000L;
        assertTrue(MessageIntent.isWithinReplyWindow(postTimestamp, now, 24));
    }

    @Test
    public void isWithinReplyWindow_clockSkewNowBeforePostIsTrue() {
        long postTimestamp = 10_000L;
        long now = 5_000L;
        assertTrue(MessageIntent.isWithinReplyWindow(postTimestamp, now, 24));
    }

    // ---- sanitizeRelayBody ----

    // The literal forgery attack (audit 2.3): a member appends a newline then a fake "[Admin]:"
    // line to their own relayed body, hoping it renders as a second, structurally genuine line.
    @Test
    public void sanitizeRelayBody_collapsesForgedAdminLineIntoSameLine() {
        String sanitized = MessageIntent.sanitizeRelayBody("bins out tonight\n[Admin]: Emergency");
        assertEquals("bins out tonight [Admin]: Emergency", sanitized);
        assertFalse(sanitized.contains("\n"));
    }

    @Test
    public void sanitizeRelayBody_collapsesCarriageReturn() {
        assertEquals("a b", MessageIntent.sanitizeRelayBody("a\rb"));
    }

    @Test
    public void sanitizeRelayBody_collapsesCarriageReturnNewline() {
        assertEquals("a b", MessageIntent.sanitizeRelayBody("a\r\nb"));
    }

    @Test
    public void sanitizeRelayBody_collapsesTab() {
        assertEquals("a b", MessageIntent.sanitizeRelayBody("a\tb"));
    }

    @Test
    public void sanitizeRelayBody_collapsesMultipleConsecutiveNewlinesToOneSpace() {
        assertEquals("a b", MessageIntent.sanitizeRelayBody("a\n\n\nb"));
    }

    @Test
    public void sanitizeRelayBody_trimsLeadingAndTrailingWhitespace() {
        assertEquals("a b", MessageIntent.sanitizeRelayBody("  \n a b \t "));
    }

    @Test
    public void sanitizeRelayBody_leavesAlreadySingleLineBodyUnchanged() {
        assertEquals("hi there", MessageIntent.sanitizeRelayBody("hi there"));
    }

    @Test
    public void sanitizeRelayBody_emptyInputReturnsEmpty() {
        assertEquals("", MessageIntent.sanitizeRelayBody(""));
    }

    @Test
    public void sanitizeRelayBody_whitespaceOnlyInputReturnsEmpty() {
        assertEquals("", MessageIntent.sanitizeRelayBody("   \n\t  "));
    }

    @Test
    public void sanitizeRelayBody_nullInputReturnsEmpty() {
        assertEquals("", MessageIntent.sanitizeRelayBody(null));
    }

    @Test
    public void sanitizeRelayBody_hebrewTextWithEmbeddedNewlineIsCollapsed() {
        assertEquals("שלום לכולם", MessageIntent.sanitizeRelayBody("שלום\nלכולם"));
    }

    @Test
    public void sanitizeRelayBody_yiddishTextWithEmbeddedNewlineIsCollapsed() {
        assertEquals("אַ גוטע וואָך", MessageIntent.sanitizeRelayBody("אַ גוטע\nוואָך"));
    }

    // ---- isValidNickname ----

    @Test
    public void isValidNickname_rejectsNull() {
        assertFalse(MessageIntent.isValidNickname(null));
    }

    @Test
    public void isValidNickname_rejectsEmpty() {
        assertFalse(MessageIntent.isValidNickname(""));
    }

    @Test
    public void isValidNickname_rejectsWhitespaceOnly() {
        assertFalse(MessageIntent.isValidNickname("   "));
    }

    @Test
    public void isValidNickname_rejectsColon() {
        assertFalse(MessageIntent.isValidNickname("Bob: hi"));
    }

    @Test
    public void isValidNickname_rejectsIsoControlCharacter() {
        assertFalse(MessageIntent.isValidNickname("Bob\nSmith"));
    }

    // Bans the exact leading markers the app's own system prefixes use ("[Admin]", "@system", "#help").
    @Test
    public void isValidNickname_rejectsLeadingBracket() {
        assertFalse(MessageIntent.isValidNickname("[Admin]"));
    }

    @Test
    public void isValidNickname_rejectsLeadingAt() {
        assertFalse(MessageIntent.isValidNickname("@system"));
    }

    @Test
    public void isValidNickname_rejectsLeadingHash() {
        assertFalse(MessageIntent.isValidNickname("#help"));
    }

    // The rule is "first NON-SPACE character", so leading spaces cannot be used to smuggle
    // a forbidden marker past a naive charAt(0) check.
    @Test
    public void isValidNickname_rejectsLeadingBracketAfterLeadingSpace() {
        assertFalse(MessageIntent.isValidNickname("  [Admin]"));
    }

    @Test
    public void isValidNickname_acceptsMaxLength32Characters() {
        String name = repeat('a', 32);
        assertTrue(MessageIntent.isValidNickname(name));
    }

    @Test
    public void isValidNickname_rejectsOverLength33Characters() {
        String name = repeat('a', 33);
        assertFalse(MessageIntent.isValidNickname(name));
    }

    @Test
    public void isValidNickname_acceptsOrdinaryName() {
        assertTrue(MessageIntent.isValidNickname("Bob"));
    }

    @Test
    public void isValidNickname_acceptsHebrewName() {
        assertTrue(MessageIntent.isValidNickname("דוד"));
    }

    @Test
    public void isValidNickname_acceptsYiddishName() {
        assertTrue(MessageIntent.isValidNickname("משה"));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- sanitizeNicknameForRender ----

    @Test
    public void sanitizeNicknameForRender_stripsLeadingAdminMarkerStoredBeforeValidationExisted() {
        assertEquals("Admin]", MessageIntent.sanitizeNicknameForRender("[Admin]"));
    }

    @Test
    public void sanitizeNicknameForRender_stripsRepeatedLeadingMarkers() {
        assertEquals("Admin]", MessageIntent.sanitizeNicknameForRender("[[Admin]"));
    }

    @Test
    public void sanitizeNicknameForRender_leavesOrdinaryNicknameUntouched() {
        assertEquals("Bob", MessageIntent.sanitizeNicknameForRender("Bob"));
    }

    @Test
    public void sanitizeNicknameForRender_fallsBackToMemberWhenNothingSurvivesSanitization() {
        assertEquals("Member", MessageIntent.sanitizeNicknameForRender("[[[["));
    }
}
