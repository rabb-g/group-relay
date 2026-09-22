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
}
