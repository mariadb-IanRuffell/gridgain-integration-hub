set pagesize 100 linesize 200 feedback off serveroutput on
col booking_ref format a8
col flight_no format a8
col segment_status format a10
col flight_status format a10
col detail format a40
col reason format a12
prompt === sp_customer_trips (customer 7)
var up refcursor
var pa refcursor
var st refcursor
exec sp_customer_trips(7, :up, :pa, :st)
print st
prompt --- upcoming
print up

prompt === sp_book_flight, then sold-out / bad input errors
declare
  v_fid number; v_id number; v_ref varchar2(6);
begin
  select min(flight_id) into v_fid from flight where status = 'SCHEDULED' and dep_time > sys_extract_utc(systimestamp) + interval '5' day;
  sp_book_flight(7, v_fid, 'BUSINESS', 'Test Passenger', 'APP', v_id, v_ref);
  dbms_output.put_line('booked flight ' || v_fid || ' -> booking ' || v_id || ' ref ' || v_ref);
  for r in (select seat_no, fare, miles_earned, status from booking_segment where booking_id = v_id) loop
    dbms_output.put_line('  segment seat ' || r.seat_no || ' fare ' || r.fare || ' miles ' || r.miles_earned || ' ' || r.status);
  end loop;
  begin sp_book_flight(7, v_fid, 'PREMIUM', 'X', 'WEB', v_id, v_ref); exception when others then dbms_output.put_line('invalid cabin  -> ' || sqlerrm); end;
  begin sp_book_flight(7, 999999, 'FIRST', 'X', 'WEB', v_id, v_ref); exception when others then dbms_output.put_line('unknown flight -> ' || sqlerrm); end;
  begin sp_book_flight(7, v_fid, 'FIRST', ' ', 'WEB', v_id, v_ref); exception when others then dbms_output.put_line('blank name     -> ' || sqlerrm); end;
end;
/
prompt === sp_cancel_booking (the booking just made) and a second attempt
declare v_id number; v_refund number;
begin
  select max(booking_id) into v_id from booking where customer_id = 7 and status = 'CONFIRMED';
  sp_cancel_booking(v_id, v_refund);
  dbms_output.put_line('cancelled booking ' || v_id || ', refund ' || v_refund);
  begin sp_cancel_booking(v_id, v_refund); exception when others then dbms_output.put_line('again -> ' || sqlerrm); end;
  begin sp_cancel_booking(0, v_refund); exception when others then dbms_output.put_line('unknown -> ' || sqlerrm); end;
end;
/
prompt === sp_record_delay / sp_cancel_flight
declare v_fid number; v_aff number;
begin
  select min(flight_id) into v_fid from flight where status = 'SCHEDULED' and dep_time > sys_extract_utc(systimestamp) + interval '10' day;
  sp_record_delay(v_fid, 400, 'smoke test', v_aff);
  dbms_output.put_line('flight ' || v_fid || ' delayed 400 min, affected bookings ' || v_aff);
  begin sp_record_delay(v_fid, 0, null, v_aff); exception when others then dbms_output.put_line('bad delay -> ' || sqlerrm); end;
  sp_cancel_flight(v_fid, 'smoke test', v_aff);
  dbms_output.put_line('flight ' || v_fid || ' cancelled, affected bookings ' || v_aff);
  begin sp_cancel_flight(v_fid, null, v_aff); exception when others then dbms_output.put_line('again -> ' || sqlerrm); end;
end;
/
prompt === sp_process_disruptions
var sm refcursor
exec sp_process_disruptions(180, null, :sm)
print sm
select count(*) as compensation_rows, sum(amount) as total from compensation;
