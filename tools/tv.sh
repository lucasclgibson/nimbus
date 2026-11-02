#!/usr/bin/env bash
# Drive the TV over adb for testing:  tv.sh key UP UP CENTER | tv.sh long CENTER | tv.sh text hello
#                                 tv.sh shot name [scale] | tv.sh top | tv.sh log
set -uo pipefail
source "$(dirname "$0")/env.sh"
DEV="${NIMBUS_DEVICE:?Set NIMBUS_DEVICE to the adb address of your TV (see tools/local.env.example)}"
A="adb -s $DEV"
OUT=/tmp/nimbus-qa
mkdir -p "$OUT"
map() {
  case "$1" in
    UP|DOWN|LEFT|RIGHT|CENTER) echo "DPAD_$1" ;;
    OK) echo DPAD_CENTER ;;
    BACK) echo BACK ;; HOME) echo HOME ;; MENU) echo MENU ;;
    PLAY|PP) echo MEDIA_PLAY_PAUSE ;; FF) echo MEDIA_FAST_FORWARD ;; RW) echo MEDIA_REWIND ;;
    *) echo "$1" ;;
  esac
}
cmd="$1"; shift
case "$cmd" in
  key)  # one or more keys, each a separate event with a small gap
    for k in "$@"; do $A shell input keyevent "$(map "$k")"; done ;;
  fast) # many keys in one shell call (fast repeat, like holding the button)
    keys=(); for k in "$@"; do keys+=("$(map "$k")"); done; $A shell input keyevent "${keys[@]}" ;;
  long) $A shell input keyevent --longpress "$(map "$1")" ;;
  text) $A shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')" ;;
  shot)
    name="${1:-shot}"; scale="${2:-0.5}"
    $A exec-out screencap -p > "$OUT/$name.full.png"
    python3 -I -c "
import sys
from PIL import Image
im=Image.open('$OUT/$name.full.png').convert('RGB'); w,h=im.size; s=float('$scale')
im.resize((int(w*s),int(h*s))).save('$OUT/$name.png')"
    echo "$OUT/$name.png" ;;
  top) $A shell 'dumpsys activity activities | grep -m1 mResumedActivity' ;;
  log) $A logcat -d --pid="$($A shell pidof io.github.lucasclgibson.nimbus)" | grep -E " [WE] " | grep -vE "BestClock|param skipped|grantorIdx|BAD_INDEX|Codec2Client" | tail -${1:-30} ;;
  launch) $A shell am start -n io.github.lucasclgibson.nimbus/.MainActivity >/dev/null ;;
  stop) $A shell am force-stop io.github.lucasclgibson.nimbus ;;
  *) echo "unknown: $cmd" >&2; exit 2 ;;
esac
