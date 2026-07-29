#!/usr/bin/env bash
# Gradle 빌드 → bridge/data/tesla-watch.apk (다운로드 /watch.apk)
set -e
cd "$(dirname "$0")"

./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
cp "$APK" "/c/Users/smile/tesla-bridge/bridge/data/tesla-watch.apk"
echo "빌드·서명 OK → bridge/data/tesla-watch.apk"
