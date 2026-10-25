#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "${1:---check}" in
  --check) MODE='--dry-run --set-exit-if-changed' ;;
  --write) MODE='--replace' ;;
  *) echo 'Usage: ./format.sh [--check|--write]' >&2; exit 2 ;;
esac
if [ -n "${LAUNCHER_JAVA_HOME:-}" ]; then
  JAVA="$LAUNCHER_JAVA_HOME/bin/java"
elif [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
  JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java'
elif [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA='java'
fi
FORMATTER='.tooling/google-java-format-1.24.0-all-deps.jar'
EXPECTED='812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1'
mkdir -p .tooling
if [ ! -f "$FORMATTER" ]; then
  curl -fL --retry 2 \
    'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.24.0/google-java-format-1.24.0-all-deps.jar' \
    -o "$FORMATTER"
fi
ACTUAL="$(shasum -a 256 "$FORMATTER" | cut -d ' ' -f 1)"
[ "$EXPECTED" = "$ACTUAL" ] || { echo 'Java formatter checksum mismatch' >&2; exit 1; }
java_files() {
  if command -v rg >/dev/null 2>&1; then
    rg --files app/src -g '*.java' -0
  else
    find app/src -type f -name '*.java' -print0
  fi
}
java_files | xargs -0 "$JAVA" -jar "$FORMATTER" $MODE
