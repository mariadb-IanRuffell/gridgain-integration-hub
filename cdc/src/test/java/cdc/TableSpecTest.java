package cdc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TableSpecTest {
    @Test
    void plainTableMapsColumnsOneToOne() {
        TableSpec t = TableSpec.plain("address", "address_id", "address_id", "customer_id", "city");
        assertEquals(List.of("address_id", "customer_id", "city"), t.columns);
        assertEquals(List.of("t.address_id", "t.customer_id", "t.city"), t.sourceExprs);
        assertEquals("address t", t.sourceFrom);
        assertEquals(0, t.pkIndex);
        assertFalse(t.pkIsString);
    }

    @Test
    void stringPrimaryKeyFlag() {
        TableSpec t = TableSpec.plain("loyalty_tier_rule", "tier", true, "tier", "min_spend");
        assertTrue(t.pkIsString);
        assertEquals(0, t.pkIndex);
    }

    @Test
    void viaOrdersInsertsCustomerIdAfterTheKeyAndJoinsOrders() {
        TableSpec t = TableSpec.viaOrders("payment", "payment_id", "payment_id", "order_id", "amount");
        assertEquals(List.of("payment_id", "customer_id", "order_id", "amount"), t.columns);
        assertEquals(List.of("t.payment_id", "o.customer_id", "t.order_id", "t.amount"), t.sourceExprs);
        assertEquals("payment t join orders o on o.order_id = t.order_id", t.sourceFrom);
    }

    @Test
    void generatedSql() {
        TableSpec t = TableSpec.plain("product", "product_id", "product_id", "sku", "name");
        assertEquals("select t.product_id, t.sku, t.name from product t", t.selectSql());
        assertEquals("MERGE INTO product (product_id, sku, name) VALUES (?, ?, ?)", t.mergeSql());
        assertEquals("DELETE FROM product WHERE product_id = ?", t.deleteSql());
        assertEquals("SELECT product_id, sku, name FROM product", t.gridSelectSql());
    }

    @Test
    void mergePlaceholderCountMatchesColumns() {
        for (TableSpec t : Tables.ALL) {
            long marks = t.mergeSql().chars().filter(c -> c == '?').count();
            assertEquals(t.columns.size(), marks, t.name);
        }
    }

    @Test
    void singleColumnMergeHasOnePlaceholder() {
        assertEquals("MERGE INTO x (a) VALUES (?)", TableSpec.plain("x", "a", "a").mergeSql());
    }
}
