-- Shops and traders the companies sell to.
create table customers (
  id int generated always as identity primary key,
  name text not null,
  channel text not null check (channel in ('supermarket','wholesaler','minibus','upcountry')),
  area text not null default '',
  -- Digits only, starting with 0, like users.phone. Optional.
  phone text,
  needs_lpo boolean not null default false,
  cash_only boolean not null default false,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index customers_name_key on customers (lower(name));

-- Which companies sell to each customer, and how many days that company gives them to pay.
create table customer_companies (
  customer_id int not null references customers(id) on delete cascade,
  company_code text not null references companies(code),
  payment_terms_days int not null default 0 check (payment_terms_days between 0 and 365),
  primary key (customer_id, company_code)
);
