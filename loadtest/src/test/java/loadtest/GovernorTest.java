package loadtest;

import static org.junit.jupiter.api.Assertions.*;

import loadtest.Governor.Action;
import org.junit.jupiter.api.Test;

class GovernorTest {
    // min 2, peak 20, p95 limit 250 ms, errors 1%, abort at 20%, back off after 2 bad windows, recover after 3 good, abort after 3
    Governor g() { return new Governor(2, 20, 250, 1.0, 20, 2, 3, 3); }

    @Test
    void healthyWindowsChangeNothing() {
        Governor g = g();
        for (int i = 0; i < 10; i++) assertEquals(Action.NONE, g.observe(1000, 0, 5, 20).action());
        assertEquals(20, g.ceiling());
    }

    @Test
    void oneBadWindowIsTolerated() {
        Governor g = g();
        assertEquals(Action.NONE, g.observe(1000, 0, 900, 20).action());
        assertEquals(Action.NONE, g.observe(1000, 0, 10, 20).action());
        assertEquals(20, g.ceiling());
    }

    @Test
    void twoSlowWindowsInARowLowerTheCeilingTo70PercentOfTheCurrentLoad() {
        Governor g = g();
        g.observe(1000, 0, 400, 20);
        Governor.Decision d = g.observe(1000, 0, 400, 20);
        assertEquals(Action.BACKOFF, d.action());
        assertEquals(14, d.ceiling());
        assertEquals(14, g.ceiling());
    }

    @Test
    void errorsAboveTheLimitAlsoBackOff() {
        Governor g = g();
        g.observe(980, 20, 5, 20);          // 2% errors
        assertEquals(Action.BACKOFF, g.observe(980, 20, 5, 20).action());
    }

    @Test
    void keepsBackingOffWhileTheClusterStaysUnhealthy() {
        Governor g = g();
        int threads = 20;
        for (int round = 0; round < 6; round++) {
            g.observe(500, 0, 800, threads);
            Governor.Decision d = g.observe(500, 0, 800, threads);
            if (d.action() == Action.BACKOFF) threads = d.ceiling();
        }
        assertEquals(2, g.ceiling());       // never below the minimum
    }

    @Test
    void neverGoesBelowTheMinimum() {
        Governor g = new Governor(3, 20, 250, 1.0, 20, 1, 3, 3);
        for (int i = 0; i < 30; i++) g.observe(10, 0, 5000, g.ceiling());
        assertEquals(3, g.ceiling());
    }

    @Test
    void recoversInStepsOfATenthOfThePeak() {
        Governor g = g();
        g.observe(1000, 0, 400, 20);
        g.observe(1000, 0, 400, 20);                         // ceiling 14
        assertEquals(Action.NONE, g.observe(1000, 0, 5, 14).action());
        assertEquals(Action.NONE, g.observe(1000, 0, 5, 14).action());
        Governor.Decision d = g.observe(1000, 0, 5, 14);
        assertEquals(Action.RECOVER, d.action());
        assertEquals(16, d.ceiling());
        for (int i = 0; i < 100; i++) g.observe(1000, 0, 5, g.ceiling());
        assertEquals(20, g.ceiling());                       // back to the peak, not beyond
    }

    @Test
    void aBadWindowResetsTheRecoveryCount() {
        Governor g = g();
        g.observe(1000, 0, 400, 20);
        g.observe(1000, 0, 400, 20);                         // ceiling 14
        g.observe(1000, 0, 5, 14);
        g.observe(1000, 0, 5, 14);
        g.observe(1000, 0, 900, 14);                         // bad window
        assertEquals(Action.NONE, g.observe(1000, 0, 5, 14).action());
        assertEquals(14, g.ceiling());
    }

    @Test
    void sustainedHighErrorRateAborts() {
        Governor g = g();
        assertEquals(Action.NONE, g.observe(700, 300, 5, 10).action());        // 30%: BACKOFF is not due yet (needs two)
        Governor.Decision second = g.observe(700, 300, 5, 10);
        assertNotEquals(Action.ABORT, second.action());
        Governor.Decision third = g.observe(700, 300, 5, 10);
        assertEquals(Action.ABORT, third.action());
        assertTrue(third.reason().contains("error rate"));
    }

    @Test
    void anAbortStreakNeedsConsecutiveWindows() {
        Governor g = g();
        g.observe(700, 300, 5, 10);
        g.observe(700, 300, 5, 10);
        g.observe(1000, 0, 5, 10);                          // clean window resets it
        assertNotEquals(Action.ABORT, g.observe(700, 300, 5, 10).action());
    }

    @Test
    void idleWindowsAreIgnored() {
        Governor g = g();
        for (int i = 0; i < 10; i++) assertEquals(Action.NONE, g.observe(0, 0, 0, 0).action());
        assertEquals(20, g.ceiling());
    }
}
