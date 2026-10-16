#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

KEYSTORE_DIR="$HOME/.android/keystores/home-release"
if [ -f "$KEYSTORE_DIR/home-release.p12" ] && [ -f "$KEYSTORE_DIR/password.txt" ]; then
    export ANDROID_KEYSTORE_PATH="$KEYSTORE_DIR/home-release.p12"
    ANDROID_KEYSTORE_PASSWORD=$(cat "$KEYSTORE_DIR/password.txt")
    export ANDROID_KEYSTORE_PASSWORD
    export ANDROID_KEY_ALIAS="home"
    export ANDROID_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
fi

if [ "$#" -gt 2 ]; then
    echo "Usage: $0 [version [version-code]]" >&2
    exit 1
fi
if [ "$#" -eq 2 ]; then
    set -- "-PreleaseVersion=$1" "-PreleaseVersionCode=$2"
elif [ "$#" -eq 1 ]; then
    set -- "-PreleaseVersion=$1"
fi

./gradlew testReleaseUnitTest lintRelease assembleRelease "$@"

echo "Release APK built successfully:"
echo "$(pwd)/app/build/outputs/apk/release/app-release.apk"
