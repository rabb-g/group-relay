package com.sh7411usa.jrelay.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class MessageMergerTest {

    // One segment per 10 characters, ceiling-divided, so tests can pin exact merge/split
    // boundaries instead of guessing at real GSM-7/UCS-2 segment math.
    private static final MessageMerger.SegmentCounter TEN_CHARS_PER_SEGMENT =
            body -> (body.length() + 9) / 10;

    // Proves a code path never actually calls the counter (single-row groups, maxSegments <= 0).
    private static final MessageMerger.SegmentCounter THROWING_COUNTER = body -> {
        throw new AssertionError("segments() should not have been called for: " + body);
    };

    // ---- merge ----

    @Test
    public void merge_threeRowsMergeIntoOneGroup() {
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(1L, "aaaaa"),
                new MessageMerger.Row(2L, "bbbbb"),
                new MessageMerger.Row(3L, "ccccc"));

        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 2, TEN_CHARS_PER_SEGMENT);

        assertEquals(1, result.size());
        MessageMerger.Merged merged = result.get(0);
        assertEquals(1L, merged.keepRowId);
        assertEquals("aaaaa\nbbbbb\nccccc", merged.body);
        assertEquals(Arrays.asList(2L, 3L), merged.mergedRowIds);
    }

    @Test
    public void merge_rowThatWouldExceedMaxSegmentsStartsNewGroup() {
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(1L, "aaaaa"),
                new MessageMerger.Row(2L, "bbbbb"));

        // Joined body is 11 chars -> 2 segments, over a maxSegments of 1.
        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 1, TEN_CHARS_PER_SEGMENT);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).keepRowId);
        assertEquals("aaaaa", result.get(0).body);
        assertTrue(result.get(0).mergedRowIds.isEmpty());
        assertEquals(2L, result.get(1).keepRowId);
        assertEquals("bbbbb", result.get(1).body);
        assertTrue(result.get(1).mergedRowIds.isEmpty());
    }

    @Test
    public void merge_singleOversizedRowAloneNeverTruncated() {
        String oversized = "x".repeat(25);
        List<MessageMerger.Row> rows = Collections.singletonList(new MessageMerger.Row(1L, oversized));

        // THROWING_COUNTER proves this never even asks how many segments the row would take:
        // with nothing to join it against, the row is emitted unchanged and untruncated.
        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 1, THROWING_COUNTER);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).keepRowId);
        assertEquals(oversized, result.get(0).body);
        assertTrue(result.get(0).mergedRowIds.isEmpty());
    }

    @Test
    public void merge_oversizedRowGetsOwnGroupWhenFollowedByAnotherRow() {
        String oversized = "x".repeat(25);
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(1L, oversized),
                new MessageMerger.Row(2L, "yyyyy"));

        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 1, TEN_CHARS_PER_SEGMENT);

        assertEquals(2, result.size());
        assertEquals(oversized, result.get(0).body);
        assertTrue(result.get(0).mergedRowIds.isEmpty());
        assertEquals("yyyyy", result.get(1).body);
    }

    @Test
    public void merge_exactBoundaryFitsIntoSameGroup() {
        String tenChars = "a".repeat(10);
        String nineChars = "b".repeat(9);
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(1L, tenChars),
                new MessageMerger.Row(2L, nineChars));

        // Joined body is exactly 20 chars -> exactly 2 segments, so <= maxSegments must merge.
        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 2, TEN_CHARS_PER_SEGMENT);

        assertEquals(1, result.size());
        assertEquals(tenChars + "\n" + nineChars, result.get(0).body);
        assertEquals(Collections.singletonList(2L), result.get(0).mergedRowIds);
    }

    @Test
    public void merge_orderPreservedAcrossGroupsAndWithinMergedRowIds() {
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(10L, "aaaaa"),
                new MessageMerger.Row(20L, "bbbbb"),
                new MessageMerger.Row(30L, "c".repeat(15)),
                new MessageMerger.Row(40L, "dddd"));

        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 2, TEN_CHARS_PER_SEGMENT);

        assertEquals(2, result.size());
        assertEquals(10L, result.get(0).keepRowId);
        assertEquals(Collections.singletonList(20L), result.get(0).mergedRowIds);
        assertEquals(30L, result.get(1).keepRowId);
        assertEquals(Collections.singletonList(40L), result.get(1).mergedRowIds);
    }

    @Test
    public void merge_singleRowListUnchanged() {
        List<MessageMerger.Row> rows = Collections.singletonList(new MessageMerger.Row(1L, "hello"));

        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 2, TEN_CHARS_PER_SEGMENT);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).keepRowId);
        assertEquals("hello", result.get(0).body);
        assertTrue(result.get(0).mergedRowIds.isEmpty());
    }

    @Test
    public void merge_emptyListReturnsEmptyList() {
        List<MessageMerger.Merged> result =
                MessageMerger.merge(Collections.emptyList(), 2, TEN_CHARS_PER_SEGMENT);

        assertTrue(result.isEmpty());
    }

    @Test
    public void merge_nullListReturnsEmptyList() {
        List<MessageMerger.Merged> result = MessageMerger.merge(null, 2, TEN_CHARS_PER_SEGMENT);

        assertTrue(result.isEmpty());
    }

    @Test
    public void merge_maxSegmentsZeroMeansUnlimitedAndSkipsCounter() {
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(1L, "a".repeat(50)),
                new MessageMerger.Row(2L, "b".repeat(50)),
                new MessageMerger.Row(3L, "c".repeat(50)));

        // THROWING_COUNTER proves the segment check is skipped entirely, not just satisfied.
        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 0, THROWING_COUNTER);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).keepRowId);
        assertEquals("a".repeat(50) + "\n" + "b".repeat(50) + "\n" + "c".repeat(50), result.get(0).body);
        assertEquals(Arrays.asList(2L, 3L), result.get(0).mergedRowIds);
    }

    @Test
    public void merge_negativeMaxSegmentsMeansUnlimited() {
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(1L, "a".repeat(50)),
                new MessageMerger.Row(2L, "b".repeat(50)));

        List<MessageMerger.Merged> result = MessageMerger.merge(rows, -5, THROWING_COUNTER);

        assertEquals(1, result.size());
        assertEquals(Collections.singletonList(2L), result.get(0).mergedRowIds);
    }

    @Test
    public void merge_mergedRowIdsExcludesKeepRowIdAndPreservesOrder() {
        List<MessageMerger.Row> rows = Arrays.asList(
                new MessageMerger.Row(100L, "p1"),
                new MessageMerger.Row(200L, "p2"),
                new MessageMerger.Row(300L, "p3"),
                new MessageMerger.Row(400L, "p4"));

        List<MessageMerger.Merged> result = MessageMerger.merge(rows, 100, TEN_CHARS_PER_SEGMENT);

        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).keepRowId);
        assertEquals(Arrays.asList(200L, 300L, 400L), result.get(0).mergedRowIds);
    }
}
