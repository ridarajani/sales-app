-- Phase 7, part 2: Airtel Money wallets.
-- Each sales manager keeps one Airtel Money wallet for the companies they run (in the prototype: one for LV and ZT,
-- one for ZF and GL). Customers pay into it; the holder ticks each payment once it shows on their phone, pays
-- expenses from it and takes money out as cash for the cashier. The app keeps the wallet's ledger.
create table airtel_wallets (
  id serial primary key,
  holder_id int not null unique references users(id),
  -- What was in the wallet on the day the app started, in whole kwacha.
  opening_balance bigint not null default 0 check (opening_balance >= 0),
  active boolean not null default true,
  created_at timestamptz not null default now()
);

-- Which companies' Airtel payments go into which wallet. A company has at most one wallet.
create table airtel_wallet_companies (
  wallet_id int not null references airtel_wallets(id) on delete cascade,
  company_code text primary key references companies(code)
);

-- Every movement in a wallet. Money in is credit, money out is debit.
create table airtel_entries (
  id serial primary key,
  wallet_id int not null references airtel_wallets(id),
  company_code text not null references companies(code),
  kind text not null check (kind in ('payment', 'expense', 'withdrawal', 'charge')),
  credit bigint not null default 0 check (credit >= 0),
  debit bigint not null default 0 check (debit >= 0),
  description text not null default '',
  reference text not null default '',
  collection_id int references collections(id),
  expense_id int references expenses(id),
  -- A payment is 'waiting' until the holder sees it in the wallet and ticks it; everything else is 'ok'.
  status text not null check (status in ('waiting', 'ticked', 'ok')),
  ticked_at timestamptz,
  -- A withdrawal becomes cash in the holder's hand until the cashier counts it, like a cash payment.
  custody text not null default 'na' check (custody in ('with', 'handed', 'counted', 'na')),
  handover_id int references handovers(id),
  created_by int not null references users(id),
  created_at timestamptz not null default now(),
  check ((credit > 0) <> (debit > 0))
);
create index airtel_entries_wallet on airtel_entries (wallet_id, id);
create index airtel_entries_custody on airtel_entries (created_by) where custody = 'with';

-- The holder compares the real balance on their phone with the ledger now and then. A difference is kept.
create table airtel_checks (
  id serial primary key,
  wallet_id int not null references airtel_wallets(id),
  actual bigint not null,
  ledger bigint not null,
  difference bigint not null,
  note text not null default '',
  checked_by int not null references users(id),
  checked_at timestamptz not null default now()
);

-- Cash taken out of a wallet is part of a handover, next to cash payments and less cash expenses.
alter table handovers add column withdrawals bigint not null default 0 check (withdrawals >= 0);
