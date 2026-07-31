<#
.SYNOPSIS
    Build and run the desktop test suite.

.DESCRIPTION
    Compiles src/ exactly as the device build does (source 1.3, CLDC subset),
    compiles test/ on top of it, then runs tgtest.AllTests.

    The point of the desktop profile is speed: crypto vectors, TL round trips
    and - later - a full MTProto handshake against a Telegram test DC all run
    here in seconds, using the same source that ships to the handset. It does
    not replace device verification; it makes device verification a
    confirmation instead of a debugging session.

    Also runs check-api.py against the device classes when they are present, so
    a CLDC violation surfaces from ./tools/test.ps1 too.

.PARAMETER Filter
    Substring match on the test name, e.g. -Filter bigint

.EXAMPLE
    ./tools/test.ps1
    ./tools/test.ps1 -Filter bigint
#>
[CmdletBinding()]
param(
    [string]$Filter = "",
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
    if ($LASTEXITCODE -ne 0) { Write-Bad "desktop build failed"; exit 1 }
    Write-Host ""
}

$classes = Join-RepoPath "build" "desktop" "classes"
$tests   = Join-RepoPath "build" "desktop" "test-classes"
if (-not (Test-Path $tests)) {
    Write-Bad "no compiled tests - expected $tests"
    exit 1
}

Write-Step "tgtest.AllTests"
$runtimeCp = (@($classes, $tests) + $MicroEmuJars) -join $PathSep
$runArgs = @("-cp", $runtimeCp, "tgtest.AllTests")
if ($Filter) { $runArgs += $Filter }
& $Jdk8Java @runArgs
$testExit = $LASTEXITCODE

# The API check is cheap and catches the failure mode the desktop profile is
# blind to by construction: a J2SE call that compiles fine here and breaks on
# the handset.
$deviceClasses = Join-RepoPath "build" "device" "classes"
if (Test-Path $deviceClasses) {
    Write-Host ""
    Write-Step "check-api.py on the device classes"
    $py = Get-PythonCommand
    if ($py) {
        & $py (Join-Path $PSScriptRoot "check-api.py") $deviceClasses
        if ($LASTEXITCODE -ne 0) { exit 1 }
    } else {
        Write-Warn2 "python 3 not on PATH - skipped"
    }
} else {
    Write-Warn2 ("no device classes yet - run {0} -Target probe to include the API check" -f $(if ($OnWindows) { ".\tools\build.ps1" } else { "./tools/build.sh" }))
}

exit $testExit
