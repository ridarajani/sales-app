#!/usr/bin/env bash
# Backs up the database and the saved files (signatures, photos, logos, app files) into deploy/backups.
# The database gets a new dated .dump each time. The files are copied once into backups/files and after
# that only new or changed files are added, so the backups don't grow by every photo every night.
#   bash backup.sh           one backup now (run inside the backup container: docker compose exec backup bash /scripts/backup.sh)
#   bash backup.sh --daily   every night at 02:00 Malawi time, keeping KEEP_DAYS days (how the backup container runs)
set -euo pipefail

backup_once() {
  local stamp
  stamp=$(date +%Y-%m-%d-%H%M)
  # pg_dump's own format: restore.sh puts it back with pg_restore.
  pg_dump --format=custom --file="/backups/sales-$stamp.dump.part"
  mv "/backups/sales-$stamp.dump.part" "/backups/sales-$stamp.dump"
  # New and changed files only. Files removed from the app are kept here, to be safe.
  mkdir -p /backups/files
  cp -a --update /files/. /backups/files/
  find /backups -maxdepth 1 -type f \( -name 'sales-*.dump' -o -name 'files-*.tar.gz' \) -mtime +"${KEEP_DAYS:-30}" -delete
  echo "$(date '+%F %T') backup done: sales-$stamp.dump and new files in files/"
}

if [[ "${1:-}" != "--daily" ]]; then
  backup_once
  exit 0
fi

while true; do
  now=$(date +%s)
  next=$(date -d 'today 02:00' +%s)
  (( next <= now )) && next=$(date -d 'tomorrow 02:00' +%s)
  sleep $(( next - now ))
  backup_once || echo "$(date '+%F %T') backup FAILED" >&2
done
