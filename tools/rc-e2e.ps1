<#
.SYNOPSIS
    Run the marked two-account scenario against one exact packaged release JAR.

.DESCRIPTION
    Uses the saved live (account A) and bigchats (account B) MicroEmulator
    profiles. Self usernames are read through the authorized profile UI, kept
    only in an ignored local state directory, compared to prove the accounts
    differ, and never printed. The receiver stays connected while the sender
    sends and edits, so the observations are live updates rather than a later
    history fetch.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ArtifactName,
    [string]$ProfileA = 'live',
    [string]$ProfileB = 'bigchats',
    [string[]]$JavaArgs = @('-Xmx32m')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

if ($ArtifactName -notmatch '^J2MEgram-(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:-min)?$') {
    Write-Bad 'Packaged E2E requires a J2MEgram-<semver>[-min] artifact name'
    exit 1
}
$artifactJar = Join-RepoPath 'dist' "$ArtifactName.jar"
if (-not (Test-Path -LiteralPath $artifactJar)) {
    Write-Bad "dist/$ArtifactName.jar not found"
    exit 1
}

$stateRoot = Join-RepoPath 'local' 'rc-e2e'
New-Item -ItemType Directory -Force -Path $stateRoot | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$state = Join-Path $stateRoot "$ArtifactName-$stamp"
New-Item -ItemType Directory -Path $state | Out-Null
$marker = 'TJ2ME-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-'
$marker += ([guid]::NewGuid().ToString('N').Substring(0, 8))
[IO.File]::WriteAllText((Join-Path $state 'marker'), $marker,
        [Text.Encoding]::UTF8)

$driver = Join-Path $PSScriptRoot 'drive-emulator.ps1'
function Invoke-Role([string]$scenario, [string]$profile, [string]$side) {
    & $driver -Scenario $scenario -Env production -EmulatorProfile $profile `
        -SkipBuild -ArtifactName $ArtifactName -StateDir $state -Role $side `
        -NoDiagTail -JavaArgs $JavaArgs | ForEach-Object { Write-Host $_ }
    $code = $LASTEXITCODE
    return $code
}

Write-Step "RC E2E identity check :: $ArtifactName"
$identityA = Invoke-Role 'rc-identity' $ProfileA 'a'
$identityB = Invoke-Role 'rc-identity' $ProfileB 'b'
if ($identityA -ne 0 -or $identityB -ne 0) {
    Write-Bad 'could not obtain both authorized self profiles'
    exit 1
}
$a = [IO.File]::ReadAllText((Join-Path $state 'a.username')).Trim()
$b = [IO.File]::ReadAllText((Join-Path $state 'b.username')).Trim()
if (-not $a -or -not $b -or $a -eq $b) {
    Write-Bad 'saved profiles are not two distinct username-addressable accounts'
    exit 1
}
Write-Ok 'authorized profiles belong to distinct accounts (identities withheld)'

function Quote-ProcessArg([string]$value) {
    return '"' + $value.Replace('"', '\"') + '"'
}
$receiverOut = Join-Path $state 'receiver.out'
$receiverErr = Join-Path $state 'receiver.err'
$receiverArgs = @(
    '-NoProfile', '-File', $driver,
    '-Scenario', 'rc-receiver', '-Env', 'production',
    '-EmulatorProfile', $ProfileB, '-SkipBuild',
    '-ArtifactName', $ArtifactName, '-StateDir', $state,
    '-Role', 'b', '-NoDiagTail', '-JavaArgs'
) + $JavaArgs
$receiverLine = ($receiverArgs | ForEach-Object {
    Quote-ProcessArg ([string] $_)
}) -join ' '

Write-Step "RC E2E marked two-account flow :: $ArtifactName"
$receiver = Start-Process -FilePath (Get-Command pwsh).Source `
    -ArgumentList $receiverLine -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $receiverOut -RedirectStandardError $receiverErr
$senderExit = 1
try {
    $senderExit = Invoke-Role 'rc-sender' $ProfileA 'a'
    if ($senderExit -ne 0) {
        [IO.File]::WriteAllText((Join-Path $state 'sender-failed'), 'failed')
    }
    if (-not $receiver.WaitForExit(150000)) {
        Stop-Process -Id $receiver.Id -Force
        Write-Bad 'receiver did not finish after the sender'
        exit 1
    }
} finally {
    if (-not $receiver.HasExited) { Stop-Process -Id $receiver.Id -Force }
}

if ($senderExit -ne 0 -or $receiver.ExitCode -ne 0) {
    Write-Bad "marked scenario failed (sender=$senderExit receiver=$($receiver.ExitCode))"
    Write-Step 'attempting cleanup of the marked message after failure'
    $cleanupExit = Invoke-Role 'rc-cleanup' $ProfileA 'a'
    if ($cleanupExit -eq 0) {
        Remove-Item -LiteralPath (Join-Path $state 'a.username') -Force
        Remove-Item -LiteralPath (Join-Path $state 'b.username') -Force
        Remove-Item -LiteralPath (Join-Path $state 'marker') -Force
        Write-Ok 'failure cleanup completed; no marker retained'
    } else {
        Write-Warn2 'automatic failure cleanup failed; private marker retained'
    }
    Write-Host "         private diagnostics: $state" -ForegroundColor Yellow
    exit 1
}

$manual = Test-Path -LiteralPath (Join-Path $state 'manual-cleanup-required')
$evidence = @(
    "artifact=$ArtifactName",
    'accounts=distinct',
    'incoming-update=pass',
    'search-history-around=pass',
    'full-text-entities-cancel=pass',
    'live-edit=pass',
    'edited-label=pass',
    $(if ($manual) { 'cleanup=manual' } else { 'cleanup=pass' })
)
[IO.File]::WriteAllText((Join-Path $state 'evidence.txt'),
        ($evidence -join "`n") + "`n", [Text.Encoding]::UTF8)

# These three files contain the only personal/test text in the state directory.
# Remove them after confirmed cleanup; retain marker only when manual removal is
# required so the caller can report the exact value without reconstructing it.
Remove-Item -LiteralPath (Join-Path $state 'a.username') -Force
Remove-Item -LiteralPath (Join-Path $state 'b.username') -Force
if (-not $manual) { Remove-Item -LiteralPath (Join-Path $state 'marker') -Force }

if ($manual) {
    Write-Warn2 'feature flow passed, but server cleanup was not confirmed'
    Write-Warn2 "private marker retained under $state for manual deletion"
} else {
    Write-Ok 'marked message deleted for everyone and server cleanup confirmed'
}
Write-Ok "exact packaged RC E2E passed: $ArtifactName"
