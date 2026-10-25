#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ "$#" -ne 8 ] || [ "$1" != '--serial' ] || [ "$3" != '--model' ] || [ "$5" != '--sdk' ] || [ "$7" != '--user' ]; then
  echo 'Usage: ./build-and-install.sh --serial SERIAL --model EXPECTED_MODEL --sdk EXPECTED_API --user USER_ID' >&2
  exit 2
fi
SERIAL="$2"
EXPECTED_MODEL="$4"
EXPECTED_SDK="$6"
TARGET_USER="$8"
case "$TARGET_USER:$EXPECTED_SDK" in *[!0-9:]*|:*) echo 'SDK/user must be numeric' >&2; exit 2;; esac
[ -n "$SERIAL" ] && [ -n "$EXPECTED_MODEL" ] || exit 2
./build.sh
ADB="${ADB_BIN:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb}"
[ "$("$ADB" -s "$SERIAL" get-state)" = device ] || { echo 'Device unavailable or unauthorized' >&2; exit 1; }
MODEL="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
SDK="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[ "$MODEL" = "$EXPECTED_MODEL" ] && [ "$SDK" = "$EXPECTED_SDK" ] || { echo "Wrong device: $MODEL / SDK $SDK" >&2; exit 1; }
trap '"$ADB" -s "$SERIAL" shell rm -f /data/local/tmp/fudge-launcher.apk >/dev/null 2>&1 || true' EXIT
"$ADB" -s "$SERIAL" push fudge-launcher.apk /data/local/tmp/fudge-launcher.apk
RESULT="$("$ADB" -s "$SERIAL" shell pm install --user "$TARGET_USER" -r /data/local/tmp/fudge-launcher.apk)"
printf '%s\n' "$RESULT"
printf '%s\n' "$RESULT" | rg '^Success' >/dev/null || { echo 'TV rejected install' >&2; exit 1; }
"$ADB" -s "$SERIAL" shell pm list packages --user "$TARGET_USER" local.tv.launcher | tr -d '\r' | rg -Fx 'package:local.tv.launcher' >/dev/null || { echo 'Installed package not found' >&2; exit 1; }
"$ADB" -s "$SERIAL" shell dumpsys package local.tv.launcher | rg 'versionCode=2 ' >/dev/null || { echo 'Installed version mismatch' >&2; exit 1; }
printf '%s\n' 'Installed version 2 for the explicitly selected profile. Default Home and system packages were not changed.'
