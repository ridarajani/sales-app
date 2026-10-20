-- Released versions of the phone app, for the download page and the in-app update check.
create table app_versions (
  id int generated always as identity primary key,
  platform text not null check (platform in ('android')),
  version_code int not null,
  version_name text not null,
  file_name text not null,
  notes text,
  released_at timestamptz not null default now(),
  unique (platform, version_code)
);
