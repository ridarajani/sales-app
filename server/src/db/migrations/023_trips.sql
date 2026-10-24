-- Phase 9: minibus routes and upcountry trips. The vehicle is loaded with stock that no customer has ordered yet;
-- the trip leader sells at each stop, makes the invoice there and takes the money, then closes the trip with
-- what came back and the cash counted.

-- One trip from one facility. Numbered in one series for both companies of the facility (TRP-000001).
-- planned: being made. out: loaded and on the road (gate pass made). closed: stock and cash counted.
create table trips (
  id serial primary key,
  number text not null unique,
  kind text not null check (kind in ('minibus', 'upcountry')),
  facility_code text not null references facilities(code),
  -- The company the float and the trip expenses belong to.
  company_code text not null references companies(code),
  trip_date date not null,
  route text not null check (route <> ''),
  stops text[] not null default '{}',
  vehicle_id int references vehicles(id),
  driver_id int references users(id),
  -- The salesman or sales manager who sells, holds the cash and closes the trip.
  leader_id int not null references users(id),
  staff text not null default '',
  note text not null default '',
  -- Cash given for fuel, lodging and so on. It is held by the leader from loading until handed to the cashier.
  float_amount bigint not null default 0 check (float_amount >= 0),
  float_custody text not null default 'na' check (float_custody in ('with', 'handed', 'counted', 'na')),
  float_handover_id int references handovers(id),
  status text not null default 'planned' check (status in ('planned', 'out', 'closed')),
  gate_pass_no text unique,
  loaded_at timestamptz,
  loaded_by int references users(id),
  -- Filled at close: cash sales + float - cash expenses, what was counted and the difference.
  cash_expected bigint,
  cash_counted bigint,
  cash_difference bigint,
  close_note text not null default '',
  closed_at timestamptz,
  closed_by int references users(id),
  created_by int not null references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index trips_status on trips (status);
create index trips_leader on trips (leader_id);

-- Products on the vehicle, in pieces. planned is asked for; loaded is what went on. At close, returned is what
-- came back and missing what was neither sold nor brought back.
create table trip_lines (
  id serial primary key,
  trip_id int not null references trips(id) on delete cascade,
  product_id int not null references products(id),
  planned int not null check (planned >= 0),
  loaded int not null default 0 check (loaded >= 0),
  sold int not null default 0 check (sold >= 0),
  returned int,
  missing int,
  unique (trip_id, product_id),
  check (sold <= loaded)
);

-- Which batches each product was loaded from and how much of each is sold. Stock sits in in_transit until sold.
create table trip_line_batches (
  line_id int not null references trip_lines(id) on delete cascade,
  batch_id int not null references stock_batches(id),
  pieces int not null check (pieces > 0),
  sold int not null default 0 check (sold >= 0 and sold <= pieces),
  primary key (line_id, batch_id)
);

-- One sale at a stop: one customer, signed for, paid in cash or Airtel Money or put on account.
-- It makes one invoice per company. sold_at is the phone's time, since a sale may be made offline and sent later.
create table trip_sales (
  id serial primary key,
  trip_id int not null references trips(id),
  customer_id int not null references customers(id),
  stop text not null default '',
  payment text not null check (payment in ('cash', 'airtel', 'credit')),
  reference text not null default '',
  total bigint not null check (total >= 0),
  received_by text not null check (received_by <> ''),
  -- File names in the uploads folder; never in the repo.
  signature_file text not null,
  proof_file text,
  sold_at timestamptz not null,
  created_by int not null references users(id),
  created_at timestamptz not null default now(),
  -- One id per sale on the phone, so a sale sent twice (a retry, or a sync after being offline) is saved once.
  request_id text not null,
  unique (created_by, request_id)
);
create index trip_sales_trip on trip_sales (trip_id);

-- A trip invoice has no order behind it.
alter table invoices add column trip_sale_id int references trip_sales(id);
create index invoices_trip_sale on invoices (trip_sale_id);
alter table invoice_lines alter column order_line_id drop not null;
alter table invoice_lines add constraint invoice_lines_source_check check (order_line_id is not null or plan_item_id is null);

-- The payment taken with a trip sale, and the expenses paid on a trip.
alter table collections add column trip_sale_id int references trip_sales(id);
alter table expenses add column trip_id int references trips(id);
create index expenses_trip on expenses (trip_id);

-- Stock sold from the vehicle, and stock missing at close.
alter table stock_movements add column trip_id int references trips(id);
alter table stock_movements add column trip_sale_id int references trip_sales(id);

-- The float left over is part of the cash handed to the cashier.
alter table handovers add column floats bigint not null default 0 check (floats >= 0);
