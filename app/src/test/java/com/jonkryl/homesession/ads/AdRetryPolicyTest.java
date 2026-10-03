package com.jonkryl.homesession.ads;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AdRetryPolicyTest {
    @Test public void failuresUseFiniteIncreasingBackoffAndThenStop() {
        AdRetryPolicy policy = new AdRetryPolicy();
        assertEquals(10_000L, policy.failed());
        assertEquals(30_000L, policy.failed());
        assertEquals(60_000L, policy.failed());
        for (int attempt = 0; attempt < 100; attempt++) assertEquals(-1L, policy.failed());
    }

    @Test public void networkRestorationOrNewForegroundVisitStartsFreshBudget() {
        AdRetryPolicy policy = new AdRetryPolicy();
        for (int attempt = 0; attempt < 5; attempt++) policy.failed();
        policy.reset();
        assertEquals(10_000L, policy.failed());
        assertEquals(30_000L, policy.failed());
    }
}
