<#
.SYNOPSIS
    Render privacy-safe README screenshots from the real Java ME Canvas UI.

.DESCRIPTION
    Builds the desktop test profile, then renders fixed fictional dialogs and
    conversations at the target handset's native 320x240 resolution. The PNGs
    are scaled 2x with nearest-neighbour interpolation for crisp display on
    GitHub. No MIDlet, RMS profile, Telegram account or network is used.
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

if (-not $Jdk8Home) {
    Write-Bad "JDK 8 not found. Run ./tools/bootstrap.ps1 first."
    exit 1
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build.ps1") -Profile desktop
    if ($LASTEXITCODE -ne 0) {
        Write-Bad "desktop build failed"
        exit 1
    }
    Write-Host ""
}

$classes = Join-RepoPath "build" "desktop" "classes"
$tests = Join-RepoPath "build" "desktop" "test-classes"
$resources = Join-Path $RepoRoot "res"
$output = Join-RepoPath "docs" "screenshots"
if (-not (Test-Path (Join-Path (Join-Path $tests "tgtest") "ShowcaseRenderer.class"))) {
    Write-Bad "showcase renderer is not compiled"
    exit 1
}

$runtimeCp = (@($classes, $tests, $resources) + $MicroEmuJars) -join $PathSep
Write-Step "privacy-safe showcase screenshots"
# Headless because this only ever writes PNGs, and because AWT otherwise wants a
# display. Note that font rasterisation still differs between platforms: the
# committed screenshots were rendered on Windows, so regenerating them elsewhere
# produces visually different images. Review before committing a cross-OS rerun.
& $Jdk8Java "-Djava.awt.headless=true" -cp $runtimeCp tgtest.ShowcaseRenderer $output
exit $LASTEXITCODE
