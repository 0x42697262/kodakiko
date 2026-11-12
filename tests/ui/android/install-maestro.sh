#!/bin/sh
set -eu

VERSION=2.12.1
URL="https://github.com/mobile-dev-inc/maestro/releases/download/cli-${VERSION}/maestro.zip"
JAVA_MIN_MAJOR=17
SCRIPT_DIRECTORY=$(cd "$(dirname "$0")" && pwd)
INSTALL_DIRECTORY="$HOME/.maestro-kodakiko/${VERSION}"
MAESTRO_BINARY="${INSTALL_DIRECTORY}/maestro/bin/maestro"

MISSING=""
for tool in curl unzip sha256sum java; do
  command -v "$tool" >/dev/null 2>&1 || MISSING="${MISSING} ${tool}"
done
if [ -n "$MISSING" ]; then
  echo "Missing:${MISSING}" >&2
  exit 1
fi

JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt "$JAVA_MIN_MAJOR" ]; then
  echo "Java ${JAVA_MIN_MAJOR} or newer is required." >&2
  exit 1
fi

if [ -x "$MAESTRO_BINARY" ]; then
  echo "Maestro ${VERSION} is already installed at ${MAESTRO_BINARY}"
  exit 0
fi

WORK_DIRECTORY=$(mktemp -d)
cleanup() {
  rm -f "${WORK_DIRECTORY}/maestro.zip" "${WORK_DIRECTORY}/maestro.sha256"
  rmdir "$WORK_DIRECTORY"
}
trap cleanup EXIT

curl --fail --location --silent --show-error --output "${WORK_DIRECTORY}/maestro.zip" "$URL"
cp "${SCRIPT_DIRECTORY}/maestro.sha256" "${WORK_DIRECTORY}/maestro.sha256"
(cd "$WORK_DIRECTORY" && sha256sum --check --status maestro.sha256) || {
  echo "The downloaded maestro.zip does not match maestro.sha256." >&2
  exit 1
}

mkdir -p "$INSTALL_DIRECTORY"
unzip -q "${WORK_DIRECTORY}/maestro.zip" -d "$INSTALL_DIRECTORY"
echo "Maestro ${VERSION} installed at ${MAESTRO_BINARY}"
