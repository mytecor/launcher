#!/bin/sh
set -eu
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
exec "$ANDROID_HOME/emulator/emulator" -avd android30 "$@"
