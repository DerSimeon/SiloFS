#!/usr/bin/env sh
set -eu

rerun=0
skip_go=0
for arg in "$@"; do
  case "$arg" in
    --rerun) rerun=1 ;;
    --skip-go) skip_go=1 ;;
    *)
      echo "unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$script_dir/.." && pwd)
lock_parent="$root/build/locks"
lock_dir="$lock_parent/production-verification.lock"

mkdir -p "$lock_parent"
while ! mkdir "$lock_dir" 2>/dev/null; do
  echo "Waiting for production verification lock: $lock_dir"
  sleep 2
done

cleanup() {
  rmdir "$lock_dir" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

gradle_args=":metadata:test :blob:test :server:test :integration-test:test :compatibility-test:test dockerBackedVerification productionFocusedVerification ktlintKotlinScriptCheck --no-parallel --max-workers=1 -x detekt"
if [ "$rerun" -eq 1 ]; then
  gradle_args="$gradle_args --rerun-tasks"
fi

# shellcheck disable=SC2086
"$root/gradlew" $gradle_args

if [ "$skip_go" -eq 0 ]; then
  docker run --rm \
    -v "$root/cli:/src" \
    -w /src \
    golang:1.25-bookworm \
    sh -c "/usr/local/go/bin/go test ./... && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 /usr/local/go/bin/go build -trimpath -ldflags '-s -w' -o /tmp/silofs . && /tmp/silofs version"
fi

git -C "$root" diff --check
