package com.sh7411usa.jrelay.sms;

/**
 * In-memory snapshot of the outbox drain's current schedule, published by {@link SmsSendService}
 * and read by the dashboard to show a live "next burst in..." countdown. Not persisted: it only
 * describes the currently-running (or just-finished) drain within this process. Sending is global
 * (one shared cadence for the whole outbox), so there is only ever one schedule to track.
 */
public class SendQueueStatus {

    private static volatile long nextBurstAtMillis = 0;
    private static volatile int nextBurstSize = 0;

    private SendQueueStatus() {
    }

    public static void setNextBurst(long atMillis, int size) {
        nextBurstAtMillis = atMillis;
        nextBurstSize = size;
    }

    public static void clear() {
        nextBurstAtMillis = 0;
        nextBurstSize = 0;
    }

    /** 0 if no burst is currently scheduled/waiting. */
    public static long getNextBurstAtMillis() {
        return nextBurstAtMillis;
    }

    public static int getNextBurstSize() {
        return nextBurstSize;
    }
}
