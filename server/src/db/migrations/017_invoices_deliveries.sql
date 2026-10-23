-- Phase 6: invoices and delivery.

-- One invoice per customer, company, LPO and invoice type on a loaded vehicle, made before delivery.
-- Numbered per company (LV-INV-000001); VAT and plain invoices share the series.
create table invoices (
  id serial primary key,
  number text not null unique,
  company_code text not null references companies(code),
  customer_id int not null references customers(id),
  invoice_type text not null check (invoice_type in ('vat', 'plain')),
  plan_id int references loading_plans(id),
  lpo_no text not null default '',
  invoice_date date not null,
  due_date date not null,
  -- Whole kwacha. vat_rate is 0 on a plain invoice.
  subtotal bigint not null check (subtotal >= 0),
  vat_rate numeric(5,2) not null default 0,
  vat bigint not null check (vat >= 0),
  total bigint not null check (total >= 0),
  salesman_id int references users(id),
  created_by int not null references users(id),
  created_at timestamptz not null default now()
);
create index invoices_customer on invoices (customer_id, company_code);
create index invoices_plan on invoices (plan_id);

create table invoice_lines (
  id serial primary key,
  invoice_id int not null references invoices(id) on delete cascade,
  order_line_id int not null references order_lines(id),
  plan_item_id int references loading_plan_items(id),
  product_id int not null references products(id),
  pieces int not null check (pieces > 0),
  price_per_carton int not null check (price_per_carton > 0),
  amount bigint not null check (amount >= 0)
);
create index invoice_lines_invoice on invoice_lines (invoice_id);
create unique index invoice_lines_plan_item on invoice_lines (plan_item_id) where plan_item_id is not null;

-- The driver's confirmation for one customer on one vehicle: who received it and their signature.
create table deliveries (
  id serial primary key,
  plan_id int not null references loading_plans(id),
  customer_id int not null references customers(id),
  received_by text not null check (received_by <> ''),
  -- File names in the uploads folder; never in the repo.
  signature_file text not null,
  photo_file text,
  note text not null default '',
  confirmed_by int not null references users(id),
  confirmed_at timestamptz not null default now(),
  unique (plan_id, customer_id)
);

-- What was delivered of each loaded item, and why less when it was less. Empty until the drop is confirmed.
alter table loading_plan_items add column delivered int check (delivered >= 0 and delivered <= loaded);
alter table loading_plan_items add column short_reason text;
alter table loading_plan_items add column delivery_id int references deliveries(id);

-- Delivery takes the stock off the books (client, 7 Oct).
alter table stock_movements drop constraint stock_movements_kind_check;
alter table stock_movements add constraint stock_movements_kind_check check (kind in ('received', 'adjusted', 'counted', 'delivered'));
alter table stock_movements add column delivery_id int references deliveries(id);
