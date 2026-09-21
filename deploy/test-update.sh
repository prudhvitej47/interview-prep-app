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
# Records compose restarts, reports $FAKE_IMAGE_ID as the image id, and writes a bundle on `cp`
# unless $FAKE_EMPTY_IMAGE is set.
case "$1" in
  compose)
    [[ " $* " == *" restart app "* ]] && echo restart >> "${FAKE_LOG}"
    exit 0 ;;
  pull)    exit 0 ;;
  image)   echo "${FAKE_IMAGE_ID}" ;;
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

exit $fail
