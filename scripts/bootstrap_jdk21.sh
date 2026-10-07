#!/usr/bin/env bash
# Bootstrap Eclipse Temurin JDK 21.0.8+9 for Linux x86_64.
# Artifact: GPL-2.0-with-classpath-exception; https://adoptium.net/about/
set -euo pipefail

readonly JDK_VERSION='21.0.8+9'
readonly JDK_ARCHIVE='OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz'
readonly JDK_URL='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz'
readonly JDK_SHA256='f2dc5418092c43003db8f9005c4a286e1c0104fea96ccdd49e8ebd037cac9219'

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly CACHE_DIR="$REPOSITORY_ROOT/.vendor-cache/temurin"
readonly INSTALL_DIR="$REPOSITORY_ROOT/.toolchains/temurin-$JDK_VERSION-linux-x64"
readonly ARCHIVE_PATH="$CACHE_DIR/$JDK_ARCHIVE"

fail() {
  printf 'bootstrap_jdk21: %s\n' "$1" >&2
  exit 1
}

verify_sha256() {
  local path=$1
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$JDK_SHA256" "$path" | sha256sum --check --status -
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "$JDK_SHA256" "$path" | shasum -a 256 --check --status
  else
    fail 'sha256sum or shasum is required'
  fi
}

[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || \
  fail 'the pinned bootstrap currently supports Linux x86_64 only'

mkdir -p -- "$CACHE_DIR" "$REPOSITORY_ROOT/.toolchains"

if [[ ! -f $ARCHIVE_PATH ]] || ! verify_sha256 "$ARCHIVE_PATH"; then
  download_path="$ARCHIVE_PATH.part.$$"
  trap 'rm -f -- "$download_path"' EXIT HUP INT TERM
  curl --fail --location --proto '=https' --tlsv1.2 \
    --output "$download_path" "$JDK_URL"
  verify_sha256 "$download_path" || fail 'downloaded Temurin archive failed SHA-256 verification'
  mv -- "$download_path" "$ARCHIVE_PATH"
  trap - EXIT HUP INT TERM
fi

if [[ ! -x $INSTALL_DIR/bin/java ]]; then
  extract_dir="$(mktemp -d "$REPOSITORY_ROOT/.toolchains/.temurin-extract.XXXXXX")"
  trap 'rm -rf -- "$extract_dir"' EXIT HUP INT TERM
  tar --extract --gzip --file "$ARCHIVE_PATH" --directory "$extract_dir" \
    --strip-components=1 --no-same-owner
  [[ -x $extract_dir/bin/java && -x $extract_dir/bin/javac ]] || \
    fail 'Temurin archive has an unexpected layout'
  mv -- "$extract_dir" "$INSTALL_DIR"
  trap - EXIT HUP INT TERM
fi

"$INSTALL_DIR/bin/java" -version 2>&1 | grep -Fq '21.0.8' || \
  fail "installed runtime does not report expected version $JDK_VERSION"

printf '%s\n' "$INSTALL_DIR"
