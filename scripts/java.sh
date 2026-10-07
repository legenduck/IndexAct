#!/usr/bin/env bash
# Shared Java entry point for the builder and search service.
set -euo pipefail
INDEXACT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(bash "$INDEXACT_ROOT/scripts/bootstrap_jdk21.sh")}"
export MAVEN_USER_HOME="$INDEXACT_ROOT/.vendor-cache/maven"
bash "$INDEXACT_ROOT/indexact/server/mvnw" -q -DskipTests package
INDEXACT_CLASSES="$INDEXACT_ROOT/indexact/server/target/classes"
INDEXACT_LUCENE="$MAVEN_USER_HOME/repository/org/apache/lucene/lucene-core/10.5.1/lucene-core-10.5.1.jar"
read -r -a INDEXACT_JAVA_ARGS <<< "${INDEXACT_JAVA_OPTS:--Xmx8g}"
exec "$JAVA_HOME/bin/java" "${INDEXACT_JAVA_ARGS[@]}" -cp "$INDEXACT_CLASSES:$INDEXACT_LUCENE" "$@"
