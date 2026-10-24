-- A phone on a slow line may send the same payment or expense twice: the first try reaches the server but the
-- answer never comes back, so the person taps Save again. The app sends one id per form; a repeat gets the
-- first record back instead of a second receipt.
alter table collections add column request_id text;
create unique index collections_request on collections (collected_by, request_id) where request_id is not null;
alter table expenses add column request_id text;
create unique index expenses_request on expenses (spent_by, request_id) where request_id is not null;
