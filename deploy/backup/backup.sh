#!/bin/bash
# Backups for the interview-prep database. One script, one subcommand per job, run by
# interview-prep-backup@<subcommand>.timer.
#
#   base          a WAL-G base backup, daily
#   dump          a compressed pg_dump to S3, nightly
#   check         fail loudly if WAL archiving has been failing
#   restore-test  restore the newest dump into a scratch database and verify it, monthly
#   status        what exists right now, for a human
#
# Layers, per the proposal: the dedicated disk holds the data, WAL-G streams the write-ahead log
# to S3 continuously so any minute of the last 30 days can be restored, pg_dump gives a portable
# copy that does not depend on WAL-G existing, and Lightsail snapshots the disk daily.
#
# Credentials come from /etc/interview-prep/backup.env through the systemd unit. Nothing here
# prints them.
set -euo pipefail

CONTAINER="${CONTAINER:-interview-prep-postgres}"
DB_NAME="${DB_NAME:-interviewprep}"
DB_USER="${DB_USER:-interviewprep}"
PGDATA_IN_CONTAINER="${PGDATA_IN_CONTAINER:-/var/lib/postgresql/18/docker}"

: "${AWS_ACCESS_KEY_ID:?set by /etc/interview-prep/backup.env}"
: "${AWS_SECRET_ACCESS_KEY:?set by /etc/interview-prep/backup.env}"
: "${AWS_REGION:?set by /etc/interview-prep/backup.env}"
: "${BACKUP_BUCKET:?set by /etc/interview-prep/backup.env}"

log() { printf '%s %s\n' "$(date -Is)" "$*"; }
die() { printf '%s ERROR: %s\n' "$(date -Is)" "$*" >&2; exit 1; }

# S3 through curl's SigV4 support rather than the AWS CLI, which is not installed and would be
# 50 MB to add for two verbs. --fail turns an HTTP error into a non-zero exit.
s3() {
  local method="$1" key="$2"
  shift 2
  curl --fail --silent --show-error \
    --aws-sigv4 "aws:amz:${AWS_REGION}:s3" \
    --user "${AWS_ACCESS_KEY_ID}:${AWS_SECRET_ACCESS_KEY}" \
    --request "${method}" \
    "$@" \
    "https://${BACKUP_BUCKET}.s3.${AWS_REGION}.amazonaws.com/${key}"
}

in_postgres() { docker exec "${CONTAINER}" "$@"; }

require_running() {
  docker inspect --format '{{.State.Running}}' "${CONTAINER}" 2>/dev/null | grep -q true \
    || die "${CONTAINER} is not running"
}

# ---------------------------------------------------------------------------

cmd_base() {
  require_running
  log "base backup starting"
  in_postgres wal-g backup-push "${PGDATA_IN_CONTAINER}"
  log "base backup done"
  # Keep the last 7 base backups and the WAL they need. The bucket's own lifecycle rule expires
  # noncurrent versions after 30 days; this is what stops the current ones accumulating forever.
  # Tolerated on the first runs, when there is nothing to prune yet — a failure here must not
  # mask the fact that the backup above succeeded.
  if in_postgres wal-g delete retain FULL 7 --confirm; then
    log "old base backups pruned"
  else
    log "nothing to prune yet"
  fi
}

cmd_dump() {
  require_running
  local stamp key tmp
  stamp="$(date -u +%Y/%m/%d/%H%M%SZ)"
  key="dumps/${stamp}-${DB_NAME}.sql.gz"
  tmp="$(mktemp /tmp/interview-prep-dump.XXXXXX.sql.gz)"
  trap 'rm -f "${tmp}"' RETURN

  log "dumping ${DB_NAME}"
  # --clean --if-exists so the dump can be restored over an existing database.
  in_postgres pg_dump --username "${DB_USER}" --dbname "${DB_NAME}" \
    --format plain --clean --if-exists --no-owner --no-privileges \
    | gzip -9 > "${tmp}"

  [[ -s "${tmp}" ]] || die "dump is empty"
  log "uploading ${key} ($(du -h "${tmp}" | cut -f1))"
  # Uploaded from a file rather than a pipe: SigV4 needs the length up front.
  s3 PUT "${key}" --upload-file "${tmp}" > /dev/null
  log "dump uploaded"
}

# If archiving breaks, PostgreSQL keeps every WAL segment until it succeeds, and this VM has an
# 8 GB disk. Silence is the dangerous outcome here, so this exits non-zero and systemd records a
# failed unit.
cmd_check() {
  require_running
  local failed last_failed archived wal_bytes wal_human
  failed="$(in_postgres psql -U "${DB_USER}" -d "${DB_NAME}" -tAc \
    "select failed_count from pg_stat_archiver")"
  archived="$(in_postgres psql -U "${DB_USER}" -d "${DB_NAME}" -tAc \
    "select archived_count from pg_stat_archiver")"
  last_failed="$(in_postgres psql -U "${DB_USER}" -d "${DB_NAME}" -tAc \
    "select coalesce(last_failed_time::text, 'never') from pg_stat_archiver")"
  wal_bytes="$(in_postgres du -sb "${PGDATA_IN_CONTAINER}/pg_wal" | cut -f1)"
  wal_human="$(in_postgres du -sh "${PGDATA_IN_CONTAINER}/pg_wal" | cut -f1)"

  log "archived=${archived} failed=${failed} last_failed=${last_failed} pg_wal=${wal_human}"

  # A gigabyte of unshipped WAL on an 8 GB disk means archiving has been broken for a while.
  if [[ "${wal_bytes}" -gt 1073741824 ]]; then
    die "pg_wal has grown to ${wal_human}; archiving is probably broken"
  fi
  if [[ "${last_failed}" != "never" ]]; then
    local failed_age
    failed_age="$(in_postgres psql -U "${DB_USER}" -d "${DB_NAME}" -tAc \
      "select extract(epoch from now() - last_failed_time)::bigint from pg_stat_archiver")"
    [[ "${failed_age}" -lt 86400 ]] && die "WAL archiving failed within the last day (${last_failed})"
  fi
  log "archiving healthy"
}

# Proves the dumps are restorable, which is the only thing that makes them backups.
cmd_restore_test() {
  local list newest tmp scratch="interview-prep-restore-test"
  log "finding the newest dump"
  list="$(s3 GET "?list-type=2&prefix=dumps/" || die "could not list the bucket")"
  newest="$(printf '%s' "${list}" | grep -o '<Key>[^<]*</Key>' | sed 's|</\?Key>||g' | sort | tail -1)"
  [[ -n "${newest}" ]] || die "no dumps found in s3://${BACKUP_BUCKET}/dumps/"
  log "restoring ${newest}"

  tmp="$(mktemp -d /tmp/interview-prep-restore.XXXXXX)"
  docker rm -f "${scratch}" >/dev/null 2>&1 || true
  trap 'rm -rf "${tmp}"; docker rm -f "'"${scratch}"'" >/dev/null 2>&1 || true' RETURN

  s3 GET "${newest}" --output "${tmp}/dump.sql.gz" || die "could not download ${newest}"
  gunzip "${tmp}/dump.sql.gz"

  # A throwaway PostgreSQL, same major version, with its data in the container's own writable
  # layer. It never touches /data, and it is capped so it cannot squeeze the real one.
  docker run --detach --name "${scratch}" \
    --memory 256m \
    --env POSTGRES_PASSWORD=restore-test \
    --env POSTGRES_DB="${DB_NAME}" \
    --env POSTGRES_USER="${DB_USER}" \
    postgres:18-trixie >/dev/null

  local i=0
  until docker exec "${scratch}" pg_isready -U "${DB_USER}" -d "${DB_NAME}" >/dev/null 2>&1; do
    i=$((i + 1))
    [[ "${i}" -gt 60 ]] && die "the scratch database never became ready"
    sleep 2
  done

  docker exec -i "${scratch}" psql --username "${DB_USER}" --dbname "${DB_NAME}" \
    --quiet --set ON_ERROR_STOP=1 < "${tmp}/dump.sql" > /dev/null \
    || die "the dump did not restore cleanly"

  # Restoring without error is not enough: check the schema actually arrived.
  local migrations tables
  migrations="$(docker exec "${scratch}" psql -U "${DB_USER}" -d "${DB_NAME}" -tAc \
    "select count(*) from flyway_schema_history where success")"
  tables="$(docker exec "${scratch}" psql -U "${DB_USER}" -d "${DB_NAME}" -tAc \
    "select count(*) from information_schema.tables where table_schema = 'public'")"

  [[ "${migrations}" -ge 2 ]] || die "expected at least 2 applied migrations, found ${migrations}"
  [[ "${tables}" -ge 20 ]] || die "expected at least 20 tables, found ${tables}"

  log "restore verified: ${migrations} migrations, ${tables} tables"
}

cmd_status() {
  require_running
  echo "--- WAL archiving"
  in_postgres psql -U "${DB_USER}" -d "${DB_NAME}" -c \
    "select archived_count, last_archived_time, failed_count, last_failed_time from pg_stat_archiver"
  echo "--- base backups"
  in_postgres wal-g backup-list || echo "(none yet)"
  echo "--- newest dumps"
  s3 GET "?list-type=2&prefix=dumps/&max-keys=100" \
    | grep -o '<Key>[^<]*</Key>' | sed 's|</\?Key>||g' | sort | tail -5
}

case "${1:-}" in
  base)         cmd_base ;;
  dump)         cmd_dump ;;
  check)        cmd_check ;;
  restore-test) cmd_restore_test ;;
  status)       cmd_status ;;
  *) die "usage: $(basename "$0") base|dump|check|restore-test|status" ;;
esac
