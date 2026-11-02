#!/usr/bin/env bash
# Build the release APK, install it on the TV (NIMBUS_DEVICE) and AOT-compile it for smooth playback/UI.
#   tools/deploy.sh            build + install + compile
#   tools/deploy.sh --launch   ...and start the app
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
: "${NIMBUS_DEVICE:?Set NIMBUS_DEVICE to your TV's adb address (see tools/local.env.example)}"
./gradlew -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
if [[ ! -f "$APK" ]]; then
  echo "The release build is unsigned: set NIMBUS_SIGNING_PROPERTIES (see README), or use ./gradlew installDebug." >&2
  exit 1
fi
adb connect "$NIMBUS_DEVICE" >/dev/null
adb -s "$NIMBUS_DEVICE" install -r "$APK"
# Sideloaded apps start interpreted/JIT; compile everything ahead of time so Compose is fluid from launch one.
adb -s "$NIMBUS_DEVICE" shell cmd package compile -m speed -f io.github.lucasclgibson.nimbus >/dev/null
# Fire OS's launcher wakes freshly updated apps; a process started before compilation keeps running the
# uncompiled code (interpreter + JIT) and is noticeably janky. Make sure the next launch is a fresh process.
adb -s "$NIMBUS_DEVICE" shell am force-stop io.github.lucasclgibson.nimbus
echo "Installed $(du -h "$APK" | cut -f1) APK and compiled (speed)."
if [[ "${1:-}" == "--launch" ]]; then
  adb -s "$NIMBUS_DEVICE" shell am start -n io.github.lucasclgibson.nimbus/.MainActivity >/dev/null
fi
