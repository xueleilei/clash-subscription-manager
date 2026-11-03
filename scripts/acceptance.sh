#!/usr/bin/env bash
set -euo pipefail
CLASH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$CLASH_ROOT"
CLASH_MODE="${1:-}"
[[ "$CLASH_MODE" == bundled || "$CLASH_MODE" == external ]] || { echo 'Usage: acceptance.sh bundled|external' >&2; exit 2; }
CLASH_STAMP="$(date +%Y%m%d%H%M%S)-$$"
CLASH_PROJECT="clash-accept-$CLASH_MODE-$CLASH_STAMP"
CLASH_STATE="$CLASH_ROOT/.cache/acceptance/$CLASH_MODE-$CLASH_STAMP"
umask 077
mkdir -p "$CLASH_STATE/key-backup"
./scripts/init-secrets.sh "$CLASH_STATE/secrets"
openssl rand -base64 36 > "$CLASH_STATE/secrets/fixture_key"
chmod 444 "$CLASH_STATE/secrets/fixture_key"
clash_free_port() { node -e 'const s=require("net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})'; }
CLASH_APP_PORT="$(clash_free_port)"
CLASH_FIXTURE_PORT="$(clash_free_port)"
CLASH_SUBNET_PART=241
CLASH_DB_SERVICE=mysql
CLASH_FILES=("$CLASH_ROOT/compose.yaml")
if [[ "$CLASH_MODE" == bundled ]]; then
  CLASH_FILES+=("$CLASH_ROOT/compose.mysql.yaml")
else
  CLASH_SUBNET_PART=242; CLASH_DB_SERVICE=existing-mysql
fi
CLASH_FILES+=("$CLASH_ROOT/deploy/compose.acceptance.yaml")
cat > "$CLASH_STATE/compose.env" <<ENV
APP_IMAGE=clash-subscription-manager:local
APP_BIND_ADDRESS=127.0.0.1
APP_PORT=$CLASH_APP_PORT
PUBLIC_BASE_URL=http://127.0.0.1:$CLASH_APP_PORT
COOKIE_SECURE=false
DB_URL=jdbc:mysql://$CLASH_DB_SERVICE:3306/clash_acceptance?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC
DB_USERNAME=clash_acceptance
DB_NAME=clash_acceptance
DB_PASSWORD_FILE=$CLASH_STATE/secrets/db_password
MYSQL_ROOT_PASSWORD_FILE=$CLASH_STATE/secrets/mysql_root_password
ADMIN_USERNAME=acceptance-admin
ADMIN_PASSWORD_FILE=$CLASH_STATE/secrets/admin_password
APP_ENCRYPTION_KEY_FILE=$CLASH_STATE/secrets/encryption.key
ACCEPTANCE_SUBNET=172.30.$CLASH_SUBNET_PART.0/24
ACCEPTANCE_FIXTURE_IP=172.30.$CLASH_SUBNET_PART.10
ACCEPTANCE_FIXTURE_PORT=$CLASH_FIXTURE_PORT
ACCEPTANCE_FIXTURE_KEY_FILE=$CLASH_STATE/secrets/fixture_key
ENV
CLASH_COMPOSE=(docker compose --project-name "$CLASH_PROJECT" --env-file "$CLASH_STATE/compose.env")
for CLASH_FILE in "${CLASH_FILES[@]}"; do CLASH_COMPOSE+=(-f "$CLASH_FILE"); done
if [[ "$CLASH_MODE" == external ]]; then CLASH_COMPOSE+=(--profile external); fi
clash_cleanup() {
  local clash_result=$?
  if [[ "${KEEP_ACCEPTANCE_STACK:-0}" != 1 ]]; then "${CLASH_COMPOSE[@]}" down --volumes --remove-orphans > "$CLASH_STATE/cleanup.log" 2>&1 || true; fi
  if ((clash_result)); then printf 'Acceptance failed. Local diagnostics: %s\n' "$CLASH_STATE" >&2; fi
}
trap clash_cleanup EXIT
printf 'Acceptance mode: %s; isolated project: %s\n' "$CLASH_MODE" "$CLASH_PROJECT"
"${CLASH_COMPOSE[@]}" config --quiet
"${CLASH_COMPOSE[@]}" build app > "$CLASH_STATE/build.log" 2>&1
"${CLASH_COMPOSE[@]}" up -d --wait --wait-timeout 240 "$CLASH_DB_SERVICE" fixture > "$CLASH_STATE/start-db.log" 2>&1
"${CLASH_COMPOSE[@]}" up -d --wait --wait-timeout 180 app > "$CLASH_STATE/start-app.log" 2>&1
export E2E_BASE_URL="http://127.0.0.1:$CLASH_APP_PORT" E2E_FIXTURE_CONTROL="http://127.0.0.1:$CLASH_FIXTURE_PORT"
export E2E_PASSWORD_FILE="$CLASH_STATE/secrets/admin_password" E2E_FIXTURE_KEY_FILE="$CLASH_STATE/secrets/fixture_key" E2E_STATE_FILE="$CLASH_STATE/expected-state.json"
export E2E_COMPOSE_PROJECT="$CLASH_PROJECT" E2E_COMPOSE_ENV="$CLASH_STATE/compose.env"
E2E_COMPOSE_FILES="$(node -e 'console.log(JSON.stringify(process.argv.slice(1)))' "${CLASH_FILES[@]}")"; export E2E_COMPOSE_FILES
pnpm --dir frontend exec playwright test
# Both modes use explicit client configuration; no production instance is discovered or selected.
CLASH_PASSWORD="$(cat "$CLASH_STATE/secrets/db_password")"
printf '[client]\nhost=%s\nuser=clash_acceptance\npassword=%s\n' "$CLASH_DB_SERVICE" "$CLASH_PASSWORD" > "$CLASH_STATE/client.cnf"
unset CLASH_PASSWORD
"${CLASH_COMPOSE[@]}" stop app > "$CLASH_STATE/stop-app.log" 2>&1
./scripts/backup.sh --database clash_acceptance --connection "$CLASH_STATE/client.cnf" --output "$CLASH_STATE/database-backup" --network "${CLASH_PROJECT}_default"
cp "$CLASH_STATE/secrets/encryption.key" "$CLASH_STATE/key-backup/encryption.key"
if ./scripts/restore.sh --database clash_acceptance --connection "$CLASH_STATE/client.cnf" --input "$CLASH_STATE/database-backup" --network "${CLASH_PROJECT}_default" > "$CLASH_STATE/nonempty-restore.log" 2>&1; then echo 'Non-empty restore guard failed.' >&2; exit 1; fi
grep -q 'Refusing to restore into a non-empty database' "$CLASH_STATE/nonempty-restore.log"
"${CLASH_COMPOSE[@]}" logs --no-color app > "$CLASH_STATE/app-before-restore.log"
"${CLASH_COMPOSE[@]}" down --volumes --remove-orphans > "$CLASH_STATE/down-for-restore.log" 2>&1
"${CLASH_COMPOSE[@]}" up -d --wait --wait-timeout 240 "$CLASH_DB_SERVICE" fixture > "$CLASH_STATE/new-db.log" 2>&1
./scripts/restore.sh --database clash_acceptance --connection "$CLASH_STATE/client.cnf" --input "$CLASH_STATE/database-backup" --network "${CLASH_PROJECT}_default"
rm "$CLASH_STATE/secrets/encryption.key"
cp "$CLASH_STATE/key-backup/encryption.key" "$CLASH_STATE/secrets/encryption.key"
chmod 444 "$CLASH_STATE/secrets/encryption.key"
"${CLASH_COMPOSE[@]}" up -d --wait --wait-timeout 180 app > "$CLASH_STATE/restored-app.log" 2>&1
node tests/recovery-check.mjs
# Missing or mismatched keys must fail before an extra application can become available.
openssl rand 32 > "$CLASH_STATE/wrong.key"
chmod 444 "$CLASH_STATE/wrong.key"
if "${CLASH_COMPOSE[@]}" run --rm --no-deps -v "$CLASH_STATE/wrong.key:/run/secrets/wrong_key:ro" -e APP_ENCRYPTION_KEY_FILE=/run/secrets/wrong_key app > "$CLASH_STATE/wrong-key.log" 2>&1; then echo 'Wrong encryption key unexpectedly started.' >&2; exit 1; fi
grep -q 'Secret storage unavailable' "$CLASH_STATE/wrong-key.log"
if "${CLASH_COMPOSE[@]}" run --rm --no-deps -e APP_ENCRYPTION_KEY_FILE=/missing-key app > "$CLASH_STATE/missing-key.log" 2>&1; then echo 'Missing encryption key unexpectedly started.' >&2; exit 1; fi
grep -q 'Persistent encryption key must be' "$CLASH_STATE/missing-key.log"
"${CLASH_COMPOSE[@]}" logs --no-color app > "$CLASH_STATE/app-after-restore.log"
node - "$CLASH_STATE" <<'NODE'
const fs=require('fs'),path=require('path'),dir=process.argv[2];const state=JSON.parse(fs.readFileSync(path.join(dir,'expected-state.json'),'utf8'));const values=['admin_password','db_password','mysql_root_password','fixture_key'].map(name=>fs.readFileSync(path.join(dir,'secrets',name),'utf8').trim());values.push(state.url,new URL(state.url).pathname.split('/').at(-1));const logs=['app-before-restore.log','app-after-restore.log','wrong-key.log','missing-key.log'].map(name=>fs.readFileSync(path.join(dir,name),'utf8')).join('\n');if(values.some(value=>value.length>=12&&logs.includes(value)))throw new Error('Secret appeared in application logs');console.log('Missing/wrong key rejection, empty-target restore guard and secret-free application logs verified.');
NODE
printf 'Acceptance %s passed, including teardown and restore into a new database.\n' "$CLASH_MODE"
