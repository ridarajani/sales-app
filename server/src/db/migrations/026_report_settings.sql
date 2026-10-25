-- Phase 10 part 1: the settings the reports need.

-- A monthly sales target for each salesman, in kwacha before VAT. month is the first day of the month.
create table sales_targets (
  salesman_id int not null references users(id),
  month date not null check (extract(day from month) = 1),
  amount bigint not null check (amount >= 0),
  set_by int not null references users(id),
  set_at timestamptz not null default now(),
  primary key (salesman_id, month)
);

-- Below this many cartons available, a product shows on the low stock report. Null means no level is set.
alter table products add column low_stock_cartons int check (low_stock_cartons >= 0);

-- How much a customer may owe one company. Shown on reports only; it never stops an order. Null means no limit.
alter table customer_companies add column credit_limit bigint check (credit_limit >= 0);
