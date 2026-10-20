-- Delivery vehicles. Drivers are users with the driver role; default_driver_id is who usually drives it.
create table vehicles (
  id int generated always as identity primary key,
  plate text not null unique,
  description text not null default '',
  facility_code text references facilities(code),
  default_driver_id int references users(id),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
