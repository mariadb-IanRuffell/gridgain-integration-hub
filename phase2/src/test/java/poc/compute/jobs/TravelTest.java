package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The travel rules shared by the booking jobs; these mirror fn_seat_no, fn_miles_factor and the refund CASE in the PL/SQL. */
class TravelTest {
    @ParameterizedTest
    @CsvSource({"FIRST,1,1A", "FIRST,6,1F", "FIRST,7,2A", "FIRST,8,2B",
                "BUSINESS,1,3A", "BUSINESS,6,3F", "BUSINESS,7,4A", "BUSINESS,42,9F",
                "ECONOMY,1,12A", "ECONOMY,6,12F", "ECONOMY,7,13A", "ECONOMY,300,61F"})
    void seatNumbersFillRowsOfSix(String cabin, int n, String expected) {
        assertEquals(expected, Travel.seatNo(cabin, n));
    }

    @Test
    void unknownCabinFallsBackToEconomyLayout() {
        assertEquals("12A", Travel.seatNo("PREMIUM", 1));
    }

    @ParameterizedTest
    @CsvSource({"FIRST,2", "BUSINESS,1.5", "ECONOMY,1", "OTHER,1"})
    void milesFactor(String cabin, String factor) {
        assertEquals(0, new BigDecimal(factor).compareTo(Travel.milesFactor(cabin)));
    }

    @ParameterizedTest
    @CsvSource({"5470,ECONOMY,5470", "5470,BUSINESS,8205", "5470,FIRST,10940", "130,BUSINESS,195", "131,BUSINESS,197", "0,FIRST,0"})
    void milesRoundHalfUp(int km, String cabin, int expected) {
        assertEquals(expected, Travel.miles(km, cabin));
    }

    @ParameterizedTest
    @CsvSource({"100,0.9", "72.01,0.9", "72,0.5", "48,0.5", "24.01,0.5", "24,0", "1,0", "-5,0"})
    void refundShareByHoursToDeparture(double hours, String pct) {
        assertEquals(0, new BigDecimal(pct).compareTo(Travel.refundPct(hours)), "hours=" + hours);
    }

    @Test
    void cabinValidation() {
        assertTrue(Travel.validCabin("ECONOMY"));
        assertTrue(Travel.validCabin("BUSINESS"));
        assertTrue(Travel.validCabin("FIRST"));
        assertFalse(Travel.validCabin("PREMIUM"));
        assertFalse(Travel.validCabin("economy"));
        assertFalse(Travel.validCabin(null));
    }

    @Test
    void bookingReferencesAreSixUppercaseLetters() {
        for (int i = 0; i < 200; i++) assertTrue(Travel.randomRef().matches("[A-Z]{6}"));
    }

    @Test
    void activeSegmentStatusesAreTheLiveOnes() {
        assertEquals("('TICKETED', 'CHECKED_IN')", Travel.ACTIVE_SEGMENTS);
    }
}
