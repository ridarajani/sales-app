-- Phase 5: loading plans. One plan is one vehicle trip from one facility, carrying many customers' orders.

create table loading_plans (
  id serial primary key,
  number text not null unique,
  facility_code text not null references facilities(code),
  vehicle_id int references vehicles(id),
  driver_id int references users(id),
  -- planned: being made, or sent to the warehouse (sent_at set). loaded: stock has left the shelves,
  -- delivery note and gate pass made. out: the vehicle left the gate. done: every drop confirmed (Phase 6).
  status text not null default 'planned' check (status in ('planned', 'loaded', 'out', 'done')),
  note text not null default '',
  sent_at timestamptz,
  delivery_note_no text unique,
  gate_pass_no text unique,
  loaded_at timestamptz,
  loaded_by int references users(id),
  out_at timestamptz,
  out_by int references users(id),
  created_by int not null references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index loading_plans_status on loading_plans (status);

-- Order lines on a plan. pieces is what is planned; loaded is what the warehouse put on the vehicle.
create table loading_plan_items (
  id serial primary key,
  plan_id int not null references loading_plans(id) on delete cascade,
  order_line_id int not null references order_lines(id),
  pieces int not null check (pieces > 0),
  loaded int not null default 0 check (loaded >= 0),
  unique (plan_id, order_line_id)
);
create index loading_plan_items_line on loading_plan_items (order_line_id);

-- Which batches each loaded item came from, for the delivery note and for returns.
create table loading_plan_item_batches (
  item_id int not null references loading_plan_items(id) on delete cascade,
  batch_id int not null references stock_batches(id),
  pieces int not null check (pieces > 0),
  primary key (item_id, batch_id)
);

-- Every change to a plan's quantities. Changes by the other side after sending show as new until seen.
create table loading_plan_changes (
  id bigserial primary key,
  plan_id int not null references loading_plans(id) on delete cascade,
  user_id int not null references users(id),
  by_warehouse boolean not null default false,
  product_id int not null references products(id),
  order_line_id int references order_lines(id),
  before int not null,
  after int not null,
  reason text not null default '',
  seen boolean not null default true,
  created_at timestamptz not null default now()
);
create index loading_plan_changes_plan on loading_plan_changes (plan_id, id);

-- Pieces loaded on a vehicle and not yet delivered or brought back.
alter table order_lines add column on_vehicle int not null default 0 check (on_vehicle >= 0);
alter table order_lines drop constraint order_lines_check;
alter table order_lines add constraint order_lines_check check (delivered >= 0 and delivered + on_vehicle <= pieces);

-- Loading does not reduce stock (client, 7 Oct): loaded pieces stay in on_hand as in transit until the
-- delivery is confirmed (Phase 6). What is on the shelf is on_hand - in_transit.
alter table stock_batches add column in_transit int not null default 0 check (in_transit >= 0);
alter table stock_batches add constraint stock_batches_in_transit_on_hand_check check (in_transit <= on_hand);
