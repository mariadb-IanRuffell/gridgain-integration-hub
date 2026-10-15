-- GridGain 8.9 tables for the ORACLE travel/reservations system (the Sybase tables are in gridgain-schema.sql).
-- bookings, segments and compensation are colocated on customer_id, exactly like the Sybase customer data, so a join
-- between the two systems' data (customer x booking x segment) runs on one node with no data movement.
-- booking_segment and compensation carry a denormalised customer_id (resolved by the reader via booking).
-- airport, flight, flight_inventory and flight_event are small reference-like tables: replicated to every node.
-- TRANSACTIONAL with explicit key/value type names so compute jobs can update several tables atomically.

CREATE TABLE IF NOT EXISTS airport (
  iata CHAR(3) PRIMARY KEY,
  name VARCHAR(80), city VARCHAR(60), country CHAR(2), tz VARCHAR(40)
) WITH "template=replicated,cache_name=airport,atomicity=transactional,value_type=Airport";

CREATE TABLE IF NOT EXISTS flight (
  flight_id INT PRIMARY KEY,
  flight_no VARCHAR(8), origin CHAR(3), destination CHAR(3), dep_time TIMESTAMP, arr_time TIMESTAMP,
  distance_km INT, aircraft VARCHAR(10), status VARCHAR(10), delay_minutes INT
) WITH "template=replicated,cache_name=flight,atomicity=transactional,value_type=Flight";

CREATE TABLE IF NOT EXISTS flight_inventory (
  flight_id INT, cabin VARCHAR(10),
  seats_total INT, seats_available INT, next_seat INT, base_fare DECIMAL(10,2),
  PRIMARY KEY (flight_id, cabin)
) WITH "template=replicated,cache_name=flight_inventory,atomicity=transactional,key_type=FlightInventoryKey,value_type=FlightInventory";

CREATE TABLE IF NOT EXISTS booking (
  booking_id INT, customer_id INT,
  booking_ref CHAR(6), status VARCHAR(10), booked_at TIMESTAMP, channel VARCHAR(10),
  total_fare DECIMAL(12,2), refunded DECIMAL(12,2), currency CHAR(3),
  PRIMARY KEY (booking_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=booking,atomicity=transactional,key_type=BookingKey,value_type=Booking";

CREATE TABLE IF NOT EXISTS booking_segment (
  segment_id INT, customer_id INT,
  booking_id INT, flight_id INT, cabin VARCHAR(10), seat_no VARCHAR(4), passenger_name VARCHAR(80),
  fare DECIMAL(10,2), status VARCHAR(10), miles_earned INT,
  PRIMARY KEY (segment_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=booking_segment,atomicity=transactional,key_type=BookingSegmentKey,value_type=BookingSegment";

CREATE TABLE IF NOT EXISTS flight_event (
  event_id INT PRIMARY KEY,
  flight_id INT, event_type VARCHAR(12), detail VARCHAR(200), event_time TIMESTAMP
) WITH "template=replicated,cache_name=flight_event,atomicity=transactional,value_type=FlightEvent";

CREATE TABLE IF NOT EXISTS compensation (
  segment_id INT, customer_id INT,
  amount DECIMAL(10,2), reason VARCHAR(20), created_at TIMESTAMP,
  PRIMARY KEY (segment_id, customer_id)
) WITH "template=partitioned,backups=1,affinity_key=customer_id,cache_name=compensation,atomicity=transactional,key_type=CompensationKey,value_type=Compensation";
