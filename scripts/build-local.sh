#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

# Optional, user-owned shell profile for machine-specific settings such as
# ANDROID_HOME or GRADLE_USER_HOME. It lives in the checkout and is git-ignored.
config_file="$repo_dir/.build.env"
if [[ -f "$config_file" ]]; then
    # shellcheck disable=SC1090
    source "$config_file"
fi

if [[ $# -eq 0 ]]; then
    set -- assembleDebug testDebugUnitTest lintDebug
fi

cd -- "$repo_dir"
exec ./gradlew "$@"
