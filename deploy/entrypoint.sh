#!/bin/sh
set -eu
if [ -n "${DB_PASSWORD_FILE:-}" ]; then
  [ -r "$DB_PASSWORD_FILE" ] || { echo 'Database password file is not readable.' >&2; exit 1; }
  DB_PASSWORD=$(cat "$DB_PASSWORD_FILE"); export DB_PASSWORD
fi
if [ ! -r "${APP_ENCRYPTION_KEY_FILE:-/missing}" ] || [ "$(wc -c < "$APP_ENCRYPTION_KEY_FILE" | tr -d ' ')" != 32 ]; then
  echo 'Persistent encryption key must be a readable file containing exactly 32 raw bytes.' >&2; exit 1
fi
exec java -XX:MaxRAMPercentage=50 -jar /opt/app/app.jar "$@"
