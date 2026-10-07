#!/usr/bin/env bash
set -euo pipefail
INDEXACT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# Keep the caller's working directory: command-line paths are relative to it.
exec "$INDEXACT_ROOT/agent/node_modules/.bin/tsx" "$INDEXACT_ROOT/agent/src/run.ts" "$@"
