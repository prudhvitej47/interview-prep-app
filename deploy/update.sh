#!/bin/bash
# Pulls the current main image and restarts the app if it changed. Run by a systemd timer every
# two minutes; nothing reaches the VM from outside.
#
# There is deliberately no digest comparison here. `docker compose pull` fetches a manifest and
# does nothing when the image is unchanged, and `docker compose up -d` recreates a container only
# when its image id differs from the running one. Hand-rolling that comparison would be more code
# doing the same job, with a bug waiting in it: get it backwards and the app either never updates
# or restarts every two minutes.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-/opt/interview-prep/src/deploy/docker-compose.yml}"
PROJECT="${PROJECT:-interview-prep}"

compose() {
  docker compose --project-name "${PROJECT}" --file "${COMPOSE_FILE}" "$@"
}

# If the registry is unreachable, stop here. The running app is fine and the next tick retries;
# carrying on to `up -d` would risk acting on a stale local image.
compose pull --quiet app

# --no-deps so a new app image never restarts PostgreSQL underneath it.
compose up --detach --no-deps app
