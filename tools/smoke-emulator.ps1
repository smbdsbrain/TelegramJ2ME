<#
.SYNOPSIS
    Launch a built MIDlet in MicroEmulator's MIDP runtime and assert it works.

.DESCRIPTION
    The desktop suite runs against build/desktop/classes - classes ProGuard has
    never touched. This runs against dist/*.jar instead, so it is the only
    automated check that the artifact which actually ships still starts: a keep
    rule that stopped covering the code, a stripped resource or a broken
    preverification pass would otherwise reach a handset first.

    dist/<name>.jar is put on the classpath ahead of nothing else - notably
    build/desktop/classes is left off - so every tg.* class resolves from the
    packaged jar. The test harness itself comes from build/desktop/test-classes.

    The run is offline: it never presses Connect, so no network, no Telegram
    account and no RMS profile are involved.

    This is not hardware evidence. MicroEmulator runs on the desktop JVM and
    says nothing about heap, AMS permissions, JAR verification, timing or key
    codes - see docs/emulator-notes.md.

.PARAMETER ArtifactName
    Which dist/<name>.jar to launch. Defaults to both shipped variants,
    "tg" and "tg-min", because the obfuscated one is a different ProGuard
    configuration and can break on its own.

.PARAMETER SkipBuild
    Reuse build/desktop/test-classes as it stands.

.EXAMPLE
    ./tools/smoke-emulator.ps1
    ./tools/smoke-emulator.ps1 -ArtifactName tg-min
#>
[CmdletBinding()]
param(
    [string[]]$ArtifactName = @('tg', 'tg-min'),
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

$tests = Join-RepoPath "build" "desktop" "test-classes"
if (-not (Test-Path (Join-Path (Join-Path $tests "tgtest") "EmulatorSmokeTest.class"))) {
    Write-Bad "the smoke harness is not compiled"
    exit 1
}

$failed = @()
foreach ($name in $ArtifactName) {
    $jar = Join-RepoPath "dist" "$name.jar"
    if (-not (Test-Path $jar)) {
        Write-Bad "dist/$name.jar not found. Build it first:  ./tools/build.ps1 -Target tg -ArtifactName $name"
        exit 1
    }

    # The packaged jar last, and build/desktop/classes absent, so tg.* comes
    # from the artifact rather than from the unshrunk build output.
    $runtimeCp = (@($tests) + $MicroEmuJars + @($jar)) -join $PathSep
    Write-Step "emulator smoke :: dist/$name.jar"
    # MicroEmulator's J2SEFontManager builds AWT font metrics as soon as the
    # device is installed. Headless is what a CI runner and a bare Linux box
    # have, and without this the toolkit tries for an X display and dies.
    # The -D must be quoted: PowerShell otherwise splits an unquoted
    # -Dfoo.bar=baz at the first dot and java sees two broken arguments.
    & $Jdk8Java "-Djava.awt.headless=true" -cp $runtimeCp tgtest.EmulatorSmokeTest $name
    if ($LASTEXITCODE -ne 0) { $failed += $name }
    Write-Host ""
}

if ($failed.Count -gt 0) {
    Write-Bad ("emulator smoke failed: " + ($failed -join ", "))
    exit 1
}
Write-Ok "emulator smoke passed for: $($ArtifactName -join ', ')"
exit 0
