#!/bin/sh
# Linux/macOS entry point for tools/test.ps1. See build.sh for why these
# wrappers hold no logic of their own.
#
#   ./tools/test.sh
#   ./tools/test.sh -Filter bigint
set -eu

if ! command -v pwsh >/dev/null 2>&1; then
    echo "pwsh (PowerShell 7) is required." >&2
    echo "  Debian/Ubuntu: https://learn.microsoft.com/powershell/scripting/install/install-ubuntu" >&2
    echo "  Fedora:        sudo dnf install powershell" >&2
    echo "  Arch:          yay -S powershell-bin" >&2
    echo "  macOS:         brew install --cask powershell" >&2
    exit 1
fi

exec pwsh -NoProfile -File "$(dirname "$0")/test.ps1" "$@"
