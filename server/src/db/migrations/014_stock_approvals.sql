-- Damaged stock is written off only after a manager approves it (client, 22 Oct 2026).
-- Until then the request waits here and the stock does not change.
create table stock_adjustment_requests (
  id serial primary key,
  batch_id int not null references stock_batches(id),
  change int not null check (change <> 0),
  reason text not null,
  note text not null default '',
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  requested_by int not null references users(id),
  requested_at timestamptz not null default now(),
  decided_by int references users(id),
  decided_at timestamptz,
  decision_note text not null default ''
);
create index stock_adjustment_requests_pending on stock_adjustment_requests (requested_at) where status = 'pending';
alter table stock_movements add column request_id int references stock_adjustment_requests(id);
