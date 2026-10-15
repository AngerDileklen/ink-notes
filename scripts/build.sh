#!/bin/bash
# Builds Ink Notes.   bash scripts/build.sh           -> debug APK   dist/ink-notes-debug.apk
#                     bash scripts/build.sh release   -> signed APK  dist/ink-notes.apk (needs keystore.properties)
set -e
cd "$(dirname "$0")/.."
source scripts/env.sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
mkdir -p dist
if [ "$1" = "release" ]; then
  [ -f keystore.properties ] || { echo "Copy keystore.properties.example to keystore.properties and fill it in." >&2; exit 1; }
  ./gradlew --no-daemon :app:assembleRelease
  cp app/build/outputs/apk/release/app-release.apk dist/ink-notes.apk
  ls -la dist/ink-notes.apk
else
  ./gradlew --no-daemon :app:assembleDebug
  cp app/build/outputs/apk/debug/app-debug.apk dist/ink-notes-debug.apk
  ls -la dist/ink-notes-debug.apk
fi
