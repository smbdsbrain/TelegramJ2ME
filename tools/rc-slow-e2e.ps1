<#
.SYNOPSIS
    Run exact packaged release E2E under a small heap and deterministic slow network.

.DESCRIPTION
    Uses the same two saved production profiles and cleanup contract as
    rc-e2e.ps1, but shapes MidpTransport inside the exact packaged JAR. Reads
    are fragmented into bounded chunks and all socket I/O is paced.
    The sender additionally reproduces the reaction foreground-worker race.
#>
[CmdletBinding()]
param(
    [string[]]$ArtifactName = @(
        'J2MEgram-1.3.0-rc1',
        'J2MEgram-1.3.0-rc1-min'
    ),
    [ValidateRange(0, 1000)][int]$DelayMs = 10,
    [ValidateRange(32, 16384)][int]$ChunkBytes = 1024,
    [string]$ProfileA = 'live',
    [string]$ProfileB = 'bigchats'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$runner = Join-Path $PSScriptRoot 'rc-e2e.ps1'
$extraJavaArgs = @(
    '-Dtg.e2e.network=slow',
    "-Dtg.e2e.delayMs=$DelayMs",
    "-Dtg.e2e.chunkBytes=$ChunkBytes",
    '-Dtg.driver.reactionflow=1'
)
$previousExtra = [Environment]::GetEnvironmentVariable(
        'TGJ2ME_E2E_JAVA_ARGS')
try {
    $env:TGJ2ME_E2E_JAVA_ARGS = $extraJavaArgs -join '|'
    foreach ($artifact in $ArtifactName) {
        Write-Step "slow-network exact packaged E2E :: $artifact"
        Write-Host "    heap=-Xmx32m delay=${DelayMs}ms chunk=${ChunkBytes}B"
        & $runner -ArtifactName $artifact -ProfileA $ProfileA `
            -ProfileB $ProfileB -JavaArgs '-Xmx32m'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}
finally {
    if ($null -eq $previousExtra) {
        Remove-Item Env:TGJ2ME_E2E_JAVA_ARGS -ErrorAction SilentlyContinue
    } else {
        $env:TGJ2ME_E2E_JAVA_ARGS = $previousExtra
    }
}

Write-Ok ('slow-network RC E2E passed: ' + ($ArtifactName -join ', '))
