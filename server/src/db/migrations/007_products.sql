-- What the companies sell. Every pack size is its own product, counted in cartons.
create table products (
  id int generated always as identity primary key,
  code text not null unique,
  company_code text not null references companies(code),
  brand text not null,
  name text not null,
  pack_size text not null,
  pieces_per_carton int not null check (pieces_per_carton > 0),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index products_company_idx on products (company_code);
