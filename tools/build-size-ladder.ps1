<#
.SYNOPSIS
    Build exact-size probe JAR/JAD pairs for measuring an unknown handset limit.

.DESCRIPTION
    Produces 64, 128, 256, 512, 1024, 1536 and 2048 KiB artifacts under
    dist/size-ladder. The executable is the normal TgProbe build; a deterministic
    STORED ZIP entry fills each JAR to the exact requested byte size. Every JAD
    contains the matching MIDlet-Jar-Size and a unique URL/name.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_env.ps1"

if (-not $Jdk8Jar) { throw "JDK 8 jar.exe not found; run tools/bootstrap.ps1" }

& (Join-Path $PSScriptRoot "build.ps1") -Target probe
if ($LASTEXITCODE -ne 0) { throw "probe build failed" }

$baseJar = Join-Path $RepoRoot "dist\probe.jar"
$outDir = Join-Path $RepoRoot "dist\size-ladder"
$workDir = Join-Path $RepoRoot "build\size-ladder"

if (Test-Path $outDir) { Remove-Item -LiteralPath $outDir -Recurse -Force }
if (Test-Path $workDir) { Remove-Item -LiteralPath $workDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir, $workDir | Out-Null

function Write-Padding([string]$Path, [int]$Length, [int]$Seed) {
    $bytes = New-Object byte[] $Length
    $random = New-Object System.Random $Seed
    $random.NextBytes($bytes)
    [System.IO.File]::WriteAllBytes($Path, $bytes)
}

foreach ($sizeKiB in @(64, 128, 256, 512, 1024, 1536, 2048)) {
    $targetBytes = $sizeKiB * 1024
    $outJar = Join-Path $outDir ("probe-{0}k.jar" -f $sizeKiB)
    $pad = Join-Path $workDir "ladder.pad"

    # Measure ZIP metadata overhead with the exact entry name and jar tool.
    Copy-Item -LiteralPath $baseJar -Destination $outJar
    Write-Padding $pad 1 $sizeKiB
    Push-Location $workDir
    try { & $Jdk8Jar uf0 $outJar ladder.pad | Out-Null }
    finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "jar trial update failed for ${sizeKiB} KiB" }
    $overhead = (Get-Item -LiteralPath $outJar).Length -
                (Get-Item -LiteralPath $baseJar).Length - 1

    $paddingLength = $targetBytes -
                     (Get-Item -LiteralPath $baseJar).Length - $overhead
    if ($paddingLength -lt 0) {
        throw "base probe JAR is already larger than ${sizeKiB} KiB"
    }

    Copy-Item -LiteralPath $baseJar -Destination $outJar -Force
    Write-Padding $pad $paddingLength $sizeKiB
    Push-Location $workDir
    try { & $Jdk8Jar uf0 $outJar ladder.pad | Out-Null }
    finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "jar update failed for ${sizeKiB} KiB" }

    $actual = (Get-Item -LiteralPath $outJar).Length
    if ($actual -ne $targetBytes) {
        throw "probe-${sizeKiB}k.jar is $actual bytes, expected $targetBytes"
    }

    $jad = @"
MIDlet-Name: TelegramJ2ME Probe ${sizeKiB}k
MIDlet-Version: 0.1.0
MIDlet-Vendor: smbdsbrain
MIDlet-1: TelegramJ2ME Probe,,tg.app.ProbeMidlet
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
MIDlet-Jar-URL: probe-${sizeKiB}k.jar
MIDlet-Jar-Size: $targetBytes
MIDlet-Description: Exact-size JAR limit probe (${sizeKiB} KiB)
"@
    $jadPath = Join-Path $outDir ("probe-{0}k.jad" -f $sizeKiB)
    $jad.Replace("`r`n", "`n") |
        Set-Content -LiteralPath $jadPath -Encoding ASCII -NoNewline
    Write-Ok ("probe-{0}k.jar = {1} bytes; matching JAD" -f $sizeKiB, $actual)
}

Remove-Item -LiteralPath $workDir -Recurse -Force
Write-Host ""
Write-Host "size ladder OK: dist/size-ladder" -ForegroundColor Green
