#!/usr/bin/env bash
# Build a release and publish it to the household hub; the TVs offer it as an update.
#   tools/publish.sh ["release notes"]
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
HUB_DATA="${NIMBUS_HUB_DATA:-$HOME/.local/share/nimbus-hub}"
./gradlew -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
[[ -f "$APK" ]] || { echo "The release build is unsigned: set NIMBUS_SIGNING_PROPERTIES (see README)." >&2; exit 1; }
AAPT2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
BADGE=$("$AAPT2" dump badging "$APK" | sed -n '/^package:/p')
CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGE")
NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGE")
CURRENT=$(python3 -I -c "import json,sys; print(json.load(open(sys.argv[1])).get('versionCode', 0))" "$HUB_DATA/releases/version.json" 2>/dev/null || echo 0)
if (( CODE <= CURRENT )); then
  echo "The hub already has version code $CURRENT: bump nimbus.version in gradle.properties first." >&2
  exit 1
fi
mkdir -p "$HUB_DATA/releases"
cp "$APK" "$HUB_DATA/releases/nimbus-$CODE.apk"
python3 -I -c "import json,sys; json.dump({'versionCode':int(sys.argv[1]),'versionName':sys.argv[2],'apk':'/nimbus/nimbus-'+sys.argv[1]+'.apk','notes':sys.argv[3]}, open(sys.argv[4]+'.tmp','w')); import os; os.replace(sys.argv[4]+'.tmp', sys.argv[4])" \
  "$CODE" "$NAME" "${1:-}" "$HUB_DATA/releases/version.json"
ls -t "$HUB_DATA"/releases/nimbus-*.apk | tail -n +4 | xargs -r rm --
echo "Published Nimbus $NAME (build $CODE) to $HUB_DATA"
