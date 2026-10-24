-- A payment recorded by mistake can be cancelled by Accounts or Admin while its cash is still with the collector.
-- The payment stays on record; it no longer counts, and what it paid is open again.
alter table collections add column cancelled_at timestamptz;
alter table collections add column cancelled_by int references users(id);
alter table collections add column cancel_reason text not null default '';
alter table collections add constraint collections_cancelled check ((cancelled_at is null) = (cancelled_by is null));
