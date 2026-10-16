"""Unit tests for the SQL generators (sybase/oracle generate_seed.py and generate_cdc.py).

The scripts write ./sql/<file> relative to the working directory, so each test run executes them in a scratch directory.
Run:  python3 -m unittest discover -s tests/python -v
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def read(path):
    with open(path) as f:
        return f.read()


def generate(script):
    """Run a generator in a fresh temp dir; return (stdout, {filename: content})."""
    with tempfile.TemporaryDirectory() as d:
        os.mkdir(os.path.join(d, "sql"))
        r = subprocess.run([sys.executable, os.path.join(ROOT, script)], cwd=d, capture_output=True, text=True, check=True)
        files = {f: read(os.path.join(d, "sql", f)) for f in os.listdir(os.path.join(d, "sql"))}
    return r.stdout, files


def split_values(s):
    """Split a SQL value list on top-level commas, honouring '...' strings with '' escapes."""
    out, cur, inq, i = [], "", False, 0
    while i < len(s):
        c = s[i]
        if inq:
            cur += c
            if c == "'":
                if i + 1 < len(s) and s[i + 1] == "'":
                    cur += "'"; i += 1
                else:
                    inq = False
        elif c == "'":
            inq = True; cur += c
        elif c == ",":
            out.append(cur.strip()); cur = ""
        else:
            cur += c
        i += 1
    out.append(cur.strip())
    return out


def unq(v):
    v = v.strip()
    if v.startswith("timestamp "):
        v = v[len("timestamp "):]
    return v[1:-1].replace("''", "'") if v.startswith("'") else (float(v) if re.fullmatch(r"-?\d+\.\d+", v) else int(v) if re.fullmatch(r"-?\d+", v) else v)


def parse_inserts(sql):
    """-> {table: [ {col: value} ]} for 'insert [into] t (cols) values (...)' lines."""
    rows = defaultdict(list)
    for m in re.finditer(r"^insert (?:into )?(\w+) \(([^)]*)\) values \((.*)\);?$", sql, re.M):
        cols = [c.strip() for c in m.group(2).split(",")]
        vals = [unq(v) for v in split_values(m.group(3))]
        assert len(cols) == len(vals), m.group(0)[:120]
        rows[m.group(1)].append(dict(zip(cols, vals)))
    return rows


class SplitValuesTest(unittest.TestCase):
    def test_commas_and_quotes_inside_strings(self):
        self.assertEqual(split_values("1, 'a, b', 'it''s', 2.5"), ["1", "'a, b'", "'it''s'", "2.5"])

    def test_unquote(self):
        self.assertEqual(unq("'it''s'"), "it's")
        self.assertEqual(unq("timestamp '2026-01-01 00:00:00'"), "2026-01-01 00:00:00")
        self.assertEqual((unq("42"), unq("1.5")), (42, 1.5))


class SybaseSeedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stdout, files = generate("sybase/generate_seed.py")
        cls.sql = files["02_seed.sql"]
        cls.t = parse_inserts(cls.sql)

    def test_row_counts_match_summary_line(self):
        for name, table in [("customers", "customer"), ("orders", "orders"), ("items", "order_item"), ("payments", "payment"),
                            ("interactions", "interaction"), ("loyalty_txns", "loyalty_txn")]:
            n = int(re.search(rf"{name}=(\d+)", self.stdout).group(1))
            self.assertEqual(len(self.t[table]), n, table)
        self.assertEqual(len(self.t["customer"]), 250)
        self.assertEqual(len(self.t["product"]), 24)
        self.assertEqual(len(self.t["loyalty_tier_rule"]) if "loyalty_tier_rule" in self.t else 4, 4)

    def test_deterministic(self):
        _, again = generate("sybase/generate_seed.py")
        self.assertEqual(again["02_seed.sql"], self.sql)

    def test_primary_keys_unique_and_dense(self):
        for table, pk in [("customer", "customer_id"), ("address", "address_id"), ("product", "product_id"), ("orders", "order_id"),
                          ("order_item", "order_item_id"), ("payment", "payment_id"), ("interaction", "interaction_id"),
                          ("loyalty_txn", "txn_id")]:
            ids = [r[pk] for r in self.t[table]]
            self.assertEqual(len(ids), len(set(ids)), f"{table} pk not unique")
            self.assertEqual(sorted(ids), list(range(1, len(ids) + 1)), f"{table} ids not 1..n")

    def test_foreign_keys(self):
        cust = {r["customer_id"] for r in self.t["customer"]}
        orders = {r["order_id"] for r in self.t["orders"]}
        prods = {r["product_id"] for r in self.t["product"]}
        for table in ("address", "orders", "interaction", "loyalty_txn"):
            self.assertLessEqual({r["customer_id"] for r in self.t[table]}, cust, table)
        self.assertLessEqual({r["order_id"] for r in self.t["order_item"]}, orders)
        self.assertLessEqual({r["product_id"] for r in self.t["order_item"]}, prods)
        self.assertLessEqual({r["order_id"] for r in self.t["payment"]}, orders)
        self.assertLessEqual({r["order_id"] for r in self.t["loyalty_txn"] if r["order_id"] is not None and r["order_id"] != "null"}, orders)

    def test_money_adds_up(self):
        lines = defaultdict(float)
        for r in self.t["order_item"]:
            self.assertEqual(r["line_total"], r["qty"] * r["unit_price"])
            lines[r["order_id"]] += r["line_total"]
        for o in self.t["orders"]:
            self.assertAlmostEqual(o["total_amount"], lines[o["order_id"]], places=2, msg=f"order {o['order_id']}")

    def test_domain_values(self):
        self.assertLessEqual({r["status"] for r in self.t["customer"]}, {"ACTIVE", "INACTIVE"})
        self.assertLessEqual({r["loyalty_tier"] for r in self.t["customer"]}, {"BLUE", "SILVER", "GOLD", "PLATINUM"})
        self.assertLessEqual({r["segment"] for r in self.t["customer"]}, {"STANDARD", "PREMIUM", "CORPORATE"})
        self.assertLessEqual({r["status"] for r in self.t["orders"]}, {"NEW", "COMPLETED", "CANCELLED"})
        self.assertLessEqual({r["type"] for r in self.t["interaction"]}, {"COMPLAINT", "ENQUIRY", "FEEDBACK", "BOOKING_CHANGE"})
        self.assertLessEqual({r["sentiment"] for r in self.t["interaction"]}, {-1, 0, 1})
        self.assertLessEqual({r["category"] for r in self.t["product"]},
                             {"FLIGHT", "HOTEL", "LOUNGE", "UPGRADE", "INSURANCE", "ANCILLARY"})

    def test_emails_unique(self):
        emails = [r["email"].lower() for r in self.t["customer"]]
        self.assertEqual(len(emails), len(set(emails)))

    def test_every_batch_terminated_and_identity_insert_balanced(self):
        self.assertEqual(self.sql.count("set identity_insert") % 2, 0)
        for tbl in re.findall(r"set identity_insert (\w+) on", self.sql):
            self.assertIn(f"set identity_insert {tbl} off", self.sql)


class OracleSeedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stdout, files = generate("oracle/generate_seed.py")
        cls.sql = files["02_seed.sql"]
        cls.t = parse_inserts(cls.sql)

    def test_counts_match_summary_line(self):
        n = int(re.search(r"airports=(\d+)", self.stdout).group(1))
        self.assertEqual(len(re.findall(r"^insert into airport values", self.sql, re.M)), n)
        for name, table in [("flights", "flight"), ("inventory", "flight_inventory"),
                            ("bookings", "booking"), ("segments", "booking_segment"), ("events", "flight_event")]:
            n = int(re.search(rf"{name}=(\d+)", self.stdout).group(1))
            self.assertEqual(len(self.t[table]), n, table)

    def test_airport_rows_use_positional_values(self):
        airports = re.findall(r"^insert into airport values \('(\w{3})'", self.sql, re.M)
        self.assertEqual(len(airports), 18)
        self.assertIn("DXB", airports)
        self.assertEqual(len(set(airports)), 18)

    def test_every_flight_touches_the_hub(self):
        for f in self.t["flight"]:
            self.assertTrue("DXB" in (f["origin"], f["destination"]), f["flight_no"])
            self.assertNotEqual(f["origin"], f["destination"])
            self.assertLess(f["dep_time"], f["arr_time"])

    def test_three_cabins_per_flight(self):
        per = Counter(r["flight_id"] for r in self.t["flight_inventory"])
        self.assertEqual(set(per.values()), {3})
        self.assertEqual(set(per), {f["flight_id"] for f in self.t["flight"]})

    def test_bookings_reference_crm_customer_ids(self):
        for b in self.t["booking"]:
            self.assertTrue(1 <= b["customer_id"] <= 250)
        refs = [b["booking_ref"] for b in self.t["booking"]]
        self.assertEqual(len(refs), len(set(refs)))
        self.assertTrue(all(re.fullmatch(r"[A-Z2-9]{6}", r) for r in refs))

    def test_segment_foreign_keys_and_unique_seats(self):
        bookings = {b["booking_id"] for b in self.t["booking"]}
        flights = {f["flight_id"] for f in self.t["flight"]}
        seen = set()
        for s in self.t["booking_segment"]:
            self.assertIn(s["booking_id"], bookings)
            self.assertIn(s["flight_id"], flights)
            key = (s["flight_id"], s["cabin"], s["seat_no"])
            self.assertNotIn(key, seen, f"seat issued twice: {key}")
            seen.add(key)

    def test_inventory_is_consistent_with_segments(self):
        live = Counter(); issued = Counter()
        for s in self.t["booking_segment"]:
            issued[(s["flight_id"], s["cabin"])] += 1
            if s["status"] != "CANCELLED":
                live[(s["flight_id"], s["cabin"])] += 1
        for i in self.t["flight_inventory"]:
            k = (i["flight_id"], i["cabin"])
            self.assertEqual(i["seats_total"] - i["seats_available"], live[k], k)
            self.assertEqual(i["next_seat"], issued[k] + 1, k)

    def test_cancelled_bookings_have_cancelled_segments_and_bounded_refund(self):
        by_b = defaultdict(list)
        for s in self.t["booking_segment"]:
            by_b[s["booking_id"]].append(s)
        for b in self.t["booking"]:
            segs = by_b[b["booking_id"]]
            self.assertTrue(segs)
            self.assertAlmostEqual(b["total_fare"], sum(s["fare"] for s in segs), places=1)
            if b["status"] == "CANCELLED":
                self.assertTrue(all(s["status"] == "CANCELLED" and s["miles_earned"] == 0 for s in segs))
                self.assertLessEqual(b["refunded"], 0.9 * b["total_fare"] + 0.01)
            else:
                self.assertEqual(b["refunded"], 0)

    def test_seat_numbers_follow_the_cabin_layout(self):
        for s in self.t["booking_segment"]:
            m = re.fullmatch(r"(\d+)([A-F])", s["seat_no"])
            self.assertIsNotNone(m, s["seat_no"])
            first_row = {"FIRST": 1, "BUSINESS": 3, "ECONOMY": 12}[s["cabin"]]
            self.assertGreaterEqual(int(m.group(1)), first_row)

    def test_flight_events_cover_delays_and_cancellations(self):
        flights = {f["flight_id"]: f for f in self.t["flight"]}
        for e in self.t["flight_event"]:
            f = flights[e["flight_id"]]
            self.assertIn(e["event_type"], ("DELAY", "CANCEL"))
            self.assertEqual(e["event_type"] == "CANCEL", f["status"] == "CANCELLED")
        expected = sum(1 for f in flights.values() if f["status"] == "CANCELLED" or f["delay_minutes"] > 0)
        self.assertEqual(len(self.t["flight_event"]), expected)

    def test_scripted_disruption_scenarios_exist(self):
        st = Counter(f["status"] for f in self.t["flight"])
        self.assertEqual(st["DELAYED"], 8)
        self.assertEqual(st["CANCELLED"], 3)

    def test_ends_with_commit_and_identity_restart(self):
        self.assertIn("\ncommit;\n", self.sql)
        self.assertEqual(self.sql.count("generated by default as identity"), 4)


class SybaseCdcScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, files = generate("sybase/generate_cdc.py")
        cls.sql = files["04_cdc.sql"]
        spec = read(os.path.join(ROOT, "cdc/src/main/java/cdc/Tables.java"))
        cls.java_tables = re.findall(r'TableSpec\.(?:plain|viaOrders)\("(\w+)", "(\w+)"', spec)

    def test_one_trigger_per_replicated_table_with_the_reader_primary_key(self):
        self.assertEqual(len(self.java_tables), 10)
        for table, pk in self.java_tables:
            m = re.search(rf"create trigger trg_cdc_{table} on {table} for insert, update, delete as(.*?)\ngo", self.sql, re.S)
            self.assertIsNotNone(m, f"no trigger for {table}")
            self.assertEqual(m.group(1).count(f"i.{pk}") + m.group(1).count(f"d.{pk}"), 9, table)   # 3 references per I/U/D statement
        self.assertEqual(self.sql.count("create trigger"), len(self.java_tables))

    def test_each_trigger_logs_insert_update_and_delete(self):
        for table, _ in self.java_tables:
            body = re.search(rf"create trigger trg_cdc_{table} .*?\ngo", self.sql, re.S).group(0)
            for op in ("'I'", "'U'", "'D'"):
                self.assertIn(f"select '{table}', {op}", body)

    def test_triggers_are_dropped_before_being_recreated_and_batches_end_in_go(self):
        self.assertEqual(self.sql.count("drop trigger"), self.sql.count("create trigger"))
        self.assertTrue(self.sql.rstrip().endswith("go"))
        self.assertIn("create table cdc_change_log", self.sql)
        self.assertIn("create table cdc_offset", self.sql)
        self.assertIn("change_id   numeric(18,0) identity", self.sql)


class OracleCdcScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, files = generate("oracle/generate_cdc.py")
        cls.sql = files["04_cdc.sql"]
        schema = read(os.path.join(ROOT, "cdc/src/main/resources/gridgain-schema-oracle.sql")).lower()
        cls.grid_tables = set(re.findall(r"create table if not exists (\w+)", schema))

    def test_one_trigger_per_oracle_table_and_all_exist_in_gridgain_schema(self):
        tables = re.findall(r"create or replace trigger trg_cdc_(\w+)", self.sql)
        self.assertEqual(len(tables), 7)
        self.assertLessEqual(set(tables), self.grid_tables)

    def test_trigger_logs_new_key_for_insert_update_and_old_key_for_delete(self):
        for t in re.findall(r"create or replace trigger trg_cdc_(\w+)", self.sql):
            body = re.search(rf"trigger trg_cdc_{t}\n(.*?)\n/\n", self.sql, re.S).group(1)
            self.assertIn("'I', ", body); self.assertIn("'U', ", body); self.assertIn("'D', ", body)
            ins, upd, dele = (re.search(rf"'{op}', (.*?)\);", body).group(1) for op in "IUD")
            self.assertIn(":new", ins); self.assertIn(":new", upd); self.assertIn(":old", dele)
            self.assertNotIn(":old", ins)

    def test_composite_keys_are_joined_with_a_pipe(self):
        self.assertIn(":new.flight_id || '|' || :new.cabin", self.sql)
        self.assertIn(":new.iata", self.sql)

    def test_log_uses_rowdependencies_for_per_row_commit_scn(self):
        self.assertIn(") rowdependencies;", self.sql)
        self.assertIn("last_scn", self.sql)

    def test_plsql_blocks_are_terminated(self):
        self.assertEqual(self.sql.count("\n/\n"), 1 + len(re.findall(r"create or replace trigger", self.sql)))


if __name__ == "__main__":
    unittest.main()
