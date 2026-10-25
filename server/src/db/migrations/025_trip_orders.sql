-- Phase 9 part 2: orders booked ahead go out on a minibus or upcountry trip and are delivered at their stop.

-- The orders a trip carries. While the trip is planned their goods count as planned (no loading plan can take
-- them); at loading their reserved stock goes on the vehicle (order_lines.on_vehicle); at close what was not
-- delivered is set aside for the order again.
create table trip_orders (
  trip_id int not null references trips(id) on delete cascade,
  order_id int not null references orders(id),
  primary key (trip_id, order_id)
);
create index trip_orders_order on trip_orders (order_id);

-- A sale at a stop that delivers one of those orders, at the order's prices.
alter table trip_sales add column order_id int references orders(id);
