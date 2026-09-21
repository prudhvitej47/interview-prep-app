#!/bin/bash
# Sets the VM up to run the app, and re-syncs it afterwards. Safe to run repeatedly.
#
# Run it from the Mac, which needs no copy of anything on the server:
#
#   ECR_REGISTRY=<account>.dkr.ecr.ap-south-1.amazonaws.com \
#     tailscale ssh ubuntu@interview-prep "sudo ECR_REGISTRY=\$ECR_REGISTRY bash -s" < deploy/vm-setup.sh
#
# Everything it installs comes from this repository, so a rebuilt VM ends up identical. It never
# touches /data, and never prints a credential.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/prudhvitej47/interview-prep-app.git}"
REPO_REF="${REPO_REF:-main}"
BASE="${BASE:-/opt/interview-prep}"
SRC="${BASE}/src"
APP_ENV="/etc/interview-prep/app.env"
WALG_VERSION="${WALG_VERSION:-v3.0.9}"

[[ $EUID -eq 0 ]] || { echo "run this as root (sudo)" >&2; exit 1; }
[[ -n "${ECR_REGISTRY:-}" ]] || {
  echo "ECR_REGISTRY must be set, e.g. <account-id>.dkr.ecr.ap-south-1.amazonaws.com" >&2
  exit 1
}
[[ -f /etc/interview-prep/backup.env ]] || {
  echo "/etc/interview-prep/backup.env is missing; this VM did not finish its first boot" >&2
  exit 1
}

log() { printf '\n==> %s\n' "$*"; }

# An SSH session forwards the client's locale, which this VM does not have installed, and apt
# prints a wall of perl warnings about it. Nothing here depends on the locale.
export LC_ALL=C.UTF-8

# ---------------------------------------------------------------------------
log "1/7 ECR credential helper"
# Turns the VM's existing AWS key into a registry token on demand, and refreshes it when it
# expires. Avoids both an AWS CLI install and a `docker login` cron job.
if ! command -v docker-credential-ecr-login >/dev/null; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq amazon-ecr-credential-helper
fi
install -d -m 700 /root/.docker
cat > /root/.docker/config.json <<JSON
{
  "credHelpers": {
    "${ECR_REGISTRY}": "ecr-login"
  }
}
JSON
chmod 600 /root/.docker/config.json
echo "configured for ${ECR_REGISTRY}"

# ---------------------------------------------------------------------------
log "2/7 Deployment files from ${REPO_URL} (${REPO_REF})"
install -d -m 755 "${BASE}" "${BASE}/content"
if [[ -d "${SRC}/.git" ]]; then
  # FETCH_HEAD rather than origin/<ref>: the clone is shallow and only tracks main, so a remote
  # tracking branch does not exist for anything else.
  git -C "${SRC}" fetch --quiet --depth 1 origin "${REPO_REF}"
  git -C "${SRC}" reset --quiet --hard FETCH_HEAD
  echo "updated"
else
  git clone --quiet --depth 1 --branch "${REPO_REF}" "${REPO_URL}" "${SRC}"
  echo "cloned"
fi
chmod +x "${SRC}/deploy/update.sh"

# ---------------------------------------------------------------------------
log "3/7 Application environment"
# The database password is generated once and then left alone, so re-running this never locks the
# app out of its own database. Never printed.
if [[ -f "${APP_ENV}" ]] && grep -q '^DB_PASSWORD=' "${APP_ENV}"; then
  DB_PASSWORD="$(grep '^DB_PASSWORD=' "${APP_ENV}" | cut -d= -f2-)"
  echo "keeping the existing database password"
else
  DB_PASSWORD="$(openssl rand -hex 24)"
  echo "generated a database password"
fi
umask 077
cat > "${APP_ENV}" <<ENV
APP_IMAGE=${ECR_REGISTRY}/interview-prep-app:main
CONTENT_IMAGE=${ECR_REGISTRY}/interview-prep-content:main
DB_PASSWORD=${DB_PASSWORD}
ENV
chmod 600 "${APP_ENV}"
umask 022
unset DB_PASSWORD

# ---------------------------------------------------------------------------
log "4/7 WAL-G"
# A single static binary, mounted into the PostgreSQL container so it can be the archive_command.
# Installing it here rather than building our own PostgreSQL image keeps one file out of a whole
# second publishing pipeline.
install -d -m 755 "${BASE}/bin"
if [[ "$("${BASE}/bin/wal-g" --version 2>/dev/null | grep -o "v[0-9.]*" | head -1)" == "${WALG_VERSION}" ]]; then
  echo "${WALG_VERSION} already installed"
else
  WALG_TMP="$(mktemp -d)"
  WALG_ASSET="wal-g-pg-24.04-amd64"
  WALG_URL="https://github.com/wal-g/wal-g/releases/download/${WALG_VERSION}/${WALG_ASSET}.tar.gz"
  curl -fsSL "${WALG_URL}" -o "${WALG_TMP}/walg.tar.gz"
  curl -fsSL "${WALG_URL}.sha256" -o "${WALG_TMP}/walg.sha256"
  # The published checksum is over the tarball. Verify before anything is unpacked or run.
  ( cd "${WALG_TMP}" && sed "s|  .*|  walg.tar.gz|" walg.sha256 | sha256sum --check --status ) \
    || { rm -rf "${WALG_TMP}"; echo "wal-g checksum did not match; refusing to install" >&2; exit 1; }
  tar -xzf "${WALG_TMP}/walg.tar.gz" -C "${WALG_TMP}"
  install -m 755 "${WALG_TMP}/${WALG_ASSET}" "${BASE}/bin/wal-g"
  rm -rf "${WALG_TMP}"
  echo "installed $("${BASE}/bin/wal-g" --version 2>&1 | head -1)"
fi

log "5/7 systemd units"
install -m 644 "${SRC}/deploy/interview-prep.service" /etc/systemd/system/
install -m 644 "${SRC}/deploy/interview-prep-update.service" /etc/systemd/system/
install -m 644 "${SRC}/deploy/interview-prep-update.timer" /etc/systemd/system/
install -m 644 "${SRC}/deploy/backup/interview-prep-backup@.service" /etc/systemd/system/
install -m 644 "${SRC}"/deploy/backup/interview-prep-backup@*.timer /etc/systemd/system/
chmod +x "${SRC}/deploy/backup/backup.sh"
systemctl daemon-reload
# enable, then restart. `enable --now` starts a unit only if it is not already active, and
# interview-prep.service is Type=oneshot with RemainAfterExit=yes — so on a re-run it stays
# "active" and the new Compose file is never applied. That made this script silently fail to do
# the one thing it exists for.
systemctl enable interview-prep.service interview-prep-update.timer
systemctl restart interview-prep.service
systemctl start interview-prep-update.timer
for job in base dump check restore-test; do
  systemctl enable --now "interview-prep-backup@${job}.timer"
done
echo "enabled"
# Run one update now rather than waiting for the timer, so the curriculum is loaded by the time
# this script finishes instead of a few minutes later.
systemctl start interview-prep-update.service && echo "curriculum synced"

# ---------------------------------------------------------------------------
log "6/7 HTTPS on 443 over Tailscale"
# tailscaled already holds this node's identity and renews the certificate itself, so it
# terminates TLS and forwards to the app. No reverse proxy, no certificate timer, no extra memory
# on a machine that has under 2 GB of it.
tailscale serve --bg --https=443 http://127.0.0.1:8080
tailscale serve status

# ---------------------------------------------------------------------------
log "7/7 State"
systemctl --no-pager --lines=0 status interview-prep.service || true
# `docker compose ps` would re-read the database password to interpolate the file it is only
# reporting on. Asking Docker for the project's containers needs no secret at all.
docker ps --filter "label=com.docker.compose.project=interview-prep" \
  --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' || true

cat <<'DONE'

Done. The app should answer at https://interview-prep.tail82d7a5.ts.net within a minute or two.

  systemctl status interview-prep-update.timer     when the next image check runs
  journalctl -u interview-prep-update.service -n 50   what the last check did
  docker compose -p interview-prep -f /opt/interview-prep/src/deploy/docker-compose.yml logs -f app

Re-run this script after changing anything under deploy/ to re-sync the VM.
DONE
