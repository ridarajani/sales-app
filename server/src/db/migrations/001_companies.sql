-- The two facilities and the four companies. Each facility serves two companies.
create table facilities (
  code text primary key,
  name text not null
);

create table companies (
  code text primary key,
  name text not null,
  facility_code text not null references facilities(code),
  tpin text,
  vat_rate numeric(5,2),
  sort_order int not null default 0
);
