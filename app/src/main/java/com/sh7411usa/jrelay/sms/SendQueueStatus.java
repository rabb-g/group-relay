package com.sh7411usa.jrelay.sms;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory snapshot of each member's outbox drain schedule, published by {@link SmsSendService}
 * (one independent burst/wait cadence per recipient, since each member can have their own send
 * pacing) and read by the dashboard to show a live "next burst in..." countdown for whichever
 * member's burst is coming up soonest. Not persisted: it only describes the currently-running
 * (or just-finished) drain within this process.
 */
public class SendQueueStatus {

    private static class Burst {
        final long atMillis;
        final int size;

        Burst(long atMillis, int size) {
            this.atMillis = atMillis;
            this.size = size;
        }
    }

    private static final Long NULL_MEMBER_KEY = -1L;
    private static final Map<Long, Burst> scheduled = new ConcurrentHashMap<>();

    private SendQueueStatus() {
    }

    public static void setNextBurst(Long memberId, long atMillis, int size) {
        scheduled.put(key(memberId), new Burst(atMillis, size));
    }

    public static void clear(Long memberId) {
        scheduled.remove(key(memberId));
    }

    public static void clearAll() {
        scheduled.clear();
    }

    /** 0 if no burst is currently scheduled/waiting for any member. */
    public static long getNextBurstAtMillis() {
        Burst soonest = soonest();
        return soonest != null ? soonest.atMillis : 0;
    }

    public static int getNextBurstSize() {
        Burst soonest = soonest();
        return soonest != null ? soonest.size : 0;
    }

    private static Long key(Long memberId) {
        return memberId != null ? memberId : NULL_MEMBER_KEY;
    }

    private static Burst soonest() {
        Burst best = null;
        for (Burst burst : scheduled.values()) {
            if (best == null || burst.atMillis < best.atMillis) {
                best = burst;
            }
        }
        return best;
    }
}
