#!/usr/bin/env bash
set -euo pipefail
CLASH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLASH_INPUT="${1:?Usage: check-offline.sh prepared-validation-directory}"
case "$(uname -m)" in arm64|aarch64) CLASH_ARCH=arm64 ;; x86_64|amd64) CLASH_ARCH=amd64 ;; *) exit 2 ;; esac
CLASH_IMAGE="$(cat "$CLASH_ROOT/tools/mihomo/validation-image.lock")"
docker run --rm -i --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
  --user 65532:65532 --memory 256m --cpus 1 --pids-limit 64 \
  -v "$CLASH_ROOT/.cache/mihomo/linux-$CLASH_ARCH/mihomo:/mihomo:ro" \
  -v "$CLASH_INPUT:/validation:ro" "$CLASH_IMAGE" \
  /mihomo -t -d /validation -f - < "$CLASH_INPUT/config.yaml"
