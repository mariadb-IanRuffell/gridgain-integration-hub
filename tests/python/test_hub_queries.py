"""Structural tests for hub/queries.sql (the cross-source SQL run by hub/run.sh and shown in the demo).

Without a cluster we cannot execute the statements, so these tests check what the runner (hub/Query.java) relies on and that every
table and column the queries mention exists in the GridGain schemas.
"""
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def read(path):
    with open(os.path.join(ROOT, path)) as f:
        return f.read()


def schema_columns():
    cols = {}
    for res in ("gridgain-schema-mariadb.sql", "gridgain-schema-oracle.sql"):
        sql = "\n".join(l for l in read("cdc/src/main/resources/" + res).splitlines() if not l.strip().startswith("--"))
        for m in re.finditer(r"CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\n?\) WITH", sql, re.S):
            names = set()
            depth, item = 0, ""
            for ch in m.group(2) + ",":
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                if ch == "," and depth == 0:
                    first = item.strip().split()[0] if item.strip() else ""
                    if first.upper() != "PRIMARY":
                        names.add(first)
                    item = ""
                else:
                    item += ch
            cols[m.group(1)] = names
    return cols


def statements():
    """Mirror of Query.java: a statement is the lines up to one ending in ';', titled by the preceding '-- N.' comment."""
    out, title, cur = [], None, []
    for line in read("hub/queries.sql").splitlines():
        if line.startswith("--"):
            if re.match(r"-- \d+\.", line):
                title = line[3:]
            continue
        if not line.strip() and not cur:
            continue
        cur.append(line)
        if line.strip().endswith(";"):
            out.append((title, "\n".join(cur)))
            cur = []
    assert not cur, "last statement is not terminated by ';'"
    return out


class HubQueriesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stmts = statements()
        cls.cols = schema_columns()
        cls.mariadb = {"customer", "address", "product", "orders", "order_item", "payment", "interaction", "loyalty_txn",
                      "customer_score", "loyalty_tier_rule"}
        cls.oracle = {"airport", "flight", "flight_inventory", "booking", "booking_segment", "flight_event", "compensation"}

    def test_six_numbered_queries(self):
        self.assertEqual(len(self.stmts), 6)
        self.assertEqual([int(re.match(r"(\d+)\.", t).group(1)) for t, _ in self.stmts], [1, 2, 3, 4, 5, 6])

    def test_every_statement_is_a_select_and_has_a_single_terminator(self):
        for title, sql in self.stmts:
            self.assertTrue(sql.lstrip().upper().startswith("SELECT"), title)
            self.assertEqual(sql.count(";"), 1, title)

    def test_every_query_joins_both_source_systems(self):
        for title, sql in self.stmts:
            tables = set(re.findall(r"(?:FROM|JOIN)\s+(\w+)", sql, re.I))
            self.assertTrue(tables & self.mariadb, f"{title}: no MariaDB table")
            self.assertTrue(tables & self.oracle, f"{title}: no Oracle table")
            self.assertTrue(tables <= self.mariadb | self.oracle, f"{title}: unknown {tables - self.mariadb - self.oracle}")

    def test_qualified_columns_exist(self):
        for title, sql in self.stmts:
            aliases = dict((a, t) for t, a in re.findall(r"(?:FROM|JOIN)\s+(\w+)\s+(?:AS\s+)?(\w+)\b(?!\s*\()", sql, re.I)
                           if a.upper() not in ("ON", "WHERE", "LEFT", "JOIN", "GROUP", "ORDER", "INNER", "LIMIT", "SELECT", "AND"))
            for alias, col in re.findall(r"\b(\w+)\.(\w+)\b", sql):
                table = aliases.get(alias)
                if table in self.cols:
                    self.assertIn(col, self.cols[table], f"{title}: {alias}.{col} ({table})")

    def test_the_join_key_is_customer_id(self):
        for title, sql in self.stmts:
            self.assertIn("customer_id", sql, title)

    def test_runner_script_and_sql_are_consistent(self):
        run = read("hub/run.sh")
        self.assertIn("queries.sql", run)
        self.assertIn("Query.java", run)
        self.assertTrue(os.path.exists(os.path.join(ROOT, "hub/Query.java")))


if __name__ == "__main__":
    unittest.main()
