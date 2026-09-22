package com.sh7411usa.jrelay.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides how several queued outbound posts for the same recipient should be coalesced into as
 * few SMS bodies as possible, instead of sending one SMS per post. Pure decision logic — no
 * Android APIs — so it can run in a plain JVM unit test. Segment counting is injected via
 * {@link SegmentCounter} so callers can wrap {@code SmsManager.divideMessage} without this class
 * ever touching Android.
 */
public final class MessageMerger {

    /** Counts how many SMS segments a body would occupy. Real impl wraps SmsManager.divideMessage. */
    public interface SegmentCounter {
        int segments(String body);
    }

    /** One mergeable queued row: its id and its body, in enqueue order. */
    public static final class Row {
        public final long id;
        public final String body;

        public Row(long id, String body) {
            this.id = id;
            this.body = body;
        }
    }

    /** One merged output: the row that keeps the merged body, and the rows folded into it. */
    public static final class Merged {
        public final long keepRowId;
        public final String body;
        public final List<Long> mergedRowIds;

        public Merged(long keepRowId, String body, List<Long> mergedRowIds) {
            this.keepRowId = keepRowId;
            this.body = body;
            this.mergedRowIds = mergedRowIds;
        }
    }

    private MessageMerger() {
    }

    /**
     * Merges `rows` (already all for ONE recipient, in enqueue order) into as few bodies as
     * possible, joining with "\n" and never letting a body exceed `maxSegments`. `maxSegments <= 0`
     * means unlimited: all rows fold into a single group.
     */
    public static List<Merged> merge(List<Row> rows, int maxSegments, SegmentCounter counter) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        boolean unlimited = maxSegments <= 0;

        List<Merged> result = new ArrayList<>();
        long keepRowId = 0;
        String currentBody = null;
        List<Long> mergedRowIds = new ArrayList<>();

        for (Row row : rows) {
            if (currentBody == null) {
                // First row of a new group: never measured, so an oversized row alone is never
                // rejected or truncated — it simply becomes (at least) its own group.
                keepRowId = row.id;
                currentBody = row.body;
                continue;
            }
            String candidate = currentBody + "\n" + row.body;
            if (unlimited || counter.segments(candidate) <= maxSegments) {
                currentBody = candidate;
                mergedRowIds.add(row.id);
            } else {
                result.add(new Merged(keepRowId, currentBody, mergedRowIds));
                keepRowId = row.id;
                currentBody = row.body;
                mergedRowIds = new ArrayList<>();
            }
        }
        result.add(new Merged(keepRowId, currentBody, mergedRowIds));
        return result;
    }
}
