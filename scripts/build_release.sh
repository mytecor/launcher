#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

if [ "$(uname)" = Darwin ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export JAVA_HOME
fi

KEYSTORE_DIR="$HOME/.android/keystores/home-release"
if [ -f "$KEYSTORE_DIR/home-release.p12" ] && [ -f "$KEYSTORE_DIR/password.txt" ]; then
    export ANDROID_KEYSTORE_PATH="$KEYSTORE_DIR/home-release.p12"
    export ANDROID_KEYSTORE_PASSWORD="$(cat "$KEYSTORE_DIR/password.txt")"
    export ANDROID_KEY_ALIAS="home"
    export ANDROID_KEY_PASSWORD="$(cat "$KEYSTORE_DIR/password.txt")"
fi

VERSION="${1:-1.0.0}"
VERSION_CODE="${2:-1}"

./gradlew testReleaseUnitTest lintRelease assembleRelease \
    -PreleaseVersion="$VERSION" \
    -PreleaseVersionCode="$VERSION_CODE"

echo "Release APK built successfully:"
echo "$(pwd)/app/build/outputs/apk/release/app-release.apk"
