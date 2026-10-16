#!/bin/sh
# Build debug APK and install it on a running emulator/device.
# Use this instead of probing adb in a sandbox — that hangs on the adb server socket.
set -eu
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$ANDROID_HOME/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -x "$ADB" ]; then
    echo "adb not found at $ADB (set ANDROID_HOME)" >&2
    exit 1
fi

./gradlew assembleDebug "$@"

has_device() {
    "$ADB" devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'
}

if ! has_device; then
    echo "No device/emulator online. Start one with ./scripts/emulator.sh" >&2
    exit 1
fi

"$ADB" install -r "$APK"
"$ADB" shell am start -n su.myt.home/.MainActivity
