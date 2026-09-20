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

# ---------------------------------------------------------------------------
log "1/6 ECR credential helper"
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
log "2/6 Deployment files from ${REPO_URL} (${REPO_REF})"
install -d -m 755 "${BASE}"
if [[ -d "${SRC}/.git" ]]; then
  git -C "${SRC}" fetch --quiet origin "${REPO_REF}"
  git -C "${SRC}" reset --quiet --hard "origin/${REPO_REF}"
  echo "updated"
else
  git clone --quiet --depth 1 --branch "${REPO_REF}" "${REPO_URL}" "${SRC}"
  echo "cloned"
fi
chmod +x "${SRC}/deploy/update.sh"

# ---------------------------------------------------------------------------
log "3/6 Application environment"
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
DB_PASSWORD=${DB_PASSWORD}
ENV
chmod 600 "${APP_ENV}"
umask 022
unset DB_PASSWORD

# ---------------------------------------------------------------------------
log "4/6 systemd units"
install -m 644 "${SRC}/deploy/interview-prep.service" /etc/systemd/system/
install -m 644 "${SRC}/deploy/interview-prep-update.service" /etc/systemd/system/
install -m 644 "${SRC}/deploy/interview-prep-update.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now interview-prep.service
systemctl enable --now interview-prep-update.timer
echo "enabled"

# ---------------------------------------------------------------------------
log "5/6 HTTPS on 443 over Tailscale"
# tailscaled already holds this node's identity and renews the certificate itself, so it
# terminates TLS and forwards to the app. No reverse proxy, no certificate timer, no extra memory
# on a machine that has under 2 GB of it.
tailscale serve --bg --https=443 http://127.0.0.1:8080
tailscale serve status

# ---------------------------------------------------------------------------
log "6/6 State"
systemctl --no-pager --lines=0 status interview-prep.service || true
docker compose --project-name interview-prep \
  --file "${SRC}/deploy/docker-compose.yml" ps || true

cat <<'DONE'

Done. The app should answer at https://interview-prep.tail82d7a5.ts.net within a minute or two.

  systemctl status interview-prep-update.timer     when the next image check runs
  journalctl -u interview-prep-update.service -n 50   what the last check did
  docker compose -p interview-prep -f /opt/interview-prep/src/deploy/docker-compose.yml logs -f app

Re-run this script after changing anything under deploy/ to re-sync the VM.
DONE
