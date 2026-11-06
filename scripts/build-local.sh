#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
config_file="${LAST_MEETING_BUILD_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/lastmeeting/build.env}"

# This optional, user-owned shell profile supplies machine-specific tool paths.
if [[ -f "$config_file" ]]; then
    source "$config_file"
fi

if [[ $# -eq 0 ]]; then
    set -- assembleDebug testDebugUnitTest lintDebug
fi

if [[ -n "${LAST_MEETING_AAPT2:-}" ]]; then
    if [[ ! -x "$LAST_MEETING_AAPT2" || "${LAST_MEETING_AAPT2##*/}" != aapt2 ]]; then
        echo "LAST_MEETING_AAPT2 must point to an executable named aapt2: $LAST_MEETING_AAPT2" >&2
        exit 1
    fi
    set -- "-Pandroid.aapt2FromMavenOverride=$LAST_MEETING_AAPT2" "$@"
elif [[ "$(uname -s)" == Linux && "$(uname -m)" =~ ^(aarch64|arm64)$ ]]; then
    echo "Configure an ARM-compatible AAPT2 wrapper in $config_file; see README.md." >&2
    exit 1
fi

if [[ -n "${LAST_MEETING_GRADLE_MAX_WORKERS:-}" ]]; then
    set -- "--max-workers=$LAST_MEETING_GRADLE_MAX_WORKERS" "$@"
fi

cd -- "$repo_dir"
exec ./gradlew "$@"
