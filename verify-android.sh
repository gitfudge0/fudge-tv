#!/bin/sh
# Explicitly opt into installing the verification app/test pair on a local emulator.
set -eu
cd "$(dirname "$0")"
if [ "$#" -ne 2 ] || [ "$1" != '--serial' ]; then
  echo 'Usage: ./verify-android.sh --serial emulator-5560' >&2
  exit 2
fi
SERIAL="$2"
case "$SERIAL" in
  emulator-*)
    PORT="${SERIAL#emulator-}"
    case "$PORT" in ''|*[!0-9]*) echo 'Invalid emulator serial' >&2; exit 2 ;; esac
    ;;
  *) echo 'This verification script accepts local emulators only; use the validated installer for a TV.' >&2; exit 2 ;;
esac
ADB="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ "$("$ADB" -s "$SERIAL" get-state)" = 'device' ] || exit 1
[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = '1' ] || {
  echo 'Selected target is not an emulator' >&2; exit 1;
}
./build.sh :app:assembleVerification :app:assembleVerificationAndroidTest
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/verification/app-verification.apk
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
mkdir -p .local/native-verification
LOG=".local/native-verification/instrumentation-$SERIAL.log"
if "$ADB" -s "$SERIAL" shell am instrument -w \
    local.tv.launcher.test/androidx.test.runner.AndroidJUnitRunner > "$LOG" 2>&1; then
  cat "$LOG"
else
  cat "$LOG"
  echo "Instrumentation command failed; raw output: $LOG" >&2
  exit 1
fi
python3 - "$LOG" <<'PYCODE'
from pathlib import Path
import re
import sys
path = Path(sys.argv[1])
output = path.read_text()
completed = re.search(r"OK\s*\((\d+) tests?\)", output)
failed = re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", output)
if not completed or int(completed.group(1)) == 0 or failed:
    raise SystemExit(f"Instrumentation did not complete successfully; raw output: {path}")
print(f"Instrumentation verified; raw output: {path}")
PYCODE
