#!/usr/bin/env bash
# Copies the shared web app into both native projects.
#
#   scripts/sync-web.sh          copy shared/index.html into Android and iOS
#   scripts/sync-web.sh --check  exit non-zero if either copy is out of date
#
# shared/index.html is the only file to edit; the native copies are generated.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/shared/index.html"
TARGETS=(
  "$ROOT/android/app/src/main/assets/index.html"
  "$ROOT/ios/Financial Adviser Exam Trainer/index.html"
)

if [[ "${1:-}" == "--check" ]]; then
  status=0
  for t in "${TARGETS[@]}"; do
    if ! cmp -s "$SRC" "$t"; then
      echo "out of date: ${t#$ROOT/} (run scripts/sync-web.sh)" >&2
      status=1
    fi
  done
  exit $status
fi

for t in "${TARGETS[@]}"; do
  mkdir -p "$(dirname "$t")"
  cp "$SRC" "$t"
  echo "synced ${t#$ROOT/}"
done
