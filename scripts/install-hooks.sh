#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
hooks_dir="$(git rev-parse --git-dir)/hooks"
mkdir -p "$hooks_dir"
cp .githooks/pre-commit "$hooks_dir/pre-commit"
chmod +x "$hooks_dir/pre-commit"
echo "Installed pre-commit hook (unit tests + lint)."
