package com.sh7411usa.jrelay.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sh7411usa.jrelay.model.Member;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RosterComposerTest {

    private static Member member(String nickname, String phoneE164) {
        Member m = new Member();
        m.nickname = nickname;
        m.phoneE164 = phoneE164;
        return m;
    }

    // ---- compose: ordering ----

    @Test
    public void compose_sortsMembersByNicknameCaseInsensitively() {
        List<Member> members = Arrays.asList(
                member("Chana", "+15550100001"),
                member("alice", "+15550100002"),
                member("Bob", "+15550100003"));
        String body = RosterComposer.compose("%1$s roster:", "Group", "Save these.", members);

        int aliceIdx = body.indexOf("alice");
        int bobIdx = body.indexOf("Bob");
        int chanaIdx = body.indexOf("Chana");
        assertTrue("alice must sort before Bob despite case", aliceIdx < bobIdx);
        assertTrue("Bob must sort before Chana", bobIdx < chanaIdx);
    }

    // ---- compose: number formatting ----

    @Test
    public void compose_groups10DigitNanpNumberWithHyphens() {
        String body = RosterComposer.compose("%1$s:", "Group", "Save.",
                Arrays.asList(member("Alice", "5550100001")));
        assertTrue(body.contains("Alice 555-010-0001"));
    }

    @Test
    public void compose_stripsLeadingPlusOneFromNanpNumberBeforeGrouping() {
        String body = RosterComposer.compose("%1$s:", "Group", "Save.",
                Arrays.asList(member("Alice", "+15550100001")));
        assertTrue(body.contains("Alice 555-010-0001"));
    }

    @Test
    public void compose_keepsLeadingPlusOnInternationalNumber() {
        // Real bug caught by running the code: stripping the leading "+" from a non-NANP number
        // produces "447700900123", which is not dialable. The "+" must survive verbatim.
        String body = RosterComposer.compose("%1$s:", "Group", "Save.",
                Arrays.asList(member("Alice", "+447700900123")));
        assertTrue("expected leading + to be preserved, got: " + body,
                body.contains("Alice +447700900123"));
        assertFalse(body.contains("Alice 447700900123"));
    }

    // ---- compose: members without a usable number are still listed ----

    @Test
    public void compose_listsMemberWithNullNumberByNicknameAloneRatherThanDropping() {
        String body = RosterComposer.compose("%1$s:", "Group", "Save.",
                Arrays.asList(member("Alice", null)));
        // Dropping this member would let a reader wrongly conclude Alice is not in the thread.
        assertTrue(body.contains("\nAlice"));
        assertFalse(body.contains("Alice null"));
    }

    @Test
    public void compose_listsMemberWithEmptyNumberByNicknameAloneRatherThanDropping() {
        String body = RosterComposer.compose("%1$s:", "Group", "Save.",
                Arrays.asList(member("Alice", "")));
        assertTrue(body.contains("\nAlice"));
    }

    // ---- compose: empty/null member list produces header only ----

    @Test
    public void compose_emptyMemberListReturnsHeaderOnlyWithNoFooter() {
        String body = RosterComposer.compose("%1$s roster:", "Group", "Save the numbers below.",
                new ArrayList<Member>());
        // A footer telling people to save numbers "below" with nothing below it is misleading.
        assertEquals("Group roster:", body);
    }

    @Test
    public void compose_nullMemberListReturnsHeaderOnlyWithNoFooter() {
        String body = RosterComposer.compose("%1$s roster:", "Group", "Save the numbers below.",
                null);
        assertEquals("Group roster:", body);
    }

    // ---- compose: layout ----

    @Test
    public void compose_putsEachMemberOnItsOwnLineWithNoBulletsOrBlankLines() {
        List<Member> members = Arrays.asList(
                member("Alice", "5550100001"),
                member("Bob", "5550100002"));
        String body = RosterComposer.compose("Header:", "Group", "Footer.", members);

        String[] lines = body.split("\n", -1);
        assertEquals(4, lines.length);
        assertEquals("Header:", lines[0]);
        assertEquals("Alice 555-010-0001", lines[1]);
        assertEquals("Bob 555-010-0002", lines[2]);
        assertEquals("Footer.", lines[3]);
        for (String line : lines) {
            assertFalse("no bullet decoration expected", line.startsWith("-"));
            assertFalse("no bullet decoration expected", line.startsWith("*"));
        }
    }

    // ---- estimateSegments ----

    @Test
    public void estimateSegments_shortMessageIsOneSegment() {
        assertEquals(1, RosterComposer.estimateSegments("Group roster:\nAlice 555-010-0001", false));
    }

    @Test
    public void estimateSegments_gsm7AtSingleSegmentLimitIsOneSegment() {
        String msg = repeat('a', 160);
        assertEquals(1, RosterComposer.estimateSegments(msg, false));
    }

    @Test
    public void estimateSegments_gsm7JustOverSingleSegmentLimitConcatenatesAt153PerSegment() {
        String msg = repeat('a', 161);
        // Once concatenation is required, each segment gives up header space: 153 chars/segment.
        assertEquals(2, RosterComposer.estimateSegments(msg, false));
    }

    @Test
    public void estimateSegments_ucs2AtSingleSegmentLimitIsOneSegment() {
        String msg = repeat('a', 70);
        assertEquals(1, RosterComposer.estimateSegments(msg, true));
    }

    @Test
    public void estimateSegments_ucs2NineMemberRosterIsAboutFourSegments() {
        // Worked example from docs/phase3-redesign.md sec2: a ~232-char Hebrew/Yiddish roster
        // (header + nine "Name 555-010-0001"-shaped lines + footer) concatenates at 67 chars/seg.
        String msg = repeat('a', 232);
        assertEquals(4, RosterComposer.estimateSegments(msg, true));
    }

    @Test
    public void estimateSegments_emptyMessageIsZeroSegments() {
        assertEquals(0, RosterComposer.estimateSegments("", false));
        assertEquals(0, RosterComposer.estimateSegments(null, false));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
