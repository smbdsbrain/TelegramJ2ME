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
    navigate search for a chat and press Back, then open one and use Jump to
             latest and First unread. Every one of those is a transition rather
             than a request, which is why none had automated coverage: the
             desktop suite can prove what a page merge does to an array and
             cannot press Back. -ChatTitle is the search query here.
    chats    scroll the chat list down -Pages screens and back up, reporting
             the retained count, requests per page, bytes per dialog and
             whether the reader's row moved under them. The chat-list twin of
             scroll: what has to grow is the retained count, and what has to
             track pages rather than keypresses is the request count. It never
             opens a chat, so it reads and mutates nothing.
    hashprobe does messages.getDialogs honour a hash, and of what? Sends each
             candidate vector back to the server twice in a row and reports
             which - if any - comes back messages.dialogsNotModified. Uses the
             profile's stored session but does not start the client. -Pages is
             the page size here. Lists dialogs and nothing else.
    rc-identity, rc-sender, rc-receiver and rc-cleanup are private roles used by
             tools/rc-e2e.ps1 against an exact packaged normal or minified JAR.
             They exchange usernames only through -StateDir and never print
             them. Do not invoke these roles by hand.

    Everything except probe contacts real Telegram servers.

.PARAMETER Mode
    Connection mode to select in Settings first: Auto, Direct,
    "Direct obfuscated", MTProxy or HTTP.

    AsIs is different: it does not open Settings at all and connects on
    whatever the profile has stored. Pair it with a fresh -EmulatorProfile to
    drive a genuine first launch - every other value visits Settings and saves,
    and saving is itself the repair for anything the stored configuration got
    wrong, so no other value can observe an untouched profile.

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

.PARAMETER SingleSocket
    scroll only. Turn "Single socket mode" on in Settings before connecting.
    That mode refuses a second concurrent connection outright, so it is where a
    path that quietly assumed it could open one fails - and the mode least
    likely to be exercised by hand.

.PARAMETER Pages
    scroll only. Screens to page up before turning round. With no -ChatTitle,
    the already-selected first chat is used and its title is never printed.
    Each one is a real
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

.PARAMETER NoDiagTail
    Do not follow the diagnostic ring. The tail runs inside the heap under
    measurement, so this is the control: if a number moves when the tail is
    switched off, the number was about the observer. Without it the run reports
    only the last hundred lines, which at the bottom of the ladder is the last
    hundred errors.

.PARAMETER EmulatorProfile
    Isolated, persistent RMS under local/microemulator/<name>/, the same
    convention run-emulator.ps1 uses. "default" keeps the host user.home.

    The auth-key store is copied before the run and put back if the run leaves
    it empty. A run that dies of OutOfMemoryError mid-write truncates it, and a
    truncated tgkeys.rs is a signed-out profile that only the user's phone can
    restore - which is a heavy price for a measurement.

.PARAMETER Env
    test or production data centres.

.PARAMETER JavaArgs
    Extra JVM options, placed before -cp. Mainly -Xmx: MicroEmulator has no
    heap option of its own but runs the MIDlet on the host JVM.

.EXAMPLE
    ./tools/drive-emulator.ps1 -Scenario probe -EmulatorProfile heapcheck
    ./tools/drive-emulator.ps1 -Scenario route -Mode Auto -Env production
    ./tools/drive-emulator.ps1 -Scenario route -Mode AsIs -Env production -EmulatorProfile firstrun
    ./tools/drive-emulator.ps1 -Scenario login -Phone +10000000000 -CodeFile code.txt -Env production
    ./tools/drive-emulator.ps1 -Scenario scroll -ChatTitle "Some channel" -Pages 40 -Env production
#>
[CmdletBinding()]
param(
    [ValidateSet('probe', 'route', 'login', 'session', 'photos', 'minheap',
                 'scroll', 'chats', 'hashprobe', 'navigate', 'rc-identity',
                 'rc-sender', 'rc-receiver', 'rc-cleanup')]
    [string]$Scenario = 'probe',
    [string]$Mode = 'Auto',
    [string]$Phone = '',
    [string]$CodeFile = '',
    [string]$SendText = '',
    [string]$ChatTitle = '',
    [int]$Pages = 40,
    [switch]$SingleSocket,
    [ValidateSet('on', 'off')][string]$Pictures = 'on',
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$EmulatorProfile = 'driver',
    [ValidateSet('test', 'production')][string]$Env = 'test',
    [int]$BallastKB = 0,
    [switch]$Remeasure,
    [switch]$NoDiagTail,
    [switch]$SkipBuild,
    [string]$ArtifactName = '',
    [string]$StateDir = '',
    [ValidateSet('', 'a', 'b')][string]$Role = '',
    [string[]]$JavaArgs = @()
)

# A background RC receiver is started through pwsh -File. PowerShell cannot
# bind several dash-prefixed values to one string[] parameter across that
# native process boundary: the second -D... is parsed as a script parameter.
# The slow-network wrapper therefore supplies additional JVM options through a
# narrow inherited environment variable. Ordinary runs leave it absent.
$e2eJavaArgs = [Environment]::GetEnvironmentVariable(
        'TGJ2ME_E2E_JAVA_ARGS')
# Identity and best-effort cleanup are orchestration pre/postconditions, not
# part of the slow-link interaction under test. Keeping them on the ordinary
# route avoids turning a transient profile lookup into a false NET-04 result;
# both live sender and receiver remain shaped for the entire marked flow.
if ($e2eJavaArgs -and $Scenario -in @('rc-sender', 'rc-receiver')) {
    $JavaArgs += @($e2eJavaArgs.Split('|') | Where-Object { $_ })
}

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

$isRcRole = $Scenario.StartsWith('rc-')
if ($isRcRole -and (-not $ArtifactName -or -not $StateDir -or -not $Role)) {
    Write-Bad "$Scenario needs -ArtifactName, -StateDir and -Role"
    exit 1
}
if ($isRcRole -and -not $SkipBuild) {
    Write-Bad "$Scenario drives an exact packaged JAR and requires -SkipBuild"
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

# --------------------------------------------------------------------------
# A signed-in profile has to survive the experiments it exists for.
#
# MicroEmulator's file-backed record store is rewritten wholesale, and a run
# that dies of OutOfMemoryError part-way through a write leaves the file at zero
# bytes. Observed: a minheap run at 1410 KB of ballast truncated tgkeys.rs, and
# every run after it reported a signed-out account - the stored auth key was
# gone and only the user's phone could put it back.
#
# The store is small. Copying it before the run and putting it back if the run
# emptied it costs nothing and is the difference between a failed measurement
# and a lost session.
# --------------------------------------------------------------------------
$keyStore = $null
$keyBackup = $null
if ($EmulatorProfile -ne 'default') {
    $keyStore = Join-Path $profileHome ".microemulator/suite-null/tgkeys.rs"
    if ((Test-Path $keyStore) -and (Get-Item $keyStore).Length -gt 0) {
        $keyBackup = "$keyStore.bak"
        Copy-Item $keyStore $keyBackup -Force
    }
}

function Restore-KeyStoreIfEmptied {
    if (-not $keyStore) { return }
    if ((Test-Path $keyStore) -and (Get-Item $keyStore).Length -gt 0) { return }

    if ($keyBackup -and (Test-Path $keyBackup)) {
        Copy-Item $keyBackup $keyStore -Force
        Write-Warn2 "tgkeys.rs was emptied by this run; restored from the backup taken before it"
        return
    }
    # No backup to put back, so at least leave the profile able to make a new
    # store. A zero-byte file is worse than an absent one: MicroEmulator reads
    # its header on every open and throws EOFException, so the profile cannot
    # even be signed in again by hand until the file is gone.
    if (Test-Path $keyStore) {
        Remove-Item $keyStore -Force
        Write-Warn2 "tgkeys.rs was emptied and there was no backup; removed it so the profile can sign in again"
    }
}

$driverArgs = @($Scenario)
switch ($Scenario) {
    'route'   { $driverArgs += $Mode }
    'login'   { $driverArgs += @($Mode, $Phone, $CodeFile) }
    'session' { $driverArgs += @($Mode, $SendText) }
    'photos'  { $driverArgs += $ChatTitle }
    'minheap' { $driverArgs += @($ChatTitle, $Pictures) }
    'scroll'  { $driverArgs += @($ChatTitle, "$Pages",
                                 $(if ($SingleSocket) { 'single' } else { 'multi' })) }
    'chats'   { $driverArgs += @("$Pages", $Pictures) }
    'hashprobe' { $driverArgs += "$Pages" }
    'navigate'  { $driverArgs += @($Mode, $ChatTitle) }
    'rc-identity' { $driverArgs = @('identity', $StateDir, $Role) }
    'rc-sender'   { $driverArgs = @('sender', $StateDir, $Role) }
    'rc-receiver' { $driverArgs = @('receiver', $StateDir, $Role) }
    'rc-cleanup'  { $driverArgs = @('cleanup', $StateDir, $Role) }
}

Write-Step "emulator driver [$profileLabel] :: $Scenario ($Env)"
if ($Scenario -ne 'probe') {
    Write-Warn2 "this talks to real Telegram servers"
}
Write-Warn2 "emulator success is not hardware evidence - see docs/emulator-notes.md"
Write-Host ""

# res/ on the classpath so the emoji sheet resolves the way it does from a jar.
# RC roles put the exact packaged artifact first and omit desktop production
# classes. Their driver imports only the kept MIDlet entry point and MIDP APIs,
# so the same bytecode works when the rest of the JAR is obfuscated.
if ($isRcRole) {
    $artifactJar = Join-RepoPath 'dist' "$ArtifactName.jar"
    if (-not (Test-Path -LiteralPath $artifactJar)) {
        Write-Bad "dist/$ArtifactName.jar not found"
        exit 1
    }
    $runtimeCp = (@($artifactJar, $tests, $res) + $MicroEmuJars) -join $PathSep
} else {
    $runtimeCp = (@($classes, $tests, $res) + $MicroEmuJars) -join $PathSep
}
# [string[]] is load-bearing. A one-element array on the right of an if()
# is unwrapped to a scalar, and += on a string concatenates instead of
# appending - the two -D options end up glued into one unusable argument.
[string[]]$measureArgs = @("-Dtg.driver.expectenv=$Env")
if ($Remeasure) { $measureArgs += "-Dtg.driver.remeasure=1" }
if ($BallastKB -gt 0) { $measureArgs += "-Dtg.driver.ballast=$BallastKB" }

# The complete diagnostic log, not the last hundred lines of it. Kept beside the
# profile it came from, under local/, which is ignored by Git and excluded by
# the public audit - a driven run against a real account logs peer keys and chat
# titles.
if ($NoDiagTail) {
    $measureArgs += "-Dtg.driver.tail=off"
} else {
    $diagDir = Join-RepoPath "local" "diaglogs"
    New-Item -ItemType Directory -Force -Path $diagDir | Out-Null
    # Stamped, not overwritten. A sweep runs the same profile and scenario back
    # to back, and a shared path lets a previous run that has not finished
    # exiting write into the file the next one just truncated - which showed up
    # as a duplicated, out-of-order line in a log that was otherwise correct.
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $diagLog = Join-Path $diagDir "$EmulatorProfile-$Scenario-$stamp.log"
    $measureArgs += "-Dtg.driver.diaglog=$diagLog"
}
$driverClass = if ($isRcRole) { 'tgtest.PackagedRcE2EDriver' }
               else { 'tgtest.EmulatorDriver' }
$invocation = @("-Djava.awt.headless=true") + $javaProfileArgs + $measureArgs + $JavaArgs `
              + @("-cp", $runtimeCp, $driverClass) + $driverArgs
& $Jdk8Java @invocation
$driverExit = $LASTEXITCODE
Restore-KeyStoreIfEmptied
exit $driverExit
