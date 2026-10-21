package loadtest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShapeTest {
    final Shape s = new Shape(2, 10, 80, 20, 40, 2);       // cycle = 140 s, two cycles = 280 s

    @Test
    void rampsUpHoldsAndRampsDown() {
        assertEquals(2, s.threadsAt(0));
        assertEquals(6, s.threadsAt(40));                 // halfway up
        assertEquals(10, s.threadsAt(80));
        assertEquals(10, s.threadsAt(99));
        assertEquals(6, s.threadsAt(120));                // halfway down
        assertEquals(2, s.threadsAt(139.9), 1);
    }

    @Test
    void secondCycleRepeatsTheFirst() {
        for (double t : new double[] {0, 17, 55, 85, 110, 130}) assertEquals(s.threadsAt(t), s.threadsAt(t + 140), "t=" + t);
    }

    @Test
    void zeroBeforeStartAndAfterTheEnd() {
        assertEquals(0, s.threadsAt(-1));
        assertEquals(0, s.threadsAt(280));
        assertEquals(0, s.threadsAt(1e6));
        assertEquals(280, s.totalSec());
    }

    @Test
    void neverLeavesTheMinToPeakRange() {
        for (double t = 0; t < 280; t += 0.5) {
            int n = s.threadsAt(t);
            assertTrue(n >= 2 && n <= 10, "t=" + t + " n=" + n);
        }
    }

    @Test
    void zeroLengthRampsJumpStraightToPeak() {
        Shape step = new Shape(1, 8, 0, 10, 0, 1);
        assertEquals(8, step.threadsAt(0));
        assertEquals(8, step.threadsAt(9.9));
        assertEquals(0, step.threadsAt(10));
    }

    @Test
    void phaseLabels() {
        assertEquals("cycle 1/2 up", s.phaseAt(10));
        assertEquals("cycle 1/2 hold", s.phaseAt(90));
        assertEquals("cycle 1/2 down", s.phaseAt(130));
        assertEquals("cycle 2/2 up", s.phaseAt(150));
        assertEquals("done", s.phaseAt(280));
    }
}
