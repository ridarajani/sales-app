-- Printed on the VAT invoice (client samples, 22 Oct 2026).
alter table customers
  add column address text not null default '',
  add column city text not null default '',
  add column tpin text not null default '',
  add column reg_no text not null default '';
