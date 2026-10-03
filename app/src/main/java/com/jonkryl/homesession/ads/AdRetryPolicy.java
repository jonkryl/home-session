package com.jonkryl.homesession.ads;

/** A finite retry budget. Network restoration or a new foreground visit starts a new budget. */
public final class AdRetryPolicy {
    private static final long[] DELAYS_MS = {10_000L, 30_000L, 60_000L};
    private int failures;

    /** Returns the next delay, or -1 after the three retries have been used. */
    public long failed() {
        if (failures >= DELAYS_MS.length) return -1L;
        return DELAYS_MS[failures++];
    }

    public void reset() { failures = 0; }
}
