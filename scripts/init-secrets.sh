#!/usr/bin/env bash
set -euo pipefail
CLASH_SECRET_DIR="${1:-./secrets}"
umask 077
mkdir -p "$CLASH_SECRET_DIR"
chmod 700 "$CLASH_SECRET_DIR"
for CLASH_SECRET_NAME in db_password mysql_root_password admin_password; do
  if [[ ! -e "$CLASH_SECRET_DIR/$CLASH_SECRET_NAME" ]]; then openssl rand -base64 36 > "$CLASH_SECRET_DIR/$CLASH_SECRET_NAME"; fi
done
if [[ ! -e "$CLASH_SECRET_DIR/encryption.key" ]]; then openssl rand 32 > "$CLASH_SECRET_DIR/encryption.key"; fi
# Docker Compose file secrets are bind mounts; the protected parent directory remains 0700 on the host.
chmod 444 "$CLASH_SECRET_DIR/"{db_password,mysql_root_password,admin_password,encryption.key}
printf '%s\n' 'Secret files prepared without overwriting existing values. Read the administrator password locally when signing in.'
