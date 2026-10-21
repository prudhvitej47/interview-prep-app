#!/bin/bash
# Checks update.sh's curriculum logic with a fake `docker` on the PATH, so it runs anywhere and
# touches nothing real. Run: bash deploy/test-update.sh
#
# This is the one piece of the updater with its own branching. Get it wrong and the curriculum
# either never refreshes, or the app restarts every two minutes for nothing.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/docker" <<'FAKE'
#!/bin/bash
# Records compose restarts and runner starts, reports $FAKE_IMAGE_ID as the content image id, says the
# sandbox image is present only when $FAKE_SANDBOX_PRESENT is set, records sandbox pulls, and fails the
# runner's start when $FAKE_RUNNER_FAILS is set. Writes a bundle on `cp` unless $FAKE_EMPTY_IMAGE is set.
case "$1" in
  compose)
    [[ " $* " == *" restart app "* ]] && echo restart >> "${FAKE_LOG}"
    if [[ " $* " == *" up "*" runner"* ]]; then
      echo up >> "${FAKE_RUNNER_LOG}"
      [[ -n "${FAKE_RUNNER_FAILS:-}" ]] && exit 1
    fi
    exit 0 ;;
  pull)
    [[ "$*" == *eclipse-temurin* ]] && { echo pull >> "${FAKE_SANDBOX_LOG}"; [[ -n "${FAKE_SANDBOX_PULL_FAILS:-}" ]] && exit 1; }
    exit 0 ;;
  image)
    if [[ "$*" == *eclipse-temurin* ]]; then [[ -n "${FAKE_SANDBOX_PRESENT:-}" ]] && exit 0 || exit 1; fi
    echo "${FAKE_IMAGE_ID}" ;;
  create)  echo "fake-container" ;;
  rm)      exit 0 ;;
  cp)
    dest="${3%/}"
    if [[ -z "${FAKE_EMPTY_IMAGE:-}" ]]; then
      echo "{\"content_hash\":\"${FAKE_IMAGE_ID}\"}" > "${dest}/manifest.json"
      echo '{"schema_version":1}' > "${dest}/content.json"
    fi ;;
esac
FAKE
chmod +x "${WORK}/bin/docker"

export PATH="${WORK}/bin:${PATH}"
export CONTENT_DIR="${WORK}/content"
export FAKE_LOG="${WORK}/restarts"
export FAKE_RUNNER_LOG="${WORK}/runner-ups" FAKE_SANDBOX_LOG="${WORK}/sandbox-pulls"
count() { [[ -f "$1" ]] && wc -l < "$1" | tr -d ' ' || echo 0; }
export COMPOSE_FILE=/dev/null

fail=0
restarts() { [[ -f "${FAKE_LOG}" ]] && wc -l < "${FAKE_LOG}" | tr -d ' ' || echo 0; }
run() { bash "${HERE}/update.sh" > /dev/null 2>&1; }
check() {
  if [[ "$2" == "$3" ]]; then printf 'ok    %s\n' "$1"; else printf 'FAIL  %s: expected %s, got %s\n' "$1" "$2" "$3"; fail=1; fi
}

unset CONTENT_IMAGE
run
check "no content image configured: nothing happens" 0 "$(restarts)"

export CONTENT_IMAGE="registry/interview-prep-content:main" FAKE_IMAGE_ID="sha256:one"
run
check "first bundle: extracted and the app restarted once" 1 "$(restarts)"
check "the bundle is in place" "yes" "$([[ -s ${CONTENT_DIR}/manifest.json && -s ${CONTENT_DIR}/content.json ]] && echo yes || echo no)"

run
check "same image again: no restart" 1 "$(restarts)"

export FAKE_IMAGE_ID="sha256:two"
run
check "new image: restarted again" 2 "$(restarts)"
check "and the new bundle replaced the old" "sha256:two" "$(grep -o 'sha256:[a-z]*' "${CONTENT_DIR}/manifest.json")"

export FAKE_IMAGE_ID="sha256:three" FAKE_EMPTY_IMAGE=1
run
check "an image with no bundle: no restart" 2 "$(restarts)"
check "and the last good bundle is kept" "sha256:two" "$(grep -o 'sha256:[a-z]*' "${CONTENT_DIR}/manifest.json")"
check "and it will be retried, not recorded as done" "sha256:two" "$(cat "${CONTENT_DIR}/.image-id")"

# --- the code runner ------------------------------------------------------------
rm -f "${FAKE_RUNNER_LOG}" "${FAKE_SANDBOX_LOG}"
unset FAKE_EMPTY_IMAGE FAKE_SANDBOX_PRESENT
export FAKE_IMAGE_ID="sha256:four"
run
check "a missing sandbox image is pulled" 1 "$(count "${FAKE_SANDBOX_LOG}")"
check "and the runner is brought up" 1 "$(count "${FAKE_RUNNER_LOG}")"
export FAKE_SANDBOX_PRESENT=1
run
check "a present sandbox image is not pulled again" 1 "$(count "${FAKE_SANDBOX_LOG}")"
export FAKE_RUNNER_FAILS=1 FAKE_IMAGE_ID="sha256:five"
before="$(restarts)"
bash "${HERE}/update.sh" > /dev/null 2>&1
check "a runner that fails to start does not fail the update" 0 "$?"
check "and the curriculum still updates" "$((before + 1))" "$(restarts)"
unset FAKE_RUNNER_FAILS FAKE_SANDBOX_PRESENT
export FAKE_SANDBOX_PULL_FAILS=1 FAKE_IMAGE_ID="sha256:six"
bash "${HERE}/update.sh" > /dev/null 2>&1
check "a sandbox pull that fails does not fail the update" 0 "$?"
check "and the curriculum still updates" "$((before + 2))" "$(restarts)"
unset FAKE_SANDBOX_PULL_FAILS

# A missing sandbox-image file (a partial sync) must not stop the curriculum either.
cp -R "${HERE}" "${WORK}/deploy-copy" && rm "${WORK}/deploy-copy/sandbox-image"
export FAKE_IMAGE_ID="sha256:seven"
before="$(restarts)"
bash "${WORK}/deploy-copy/update.sh" > /dev/null 2>&1
check "no sandbox-image file does not fail the update" 0 "$?"
check "and the curriculum still updates" "$((before + 1))" "$(restarts)"

# The stack unit starts the database and the app; the runner starts after them, and a runner that
# fails to start never fails the unit (the leading - on ExecStartPost) or stops vm-setup.sh.
unit="${HERE}/interview-prep.service"
check "the stack unit starts only postgres and app" 1 "$(grep -c '^ExecStart=.* up --detach postgres app$' "${unit}")"
check "the runner starts after them, its failure ignored" 1 \
  "$(grep -c '^ExecStartPost=-/usr/bin/docker compose .* up --detach runner$' "${unit}")"

# Every service's log is capped and rotated by Docker, so a long-lived container cannot fill the disk.
compose="${HERE}/docker-compose.yml"
check "one log limit for every service" 1 "$(grep -c '^x-logging: &logging' "${compose}")"
check "the limit rotates" 1 "$(grep -cE 'max-size: "10m"' "${compose}")"
check "postgres, app and runner all use it" 3 "$(grep -c '^    logging: \*logging$' "${compose}")"

exit $fail
