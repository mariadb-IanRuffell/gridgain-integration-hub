#!/usr/bin/env python3
"""Generate sql/02_seed.sql: deterministic Customer 360 sample data for ASE."""
import random
from datetime import datetime, timedelta

random.seed(42)
NOW = datetime(2026, 10, 13, 12, 0, 0)
N_CUSTOMERS = 250

FIRST = ["Aisha", "Omar", "Layla", "Khalid", "Fatima", "Yusuf", "Noor", "Hassan", "Mariam", "Tariq",
         "James", "Emma", "Oliver", "Sophia", "Liam", "Olivia", "Noah", "Ava", "Lucas", "Mia",
         "Priya", "Arjun", "Ananya", "Rohan", "Wei", "Mei", "Hiroshi", "Yuki", "Chloe", "Hugo",
         "Elena", "Marco", "Sofia", "Ivan", "Anna", "Carlos", "Lucia", "Ahmed", "Sara", "Daniel"]
LAST = ["Haddad", "Rahman", "Khan", "Mansour", "Siddiqui", "Farouk", "Nasser", "Sharma", "Patel", "Singh",
        "Smith", "Johnson", "Brown", "Taylor", "Wilson", "Evans", "Thomas", "Roberts", "Walker", "Wright",
        "Chen", "Wang", "Tanaka", "Sato", "Dubois", "Martin", "Rossi", "Ferrari", "Ivanov", "Novak",
        "Garcia", "Lopez", "Silva", "Costa", "Hassan", "Ali", "Ibrahim", "Kaplan", "Schmidt", "Muller"]
COUNTRIES = [("AE", "Dubai"), ("AE", "Abu Dhabi"), ("GB", "London"), ("IN", "Mumbai"), ("IN", "Delhi"),
             ("US", "New York"), ("DE", "Frankfurt"), ("FR", "Paris"), ("AU", "Sydney"), ("SA", "Riyadh"),
             ("PK", "Karachi"), ("EG", "Cairo"), ("JP", "Tokyo"), ("SG", "Singapore"), ("ZA", "Johannesburg")]
STREETS = ["Marina Walk", "King Road", "Park Lane", "Station Street", "Garden Avenue", "Palm Boulevard",
           "Harbour View", "Market Square", "Oak Street", "Sheikh Zayed Road"]

CATALOG = {
    "FLIGHT":    [("Economy Saver", 1200), ("Economy Flex", 1900), ("Premium Economy", 3400),
                  ("Business Saver", 5800), ("Business Flex", 8600), ("First Class", 13500)],
    "HOTEL":     [("City Hotel 3-star night", 650), ("City Hotel 4-star night", 1100),
                  ("Resort 5-star night", 2200), ("Beach Villa night", 3800)],
    "LOUNGE":    [("Lounge Day Pass", 220), ("Lounge Annual Pass", 2400)],
    "UPGRADE":   [("Seat Upgrade", 450), ("Cabin Upgrade", 2800), ("Extra Legroom", 300)],
    "INSURANCE": [("Travel Insurance Basic", 120), ("Travel Insurance Plus", 260), ("Cancellation Cover", 180)],
    "ANCILLARY": [("Extra Baggage 10kg", 200), ("Priority Boarding", 90), ("Airport Transfer", 180),
                  ("In-flight Wifi", 40), ("Preferred Meal", 60), ("Fast Track Security", 75)],
}
TIERS = [("PLATINUM", 60000), ("GOLD", 20000), ("SILVER", 5000), ("BLUE", 0)]
SUBJECTS = {
    "COMPLAINT": ["Delayed baggage", "Flight delay compensation", "Seat not as booked", "Refund not received",
                  "Poor lounge experience"],
    "ENQUIRY": ["Upgrade options", "Baggage allowance", "Points balance", "Visa requirements", "Special meals"],
    "FEEDBACK": ["Great crew service", "Booking site feedback", "App suggestion", "Excellent lounge"],
    "BOOKING_CHANGE": ["Date change request", "Name correction", "Cancel booking", "Add passenger"],
}


def q(s):
    return "'" + s.replace("'", "''") + "'"


def ts(d):
    return "'" + d.strftime("%Y%m%d %H:%M:%S") + "'"


def rdate(start, end):
    return start + timedelta(seconds=random.randint(0, int((end - start).total_seconds())))


out = []
n = 0


def emit(s):
    global n
    out.append(s)
    n += 1
    if n % 50 == 0:
        out.append("go")


def table(name, ident=True):
    out.append("go")
    if ident:
        out.append(f"set identity_insert {name} on")
        out.append("go")


def end_table(name, ident=True):
    out.append("go")
    if ident:
        out.append(f"set identity_insert {name} off")
        out.append("go")


# ---- products ----
products = []
pid = 0
for cat, items in CATALOG.items():
    for name, price in items:
        pid += 1
        stock = 800 if cat in ("FLIGHT", "HOTEL") else 5000
        products.append(dict(id=pid, sku=f"{cat[:3]}-{pid:03d}", name=name, cat=cat, price=price, stock=stock))

# ---- customers ----
customers = []
used_emails = set()
for cid in range(1, N_CUSTOMERS + 1):
    while True:
        f, l = random.choice(FIRST), random.choice(LAST)
        email = f"{f}.{l}{random.randint(1, 999)}@example.com".lower()
        if email not in used_emails:
            used_emails.add(email)
            break
    country, city = random.choice(COUNTRIES)
    created = rdate(NOW - timedelta(days=5 * 365), NOW - timedelta(days=60))
    # activity profile: drives order recency so churn scores vary
    profile = random.choices(["active", "occasional", "lapsed", "dormant"], [40, 30, 20, 10])[0]
    customers.append(dict(
        id=cid, first=f, last=l, email=email, phone=f"+{random.randint(1, 99)}{random.randint(500000000, 599999999)}",
        dob=(NOW - timedelta(days=random.randint(20 * 365, 75 * 365))).date(),
        nat=country, segment=random.choices(["STANDARD", "PREMIUM", "CORPORATE"], [60, 30, 10])[0],
        opt_in=random.random() < 0.6, status="INACTIVE" if random.random() < 0.04 else "ACTIVE",
        created=created, profile=profile, city=city, country=country))

# ---- orders / items / payments / points ----
orders, items, payments, txns = [], [], [], []
oid = iid = payid = txid = 0
for c in customers:
    hi = NOW
    if c["profile"] == "active":
        n_orders, lo, hi = random.randint(5, 14), max(c["created"], NOW - timedelta(days=900)), NOW - timedelta(days=3)
    elif c["profile"] == "occasional":
        n_orders, lo, hi = random.randint(2, 6), max(c["created"], NOW - timedelta(days=900)), NOW - timedelta(days=70)
    elif c["profile"] == "lapsed":
        n_orders, lo, hi = random.randint(2, 6), max(c["created"], NOW - timedelta(days=1000)), NOW - timedelta(days=250)
    else:
        n_orders, lo, hi = random.randint(0, 2), c["created"], NOW - timedelta(days=500)
    if hi <= lo:
        hi, lo = lo + timedelta(days=1), lo
    for _ in range(n_orders):
        oid += 1
        odate = rdate(lo, hi)
        status = random.choices(["COMPLETED", "CANCELLED"], [92, 8])[0]
        total = 0
        for _ in range(random.choices([1, 2, 3], [50, 35, 15])[0]):
            p = random.choice(products if random.random() < 0.5 else [x for x in products if x["cat"] == "FLIGHT"])
            qty = random.choices([1, 2, 3], [70, 25, 5])[0]
            iid += 1
            line = p["price"] * qty
            total += line
            items.append((iid, oid, p["id"], qty, p["price"], line))
        orders.append((oid, c["id"], odate, status, random.choice(["WEB", "APP", "CALL", "AGENT"]), total))
        c.setdefault("orders", []).append((odate, status, total, oid))
        if status == "COMPLETED":
            payid += 1
            payments.append((payid, oid, random.choice(["CARD", "CARD", "CARD", "WALLET", "POINTS"]),
                             total, "CAPTURED", odate + timedelta(minutes=2)))
            txid += 1
            txns.append((txid, c["id"], oid, int(total), "PURCHASE", odate + timedelta(minutes=2)))

# bonus / redemption points, then final points + tier consistent with sp_recalc_loyalty_tier
for c in customers:
    if random.random() < 0.3:
        txid += 1
        txns.append((txid, c["id"], None, random.choice([500, 1000, 2500]), "BONUS",
                     rdate(c["created"], NOW - timedelta(days=30))))
    spend12 = sum(t for d, s, t, _ in c.get("orders", []) if s == "COMPLETED" and d >= NOW - timedelta(days=365))
    c["tier"] = next(t for t, m in TIERS if spend12 >= m)
    c["spend12"] = spend12
pts = {}
for t in txns:
    pts[t[1]] = pts.get(t[1], 0) + t[3]
# a few redemptions for customers with enough points
for c in customers:
    if pts.get(c["id"], 0) > 5000 and random.random() < 0.25:
        txid += 1
        r = -random.choice([2000, 4000])
        txns.append((txid, c["id"], None, r, "REDEMPTION", NOW - timedelta(days=random.randint(10, 300))))
        pts[c["id"]] += r

# ---- interactions ----
inter = []
inid = 0
for c in customers:
    for _ in range(random.choices([0, 1, 2, 3, 4], [30, 30, 20, 12, 8])[0]):
        inid += 1
        typ = random.choices(list(SUBJECTS), [25, 35, 15, 25])[0]
        sent = random.choices([-1, 0, 1], [70, 25, 5] if typ == "COMPLAINT" else [10, 55, 35])[0]
        opened = rdate(c["created"], NOW - timedelta(hours=1))
        is_open = opened > NOW - timedelta(days=21) and random.random() < 0.5
        inter.append((inid, c["id"], random.choice(["CALL", "EMAIL", "CHAT", "SOCIAL"]), typ,
                      random.choice(SUBJECTS[typ]), sent, "OPEN" if is_open else "CLOSED", opened,
                      None if is_open else opened + timedelta(hours=random.randint(1, 120))))

# ---- emit ----
out.append("-- Generated by generate_seed.py (deterministic, seed=42). Do not edit by hand.")
out.append("use testdb")
out.append("go")

table("product")
for p in products:
    emit(f"insert product (product_id, sku, name, category, unit_price, stock_qty, active) values "
         f"({p['id']}, {q(p['sku'])}, {q(p['name'])}, {q(p['cat'])}, {p['price']}, {p['stock']}, 1)")
end_table("product")

table("customer")
for c in customers:
    pt = pts.get(c["id"], 0)
    emit(f"insert customer (customer_id, first_name, last_name, email, phone, date_of_birth, nationality, segment, "
         f"loyalty_tier, loyalty_points, marketing_opt_in, status, created_at, updated_at) values "
         f"({c['id']}, {q(c['first'])}, {q(c['last'])}, {q(c['email'])}, {q(c['phone'])}, "
         f"'{c['dob'].strftime('%Y%m%d')}', {q(c['nat'])}, {q(c['segment'])}, {q(c['tier'])}, {pt}, "
         f"{int(c['opt_in'])}, {q(c['status'])}, {ts(c['created'])}, {ts(c['created'])})")
end_table("customer")

table("address")
aid = 0
for c in customers:
    aid += 1
    emit(f"insert address (address_id, customer_id, addr_type, line1, city, postcode, country, is_primary) values "
         f"({aid}, {c['id']}, 'HOME', {q(str(random.randint(1, 250)) + ' ' + random.choice(STREETS))}, "
         f"{q(c['city'])}, {q(str(random.randint(10000, 99999)))}, {q(c['country'])}, 1)")
    if random.random() < 0.3:
        aid += 1
        emit(f"insert address (address_id, customer_id, addr_type, line1, city, postcode, country, is_primary) values "
             f"({aid}, {c['id']}, 'BILLING', {q(str(random.randint(1, 250)) + ' ' + random.choice(STREETS))}, "
             f"{q(c['city'])}, {q(str(random.randint(10000, 99999)))}, {q(c['country'])}, 0)")
end_table("address")

table("orders")
for o in orders:
    emit(f"insert orders (order_id, customer_id, order_date, status, channel, total_amount, currency) values "
         f"({o[0]}, {o[1]}, {ts(o[2])}, {q(o[3])}, {q(o[4])}, {o[5]}, 'AED')")
end_table("orders")

table("order_item")
for i in items:
    emit(f"insert order_item (order_item_id, order_id, product_id, qty, unit_price, line_total) values "
         f"({i[0]}, {i[1]}, {i[2]}, {i[3]}, {i[4]}, {i[5]})")
end_table("order_item")

table("payment")
for p in payments:
    emit(f"insert payment (payment_id, order_id, method, amount, status, paid_at) values "
         f"({p[0]}, {p[1]}, {q(p[2])}, {p[3]}, {q(p[4])}, {ts(p[5])})")
end_table("payment")

table("interaction")
for i in inter:
    closed = ts(i[8]) if i[8] else "null"
    emit(f"insert interaction (interaction_id, customer_id, channel, type, subject, sentiment, status, opened_at, closed_at) "
         f"values ({i[0]}, {i[1]}, {q(i[2])}, {q(i[3])}, {q(i[4])}, {i[5]}, {q(i[6])}, {ts(i[7])}, {closed})")
end_table("interaction")

table("loyalty_txn")
for t in txns:
    emit(f"insert loyalty_txn (txn_id, customer_id, order_id, points, reason, txn_date) values "
         f"({t[0]}, {t[1]}, {t[2] if t[2] else 'null'}, {t[3]}, {q(t[4])}, {ts(t[5])})")
end_table("loyalty_txn", True)

with open("sql/02_seed.sql", "w") as f:
    f.write("\n".join(out) + "\n")
print(f"customers={len(customers)} orders={len(orders)} items={len(items)} payments={len(payments)} "
      f"interactions={len(inter)} loyalty_txns={len(txns)}")
