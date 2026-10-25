#!/usr/bin/env bash
# Gives staff a new Android version: copies the .apk onto the server and registers it.
# The download page then offers it, and the app asks to update when it opens.
#   cd deploy && bash add-apk.sh ~/sales-1.0.0.apk 2 1.0.0 "What is new, in a few words"
set -euo pipefail
cd "$(dirname "$0")"
apk=${1:?Give the .apk file}
code=${2:?Give the version code, a whole number bigger than the last one}
name=${3:?Give the version name, for example 1.0.0}
notes=${4:-}
[[ -f "$apk" ]] || { echo "No such file: $apk" >&2; exit 1; }
[[ "$code" =~ ^[0-9]+$ ]] || { echo "The version code must be a whole number." >&2; exit 1; }
file="sales-$name.apk"
[[ "$file" =~ ^sales-[A-Za-z0-9._-]+\.apk$ ]] || { echo "The version name may use only letters, numbers, dots and dashes." >&2; exit 1; }

docker compose cp "$apk" "server:/app/apk/$file"
docker compose exec -T db psql --username=sales --dbname=sales --set=ON_ERROR_STOP=1 \
  --set=code="$code" --set=name="$name" --set=file="$file" --set=notes="$notes" <<'SQL'
insert into app_versions (platform, version_code, version_name, file_name, notes)
values ('android', :code, :'name', :'file', nullif(:'notes', ''));
SQL
echo "Version $name ($code) is on the download page."
