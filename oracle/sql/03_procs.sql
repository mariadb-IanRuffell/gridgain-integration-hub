-- PL/SQL procedures for the travel system (migration candidates for GridGain compute jobs).
-- Business errors use RAISE_APPLICATION_ERROR with these numbers (the GridGain jobs return the same ones):
--   20103 flight not found        20104 passenger name required   20105 invalid cabin      20106 flight not open for booking
--   20107 cabin sold out          20110 booking not found         20111 booking not active 20112 nothing to cancel
--   20120 delay out of range      20121 flight already operated or cancelled              20122 (cancel) same as 20121
set define off

create or replace function fn_miles_factor(p_cabin varchar2) return number deterministic is
begin
  return case p_cabin when 'FIRST' then 2 when 'BUSINESS' then 1.5 else 1 end;
end;
/
show errors

-- Seat label from the per-flight-cabin issue counter: rows start at 1 (first), 3 (business), 12 (economy), 6 seats per row.
create or replace function fn_seat_no(p_cabin varchar2, p_n number) return varchar2 deterministic is
  v_base number := case p_cabin when 'FIRST' then 1 when 'BUSINESS' then 3 else 12 end;
begin
  return to_char(v_base + floor((p_n - 1) / 6)) || substr('ABCDEF', mod(p_n - 1, 6) + 1, 1);
end;
/
show errors

-----------------------------------------------------------------------------
-- 1. sp_customer_trips: three cursors (upcoming, past, statistics) for one customer.
--    Pattern: multi-result read across booking / segment / flight.
-----------------------------------------------------------------------------
create or replace procedure sp_customer_trips(
  p_customer_id in number,
  p_upcoming    out sys_refcursor,
  p_past        out sys_refcursor,
  p_stats       out sys_refcursor) as
  v_now timestamp := sys_extract_utc(systimestamp);
begin
  open p_upcoming for
    select b.booking_ref, f.flight_no, f.origin, f.destination, f.dep_time, f.arr_time, s.cabin, s.seat_no,
           s.status as segment_status, f.status as flight_status, f.delay_minutes
      from booking b
      join booking_segment s on s.booking_id = b.booking_id
      join flight f          on f.flight_id  = s.flight_id
     where b.customer_id = p_customer_id and s.status <> 'CANCELLED' and f.dep_time >= v_now
     order by f.dep_time, s.segment_id
     fetch first 10 rows only;

  open p_past for
    select b.booking_ref, f.flight_no, f.origin, f.destination, f.dep_time, f.arr_time, s.cabin, s.seat_no,
           s.status as segment_status, f.status as flight_status, f.delay_minutes
      from booking b
      join booking_segment s on s.booking_id = b.booking_id
      join flight f          on f.flight_id  = s.flight_id
     where b.customer_id = p_customer_id and s.status <> 'CANCELLED' and f.dep_time < v_now
     order by f.dep_time desc, s.segment_id desc
     fetch first 10 rows only;

  open p_stats for
    select count(distinct case when b.status = 'CONFIRMED' then b.booking_id end) as bookings,
           count(distinct case when b.status = 'CANCELLED' then b.booking_id end) as cancelled_bookings,
           nvl(sum(case when s.status = 'FLOWN' then 1 else 0 end), 0)            as segments_flown,
           nvl(sum(case when s.status <> 'CANCELLED' then s.miles_earned else 0 end), 0) as total_miles,
           nvl(sum(case when s.status <> 'CANCELLED' then s.fare else 0 end), 0)  as total_spent,
           max(case when s.status = 'FLOWN' then f.dep_time end)                  as last_flown
      from booking b
      left join booking_segment s on s.booking_id = b.booking_id
      left join flight f          on f.flight_id  = s.flight_id
     where b.customer_id = p_customer_id;
end;
/
show errors

-----------------------------------------------------------------------------
-- 2. sp_book_flight: reserve one seat and create the booking and its segment in one transaction.
--    Pattern: guarded inventory decrement (UPDATE ... WHERE seats_available > 0), generated keys, error codes.
-----------------------------------------------------------------------------
create or replace procedure sp_book_flight(
  p_customer_id in  number,
  p_flight_id   in  number,
  p_cabin       in  varchar2,
  p_passenger   in  varchar2,
  p_channel     in  varchar2 default 'WEB',
  p_booking_id  out number,
  p_booking_ref out varchar2) as
  v_status flight.status%type;
  v_dep    flight.dep_time%type;
  v_km     flight.distance_km%type;
  v_fare   flight_inventory.base_fare%type;
  v_n      flight_inventory.next_seat%type;
begin
  if p_passenger is null or trim(p_passenger) is null then
    raise_application_error(-20104, 'Passenger name required');
  end if;
  if p_cabin is null or p_cabin not in ('ECONOMY', 'BUSINESS', 'FIRST') then
    raise_application_error(-20105, 'Invalid cabin');
  end if;
  begin
    select status, dep_time, distance_km into v_status, v_dep, v_km from flight where flight_id = p_flight_id;
  exception when no_data_found then
    raise_application_error(-20103, 'Flight not found');
  end;
  if v_status not in ('SCHEDULED', 'DELAYED') or v_dep <= sys_extract_utc(systimestamp) then
    raise_application_error(-20106, 'Flight is not open for booking');
  end if;

  update flight_inventory
     set seats_available = seats_available - 1, next_seat = next_seat + 1
   where flight_id = p_flight_id and cabin = p_cabin and seats_available > 0
   returning base_fare, next_seat - 1 into v_fare, v_n;
  if sql%rowcount = 0 then
    raise_application_error(-20107, 'Cabin sold out');
  end if;

  loop
    p_booking_ref := dbms_random.string('U', 6);
    begin
      insert into booking (booking_ref, customer_id, status, channel, total_fare)
      values (p_booking_ref, p_customer_id, 'CONFIRMED', nvl(p_channel, 'WEB'), v_fare)
      returning booking_id into p_booking_id;
      exit;
    exception when dup_val_on_index then null;   -- reference collision: draw another
    end;
  end loop;

  insert into booking_segment (booking_id, flight_id, cabin, seat_no, passenger_name, fare, status, miles_earned)
  values (p_booking_id, p_flight_id, p_cabin, fn_seat_no(p_cabin, v_n), p_passenger, v_fare, 'TICKETED',
          round(v_km * fn_miles_factor(p_cabin)));
  commit;
end;
/
show errors

-----------------------------------------------------------------------------
-- 3. sp_cancel_booking: cancel every future segment, release the seats, work out the refund.
--    Refund by time to departure: > 72 h 90%, > 24 h 50%, otherwise nothing.
-----------------------------------------------------------------------------
create or replace procedure sp_cancel_booking(
  p_booking_id in  number,
  p_refund     out number) as
  v_status booking.status%type;
  v_now    timestamp := sys_extract_utc(systimestamp);
  v_cnt    number := 0;
  v_hours  number;
  v_pct    number;
begin
  begin
    select status into v_status from booking where booking_id = p_booking_id for update;
  exception when no_data_found then
    raise_application_error(-20110, 'Booking not found');
  end;
  if v_status <> 'CONFIRMED' then
    raise_application_error(-20111, 'Booking is not active');
  end if;

  p_refund := 0;
  for s in (select bs.segment_id, bs.flight_id, bs.cabin, bs.fare, f.dep_time
              from booking_segment bs join flight f on f.flight_id = bs.flight_id
             where bs.booking_id = p_booking_id and bs.status in ('TICKETED', 'CHECKED_IN') and f.dep_time > v_now
             order by bs.segment_id) loop
    v_hours := (cast(s.dep_time as date) - cast(v_now as date)) * 24;
    v_pct := case when v_hours > 72 then 0.9 when v_hours > 24 then 0.5 else 0 end;
    p_refund := p_refund + round(s.fare * v_pct, 2);
    update booking_segment set status = 'CANCELLED' where segment_id = s.segment_id;
    update flight_inventory set seats_available = seats_available + 1 where flight_id = s.flight_id and cabin = s.cabin;
    v_cnt := v_cnt + 1;
  end loop;
  if v_cnt = 0 then
    raise_application_error(-20112, 'No cancellable segments');
  end if;

  update booking b
     set status = case when not exists (select 1 from booking_segment where booking_id = b.booking_id and status <> 'CANCELLED')
                       then 'CANCELLED' else b.status end,
         refunded = refunded + p_refund
   where booking_id = p_booking_id;
  commit;
end;
/
show errors

-----------------------------------------------------------------------------
-- 4. sp_record_delay / sp_cancel_flight: operational events on a flight; return the number of affected bookings.
-----------------------------------------------------------------------------
create or replace procedure sp_record_delay(
  p_flight_id in  number,
  p_minutes   in  number,
  p_reason    in  varchar2 default null,
  p_affected  out number) as
  v_status flight.status%type;
begin
  if p_minutes is null or p_minutes < 1 or p_minutes > 1440 then
    raise_application_error(-20120, 'Delay must be between 1 and 1440 minutes');
  end if;
  begin
    select status into v_status from flight where flight_id = p_flight_id for update;
  exception when no_data_found then
    raise_application_error(-20103, 'Flight not found');
  end;
  if v_status in ('DEPARTED', 'ARRIVED', 'CANCELLED') then
    raise_application_error(-20121, 'Flight has already operated or is cancelled');
  end if;
  update flight set status = 'DELAYED', delay_minutes = p_minutes where flight_id = p_flight_id;
  insert into flight_event (flight_id, event_type, detail)
  values (p_flight_id, 'DELAY', 'Delayed ' || p_minutes || ' min' || case when p_reason is not null then ': ' || p_reason end);
  select count(*) into p_affected
    from booking_segment bs join booking b on b.booking_id = bs.booking_id
   where bs.flight_id = p_flight_id and bs.status in ('TICKETED', 'CHECKED_IN') and b.status = 'CONFIRMED';
  commit;
end;
/
show errors

create or replace procedure sp_cancel_flight(
  p_flight_id in  number,
  p_reason    in  varchar2 default null,
  p_affected  out number) as
  v_status flight.status%type;
begin
  begin
    select status into v_status from flight where flight_id = p_flight_id for update;
  exception when no_data_found then
    raise_application_error(-20103, 'Flight not found');
  end;
  if v_status in ('DEPARTED', 'ARRIVED', 'CANCELLED') then
    raise_application_error(-20121, 'Flight has already operated or is cancelled');
  end if;
  update flight set status = 'CANCELLED' where flight_id = p_flight_id;
  insert into flight_event (flight_id, event_type, detail)
  values (p_flight_id, 'CANCEL', 'Flight cancelled' || case when p_reason is not null then ': ' || p_reason end);
  select count(*) into p_affected
    from booking_segment bs join booking b on b.booking_id = bs.booking_id
   where bs.flight_id = p_flight_id and bs.status in ('TICKETED', 'CHECKED_IN') and b.status = 'CONFIRMED';
  commit;
end;
/
show errors

-----------------------------------------------------------------------------
-- 5. sp_process_disruptions: batch. For every segment of a cancelled flight, or of a flight delayed by at least
--    p_min_delay minutes, work out compensation (cancelled 100%; delay >= 720 min 75%, >= 360 min 50%, otherwise 25%
--    of the fare) and upsert it into COMPENSATION. Returns a summary by reason.
--    Pattern: cursor loop + MERGE per row (ideal for distributed per-node compute).
-----------------------------------------------------------------------------
create or replace procedure sp_process_disruptions(
  p_min_delay in  number    default 180,
  p_as_of     in  timestamp default null,
  p_summary   out sys_refcursor) as
  v_as_of timestamp := nvl(p_as_of, sys_extract_utc(systimestamp));
  v_pct   number;
  v_reason varchar2(20);
begin
  for s in (select bs.segment_id, bs.fare, f.status as fstatus, f.delay_minutes
              from booking_segment bs
              join booking b on b.booking_id = bs.booking_id and b.status = 'CONFIRMED'
              join flight f  on f.flight_id  = bs.flight_id
             where bs.status in ('TICKETED', 'CHECKED_IN', 'FLOWN')
               and (f.status = 'CANCELLED' or (f.status in ('DELAYED', 'ARRIVED', 'DEPARTED') and f.delay_minutes >= p_min_delay))
             order by bs.segment_id) loop
    if s.fstatus = 'CANCELLED' then       v_pct := 1;    v_reason := 'CANCELLED';
    elsif s.delay_minutes >= 720 then     v_pct := 0.75; v_reason := 'DELAY_720';
    elsif s.delay_minutes >= 360 then     v_pct := 0.5;  v_reason := 'DELAY_360';
    else                                  v_pct := 0.25; v_reason := 'DELAY_SHORT';
    end if;
    merge into compensation c
    using (select s.segment_id as segment_id from dual) d on (c.segment_id = d.segment_id)
    when matched then update set c.amount = round(s.fare * v_pct, 2), c.reason = v_reason, c.created_at = v_as_of
    when not matched then insert (segment_id, amount, reason, created_at)
         values (s.segment_id, round(s.fare * v_pct, 2), v_reason, v_as_of);
  end loop;
  commit;

  open p_summary for
    select reason, count(*) as segments, sum(amount) as total_amount
      from compensation where created_at = v_as_of
     group by reason order by reason;
end;
/
show errors
