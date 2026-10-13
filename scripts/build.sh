#!/bin/bash
# Builds the debug APK and copies it to dist/ for serving to the tablet.
set -e
cd "$(dirname "$0")/.."
source scripts/env.sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle --no-daemon :app:assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/ink-notes.apk
ls -la dist/ink-notes.apk
