-- Contact details printed on invoices. Filled in by the admin; never seeded with real values.
alter table companies
  add column phone text,
  add column email text,
  add column address text;
