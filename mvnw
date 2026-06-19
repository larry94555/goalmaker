#!/usr/bin/env sh
# Lightweight Maven wrapper for imini (macOS, Linux, WSL, Git Bash). Windows-native: mvnw.cmd.
# Prefers a system 'mvn'; otherwise downloads a pinned Apache Maven into .maven/ once and uses it.
set -eu

BASEDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
PROPS="$BASEDIR/.mvn/wrapper/maven-wrapper.properties"

prop() { [ -f "$PROPS" ] && sed -n "s/^$1=//p" "$PROPS" | tr -d '\r' | head -n1 || true; }

VER="$(prop distributionVersion)"; [ -n "$VER" ] || VER="3.9.9"
URL="$(prop distributionUrl)"
[ -n "$URL" ] || URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VER/apache-maven-$VER-bin.tar.gz"
SUM="$(prop distributionSha512Sum)"

# 1) Use a system Maven if present.
if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

# 2) Otherwise use (or fetch) a wrapper-managed Maven under .maven/.
MVN_HOME="$BASEDIR/.maven/apache-maven-$VER"
MVN_BIN="$MVN_HOME/bin/mvn"
if [ ! -x "$MVN_BIN" ]; then
  echo "[mvnw] no system Maven found; downloading Apache Maven $VER (one time)..."
  mkdir -p "$BASEDIR/.maven"
  TGZ="$BASEDIR/.maven/apache-maven-$VER-bin.tar.gz"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$URL" -o "$TGZ"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$URL" -O "$TGZ"
  else
    echo "[mvnw] need curl or wget to download Maven. Install Maven manually, or use Docker." >&2
    exit 1
  fi
  if [ -n "${SUM:-}" ]; then
    if command -v sha512sum >/dev/null 2>&1; then
      echo "$SUM  $TGZ" | sha512sum -c - >/dev/null || { echo "[mvnw] checksum mismatch" >&2; rm -f "$TGZ"; exit 1; }
    elif command -v shasum >/dev/null 2>&1; then
      echo "$SUM  $TGZ" | shasum -a 512 -c - >/dev/null || { echo "[mvnw] checksum mismatch" >&2; rm -f "$TGZ"; exit 1; }
    else
      echo "[mvnw] warning: no sha512sum/shasum available; skipping checksum verification" >&2
    fi
  fi
  if command -v tar >/dev/null 2>&1; then
    tar -xzf "$TGZ" -C "$BASEDIR/.maven"
  else
    echo "[mvnw] need 'tar' to extract Maven. Install it, or use Docker." >&2
    exit 1
  fi
  rm -f "$TGZ"
fi
exec "$MVN_BIN" "$@"
