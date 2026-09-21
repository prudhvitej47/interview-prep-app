#!/bin/bash
# Pulls the current app image and the current curriculum bundle, and restarts the app when either
# changed. Run by a systemd timer every two minutes; nothing reaches the VM from outside.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-/opt/interview-prep/src/deploy/docker-compose.yml}"
PROJECT="${PROJECT:-interview-prep}"
CONTENT_DIR="${CONTENT_DIR:-/opt/interview-prep/content}"

compose() {
  docker compose --project-name "${PROJECT}" --file "${COMPOSE_FILE}" "$@"
}

# --- the app ------------------------------------------------------------------
# No digest comparison here: `docker compose pull` does nothing when the image is unchanged, and
# `docker compose up -d` recreates a container only when its image id differs. Both verified before
# relying on them. If the registry is unreachable, set -e stops us before touching the running app.
compose pull --quiet app
# --no-deps so a new app image never restarts PostgreSQL underneath it.
compose up --detach --no-deps app

# --- the curriculum -----------------------------------------------------------
# The bundle is not a running container, so Compose cannot decide this one. The content image is
# built deterministically, so an unchanged curriculum has an unchanged image id and nothing happens.
update_content() {
  # Unset until the setup script has run with content support; the app keeps what it has loaded.
  [[ -n "${CONTENT_IMAGE:-}" ]] || return 0

  docker pull --quiet "${CONTENT_IMAGE}" > /dev/null
  local new old
  new="$(docker image inspect --format '{{.Id}}' "${CONTENT_IMAGE}")"
  old="$(cat "${CONTENT_DIR}/.image-id" 2>/dev/null || true)"
  [[ "${new}" == "${old}" ]] && return 0

  # The image is FROM scratch: no shell to run, so the files are copied out of a created container.
  local tmp cid
  tmp="$(mktemp -d)"
  cid="$(docker create "${CONTENT_IMAGE}" /nonexistent)"
  docker cp "${cid}:/bundle/." "${tmp}/"
  docker rm "${cid}" > /dev/null
  if [[ ! -s "${tmp}/manifest.json" || ! -s "${tmp}/content.json" ]]; then
    rm -rf "${tmp}"
    echo "content image ${CONTENT_IMAGE} holds no bundle; keeping the current one" >&2
    return 1
  fi

  install -d -m 755 "${CONTENT_DIR}"
  # Same filesystem, so each mv is an atomic rename. Content first and the manifest last, because
  # the manifest is what the loader reads to decide whether anything changed.
  mv -f "${tmp}/content.json" "${CONTENT_DIR}/content.json"
  mv -f "${tmp}/manifest.json" "${CONTENT_DIR}/manifest.json"
  rm -rf "${tmp}"
  # Recorded only after the files are in place, so a failure part-way retries next time.
  echo "${new}" > "${CONTENT_DIR}/.image-id"

  echo "new curriculum bundle; restarting the app to load it"
  # ponytail: if the app image changed in this same run, the app restarts twice. Rare, and each is
  # a few seconds; coordinating the two would cost more than it saves.
  compose restart app
}

update_content
