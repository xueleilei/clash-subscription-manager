#!/usr/bin/env bash
set -euo pipefail
CLASH_TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASH_ROOT="$(cd "$CLASH_TOOLS/../.." && pwd)"
case "$(uname -s)" in Darwin) CLASH_OS=darwin ;; Linux) CLASH_OS=linux ;; *) exit 2 ;; esac
case "$(uname -m)" in arm64|aarch64) CLASH_ARCH=arm64 ;; x86_64|amd64) CLASH_ARCH=amd64 ;; *) exit 2 ;; esac
CLASH_PLATFORM="${1:-$CLASH_OS-$CLASH_ARCH}"
CLASH_CACHE="$CLASH_ROOT/.cache/mihomo"
CLASH_ENTRY="$(awk -v p="$CLASH_PLATFORM" '$1 == "core" && $2 == p { print $3, $4 }' "$CLASH_TOOLS/resources.lock")"
if [[ -z "$CLASH_ENTRY" ]]; then printf '%s\n' 'Unsupported platform; add an official verified archive to resources.lock.' >&2; exit 2; fi
read -r CLASH_DIGEST CLASH_URL <<< "$CLASH_ENTRY"
clash_hash() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi
}
mkdir -p "$CLASH_CACHE/$CLASH_PLATFORM" "$CLASH_CACHE/resources"
CLASH_ARCHIVE="$CLASH_CACHE/$CLASH_PLATFORM/mihomo.gz"
if [[ ! -f "$CLASH_ARCHIVE" ]] || [[ "$(clash_hash "$CLASH_ARCHIVE")" != "$CLASH_DIGEST" ]]; then
  curl -fL --retry 2 --connect-timeout 10 --max-time 180 "$CLASH_URL" -o "$CLASH_ARCHIVE.part"
  [[ "$(clash_hash "$CLASH_ARCHIVE.part")" == "$CLASH_DIGEST" ]] || { printf '%s\n' 'Core checksum mismatch' >&2; exit 1; }
  mv "$CLASH_ARCHIVE.part" "$CLASH_ARCHIVE"
fi
gzip -dc "$CLASH_ARCHIVE" > "$CLASH_CACHE/$CLASH_PLATFORM/mihomo.part"
chmod 755 "$CLASH_CACHE/$CLASH_PLATFORM/mihomo.part"
mv "$CLASH_CACHE/$CLASH_PLATFORM/mihomo.part" "$CLASH_CACHE/$CLASH_PLATFORM/mihomo"
while read -r CLASH_KIND CLASH_NAME CLASH_DIGEST CLASH_ASSET; do
  [[ "$CLASH_KIND" == resource ]] || continue
  gzip -dc "$CLASH_TOOLS/$CLASH_ASSET" > "$CLASH_CACHE/resources/$CLASH_NAME.part"
  [[ "$(clash_hash "$CLASH_CACHE/resources/$CLASH_NAME.part")" == "$CLASH_DIGEST" ]] || { printf '%s\n' 'Resource checksum mismatch' >&2; exit 1; }
  mv "$CLASH_CACHE/resources/$CLASH_NAME.part" "$CLASH_CACHE/resources/$CLASH_NAME"
done < "$CLASH_TOOLS/resources.lock"
printf 'Prepared Mihomo 1.19.33 for %s with verified fixed resources.\n' "$CLASH_PLATFORM"
