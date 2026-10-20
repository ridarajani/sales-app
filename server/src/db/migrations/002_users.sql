-- People who can sign in. The phone number is stored as digits only, for example 0991234567.
create table users (
  id int generated always as identity primary key,
  name text not null,
  phone text not null unique,
  role text not null check (role in ('admin','management','sales_manager','salesman','warehouse','driver','cashier','accounts')),
  pin_hash text not null,
  -- The sales manager a salesman works under. Required for salesmen, empty for everyone else (checked in code).
  manager_id int references users(id),
  active boolean not null default true,
  failed_pin_attempts int not null default 0,
  locked_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Which companies each user can see.
create table user_companies (
  user_id int not null references users(id) on delete cascade,
  company_code text not null references companies(code),
  primary key (user_id, company_code)
);

-- Signed in until the user signs out. Only a hash of the token is kept.
create table sessions (
  token_hash text primary key,
  user_id int not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  last_used_at timestamptz not null default now()
);
