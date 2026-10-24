-- Client answers of 24 October: products can be VAT exempt; lists can be deleted while live; each person keeps
-- their own short list of vehicles.

-- A VAT exempt product is charged no VAT, even on a VAT invoice. Invoice lines keep what applied when invoiced.
alter table products add column vat_exempt boolean not null default false;
alter table invoice_lines add column vat_exempt boolean not null default false;

-- Delete removes a product, customer, vehicle or user that was never used. One with history (an invoice, a trip,
-- an audit entry) is marked deleted instead: it is switched off, hidden from every list and cannot be switched on,
-- but old documents still show its name. Its code, name, plate or phone can then be used again.
alter table products add column deleted_at timestamptz;
alter table customers add column deleted_at timestamptz;
alter table vehicles add column deleted_at timestamptz;
alter table users add column deleted_at timestamptz;

-- An opening balance is history too: the customer is kept rather than losing what they owe.
alter table opening_balances drop constraint opening_balances_customer_id_fkey;
alter table opening_balances add constraint opening_balances_customer_id_fkey foreign key (customer_id) references customers(id);

alter table products drop constraint products_code_key;
create unique index products_code_key on products (code) where deleted_at is null;
drop index customers_name_key;
create unique index customers_name_key on customers (lower(name)) where deleted_at is null;
alter table vehicles drop constraint vehicles_plate_key;
create unique index vehicles_plate_key on vehicles (plate) where deleted_at is null;
alter table users drop constraint users_phone_key;
create unique index users_phone_key on users (phone) where deleted_at is null;

-- Vehicles are shared by all four companies. A person marks the ones they do not use as dormant, so they pick
-- from a short list. Nothing is stored for vehicles they use.
create table user_dormant_vehicles (
  user_id int not null references users(id) on delete cascade,
  vehicle_id int not null references vehicles(id) on delete cascade,
  primary key (user_id, vehicle_id)
);
