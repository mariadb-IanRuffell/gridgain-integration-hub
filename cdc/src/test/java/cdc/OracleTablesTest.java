package cdc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class OracleTablesTest {
    @Test
    void columnSpecParsing() {
        OracleCdc.Tbl t = new OracleCdc.Tbl("booking_segment", "booking_segment t join booking b on b.booking_id = t.booking_id",
            new String[] {"segment_id"}, new char[] {'I'}, "segment_id:I", "customer_id=b.customer_id:I", "fare:D", "status:S", "at:T");
        assertEquals(List.of("segment_id", "customer_id", "fare", "status", "at"), t.cols);
        assertEquals(List.of("t.segment_id", "b.customer_id", "t.fare", "t.status", "t.at"), t.exprs);
        assertEquals(List.of('I', 'I', 'D', 'S', 'T'), t.types);
        assertEquals(0, t.pkIndex());
    }

    @Test
    void generatedSql() {
        OracleCdc.Tbl t = OracleCdc.BY_NAME.get("flight_inventory");
        assertEquals("DELETE FROM flight_inventory WHERE flight_id = ? AND cabin = ?", t.deleteSql());
        assertTrue(t.mergeSql().startsWith("MERGE INTO flight_inventory (flight_id, cabin, "));
        assertEquals(t.cols.size(), t.mergeSql().chars().filter(c -> c == '?').count());
        assertEquals("select t.flight_id, t.cabin, t.seats_total, t.seats_available, t.next_seat, t.base_fare from flight_inventory t",
            t.selectSql());
        assertTrue(t.gridSelectSql().endsWith(" FROM flight_inventory"));
    }

    @Test
    void sevenTables() {
        assertEquals(7, OracleCdc.ALL.size());
        assertEquals(OracleCdc.ALL.size(), OracleCdc.BY_NAME.size());
        for (OracleCdc.Tbl t : OracleCdc.ALL) {
            assertEquals(t.cols.size(), t.exprs.size(), t.name);
            assertEquals(t.cols.size(), t.types.size(), t.name);
            assertEquals(t.pk.length, t.pkTypes.length, t.name);
            for (String k : t.pk) assertTrue(t.cols.contains(k), t.name + "." + k);
        }
    }

    @Test
    void columnsMatchTheGridGainDdl() throws IOException {
        Ddl ddl = Ddl.load("/gridgain-schema-oracle.sql");
        assertEquals(OracleCdc.BY_NAME.keySet(), ddl.columns.keySet());
        for (OracleCdc.Tbl t : OracleCdc.ALL) {
            assertEquals(new HashSet<>(t.cols), ddl.columns.get(t.name), t.name);
            assertTrue(ddl.primaryKey.get(t.name).containsAll(List.of(t.pk)), t.name);   // colocated tables add customer_id to the key
        }
    }

    @Test
    void customerOwnedTablesAreColocatedOnCustomerId() throws IOException {
        Ddl ddl = Ddl.load("/gridgain-schema-oracle.sql");
        for (String t : List.of("booking", "booking_segment", "compensation"))
            assertTrue(ddl.options.get(t).contains("affinity_key=customer_id"), t);
        for (String t : List.of("airport", "flight", "flight_inventory", "flight_event"))
            assertTrue(ddl.options.get(t).contains("template=replicated"), t);
    }

    @Test
    void derivedCustomerIdComesFromTheBookingJoin() {
        for (String n : List.of("booking_segment", "compensation")) {
            OracleCdc.Tbl t = OracleCdc.BY_NAME.get(n);
            assertTrue(t.exprs.contains("b.customer_id"), n);
            assertTrue(t.from.contains("join booking b"), n);
        }
    }

    @Test
    void everyOracleTableHasACdcTriggerKey() throws IOException {
        // the reader and the generated triggers must agree on the key encoding: "a|b" for the composite key
        for (OracleCdc.Tbl t : OracleCdc.ALL) assertTrue(t.pk.length <= 2, t.name);
    }
}
