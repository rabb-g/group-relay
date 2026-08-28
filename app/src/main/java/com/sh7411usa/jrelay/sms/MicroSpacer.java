package com.sh7411usa.jrelay.sms;

import java.util.concurrent.locks.LockSupport;

/**
 * Precise sub-second pacing between individual sends inside one burst, deliberately not built on
 * {@link Thread#sleep(long)}. A single {@code Thread.sleep(millis)} call rounds its argument to
 * whole milliseconds and is only guaranteed to sleep "at least" that long — on a loaded device the
 * overshoot compounds across many short waits. Instead, a fractional-millisecond target (e.g. a
 * randomized 217.5ms) is converted once to an absolute nanosecond deadline via {@code Math.round},
 * then the wait is a self-correcting loop of short {@link LockSupport#parkNanos} calls that always
 * re-measures {@link System#nanoTime()} against that fixed deadline, so an early or late wake-up on
 * any one iteration doesn't drift the actual elapsed time.
 */
public final class MicroSpacer {

    /** Never park for more than this many nanoseconds per iteration, so drift is corrected quickly. */
    private static final long MAX_SLICE_NANOS = 2_000_000L; // 2ms

    private MicroSpacer() {
    }

    /** Blocks the calling thread for approximately {@code millis} (fractional) milliseconds. */
    public static void waitMillis(double millis) {
        if (millis <= 0) {
            return;
        }
        long deadlineNanos = System.nanoTime() + Math.round(millis * 1_000_000.0);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            LockSupport.parkNanos(Math.min(remainingNanos, MAX_SLICE_NANOS));
        }
    }
}
