#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$(uname)" = Darwin ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export JAVA_HOME
fi
exec ./gradlew testDebugUnitTest lintDebug assembleDebug "$@"
