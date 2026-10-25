#!/usr/bin/env bash
# Puts a backup back. This REPLACES everything in the live database with the backup.
#   cd deploy && bash restore.sh backups/sales-2026-11-02-0200.dump [backups/files-2026-11-02-0200.tar.gz]
set -euo pipefail
cd "$(dirname "$0")"
dump=${1:?Give the .dump file to restore, from deploy/backups}
files=${2:-}
[[ -f "$dump" ]] || { echo "No such file: $dump" >&2; exit 1; }

read -r -p "This replaces ALL data on the live server with $dump. Type RESTORE to go on: " answer
[[ "$answer" == "RESTORE" ]] || { echo "Stopped. Nothing was changed."; exit 1; }

docker compose stop server
docker compose exec -T db pg_restore --username=sales --dbname=sales --clean --if-exists --no-owner < "$dump"
if [[ -n "$files" ]]; then
  [[ -f "$files" ]] || { echo "No such file: $files" >&2; exit 1; }
  # Signatures, photos, logos and app files go back into their volumes, with their owners kept.
  docker run --rm -v sales_uploads:/files/uploads -v sales_branding:/files/branding -v sales_apk:/files/apk \
    -v "$PWD/$files:/restore.tar.gz:ro" postgres:16 tar -xzf /restore.tar.gz -C /files
fi
docker compose start server
echo "Restored $dump."
