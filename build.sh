#!/bin/sh
set -eu
cd "$(dirname "$0")"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
if [ -n "${LAUNCHER_JAVA_HOME:-}" ]; then
  export JAVA_HOME="$LAUNCHER_JAVA_HOME"
elif [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
else
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export PATH="$JAVA_HOME/bin:$PATH"
if [ ! -f launcher-signing.p12 ]; then
  keytool -genkeypair -keystore launcher-signing.p12 -storetype PKCS12 -storepass local-launcher-build -keypass local-launcher-build -alias launcher -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Local Scenic Home'
fi
GRADLE="${GRADLE_BIN:-}"
if [ -z "$GRADLE" ]; then
  for candidate in "$HOME"/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle; do
    if [ -x "$candidate" ]; then GRADLE="$candidate"; break; fi
  done
fi
if [ -z "$GRADLE" ]; then
  mkdir -p .tooling
  if [ ! -x .tooling/gradle-8.7/bin/gradle ]; then
    curl -fL --retry 2 https://services.gradle.org/distributions/gradle-8.7-bin.zip -o .tooling/gradle.zip
    curl -fL https://services.gradle.org/distributions/gradle-8.7-bin.zip.sha256 -o .tooling/gradle.zip.sha256
    EXPECTED="$(tr -d '\n\r ' < .tooling/gradle.zip.sha256)"
    ACTUAL="$(shasum -a 256 .tooling/gradle.zip | cut -d ' ' -f 1)"
    [ "$EXPECTED" = "$ACTUAL" ] || { echo 'Gradle checksum mismatch' >&2; exit 1; }
    unzip -q .tooling/gradle.zip -d .tooling
  fi
  GRADLE="$PWD/.tooling/gradle-8.7/bin/gradle"
fi
"$GRADLE" --no-daemon :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
cp app/build/outputs/apk/release/app-release.apk fudge-launcher.apk
"$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --verbose fudge-launcher.apk 2>/dev/null || "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify --verbose fudge-launcher.apk
shasum -a 256 fudge-launcher.apk
