#!/usr/bin/env bash
set -euo pipefail
CLASH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${CLASH_JAVA_HOME:-}" ]]; then
  CLASH_JAVA_PATH="$CLASH_JAVA_HOME"
elif [[ "$(uname -s)" == Darwin ]]; then
  CLASH_JAVA_PATH="$(/usr/libexec/java_home -v 21)"
elif [[ -n "${JAVA_HOME:-}" ]]; then
  CLASH_JAVA_PATH="$JAVA_HOME"
else
  printf '%s\n' 'Set CLASH_JAVA_HOME to a Java 21 installation.' >&2
  exit 1
fi
exec env JAVA_HOME="$CLASH_JAVA_PATH" "$CLASH_ROOT/backend/mvnw" -B -ntp -f "$CLASH_ROOT/backend/pom.xml" "$@"
