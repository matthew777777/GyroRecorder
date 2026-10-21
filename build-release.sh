#!/bin/sh
# Canonical build: release APK only (signed with the local debug key, see README).
set -e
cd "$(dirname "$0")"
./gradlew :app:assembleRelease "$@"
echo "APK: app/build/outputs/apk/release/app-release.apk"
