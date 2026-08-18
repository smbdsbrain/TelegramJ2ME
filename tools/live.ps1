<#
.SYNOPSIS
    Run a live test against a real Telegram data centre.

.DESCRIPTION
    These are not unit tests: they need the network, they talk to somebody
    else's servers, and they take seconds rather than milliseconds. They are
    kept out of ./tools/test.ps1 for exactly that reason.

    The code they exercise is the same CLDC-subset source that ships to the
    handset - only the socket implementation differs (tgtest.SeTransport in
    place of tg.plat.MidpTransport). That is what makes it possible to debug
    MTProto against a real DC with a real debugger, long before a phone is
    involved.

    Which data centres are used follows the build:  ./tools/build.ps1 -Env

.PARAMETER Scenario
    handshake  - full authorization key exchange (req_pq_multi .. dh_gen_ok)
    config     - handshake, then an encrypted help.getConfig
    login      - interactive sign-in, persists the session
    dialogs    - list dialogs using a stored session
    dialog-hash- does messages.getDialogs honour a hash, and of what? (no writes)
    reactions  - read global/per-peer allowed reaction policies (no writes)
    send       - send a message using a stored session
    forum      - forum topics end to end against the prepared test group
    updates    - wait for proactive messages/read state and catch-up

.EXAMPLE
    ./tools/live.ps1 handshake
    ./tools/live.ps1 config
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('handshake', 'config', 'obfs-config', 'http-config',
                 'proxy-config', 'login', 'dialogs', 'dialog-hash',
                 'reactions', 'send', 'forum', 'updates')]
    [string]$Scenario = 'handshake',

    [ValidateSet('test', 'production')]
    [string]$Env = 'test',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

if (-not $Jdk8Home) {
    Write-Bad "JDK 8 not found. Run ./tools/bootstrap.ps1 first."
    exit 1
}

& (Join-Path $PSScriptRoot "build.ps1") -Profile desktop -Env $Env
if ($LASTEXITCODE -ne 0) { Write-Bad "desktop build failed"; exit 1 }

$mainClass = @{
    handshake = "tgtest.LiveHandshakeTest"
    config    = "tgtest.LiveConfigTest"
    'obfs-config'  = "tgtest.LiveRouteTest"
    'http-config'  = "tgtest.LiveRouteTest"
    'proxy-config' = "tgtest.LiveRouteTest"
    login     = "tgtest.LiveLoginTest"
    dialogs   = "tgtest.LiveDialogsTest"
    'dialog-hash' = "tgtest.LiveDialogHashTest"
    reactions = "tgtest.LiveReactionsTest"
    send      = "tgtest.LiveSendTest"
    forum     = "tgtest.LiveForumTest"
    updates   = "tgtest.LiveUpdatesTest"
}[$Scenario]

$classes = Join-RepoPath "build" "desktop" "classes"
$tests   = Join-RepoPath "build" "desktop" "test-classes"

Write-Host ""
Write-Step "live :: $Scenario"
Write-Warn2 "this talks to real Telegram servers"
Write-Host ""

$runArgs = @("-cp", (@($classes, $tests) -join $PathSep), $mainClass)
if ($Scenario -eq 'obfs-config')  { $runArgs += 'obfuscated' }
if ($Scenario -eq 'http-config')  { $runArgs += 'http' }
if ($Scenario -eq 'proxy-config') { $runArgs += 'proxy' }
if ($Rest) { $runArgs += $Rest }
& $Jdk8Java @runArgs
exit $LASTEXITCODE
