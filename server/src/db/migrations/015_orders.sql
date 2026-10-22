-- Phase 4: orders. One order is for one customer and the companies of one facility.

-- Numbers shared by all companies, for example SO-000123 for orders.
create table shared_number_series (
  doc_type text primary key check (doc_type ~ '^[A-Z]{2,4}$'),
  last_number int not null default 0
);

-- The salesman picks a VAT or a plain invoice on each order; the app offers the customer's last choice next time.
alter table customers add column last_invoice_type text check (last_invoice_type in ('vat', 'plain'));

create table orders (
  id serial primary key,
  number text not null unique,
  facility_code text not null references facilities(code),
  customer_id int not null references customers(id),
  salesman_id int references users(id),
  invoice_type text not null check (invoice_type in ('vat', 'plain')),
  wanted_on date,
  note text not null default '',
  -- open: confirmed, stock reserved. hold: stays reserved but cannot be loaded.
  -- done: closed by a manager. cancelled: reservation released. Later phases add loading states.
  status text not null default 'open' check (status in ('open', 'hold', 'done', 'cancelled')),
  booked_by int not null references users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index orders_customer on orders (customer_id);
create index orders_status on orders (status);
create index orders_salesman on orders (salesman_id);

-- The companies on an order, each with the customer's LPO number for that company.
create table order_companies (
  order_id int not null references orders(id) on delete cascade,
  company_code text not null references companies(code),
  lpo_no text not null default '',
  primary key (order_id, company_code)
);

-- Quantities are pieces, like stock. The price is typed per carton, in whole kwacha, without VAT.
create table order_lines (
  id serial primary key,
  order_id int not null references orders(id) on delete cascade,
  product_id int not null references products(id),
  pieces int not null check (pieces >= 0),
  price_per_carton int not null check (price_per_carton > 0),
  delivered int not null default 0 check (delivered >= 0 and delivered <= pieces),
  unique (order_id, product_id)
);

-- Stock set aside for an order line, per batch, oldest expiry first. Free stock = on_hand - reserved.
create table order_allocations (
  line_id int not null references order_lines(id) on delete cascade,
  batch_id int not null references stock_batches(id),
  pieces int not null check (pieces > 0),
  primary key (line_id, batch_id)
);
create index order_allocations_batch on order_allocations (batch_id);

-- What happened to each order, shown as its history.
create table order_events (
  id bigserial primary key,
  order_id int not null references orders(id) on delete cascade,
  user_id int not null references users(id),
  title text not null,
  detail text not null default '',
  created_at timestamptz not null default now()
);
create index order_events_order on order_events (order_id, id);
