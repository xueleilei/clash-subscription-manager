#!/usr/bin/env bash
set -euo pipefail
CLASH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$CLASH_ROOT"
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend test --run
pnpm --dir frontend typecheck
pnpm --dir frontend build
./tools/mihomo/fetch.sh
case "$(uname -m)" in arm64|aarch64) CLASH_VERIFY_ARCH=arm64 ;; x86_64|amd64) CLASH_VERIFY_ARCH=amd64 ;; *) exit 2 ;; esac
./tools/mihomo/fetch.sh "linux-$CLASH_VERIFY_ARCH"
# MihomoValidatorIT invokes check-offline.sh with its prepared validation directory.
if [[ -n "${CLASH_MAVEN_SETTINGS:-}" ]]; then ./scripts/mvn.sh -s "$CLASH_MAVEN_SETTINGS" verify; else ./scripts/mvn.sh verify; fi
printf '%s\n' 'All frontend, backend, MySQL and offline Mihomo checks passed.'
