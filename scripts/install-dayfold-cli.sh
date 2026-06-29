#!/usr/bin/env bash
# Install the `dayfold` CLI into a headless / cloud environment (scheduled-task
# setup). Idempotent: safe to re-run. Builds from source because no `cli-v*`
# release tarball is published yet — once one is, prefer downloading it (see the
# NOTE at the bottom) so the runtime only needs a JRE, not the full JDK + Gradle.
#
# Usage (run from the repo root, e.g. as a cloud-env setup script):
#   bash scripts/install-dayfold-cli.sh
# Then `dayfold` is on PATH at /usr/local/bin/dayfold.
#
# Auth is NOT configured here — the scheduled task supplies the legacy household
# token via env (DAYFOLD_API, FAMILY_ID, HOUSEHOLD_SECRET). See
# processes/scheduled-authoring-loop.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI_DIR="$REPO_ROOT/apps/cli"
PREFIX="${DAYFOLD_PREFIX:-/opt/dayfold}"
BIN_LINK="${DAYFOLD_BIN_LINK:-/usr/local/bin/dayfold}"

# Already installed? Done.
if command -v dayfold >/dev/null 2>&1; then
  echo "dayfold already on PATH: $(command -v dayfold)"
  exit 0
fi

# The build pins jvmToolchain(17); Gradle will not substitute another major and
# toolchain auto-download is not configured. Ensure a JDK 17 exists and point
# Gradle at it explicitly.
JDK17="${JAVA17_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
if [ ! -x "$JDK17/bin/javac" ]; then
  echo "Installing OpenJDK 17 (required: build pins jvmToolchain 17)…"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y
    apt-get install -y openjdk-17-jdk-headless
  else
    echo "ERROR: need JDK 17 but no apt-get. Install OpenJDK 17 and set JAVA17_HOME." >&2
    exit 1
  fi
fi

echo "Building the CLI from source (installDist)…"
( cd "$CLI_DIR" && ./gradlew --no-daemon installDist -q \
    -Porg.gradle.java.installations.paths="$JDK17" )

echo "Installing to $PREFIX and linking $BIN_LINK…"
rm -rf "$PREFIX"
mkdir -p "$(dirname "$PREFIX")"
cp -r "$CLI_DIR/build/install/dayfold" "$PREFIX"
ln -sf "$PREFIX/bin/dayfold" "$BIN_LINK"

echo "Installed: $("$BIN_LINK" --version)"
# NOTE: once the operator cuts a release (`git tag cli-vX.Y.Z` → release-cli.yml),
# replace the build-from-source block above with a download of
#   https://github.com/SloopWorks/dayfold/releases/download/cli-vX.Y.Z/dayfold-X.Y.Z.tar
# extracted into $PREFIX. That path needs only a JRE >=17 at runtime — no Gradle,
# no full JDK, far faster cold start.
