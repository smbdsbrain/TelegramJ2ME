<#
.SYNOPSIS
    Drive the client through MicroEmulator's MIDP runtime without a person.

.DESCRIPTION
    tools/smoke-emulator.ps1 deliberately stops before the network, and
    tools/run-emulator.ps1 needs somebody at the keyboard. Everything in
    between - connect, sign in, open a chat under a real session - therefore
    had no automated path at all, and the one place those flows are observable
    is the Log screen of a running emulator.

    This runs tgtest.EmulatorDriver, which presses the same commands by label,
    with a working record store, so auth keys and the stored heap measurement
    persist across runs exactly as they do in the GUI emulator.

    It is not hardware evidence. MicroEmulator runs on the desktop JVM - see
    docs/emulator-notes.md. It replaces clicking, not a handset.

.PARAMETER Scenario
    probe    measure the heap and print the derived budgets. Offline.
    route    set the connection mode, then connect.
    login    connect, request a sign-in code, wait for -CodeFile, sign in.
    session  use the stored session: connect and open the first chat.
    photos   open a named picture-heavy chat and decode its photos. This is the
             scenario the memory budgets exist for; pair it with -JavaArgs -Xmx
             to make the pressure ladder fire.
    minheap  one verdict line per run: what still works at this heap. Built for
             sweeping -Xmx downwards to find the real minimum, with -Pictures
             on and off.
    scroll   read a chat backwards -Pages screens and then forwards again,
             reporting the laid-out line count, the number of history requests
             and the number of transcript reflows. This is the scenario the
             virtualised window exists for: what has to stay flat is the line
             count, and what has to track pages rather than keypresses is the
             request count.

    Everything except probe contacts real Telegram servers.

.PARAMETER Mode
    Connection mode to select in Settings first: Auto, Direct,
    "Direct obfuscated", MTProxy or HTTP.

    Worth knowing: on a profile with nothing stored, a build carrying a
    compiled-in MTProxy pins itself to MTProxy alone, because attempts()
    returns a single route for any mode but Auto. Saving Auto once persists the
    proxy as a fallback and restores the full chain.

.PARAMETER Phone
    login only. International format, e.g. +10000000000.

.PARAMETER CodeFile
    login only. The driver waits for this file to appear and reads the sign-in
    code from it. The code arrives on the user's phone and phoneCodeHash lives
    only in memory, so it has to be entered by the process that asked for it.

.PARAMETER SendText
    session only. When set, the driver opens Saved Messages and sends this
    text. Saved Messages on purpose: the send path needs a real account and
    nobody else should receive a test message.

.PARAMETER ChatTitle
    photos, minheap and scroll. Substring of the conversation title to open.

.PARAMETER Pages
    scroll only. Screens to page up before turning round. Each one is a real
    keypress with a settle delay, so this is also roughly the run time in
    seconds times two.

.PARAMETER BallastKB
    Occupy this much heap before the client starts, so the client sees a
    smaller free heap than -Xmx alone can express. On this JVM every -Xmx
    between 1536k and 3584k resolves to one of those two values, which makes
    the whole feature-phone interval unreachable without this. It is also the
    more faithful model: a handset whose AMS already holds a megabyte is this
    situation exactly.

.PARAMETER Remeasure
    Clear the stored heap measurement before starting, so the client measures
    the heap it is actually given. Needed with -JavaArgs -Xmx: a profile
    otherwise carries the ceiling of whichever JVM first ran it, which under a
    smaller -Xmx is a lie in the dangerous direction.

.PARAMETER EmulatorProfile
    Isolated, persistent RMS under local/microemulator/<name>/, the same
    convention run-emulator.ps1 uses. "default" keeps the host user.home.

.PARAMETER Env
    test or production data centres.

.PARAMETER JavaArgs
    Extra JVM options, placed before -cp. Mainly -Xmx: MicroEmulator has no
    heap option of its own but runs the MIDlet on the host JVM.

.EXAMPLE
    ./tools/drive-emulator.ps1 -Scenario probe -EmulatorProfile heapcheck
    ./tools/drive-emulator.ps1 -Scenario route -Mode Auto -Env production
    ./tools/drive-emulator.ps1 -Scenario login -Phone +10000000000 -CodeFile code.txt -Env production
    ./tools/drive-emulator.ps1 -Scenario scroll -ChatTitle "Some channel" -Pages 40 -Env production
#>
[CmdletBinding()]
param(
    [ValidateSet('probe', 'route', 'login', 'session', 'photos', 'minheap', 'scroll')]
    [string]$Scenario = 'probe',
    [string]$Mode = 'Auto',
    [string]$Phone = '',
    [string]$CodeFile = '',
    [string]$SendText = '',
    [string]$ChatTitle = '',
    [int]$Pages = 40,
    [ValidateSet('on', 'off')][string]$Pictures = 'on',
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$EmulatorProfile = 'driver',
    [ValidateSet('test', 'production')][string]$Env = 'test',
    [int]$BallastKB = 0,
    [switch]$Remeasure,
    [switch]$SkipBuild,
    [string[]]$JavaArgs = @()
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

if (-not $Jdk8Home) {
    Write-Bad "JDK 8 not found. Run ./tools/bootstrap.ps1 first."
    exit 1
}

if ($Scenario -eq 'login' -and (-not $Phone -or -not $CodeFile)) {
    Write-Bad "-Scenario login needs -Phone and -CodeFile"
    exit 1
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build.ps1") -Profile desktop -Env $Env
    if ($LASTEXITCODE -ne 0) { Write-Bad "desktop build failed"; exit 1 }
    Write-Host ""
}

$classes = Join-RepoPath "build" "desktop" "classes"
$tests   = Join-RepoPath "build" "desktop" "test-classes"
$res     = Join-RepoPath "res"

# MicroEmulator's FileRecordStoreManager writes below user.home, which is the
# same lever run-emulator.ps1 pulls for -EmulatorProfile. Keeping the two
# conventions identical means a driver run and a GUI run can share a profile.
if ($EmulatorProfile -eq 'default') {
    $javaProfileArgs = @()
    $profileLabel = 'default (existing state)'
} else {
    $profileHome = Join-RepoPath "local" "microemulator" $EmulatorProfile
    New-Item -ItemType Directory -Force -Path $profileHome | Out-Null
    $javaProfileArgs = @("-Duser.home=$profileHome")
    $profileLabel = $EmulatorProfile
}

$driverArgs = @($Scenario)
switch ($Scenario) {
    'route'   { $driverArgs += $Mode }
    'login'   { $driverArgs += @($Mode, $Phone, $CodeFile) }
    'session' { $driverArgs += @($Mode, $SendText) }
    'photos'  { $driverArgs += $ChatTitle }
    'minheap' { $driverArgs += @($ChatTitle, $Pictures) }
    'scroll'  { $driverArgs += @($ChatTitle, "$Pages") }
}

Write-Step "emulator driver [$profileLabel] :: $Scenario ($Env)"
if ($Scenario -ne 'probe') {
    Write-Warn2 "this talks to real Telegram servers"
}
Write-Warn2 "emulator success is not hardware evidence - see docs/emulator-notes.md"
Write-Host ""

# res/ on the classpath so the emoji sheet resolves the way it does from a jar.
$runtimeCp = (@($classes, $tests, $res) + $MicroEmuJars) -join $PathSep
# [string[]] is load-bearing. A one-element array on the right of an if()
# is unwrapped to a scalar, and += on a string concatenates instead of
# appending - the two -D options end up glued into one unusable argument.
[string[]]$measureArgs = @()
if ($Remeasure) { $measureArgs += "-Dtg.driver.remeasure=1" }
if ($BallastKB -gt 0) { $measureArgs += "-Dtg.driver.ballast=$BallastKB" }
$invocation = @("-Djava.awt.headless=true") + $javaProfileArgs + $measureArgs + $JavaArgs `
              + @("-cp", $runtimeCp, "tgtest.EmulatorDriver") + $driverArgs
& $Jdk8Java @invocation
exit $LASTEXITCODE
