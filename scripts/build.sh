#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
. "$(dirname "$0")/jdk.sh"
exec ./gradlew assembleDebug "$@"
