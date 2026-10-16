package poc.compute.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientArgsTest {
    @Test
    void orderArgsFlattensProductQuantityPairs() {
        Map<String, Object> m = Main.orderArgs(new String[] {"order", "42", "WEB", "CARD", "1:2,13:1"});
        assertEquals(42, m.get("customer_id"));
        assertEquals("WEB", m.get("channel"));
        assertEquals("CARD", m.get("payment_method"));
        assertEquals(List.of(1, 2, 13, 1), m.get("items"));
    }

    @Test
    void orderArgsWithNoItemsIsAnEmptyList() {
        assertEquals(List.of(), Main.orderArgs(7, "APP", "POINTS", "  ").get("items"));
    }

    @Test
    void orderArgsRejectsMalformedItems() {
        assertThrows(NumberFormatException.class, () -> Main.orderArgs(1, "WEB", "CARD", "x:1"));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> Main.orderArgs(1, "WEB", "CARD", "5"));
    }

    @Test
    void bookArgsHasOptionalChannel() {
        Map<String, Object> m = Main.bookArgs(new String[] {"book", "72", "143", "BUSINESS", "Marco Rossi"});
        assertEquals(72, m.get("customer_id"));
        assertEquals(143, m.get("flight_id"));
        assertEquals("BUSINESS", m.get("cabin"));
        assertEquals("Marco Rossi", m.get("passenger"));
        assertFalse(m.containsKey("channel"));
        assertEquals("APP", Main.bookArgs(new String[] {"book", "1", "2", "FIRST", "A B", "APP"}).get("channel"));
    }

    @Test
    void delayAndCancelFlightArguments() {
        Map<String, Object> delay = Main.flightOp(new String[] {"delay", "134", "300", "weather"}, false);
        assertEquals(134, delay.get("flight_id"));
        assertEquals(false, delay.get("cancel"));
        assertEquals(300, delay.get("minutes"));
        assertEquals("weather", delay.get("reason"));
        assertFalse(Main.flightOp(new String[] {"delay", "134", "300"}, false).containsKey("reason"));

        Map<String, Object> cancel = Main.flightOp(new String[] {"cancel-flight", "134", "crew"}, true);
        assertEquals(true, cancel.get("cancel"));
        assertFalse(cancel.containsKey("minutes"));
        assertEquals("crew", cancel.get("reason"));
        assertFalse(Main.flightOp(new String[] {"cancel-flight", "134"}, true).containsKey("reason"));
    }

    private static String printed(Object o) {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Main.print(o);
        } finally {
            System.setOut(old);
        }
        return buf.toString().replace("\r", "");
    }

    @Test
    void printRendersNestedMapsAndRows() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rc", 0);
        m.put("rows", List.of(new LinkedHashMap<>(Map.of("a", 1)), "plain"));
        m.put("inner", new LinkedHashMap<>(Map.of("k", "v")));
        assertEquals("rc = 0\nrows:\n  a=1\n  plain\ninner:\n  k = v\n", printed(m));
    }

    @Test
    void printScalar() {
        assertEquals("hello\n", printed("hello"));
    }
}
