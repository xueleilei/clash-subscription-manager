#!/usr/bin/env bash
set -euo pipefail
CLASH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$CLASH_ROOT/deploy/images.lock"
CLASH_DB=''; CLASH_CONNECTION=''; CLASH_INPUT=''; CLASH_NETWORK=bridge
while (($#)); do
  case "$1" in
    --database) CLASH_DB="$2"; shift 2;;
    --connection) CLASH_CONNECTION="$2"; shift 2;;
    --input) CLASH_INPUT="$2"; shift 2;;
    --network) CLASH_NETWORK="$2"; shift 2;;
    *) echo 'Usage: restore.sh --database NAME --connection CLIENT_CNF --input BACKUP_DIRECTORY [--network DOCKER_NETWORK]' >&2; exit 2;;
  esac
done
[[ "$CLASH_DB" =~ ^[A-Za-z0-9_]{1,64}$ && -f "$CLASH_CONNECTION" && -f "$CLASH_INPUT/database.sql.gz" && -f "$CLASH_INPUT/database.sha256" && -f "$CLASH_INPUT/database-name" ]] || { echo 'Explicit database, client configuration, and complete backup directory are required.' >&2; exit 2; }
[[ "$(cat "$CLASH_INPUT/database-name")" == "$CLASH_DB" ]] || { echo 'Backup database name does not match the explicitly selected target.' >&2; exit 2; }
CLASH_CONNECTION="$(cd "$(dirname "$CLASH_CONNECTION")" && pwd)/$(basename "$CLASH_CONNECTION")"
(cd "$CLASH_INPUT"; if command -v sha256sum >/dev/null 2>&1; then sha256sum -c database.sha256; else shasum -a 256 -c database.sha256; fi)
CLASH_TABLES="$(docker run --rm --network "$CLASH_NETWORK" -v "$CLASH_CONNECTION:/run/client.cnf:ro" "$MYSQL_IMAGE" mysql --defaults-extra-file=/run/client.cnf --batch --skip-column-names -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$CLASH_DB'")"
[[ "$CLASH_TABLES" == 0 ]] || { echo 'Refusing to restore into a non-empty database. Use a newly provisioned empty database.' >&2; exit 2; }
gzip -t "$CLASH_INPUT/database.sql.gz"
gzip -dc "$CLASH_INPUT/database.sql.gz" | docker run --rm -i --network "$CLASH_NETWORK" -v "$CLASH_CONNECTION:/run/client.cnf:ro" "$MYSQL_IMAGE" mysql --defaults-extra-file=/run/client.cnf "$CLASH_DB"
printf '%s\n' 'Database restored. Start the application with its matching persistent encryption key.'
