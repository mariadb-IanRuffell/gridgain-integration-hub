package cdc;

import java.util.List;

/** Maps one Sybase source table to its GridGain table. */
final class TableSpec {
    final String name;
    final String pk;                 // primary-key column (same name on both sides)
    final boolean pkIsString;
    final List<String> columns;      // GridGain column names, in order
    final List<String> sourceExprs;  // Sybase select expressions, aligned with columns
    final String sourceFrom;         // Sybase FROM clause; the base table is aliased "t"
    final int pkIndex;

    TableSpec(String name, String pk, boolean pkIsString, List<String> columns,
              List<String> sourceExprs, String sourceFrom) {
        this.name = name;
        this.pk = pk;
        this.pkIsString = pkIsString;
        this.columns = columns;
        this.sourceExprs = sourceExprs;
        this.sourceFrom = sourceFrom;
        this.pkIndex = columns.indexOf(pk);
    }

    /** Columns map 1:1 to the Sybase table. */
    static TableSpec plain(String name, String pk, String... cols) {
        return plain(name, pk, false, cols);
    }

    static TableSpec plain(String name, String pk, boolean pkIsString, String... cols) {
        List<String> c = List.of(cols);
        return new TableSpec(name, pk, pkIsString, c, c.stream().map(x -> "t." + x).toList(), name + " t");
    }

    /** Child table with no customer_id of its own: resolve it through orders (the affinity key in GridGain). */
    static TableSpec viaOrders(String name, String pk, String... cols) {
        List<String> c = new java.util.ArrayList<>(List.of(cols));
        c.add(1, "customer_id");
        List<String> e = c.stream().map(x -> x.equals("customer_id") ? "o.customer_id" : "t." + x).toList();
        return new TableSpec(name, pk, false, c, e, name + " t join orders o on o.order_id = t.order_id");
    }

    String selectSql() {
        return "select " + String.join(", ", sourceExprs) + " from " + sourceFrom;
    }

    String mergeSql() {
        return "MERGE INTO " + name + " (" + String.join(", ", columns) + ") VALUES ("
                + "?, ".repeat(columns.size() - 1) + "?)";
    }

    String deleteSql() {
        return "DELETE FROM " + name + " WHERE " + pk + " = ?";
    }

    String gridSelectSql() {
        return "SELECT " + String.join(", ", columns) + " FROM " + name;
    }
}
