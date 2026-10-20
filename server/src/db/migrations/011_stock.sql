-- Stock is kept per batch. A product's batches sit in the facility of its company.
-- Quantities are pieces (the unit the product is sold in); cartons are worked out from pieces_per_carton.
create table stock_batches (
  id serial primary key,
  product_id int not null references products(id),
  batch_no text not null check (batch_no <> ''),
  expiry_date date not null,
  on_hand int not null default 0 check (on_hand >= 0),
  created_at timestamptz not null default now(),
  unique (product_id, batch_no)
);
create index stock_batches_expiry on stock_batches (expiry_date) where on_hand > 0;

-- One delivery of goods into the warehouse, for one company.
create table stock_receipts (
  id serial primary key,
  number text not null unique,
  company_code text not null references companies(code),
  supplier text not null default '',
  reference text not null default '',
  received_on date not null,
  note text not null default '',
  received_by int not null references users(id),
  created_at timestamptz not null default now()
);

-- One stock count of a company's batches.
create table stock_counts (
  id serial primary key,
  number text not null unique,
  company_code text not null references companies(code),
  note text not null default '',
  counted_by int not null references users(id),
  created_at timestamptz not null default now()
);

-- Every change to on_hand, so the history of each batch can be shown.
create table stock_movements (
  id bigserial primary key,
  batch_id int not null references stock_batches(id),
  change int not null check (change <> 0),
  kind text not null check (kind in ('received', 'adjusted', 'counted')),
  reason text,
  note text not null default '',
  receipt_id int references stock_receipts(id),
  count_id int references stock_counts(id),
  user_id int not null references users(id),
  created_at timestamptz not null default now()
);
create index stock_movements_batch on stock_movements (batch_id, created_at);
