-- Phase 8: sales returns, credit and debit notes.

-- Goods that came back, booked against their invoice and numbered per company (LV-SR-000001).
-- 'delivery': not taken at delivery. Made by the server when a drop is confirmed short; the goods never left the
--   vehicle, so they are back on the shelf already. Sales books it to take it off the invoice.
-- 'after': given back after delivery. Booked when it is saved; the warehouse ticks when the goods are back.
create table sales_returns (
  id serial primary key,
  number text not null unique,
  company_code text not null references companies(code),
  customer_id int not null references customers(id),
  invoice_id int not null references invoices(id),
  kind text not null check (kind in ('delivery', 'after')),
  delivery_id int references deliveries(id),
  reason text not null check (reason <> ''),
  note text not null default '',
  -- Whole kwacha, with VAT at the invoice's rate: what comes off the invoice once booked.
  subtotal bigint not null check (subtotal >= 0),
  vat bigint not null check (vat >= 0),
  total bigint not null check (total >= 0),
  booked_by int references users(id),
  booked_at timestamptz,
  -- 'waiting' until the warehouse has the goods back.
  goods text not null check (goods in ('waiting', 'received')),
  received_by int references users(id),
  received_at timestamptz,
  created_by int not null references users(id),
  created_at timestamptz not null default now(),
  check ((booked_at is null) = (booked_by is null))
);
create index sales_returns_invoice on sales_returns (invoice_id);
create index sales_returns_customer on sales_returns (customer_id, company_code);

create table sales_return_lines (
  id serial primary key,
  return_id int not null references sales_returns(id) on delete cascade,
  invoice_line_id int not null references invoice_lines(id),
  product_id int not null references products(id),
  pieces int not null check (pieces > 0),
  amount bigint not null check (amount >= 0),
  -- Set when the warehouse receives it: good goes back on the shelf, damaged does not.
  condition text check (condition in ('good', 'damaged'))
);
create index sales_return_lines_invoice_line on sales_return_lines (invoice_line_id);

-- A note against an invoice, mostly for a price difference; stock does not change. Numbered per company:
-- CN credit note issued (owes less), DN debit note issued (owes more), RDN debit note received from the
-- customer (owes less), RCN credit note received from the customer (owes more).
create table invoice_notes (
  id serial primary key,
  number text not null unique,
  company_code text not null references companies(code),
  customer_id int not null references customers(id),
  invoice_id int not null references invoices(id),
  kind text not null check (kind in ('credit_issued', 'debit_issued', 'debit_received', 'credit_received')),
  amount bigint not null check (amount > 0),
  reason text not null check (reason <> ''),
  -- The customer's own number on a note they sent us.
  their_number text not null default '',
  note text not null default '',
  created_by int not null references users(id),
  created_at timestamptz not null default now()
);
create index invoice_notes_invoice on invoice_notes (invoice_id);
create index invoice_notes_customer on invoice_notes (customer_id, company_code);

-- Good goods given back go on the shelf again.
alter table stock_movements drop constraint stock_movements_kind_check;
alter table stock_movements add constraint stock_movements_kind_check
  check (kind in ('received', 'adjusted', 'counted', 'delivered', 'returned'));
alter table stock_movements add column return_id int references sales_returns(id);

-- One id per form on the phone, so a retry after a lost answer does not save the return or note twice.
alter table sales_returns add column request_id text;
create unique index sales_returns_request on sales_returns (created_by, request_id) where request_id is not null;
alter table invoice_notes add column request_id text;
create unique index invoice_notes_request on invoice_notes (created_by, request_id) where request_id is not null;
