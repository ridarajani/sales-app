#!/usr/bin/env bash
# Backs up the database and the saved files (signatures, photos, logos, app files) into deploy/backups.
#   bash backup.sh           one backup now (run inside the backup container: docker compose exec backup bash /scripts/backup.sh)
#   bash backup.sh --daily   every night at 02:00 Malawi time, keeping KEEP_DAYS days (how the backup container runs)
set -euo pipefail

backup_once() {
  local stamp
  stamp=$(date +%Y-%m-%d-%H%M)
  # pg_dump's own format: restore.sh puts it back with pg_restore.
  pg_dump --format=custom --file="/backups/sales-$stamp.dump.part"
  mv "/backups/sales-$stamp.dump.part" "/backups/sales-$stamp.dump"
  tar -czf "/backups/files-$stamp.tar.gz.part" -C /files .
  mv "/backups/files-$stamp.tar.gz.part" "/backups/files-$stamp.tar.gz"
  find /backups -maxdepth 1 -type f \( -name 'sales-*.dump' -o -name 'files-*.tar.gz' \) -mtime +"${KEEP_DAYS:-30}" -delete
  echo "$(date '+%F %T') backup done: sales-$stamp.dump and files-$stamp.tar.gz"
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
