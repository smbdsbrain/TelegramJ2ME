<#
.SYNOPSIS
    Deterministic 1.0 release-candidate stability gate.

.DESCRIPTION
    Validates the failure-matrix scenario set, runs desktop/Python/schema tests,
    builds normal and minified production JARs, runs both packaged artifacts
    with a 32 MiB host heap, and performs the public audit. Any required step
    failure returns a non-zero exit code.

.PARAMETER SelfTest
    Matrix validates only the matrix contract. Pass and Fail exercise the
    gate's result aggregation without compiling the project.
#>
[CmdletBinding()]
param(
    [ValidateSet('None', 'Matrix', 'Pass', 'Fail')]
    [string]$SelfTest = 'None',
    [string]$MatrixPath = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

if (-not $MatrixPath) {
    $MatrixPath = Join-RepoPath 'docs' 'testing' '1.0-failure-matrix.md'
}

$RequiredIds = @(
    'AUTH-01', 'AUTH-02', 'AUTH-03',
    'OUTBOX-01', 'OUTBOX-02',
    'NET-01', 'NET-02', 'NET-03',
    'UPDATE-01', 'UPDATE-02', 'UPDATE-03',
    'RMS-01', 'RMS-02', 'ACCOUNT-01', 'STALE-01',
    'HISTORY-01', 'HISTORY-02', 'SEARCH-01', 'SEARCH-02',
    'NAV-01', 'HEAP-01', 'PACKAGE-01', 'DEVICE-01'
)
$AllowedStatuses = @('PASS', 'FAIL', 'NOT RUN', 'NOT APPLICABLE')
$Scenarios = @{}
$Failures = New-Object System.Collections.Generic.List[string]

function Add-Failure([string]$id, [string]$detail) {
    $Failures.Add("$id :: $detail")
    Write-Host "    FAIL $id :: $detail" -ForegroundColor Red
}

function Read-Matrix {
    if (-not (Test-Path -LiteralPath $MatrixPath)) {
        Add-Failure 'MATRIX' "not found: $MatrixPath"
        return
    }
    foreach ($line in Get-Content -LiteralPath $MatrixPath) {
        if ($line -notmatch '^\|\s*([A-Z]+-[0-9]{2})\s*\|') { continue }
        $id = $Matches[1]
        $columns = @($line.Split('|') | ForEach-Object { $_.Trim() })
        if ($columns.Count -lt 8) {
            Add-Failure $id 'malformed matrix row'
            continue
        }
        $status = $columns[6]
        if ($Scenarios.ContainsKey($id)) {
            Add-Failure $id 'duplicate scenario ID'
        } elseif (-not $AllowedStatuses.Contains($status)) {
            Add-Failure $id "invalid status '$status'"
        } else {
            $Scenarios[$id] = $status
        }
    }
    foreach ($id in $RequiredIds) {
        if (-not $Scenarios.ContainsKey($id)) {
            Add-Failure $id 'required scenario ID is missing'
        }
    }
    foreach ($id in @($Scenarios.Keys)) {
        if (-not $RequiredIds.Contains($id)) {
            Add-Failure $id 'scenario is not in the gate contract'
        }
    }
}

function Show-ScenarioSummary {
    Write-Host ''
    Write-Host 'Scenario summary'
    foreach ($id in $RequiredIds) {
        $status = if ($Scenarios.ContainsKey($id)) { $Scenarios[$id] }
                  else { 'MISSING' }
        Write-Host ("  {0,-12} {1}" -f $id, $status)
    }
}

function Invoke-GateStep([string]$id, [string]$description,
        [string]$script, [string[]]$arguments) {
    Write-Host ''
    Write-Host "==> $id :: $description" -ForegroundColor Cyan
    & $script @arguments
    if ($LASTEXITCODE -ne 0) {
        Add-Failure $id "exit code $LASTEXITCODE"
    } else {
        Write-Host "    PASS $id" -ForegroundColor Green
    }
}

Read-Matrix

if ($SelfTest -eq 'Matrix') {
    Show-ScenarioSummary
    if ($Failures.Count -gt 0) { exit 1 }
    Write-Host 'stability-gate matrix self-test passed' -ForegroundColor Green
    exit 0
}
if ($SelfTest -eq 'Pass') {
    Write-Host '==> SELF-PASS :: deterministic successful probe' -ForegroundColor Cyan
    Write-Host '    PASS SELF-PASS' -ForegroundColor Green
    Show-ScenarioSummary
    if ($Failures.Count -gt 0) { exit 1 }
    Write-Host 'stability-gate pass self-test passed' -ForegroundColor Green
    exit 0
}
if ($SelfTest -eq 'Fail') {
    Add-Failure 'SELF-FAIL' 'intentional deterministic failure'
    Show-ScenarioSummary
    exit 1
}

$Pwsh = (Get-Command pwsh -ErrorAction Stop).Source
$Test = Join-Path $PSScriptRoot 'test.ps1'
$Build = Join-Path $PSScriptRoot 'build.ps1'
$Smoke = Join-Path $PSScriptRoot 'smoke-emulator.ps1'
$Audit = Join-Path $PSScriptRoot 'audit-public.ps1'

Invoke-GateStep 'TEST' 'desktop, Python, schema, and CLDC tests' $Pwsh @(
    '-NoProfile', '-File', $Test
)
Invoke-GateStep 'BUILD-NORMAL' 'production normal build and CLDC audit' $Pwsh @(
    '-NoProfile', '-File', $Build, '-Target', 'tg', '-Env', 'production',
    '-Clean', '-ArtifactName', 'stability-gate'
)
Invoke-GateStep 'BUILD-MIN' 'production minified build and CLDC audit' $Pwsh @(
    '-NoProfile', '-File', $Build, '-Target', 'tg', '-Env', 'production',
    '-Release', '-Clean', '-ArtifactName', 'stability-gate-min'
)
Invoke-GateStep 'SMOKE-NORMAL' 'packaged normal JAR, offline, -Xmx32m' $Pwsh @(
    '-NoProfile', '-File', $Smoke, '-SkipBuild',
    '-ArtifactName', 'stability-gate', '-JavaArgs', '-Xmx32m'
)
Invoke-GateStep 'SMOKE-MIN' 'packaged minified JAR, offline, -Xmx32m' $Pwsh @(
    '-NoProfile', '-File', $Smoke, '-SkipBuild',
    '-ArtifactName', 'stability-gate-min', '-JavaArgs', '-Xmx32m'
)
Invoke-GateStep 'PUBLIC-AUDIT' 'publication-set secret/path audit' $Pwsh @(
    '-NoProfile', '-File', $Audit
)

Show-ScenarioSummary
Write-Host ''
if ($Failures.Count -gt 0) {
    Write-Host "STABILITY GATE FAILED ($($Failures.Count) failure(s))" -ForegroundColor Red
    foreach ($failure in $Failures) { Write-Host "  $failure" }
    exit 1
}
Write-Host 'STABILITY GATE PASSED' -ForegroundColor Green
exit 0
