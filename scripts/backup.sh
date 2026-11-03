#!/usr/bin/env bash
set -euo pipefail
CLASH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$CLASH_ROOT/deploy/images.lock"
CLASH_DB=''; CLASH_CONNECTION=''; CLASH_OUTPUT=''; CLASH_NETWORK=bridge
while (($#)); do
  case "$1" in
    --database) CLASH_DB="$2"; shift 2;;
    --connection) CLASH_CONNECTION="$2"; shift 2;;
    --output) CLASH_OUTPUT="$2"; shift 2;;
    --network) CLASH_NETWORK="$2"; shift 2;;
    *) echo 'Usage: backup.sh --database NAME --connection CLIENT_CNF --output NEW_DIRECTORY [--network DOCKER_NETWORK]' >&2; exit 2;;
  esac
done
[[ "$CLASH_DB" =~ ^[A-Za-z0-9_]{1,64}$ && -f "$CLASH_CONNECTION" && -n "$CLASH_OUTPUT" ]] || { echo 'Explicit database, readable client configuration, and output directory are required.' >&2; exit 2; }
CLASH_CONNECTION="$(cd "$(dirname "$CLASH_CONNECTION")" && pwd)/$(basename "$CLASH_CONNECTION")"
[[ ! -e "$CLASH_OUTPUT/database.sql.gz" ]] || { echo 'Refusing to overwrite an existing backup.' >&2; exit 2; }
umask 077
mkdir -p "$CLASH_OUTPUT"
chmod 700 "$CLASH_OUTPUT"
trap 'rm -f "$CLASH_OUTPUT/.database.sql.gz.part"' EXIT
docker run --rm --network "$CLASH_NETWORK" -v "$CLASH_CONNECTION:/run/client.cnf:ro" "$MYSQL_IMAGE" \
  mysqldump --defaults-extra-file=/run/client.cnf --single-transaction --quick --routines --events --triggers --set-gtid-purged=OFF --no-tablespaces --column-statistics=0 "$CLASH_DB" | gzip > "$CLASH_OUTPUT/.database.sql.gz.part"
mv "$CLASH_OUTPUT/.database.sql.gz.part" "$CLASH_OUTPUT/database.sql.gz"
printf '%s\n' "$CLASH_DB" > "$CLASH_OUTPUT/database-name"
(cd "$CLASH_OUTPUT"; if command -v sha256sum >/dev/null 2>&1; then sha256sum database.sql.gz; else shasum -a 256 database.sql.gz; fi) > "$CLASH_OUTPUT/database.sha256"
printf '%s\n' 'Database backup complete. Back up the persistent encryption key separately; it is not included here.'
