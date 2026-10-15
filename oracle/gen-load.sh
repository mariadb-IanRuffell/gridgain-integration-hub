#!/usr/bin/env bash
# Generate a steady stream of real reservations in Oracle so CDC has something to replicate.
#   ./gen-load.sh [seconds=30] [bookings_per_second=5]
# Each booking is a sp_book_flight call (booking + segment + seat inventory + miles) for a random customer 1..250 on a random
# open flight; ~8% are cancelled again with sp_cancel_booking (refund + seat release). That is about 5-8 CDC events per
# booking. These are REAL changes: ./deploy.sh resets Oracle to the seed data. Watch it live at http://localhost:8080/
set -euo pipefail
cd "$(dirname "$0")"
SECS="${1:-30}"; RATE="${2:-5}"
# Pacing: rate >= 10/s -> every 100 ms do rate/10 bookings; slower -> one booking every 1000/rate ms.
if [ "$RATE" -ge 10 ]; then K=$(( RATE / 10 )); TICK_MS=100; else K=1; TICK_MS=$(( 1000 / RATE )); fi
ITER=$(( SECS * 1000 / TICK_MS )); TICK=$(awk "BEGIN{printf \"%.3f\", $TICK_MS/1000}")
echo "booking ~$(( K * 1000 / TICK_MS )) flights/s for ${SECS}s (~$(( K * 1000 / TICK_MS * SECS )) bookings)"
F=$(mktemp); trap 'rm -f "$F"' EXIT
cat > "$F" <<SQL
set serveroutput on feedback off
declare
  v_id number; v_ref varchar2(6); v_refund number; v_fid number; v_cust number; v_cab varchar2(10); r number;
  ok number := 0; fail number := 0; canc number := 0;
begin
  for i in 1..$ITER loop
    for j in 1..$K loop
      begin
        select flight_id into v_fid from (select flight_id from flight
                where status in ('SCHEDULED', 'DELAYED') and dep_time > sys_extract_utc(systimestamp) + interval '1' hour
                order by dbms_random.value) fetch first 1 rows only;
        r := dbms_random.value;
        v_cab := case when r < 0.8 then 'ECONOMY' when r < 0.97 then 'BUSINESS' else 'FIRST' end;
        v_cust := trunc(dbms_random.value(1, 251));
        sp_book_flight(v_cust, v_fid, v_cab, 'Load Test', 'APP', v_id, v_ref);
        ok := ok + 1;
        if dbms_random.value < 0.08 then sp_cancel_booking(v_id, v_refund); canc := canc + 1; end if;
      exception when others then fail := fail + 1;
      end;
    end loop;
    dbms_session.sleep($TICK);
  end loop;
  dbms_output.put_line('booked=' || ok || ' cancelled=' || canc || ' failed=' || fail);
end;
/
SQL
./sqlplus.sh -f "$F" | grep -E "booked=|ORA-|PLS-"
echo done
