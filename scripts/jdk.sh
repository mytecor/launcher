# shellcheck shell=sh
# POSIX helper, sourced by gradlew and build scripts.
# Picks a JDK 17 without calling macOS java_home first — that scan can stall
# for tens of seconds when the runtime lives outside /Library/Java.

if [ -n "${ZSH_VERSION:-}" ]; then
    setopt NULL_GLOB
fi

_jdk_usable() {
    [ -n "${1:-}" ] && [ -x "$1/bin/java" ] || return 1
    # Homebrew's unsuffixed openjdk is currently 27; Gradle 8 dies on it
    # with a one-word "27" error after spawning an incompatible daemon.
    _jdk_major=$("$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
    [ "$_jdk_major" = "17" ]
}

_jdk_home_file=
if [ -n "${APP_HOME:-}" ]; then
    _jdk_home_file="$APP_HOME/.jdk-home"
elif [ -f ./.jdk-home ] || [ -d ./.git ] || [ -f ./gradlew ]; then
    _jdk_home_file="./.jdk-home"
fi

if _jdk_usable "${JAVA_HOME:-}"; then
    export JAVA_HOME
    unset _jdk_home_file
    unset -f _jdk_usable
    return 0
fi

if [ -n "$_jdk_home_file" ] && [ -f "$_jdk_home_file" ]; then
    _jdk_home=$(head -n 1 "$_jdk_home_file")
    if _jdk_usable "$_jdk_home"; then
        JAVA_HOME=$_jdk_home
        export JAVA_HOME
        unset _jdk_home _jdk_home_file
        unset -f _jdk_usable
        return 0
    fi
    unset _jdk_home
fi

_jdk_root="${HOME}/.cache/bottom-launcher"
if [ -d "$_jdk_root" ]; then
    for _jdk_dir in "$_jdk_root"/jdk-17*; do
        if _jdk_usable "$_jdk_dir/Contents/Home"; then
            JAVA_HOME="$_jdk_dir/Contents/Home"
            export JAVA_HOME
            unset _jdk_dir _jdk_root
            unset -f _jdk_usable
            if [ -n "$_jdk_home_file" ]; then
                printf '%s\n' "$JAVA_HOME" > "$_jdk_home_file"
            fi
            unset _jdk_home_file
            return 0
        fi
    done
    unset _jdk_dir
fi
unset _jdk_root

for _jdk_home in \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@17 \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
do
    if _jdk_usable "$_jdk_home"; then
        JAVA_HOME=$_jdk_home
        export JAVA_HOME
        unset _jdk_home
        unset -f _jdk_usable
        if [ -n "$_jdk_home_file" ]; then
            printf '%s\n' "$JAVA_HOME" > "$_jdk_home_file"
        fi
        unset _jdk_home_file
        return 0
    fi
done
unset _jdk_home

if [ "$(uname)" = Darwin ] && [ -x /usr/libexec/java_home ]; then
    _jdk_home=$(/usr/libexec/java_home -v 17 2>/dev/null) || _jdk_home=
    if _jdk_usable "$_jdk_home"; then
        JAVA_HOME=$_jdk_home
        export JAVA_HOME
        if [ -n "$_jdk_home_file" ]; then
            printf '%s\n' "$JAVA_HOME" > "$_jdk_home_file"
        fi
    fi
    unset _jdk_home
fi
unset _jdk_home_file
unset -f _jdk_usable
