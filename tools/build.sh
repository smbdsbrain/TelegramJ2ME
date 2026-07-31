#!/bin/sh
# Linux/macOS entry point for tools/build.ps1.
#
# There is deliberately no build logic here. build.ps1 is cross-platform, and a
# second implementation of the same ~450 lines would drift from it within a
# release - the version string, the ProGuard argument order and the exact
# MIDlet-Jar-Size handling all have to stay in one place.
#
#   ./tools/build.sh -Target tg -Env production -Release
#
# Arguments are passed straight through, so every flag documented for build.ps1
# works here unchanged.
set -eu

if ! command -v pwsh >/dev/null 2>&1; then
    echo "pwsh (PowerShell 7) is required." >&2
    echo "  Debian/Ubuntu: https://learn.microsoft.com/powershell/scripting/install/install-ubuntu" >&2
    echo "  Fedora:        sudo dnf install powershell" >&2
    echo "  Arch:          yay -S powershell-bin" >&2
    echo "  macOS:         brew install --cask powershell" >&2
    exit 1
fi

exec pwsh -NoProfile -File "$(dirname "$0")/build.ps1" "$@"
