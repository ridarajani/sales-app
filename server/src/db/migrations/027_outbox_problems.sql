-- Phase 10 part 3: trip sales saved on a phone that the phone could not read back.

-- The phone sends the file it could not read, so Admin can look at it and enter what is missing by hand.
-- The trip cannot be closed until Admin has cleared every problem sent for it.
create table outbox_problems (
  id serial primary key,
  trip_id int not null references trips(id),
  -- The file as it was on the phone. It may be broken, so it is kept as bytes.
  file bytea not null,
  file_name text not null default '',
  note text not null default '',
  -- So a phone that sends the same file twice (no answer the first time) makes one problem.
  request_id text not null,
  reported_by int not null references users(id),
  reported_at timestamptz not null default now(),
  cleared_by int references users(id),
  cleared_at timestamptz,
  clear_note text not null default '',
  unique (reported_by, request_id),
  check ((cleared_at is null) = (cleared_by is null))
);
create index outbox_problems_trip on outbox_problems (trip_id);
create index outbox_problems_open on outbox_problems (trip_id) where cleared_at is null;
