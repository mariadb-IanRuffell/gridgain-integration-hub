#!/usr/bin/env bash
# End-to-end test of the running API: full CRUD lifecycles, validation and business-rule errors, and the
# compute-job operations.   ./smoke-test.sh [base-url]
# It creates rows (customers/products/an order, a flight with bookings) that stay in GridGain; `../cdc/run.sh init` resets GridGain to Sybase.
set -uo pipefail
BASE="${1:-http://localhost:8080}/api/v1"
PASS=0; FAIL=0; TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
RUN="$(date +%s)"

req() {  # req METHOD PATH [JSON]  -> sets STATUS, BODY, LOCATION
  local m="$1" p="$2" d="${3:-}"
  if [ -n "$d" ]; then
    STATUS=$(curl -s -o "$TMP" -D "$TMP.h" -w '%{http_code}' -X "$m" -H 'Content-Type: application/json' -d "$d" "$BASE$p")
  else
    STATUS=$(curl -s -o "$TMP" -D "$TMP.h" -w '%{http_code}' -X "$m" "$BASE$p")
  fi
  BODY="$(cat "$TMP")"; LOCATION="$(grep -i '^location:' "$TMP.h" 2>/dev/null | tr -d '\r' | awk '{print $2}')"
}
ok()   { PASS=$((PASS+1)); echo "  PASS $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1  (status=$STATUS body=${BODY:0:200})"; }
status() { [ "$STATUS" = "$1" ] && ok "$2" || bad "$2 (expected $1)"; }
field()  { [ "$(echo "$BODY" | jq -r "$1")" = "$2" ] && ok "$3" || { bad "$3 (expected $2, got $(echo "$BODY" | jq -r "$1"))"; }; }

echo "== customers"
req POST /customers "{\"firstName\":\"Smoke\",\"lastName\":\"Test$RUN\",\"email\":\"smoke$RUN@example.com\",\"phone\":\"+971500000000\",\"nationality\":\"AE\",\"segment\":\"PREMIUM\",\"marketingOptIn\":true}"
status 201 "create customer"; CID=$(echo "$BODY" | jq -r .customerId)
field .loyaltyTier BLUE "new customer starts at BLUE"; field .loyaltyPoints 0 "new customer has 0 points"; field .status ACTIVE "status defaults to ACTIVE"
[ -n "$LOCATION" ] && ok "Location header set" || bad "Location header"
req GET "/customers/$CID"; status 200 "get customer"; field .email "smoke$RUN@example.com" "email stored"
req POST /customers "{\"firstName\":\"Dup\",\"lastName\":\"Mail\",\"email\":\"SMOKE$RUN@example.com\"}"; status 409 "duplicate email (case-insensitive) rejected"
req POST /customers '{"lastName":"NoFirst","email":"not-an-email"}'; status 400 "invalid body rejected"
req PUT "/customers/$CID" "{\"firstName\":\"Smoked\",\"lastName\":\"Test$RUN\",\"email\":\"smoke$RUN@example.com\",\"segment\":\"CORPORATE\"}"
status 200 "update customer"; field .firstName Smoked "firstName updated"; field .segment CORPORATE "segment updated"; field .phone null "omitted optional field cleared (PUT)"
req GET "/customers?q=test$RUN"; status 200 "search by name"; field .total 1 "search finds exactly the new customer"
req GET "/customers?limit=0"; status 400 "limit validation"
req GET "/customers?status=ACTIVE&tier=string"; status 400 "invalid enum filter is a 400, not a silent empty page"
field '.detail | contains("Allowed values: BLUE, SILVER, GOLD, PLATINUM")' true "error names the allowed values"
req GET "/customers?status=ACTIVE&tier=&email=&q=&limit=5"; status 200 "blank filters are ignored"; field '.total > 100' true "blank filters match everything"
req GET "/customers?tier=GOLD&limit=3"; field '[.items[].loyaltyTier] | unique | join(",")' GOLD "tier filter returns only that tier"
req GET "/products?category=CARS"; status 400 "invalid product category is a 400"
req GET "/orders?status=PENDING"; status 400 "invalid order status is a 400"
req GET /customers/999999999; status 404 "unknown customer is 404"

echo "== addresses"
req POST "/customers/$CID/addresses" '{"addrType":"HOME","line1":"1 Palm Road","city":"Dubai","postcode":"00000","country":"AE","primary":true}'
status 201 "create address"; A1=$(echo "$BODY" | jq -r .addressId)
req POST "/customers/$CID/addresses" '{"addrType":"WORK","line1":"2 Marina Walk","city":"Dubai","country":"AE","primary":true}'
status 201 "create second primary address"; A2=$(echo "$BODY" | jq -r .addressId)
req GET "/customers/$CID/addresses"; field length 2 "two addresses listed"; field '[.[]|select(.primary==true)]|length' 1 "only one primary address"
req PUT "/customers/$CID/addresses/$A1" '{"addrType":"BILLING","line1":"1 Palm Road","city":"Abu Dhabi","country":"AE","primary":false}'
status 200 "update address"; field .city "Abu Dhabi" "city updated"
req DELETE "/customers/$CID/addresses/$A2"; status 204 "delete address"
req GET "/customers/$CID/addresses/$A2"; status 404 "deleted address is 404"
req POST "/customers/999999999/addresses" '{"addrType":"HOME","line1":"x","city":"y","country":"AE"}'; status 404 "address for unknown customer is 404"

echo "== interactions"
req POST "/customers/$CID/interactions" '{"channel":"CHAT","type":"ENQUIRY","subject":"Baggage allowance","sentiment":0}'
status 201 "create interaction"; I1=$(echo "$BODY" | jq -r .interactionId); field .status OPEN "starts OPEN"
req PUT "/customers/$CID/interactions/$I1" '{"channel":"CHAT","type":"ENQUIRY","subject":"Baggage allowance","sentiment":1,"status":"CLOSED"}'
status 200 "close interaction"; field '.closedAt != null' true "closedAt stamped on close"
req PUT "/customers/$CID/interactions/$I1" '{"channel":"CHAT","type":"ENQUIRY","subject":"Baggage allowance","sentiment":1,"status":"OPEN"}'
field .closedAt null "closedAt cleared on re-open"
req GET "/customers/$CID/interactions?status=OPEN"; field .total 1 "list filtered by status"
req POST "/customers/$CID/interactions" '{"channel":"FAX","type":"ENQUIRY"}'; status 400 "invalid channel rejected"
req DELETE "/customers/$CID/interactions/$I1"; status 204 "delete interaction"

echo "== products"
req POST /products "{\"sku\":\"SMK-$RUN\",\"name\":\"Smoke Fare\",\"category\":\"FLIGHT\",\"unitPrice\":100.00,\"stockQty\":5}"
status 201 "create product"; PID=$(echo "$BODY" | jq -r .productId); field .active true "active defaults to true"
req POST /products "{\"sku\":\"SMK-$RUN\",\"name\":\"Other\",\"category\":\"FLIGHT\",\"unitPrice\":1,\"stockQty\":1}"; status 409 "duplicate SKU rejected"
req POST /products '{"sku":"BAD","name":"Bad","category":"CARS","unitPrice":-1,"stockQty":1}'; status 400 "invalid product rejected"
req GET "/products?category=FLIGHT&limit=3"; status 200 "list products by category"
req DELETE /products/4; status 409 "product on orders cannot be deleted"

echo "== orders (place-order compute job)"
req POST /orders "{\"customerId\":$CID,\"channel\":\"APP\",\"paymentMethod\":\"CARD\",\"items\":[{\"productId\":$PID,\"quantity\":2}]}"
status 201 "place order"; OID=$(echo "$BODY" | jq -r .orderId)
field '.totalAmount == 200' true "total = 2 x 100"; field .points 200 "points at BLUE x1.0"; field .tierAfter BLUE "tier unchanged"
req GET "/orders/$OID"; status 200 "get order detail"; field '.items|length' 1 "one line item"; field '.payments|length' 1 "one payment"; field .status COMPLETED "order completed"
req GET "/products/$PID"; field .stockQty 3 "stock decremented by the job"
req GET "/customers/$CID"; field .loyaltyPoints 200 "customer points updated"
req GET "/customers/$CID/loyalty-transactions"; field .total 1 "points ledger entry written"
req POST /orders "{\"customerId\":$CID,\"items\":[{\"productId\":$PID,\"quantity\":10}]}"; status 409 "insufficient stock is 409"
req GET "/products/$PID"; field .stockQty 3 "failed order leaves stock untouched"
req POST /orders "{\"customerId\":$CID,\"items\":[{\"productId\":$PID,\"quantity\":0}]}"; status 400 "zero quantity rejected"
req POST /orders "{\"customerId\":$CID,\"items\":[{\"productId\":999999999,\"quantity\":1}]}"; status 400 "unknown product rejected"
req POST /orders "{\"customerId\":999999999,\"items\":[{\"productId\":$PID,\"quantity\":1}]}"; status 404 "unknown customer is 404"
req POST /orders "{\"customerId\":$CID,\"items\":[]}"; status 400 "empty order rejected"
req PUT "/orders/$OID" '{"channel":"CALL"}'; status 200 "change channel"; field .channel CALL "channel updated"
req GET "/orders?customerId=$CID"; field .total 1 "list orders by customer"
req DELETE "/orders/$OID"; status 409 "completed order cannot be deleted"
req DELETE "/customers/$CID"; status 409 "customer with orders cannot be deleted"
req DELETE "/products/$PID"; status 409 "product now on an order cannot be deleted"

echo "== operations (compute jobs)"
req GET "/customers/$CID/360"; status 200 "customer 360"
field '.profile[0].customer_id' "$CID" "360 profile is this customer"; field '.order_summary[0].order_count' 1 "360 order summary"; field '.loyalty[0].next_tier' SILVER "360 next tier"
req GET /customers/999999999/360; status 404 "360 for unknown customer is 404"
req POST "/customers/$CID/loyalty/recalculate"; status 200 "recalculate tier"; field .new_tier BLUE "tier stays BLUE at 200 spend"
req POST "/scoring/run"; status 200 "run scoring"; field 'map(.customers)|add > 200' true "scored all active customers"
req GET "/customers/$CID/score"; status 200 "score available after scoring"
req GET "/loyalty-tiers"; field length 4 "four loyalty tiers"

echo "== travel: airports and flights (Oracle data)"
req GET /airports; status 200 "list airports"; field length 18 "18 airports"
req GET /airports/DXB; status 200 "get airport"; field .city Dubai "DXB is Dubai"
req GET /airports/ZZZ; status 404 "unknown airport is 404"
req GET "/flights?origin=DXB&destination=LHR&limit=3"; status 200 "filter flights by route"; field '.items | length > 0' true "route has flights"
req GET "/flights?status=BOGUS"; status 400 "invalid flight status is a 400"
DEP=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=30)).strftime('%Y-%m-%dT10:00:00Z'))")
ARR=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=30)).strftime('%Y-%m-%dT14:00:00Z'))")
FNO="XS$(echo $RUN | tail -c 5)"
req POST /flights "{\"flightNo\":\"$FNO\",\"origin\":\"DXB\",\"destination\":\"LHR\",\"depTime\":\"$DEP\",\"arrTime\":\"$ARR\",\"distanceKm\":1000,\"aircraft\":\"A380\",\"inventory\":[{\"cabin\":\"ECONOMY\",\"seats\":2,\"baseFare\":500},{\"cabin\":\"BUSINESS\",\"seats\":1,\"baseFare\":1500}]}"
status 201 "create flight"; FID=$(echo "$BODY" | jq -r .flightId); field .status SCHEDULED "new flight is SCHEDULED"; field '.inventory | length' 2 "two cabins of inventory"
req POST /flights "{\"flightNo\":\"BAD\",\"origin\":\"DXB\",\"destination\":\"LHR\",\"depTime\":\"$ARR\",\"arrTime\":\"$DEP\",\"distanceKm\":1,\"inventory\":[{\"cabin\":\"ECONOMY\",\"seats\":1,\"baseFare\":1}]}"; status 400 "arrival before departure rejected"
req POST /flights "{\"flightNo\":\"BAD\",\"origin\":\"DXB\",\"destination\":\"QQQ\",\"depTime\":\"$DEP\",\"arrTime\":\"$ARR\",\"distanceKm\":1,\"inventory\":[{\"cabin\":\"ECONOMY\",\"seats\":1,\"baseFare\":1}]}"; status 400 "unknown destination airport rejected"
req PUT "/flights/$FID" "{\"flightNo\":\"$FNO\",\"origin\":\"DXB\",\"destination\":\"LHR\",\"depTime\":\"$DEP\",\"arrTime\":\"$ARR\",\"distanceKm\":1000,\"aircraft\":\"B777\"}"; status 200 "update flight"; field .aircraft B777 "aircraft changed"

echo "== bookings (book-flight and cancel-booking compute jobs)"
req POST /bookings "{\"customerId\":$CID,\"flightId\":$FID,\"cabin\":\"BUSINESS\",\"passenger\":\"Smoke Passenger\",\"channel\":\"APP\"}"
status 201 "book a business seat"; BID=$(echo "$BODY" | jq -r .bookingId); field .seatNo 3A "first business seat is 3A"; field '.fare == 1500' true "fare = base fare"; field .milesEarned 1500 "miles = 1000 km x 1.5"
req POST /bookings "{\"customerId\":$CID,\"flightId\":$FID,\"cabin\":\"BUSINESS\",\"passenger\":\"Second Passenger\"}"; status 409 "business cabin sold out is 409"
req POST /bookings "{\"customerId\":$CID,\"flightId\":$FID,\"cabin\":\"ECONOMY\",\"passenger\":\"Eco One\"}"; status 201 "book economy seat 1"; field .seatNo 12A "first economy seat is 12A"
req POST /bookings "{\"customerId\":$CID,\"flightId\":$FID,\"cabin\":\"ECONOMY\",\"passenger\":\"Eco Two\"}"; status 201 "book economy seat 2"; field .seatNo 12B "second economy seat is 12B"
req POST /bookings "{\"customerId\":$CID,\"flightId\":$FID,\"cabin\":\"ECONOMY\",\"passenger\":\"Eco Three\"}"; status 409 "economy sold out is 409"
req POST /bookings "{\"customerId\":$CID,\"flightId\":$FID,\"cabin\":\"PREMIUM\",\"passenger\":\"X\"}"; status 400 "invalid cabin rejected"
req POST /bookings "{\"customerId\":$CID,\"flightId\":999999999,\"cabin\":\"ECONOMY\",\"passenger\":\"X\"}"; status 404 "unknown flight is 404"
req GET "/bookings/$BID"; status 200 "get booking"; field '.segments | length' 1 "one segment"; field .status CONFIRMED "booking confirmed"
req GET "/bookings?customerId=$CID"; field '.total >= 3' true "customer has at least 3 bookings"
req POST "/bookings/$BID/cancel"; status 200 "cancel the business booking"; field '.refund == 1350' true "refund = 90% of 1500 (departure > 72 h away)"; field .bookingStatus CANCELLED "booking cancelled"
req POST "/bookings/$BID/cancel"; status 409 "cancelling twice is 409"
req POST /bookings/999999999/cancel; status 404 "cancel unknown booking is 404"
req GET "/flights/$FID"; field '.inventory[] | select(.cabin=="BUSINESS") | .seatsAvailable' 1 "business seat released"

echo "== flight operations, disruption processing and the hub (Oracle x Sybase)"
req POST "/flights/$FID/delay" '{"minutes":400,"reason":"smoke test"}'; status 200 "record a delay"; field .status DELAYED "flight DELAYED"; field .affectedBookings 2 "two bookings affected"
req POST "/flights/$FID/delay" '{"minutes":0}'; status 400 "delay out of range rejected"
req GET "/flights/$FID/events"; field length 1 "one flight event"; field '.[0].eventType' DELAY "event is a DELAY"
req GET "/flights/$FID/impact"; status 200 "disruption impact (hub)"; field '.passengers | length' 2 "two affected passengers"
field '.passengers[0].loyalty_tier != null' true "passengers carry the Sybase loyalty tier"; field '.total_base_compensation == 500' true "base compensation = 2 x 50% of 500"
req POST "/disruptions/process?minDelay=180"; status 200 "process disruptions"; field '[.[] | select(.reason=="DELAY_360")] | length' 1 "a DELAY_360 group exists (400 min delay)"
req GET "/compensation?flightId=$FID"; field .total 2 "two compensation rows for the flight"; field '.items[0].amount == 250' true "50% of the 500 economy fare"
req GET "/customers/$CID/trips"; status 200 "customer trips"; field '.upcoming | length >= 2' true "upcoming trips include the new bookings"
req GET "/customers/$CID/hub"; status 200 "customer hub view"; field '.profile[0].customer_id' "$CID" "hub profile is this customer (Sybase)"
field '.travel[0].bookings >= 2' true "hub has the customer's bookings (Oracle)"; field '.at_risk_trips | length >= 2' true "hub flags trips on the delayed flight"
field '.value[0].lifetime_value == (.value[0].retail_spend + .value[0].travel_spend)' true "lifetime value = retail spend + travel spend"
req GET /customers/999999999/hub; status 404 "hub for unknown customer is 404"
req GET /customers/999999999/trips; status 404 "trips for unknown customer is 404"
req POST "/flights/$FID/cancel" '{"reason":"smoke test"}'; status 200 "cancel the flight"; field .status CANCELLED "flight CANCELLED"
req POST "/flights/$FID/cancel" '{"reason":"again"}'; status 409 "cancelling a cancelled flight is 409"
req DELETE "/flights/$FID"; status 409 "flight with bookings cannot be deleted"

echo "== cleanup: customer without orders can be deleted"
req POST /customers "{\"firstName\":\"Temp\",\"lastName\":\"Delete$RUN\",\"email\":\"temp$RUN@example.com\"}"; T=$(echo "$BODY" | jq -r .customerId)
req POST "/customers/$T/addresses" '{"addrType":"HOME","line1":"x","city":"y","country":"AE"}'
req DELETE "/customers/$T"; status 204 "delete customer without orders"
req GET "/customers/$T"; status 404 "deleted customer is gone"
req GET "/customers/$T/addresses"; status 404 "its addresses are gone too"

echo; echo "$([ $FAIL -eq 0 ] && echo SMOKE TEST OK || echo SMOKE TEST FAILED): $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
