-- One running number per company and document type, for example LV-SO-000123. Numbers are never reused.
create table number_series (
  company_code text not null references companies(code),
  doc_type text not null check (doc_type ~ '^[A-Z]{2,4}$'),
  last_number int not null default 0,
  primary key (company_code, doc_type)
);
