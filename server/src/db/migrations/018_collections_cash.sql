-- Phase 7: payments from customers, expenses and handing cash to the cashier.

-- One payment from a customer to one company: cash, bank transfer or Airtel Money, numbered per company (LV-RCT-000001).
-- A discount or withholding tax typed at collection comes off the invoice picked (client, 7 Oct; prototype).
create table collections (
  id serial primary key,
  number text not null unique,
  company_code text not null references companies(code),
  customer_id int not null references customers(id),
  -- The invoice the customer is paying; the money also covers older debts when it is more.
  invoice_id int references invoices(id),
  mode text not null check (mode in ('cash', 'bank', 'airtel')),
  -- Whole kwacha received, and what came off the invoice without money.
  amount bigint not null check (amount >= 0),
  discount bigint not null default 0 check (discount >= 0),
  wht bigint not null default 0 check (wht >= 0),
  wht_certificate text not null default '',
  reference text not null default '',
  note text not null default '',
  -- File names in the uploads folder; never in the repo.
  proof_file text,
  signature_file text,
  received_by text not null default '',
  collected_by int not null references users(id),
  -- Cash stays the collector's responsibility until the cashier counts it. Bank and Airtel Money are 'na'.
  custody text not null check (custody in ('with', 'handed', 'counted', 'na')),
  handover_id int,
  created_at timestamptz not null default now(),
  check (amount + discount + wht > 0),
  check ((discount = 0 and wht = 0) or invoice_id is not null)
);
create index collections_customer on collections (customer_id, company_code);
create index collections_custody on collections (collected_by) where custody = 'with';

-- Where each payment went: invoices, or the opening balance (invoice_id null). What is left over stays on account.
create table collection_allocations (
  id serial primary key,
  collection_id int not null references collections(id) on delete cascade,
  invoice_id int references invoices(id),
  amount bigint not null check (amount > 0)
);
create index collection_allocations_invoice on collection_allocations (invoice_id);
create index collection_allocations_collection on collection_allocations (collection_id);

-- Money spent for a company: fuel, airtime and so on, with a photo of the bill when there is one.
-- Cash expenses come off the cash handed to the cashier.
create table expenses (
  id serial primary key,
  number text not null unique,
  company_code text not null references companies(code),
  amount bigint not null check (amount > 0),
  description text not null check (description <> ''),
  mode text not null check (mode in ('cash', 'airtel')),
  photo_file text,
  spent_by int not null references users(id),
  -- Cash: 'with' until handed over, then 'handed' and 'counted'. Airtel Money: 'na'.
  custody text not null check (custody in ('with', 'handed', 'counted', 'na')),
  handover_id int,
  created_at timestamptz not null default now()
);
create index expenses_custody on expenses (spent_by) where custody = 'with';

-- One handover of cash to the cashier: everything the person holds, less their cash expenses, in one total.
-- Numbered in one series for all companies (HO-000001), since one handover can cover several companies.
create table handovers (
  id serial primary key,
  number text not null unique,
  handed_by int not null references users(id),
  cash bigint not null check (cash >= 0),
  expenses bigint not null check (expenses >= 0),
  net bigint not null,
  status text not null default 'handed' check (status in ('handed', 'counted')),
  counted bigint,
  difference bigint,
  note text not null default '',
  counted_by int references users(id),
  counted_at timestamptz,
  created_at timestamptz not null default now()
);
alter table collections add constraint collections_handover_fk foreign key (handover_id) references handovers(id);
alter table expenses add constraint expenses_handover_fk foreign key (handover_id) references handovers(id);
