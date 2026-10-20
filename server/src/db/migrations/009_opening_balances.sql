-- What each customer owed each company on the day the app started, in whole kwacha.
create table opening_balances (
  customer_id int not null references customers(id) on delete cascade,
  company_code text not null references companies(code),
  amount bigint not null check (amount >= 0),
  as_of date not null,
  entered_by int not null references users(id),
  entered_at timestamptz not null default now(),
  primary key (customer_id, company_code)
);
