package cdc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TablesTest {
    @Test
    void tenTablesIndexedByName() {
        assertEquals(10, Tables.ALL.size());
        assertEquals(Tables.ALL.size(), Tables.BY_NAME.size());
        for (TableSpec t : Tables.ALL) assertSame(t, Tables.BY_NAME.get(t.name));
    }

    @Test
    void primaryKeyIsOneOfTheColumns() {
        for (TableSpec t : Tables.ALL) assertTrue(t.pkIndex >= 0, t.name + " pk " + t.pk);
    }

    @Test
    void columnsMatchTheGridGainDdl() throws IOException {
        Ddl ddl = Ddl.load("/gridgain-schema-sybase.sql");
        assertEquals(Tables.BY_NAME.keySet(), ddl.columns.keySet());
        for (TableSpec t : Tables.ALL) {
            assertEquals(new HashSet<>(t.columns), ddl.columns.get(t.name), t.name);
            assertTrue(ddl.primaryKey.get(t.name).contains(t.pk), t.name + " key must include " + t.pk);
        }
    }

    @Test
    void customerOwnedTablesAreColocatedOnCustomerId() throws IOException {
        Ddl ddl = Ddl.load("/gridgain-schema-sybase.sql");
        for (String t : Set.of("address", "orders", "order_item", "payment", "interaction", "loyalty_txn")) {
            assertTrue(ddl.options.get(t).contains("affinity_key=customer_id"), t);
            assertTrue(ddl.columns.get(t).contains("customer_id"), t);
        }
        for (String t : Set.of("product", "loyalty_tier_rule"))
            assertTrue(ddl.options.get(t).contains("template=replicated"), t);
    }

    @Test
    void childTablesResolveCustomerThroughOrders() {
        for (String n : Set.of("order_item", "payment")) {
            TableSpec t = Tables.BY_NAME.get(n);
            assertTrue(t.sourceFrom.contains("join orders o"), n);
            assertTrue(t.sourceExprs.contains("o.customer_id"), n);
        }
    }
}
