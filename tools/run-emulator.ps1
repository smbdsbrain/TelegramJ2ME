<#
.SYNOPSIS
    Launch a built MIDlet suite in an emulator.

.DESCRIPTION
    Default is MicroEmulator, which runs the MIDlet on the desktop JVM. That
    makes it a fast iteration surface with real sockets - good enough to drive
    MTProto against a Telegram DC - but it is NOT evidence about the handset:
    it says nothing about heap limits, the AMS socket permission policy, JAR
    verification, timing, or QWERTY key codes.

    With -UseWtk (requires WTK_HOME) the Sun emulator runs instead. That is the
    reference MIDP/CLDC implementation and the closer approximation of a 2011
    device, so use it before believing anything.

.PARAMETER Target
    probe (default) or tg.

.PARAMETER UseWtk
    Use the Sun WTK emulator instead of MicroEmulator.

.PARAMETER Ota
    WTK only: run the OTA provisioning flow (emulator -Xjam) against the JAD
    rather than launching the JAR directly. This is how installation gets
    exercised before touching a real phone.

.PARAMETER EmulatorProfile
    MicroEmulator only: persistent, isolated emulator state. "default" keeps
    using MicroEmulator's existing unscoped storage, so adding profiles never
    moves or overwrites the account that was already signed in. Any other name
    uses a separate JVM user.home below local/microemulator/ and therefore a
    physically separate MicroEmulator configuration and RMS.

.PARAMETER ArtifactName
    Which dist/<name>.jar to launch. Defaults to the target. Needed to run a
    build made with build.ps1 -ArtifactName - in particular the obfuscated
    release variant, which is the one worth smoke-testing before publishing.

.EXAMPLE
    ./tools/run-emulator.ps1 -Target probe
    ./tools/run-emulator.ps1 -Target probe -UseWtk
    ./tools/run-emulator.ps1 -Target tg -EmulatorProfile 2fa
    ./tools/run-emulator.ps1 -Target tg -ArtifactName TelegramJ2ME-0.1.0-min
#>
[CmdletBinding()]
param(
    [ValidateSet('probe', 'crypto', 'tg')][string]$Target = 'probe',
    [switch]$UseWtk,
    [switch]$Ota,
    [switch]$Headless,
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$EmulatorProfile = 'default',
    [string]$ArtifactName = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

if (-not $ArtifactName) { $ArtifactName = $Target }

$jar = Join-RepoPath "dist" "$ArtifactName.jar"
$jad = Join-RepoPath "dist" "$ArtifactName.jad"

if (-not (Test-Path $jar)) {
    Write-Bad "dist/$ArtifactName.jar not found. Build it first:  ./tools/build.ps1 -Target $Target"
    exit 1
}

# --------------------------------------------------------------------------
# Sun WTK
# --------------------------------------------------------------------------
if ($UseWtk) {
    if ($EmulatorProfile -ne 'default') {
        Write-Bad "-EmulatorProfile is supported by MicroEmulator only."
        exit 1
    }
    if (-not $WtkHome) {
        Write-Bad "WTK not found. Set WTK_HOME, or drop -UseWtk to use MicroEmulator."
        Write-Host "  https://www.oracle.com/java/technologies/java-archive-downloads-javame-downloads.html"
        exit 1
    }
    $emulator = Join-Path (Join-Path $WtkHome "bin") "emulator$ExeSuffix"
    if (-not (Test-Path $emulator)) { Write-Bad "not found: $emulator"; exit 1 }

    if ($Ota) {
        Write-Step "WTK emulator, OTA provisioning (-Xjam) from $jad"
        # file:// plus an absolute path. On Windows that path starts with a drive
        # letter and needs the third slash ("file:///C:/..."); on Linux it already
        # starts with one, so [Uri] is left to produce the right form either way.
        $jadUri = ([Uri](Resolve-Path $jad).Path).AbsoluteUri
        & $emulator "-Xjam:install=$jadUri"
    } else {
        Write-Step "WTK emulator, direct launch of dist/$Target.jar"
        & $emulator "-Xdescriptor:$jad"
    }
    exit $LASTEXITCODE
}

# --------------------------------------------------------------------------
# MicroEmulator
# --------------------------------------------------------------------------
$missing = @($MicroEmuJars | Where-Object { -not (Test-Path $_) })
if ($missing.Count -gt 0) {
    Write-Bad "MicroEmulator jars missing - run ./tools/bootstrap.ps1"
    $missing | ForEach-Object { Write-Host "    $_" }
    exit 1
}

# MicroEmulator 2.0.4 was built against JDK 1.6; the JDK 8 we already require
# runs it cleanly, whereas a current JDK trips over removed AWT/security APIs.
$java = if ($Jdk8Java -and (Test-Path $Jdk8Java)) { $Jdk8Java } else { "java" }
$mainClass = if ($Headless) { "org.microemu.app.Headless" } else { "org.microemu.app.Main" }

$midletClass = @{
    probe  = "tg.app.ProbeMidlet"
    crypto = "tg.app.CryptoMidlet"
    tg     = "tg.app.TgMidlet"
}[$Target]

if ($EmulatorProfile -eq 'default') {
    # Deliberately keep the host user.home. This is MicroEmulator's historical
    # storage at ~/.microemulator and contains the existing signed-in account.
    $javaProfileArgs = @()
    $profileLabel = 'default (existing state)'
} else {
    # MicroEmulator GUI 2.0.4 processes --id after Config has already cached
    # its home path, so --id does not reliably isolate RMS. A JVM property is
    # evaluated before any MicroEmulator class loads and is therefore strict.
    $profileHome = Join-RepoPath "local" "microemulator" $EmulatorProfile
    New-Item -ItemType Directory -Force -Path $profileHome | Out-Null
    $javaProfileArgs = @("-Duser.home=$profileHome")
    $profileLabel = $EmulatorProfile
}

# MicroEmulator keeps every classpath JAR open on Windows. Launching dist/*.jar
# directly therefore prevents the next build from replacing it. Stage binaries
# by content hash and profile: an already-running build can keep its immutable
# file handle while a rebuild and another profile use a different file.
$jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash.Substring(0, 12).ToLowerInvariant()
$profileKey = if ($EmulatorProfile -eq 'default') { 'default' } else { $EmulatorProfile }
$launchDir = Join-RepoPath "build" "emulator" $profileKey
New-Item -ItemType Directory -Force -Path $launchDir | Out-Null
$launchJar = Join-Path $launchDir "$Target-$jarHash.jar"
if (-not (Test-Path $launchJar)) {
    Copy-Item -LiteralPath $jar -Destination $launchJar
}

# Putting the staged JAR on the classpath and naming the MIDlet class starts it
# immediately instead of showing MicroEmulator's launcher list.
$cp = ($MicroEmuJars + @($launchJar)) -join $PathSep

Write-Step "MicroEmulator [$profileLabel] :: $midletClass from dist/$ArtifactName.jar"
Write-Host "    staged binary: $launchJar"
Write-Warn2 "emulator success is not hardware evidence - see docs/emulator-notes.md"
Write-Host ""

# Errors from MicroEmulator's class loader go to stderr and nowhere else. A
# missing dependency there shows up in the emulator as a blank white screen with
# no other symptom, so stderr must not be swallowed.
& $java @javaProfileArgs -cp $cp $mainClass $midletClass
exit $LASTEXITCODE
