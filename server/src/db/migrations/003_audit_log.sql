-- Who did what and when. user_id is empty for failed logins by an unknown phone.
create table audit_log (
  id bigint generated always as identity primary key,
  at timestamptz not null default now(),
  user_id int references users(id),
  action text not null,
  target_user_id int references users(id),
  details jsonb
);

create index audit_log_at_idx on audit_log (at desc);
