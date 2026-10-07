#!/usr/bin/env bash
set -euo pipefail
INDEXACT_SCRIPTS="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$INDEXACT_SCRIPTS/java.sh" org.indexact.index.SnapshotBuildCli "$@"
