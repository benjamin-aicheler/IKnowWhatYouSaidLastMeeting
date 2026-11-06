#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

# Optional, user-owned shell profile for machine-specific settings such as
# ANDROID_HOME or GRADLE_USER_HOME. It stays outside the repository.
config_file="${LAST_MEETING_BUILD_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/lastmeeting/build.env}"
if [[ -f "$config_file" ]]; then
    # shellcheck disable=SC1090
    source "$config_file"
fi

if [[ $# -eq 0 ]]; then
    set -- assembleDebug testDebugUnitTest lintDebug
fi

cd -- "$repo_dir"
exec ./gradlew "$@"
