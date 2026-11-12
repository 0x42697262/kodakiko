#!/bin/sh
set -eu

AVD_NAME=kodakiko-ui
SYSTEM_IMAGE="system-images;android-36;google_apis;x86_64"
DEVICE_PROFILE=pixel_7

MISSING=""
for tool in avdmanager emulator adb; do
  command -v "$tool" >/dev/null 2>&1 || MISSING="${MISSING} ${tool}"
done
if [ -n "$MISSING" ]; then
  echo "Missing:${MISSING}. Install the Android SDK command-line tools and platform-tools, and put them on PATH." >&2
  exit 1
fi

if avdmanager list avd -c | grep -qx "$AVD_NAME"; then
  echo "AVD ${AVD_NAME} already exists."
  exit 0
fi

echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$DEVICE_PROFILE"
echo "Created AVD ${AVD_NAME}. Start it with: emulator -avd ${AVD_NAME}"
