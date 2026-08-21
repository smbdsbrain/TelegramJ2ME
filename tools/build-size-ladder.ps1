<#
.SYNOPSIS
    Build exact-size probe JAR/JAD pairs for measuring an unknown handset limit.

.DESCRIPTION
    Produces 64, 128, 256, 512, 1024, 1536 and 2048 KiB artifacts under
    dist/size-ladder. The executable is the normal TgProbe build; a deterministic
    STORED ZIP entry fills each JAR to the exact requested byte size. Every JAD
    contains the matching MIDlet-Jar-Size and a unique URL/name.

    Padding only goes up, so a rung smaller than probe.jar itself cannot be
    built and is skipped with a warning rather than failing the run. Since the
    crypto suite was folded into the probe that applies to 64 and 128 KiB: a
    handset whose install cap is down there has to be measured with an older
    probe release, from before the merge.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

if (-not $Jdk8Jar) { throw "JDK 8 jar tool not found; run tools/bootstrap.ps1" }

& (Join-Path $PSScriptRoot "build.ps1") -Target probe
if ($LASTEXITCODE -ne 0) { throw "probe build failed" }

$baseJar = Join-RepoPath "dist" "probe.jar"
$outDir = Join-RepoPath "dist" "size-ladder"
$workDir = Join-RepoPath "build" "size-ladder"

if (Test-Path $outDir) { Remove-Item -LiteralPath $outDir -Recurse -Force }
if (Test-Path $workDir) { Remove-Item -LiteralPath $workDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir, $workDir | Out-Null

function Write-Padding([string]$Path, [int]$Length, [int]$Seed) {
    $bytes = New-Object byte[] $Length
    $random = New-Object System.Random $Seed
    $random.NextBytes($bytes)
    [System.IO.File]::WriteAllBytes($Path, $bytes)
}

$skipped = @()

foreach ($sizeKiB in @(64, 128, 256, 512, 1024, 1536, 2048)) {
    $targetBytes = $sizeKiB * 1024
    $outJar = Join-Path $outDir ("probe-{0}k.jar" -f $sizeKiB)
    $pad = Join-Path $workDir "ladder.pad"

    # A rung below the base JAR cannot be built: padding only adds bytes. Skip
    # it and say so - the remaining rungs still measure a cap above this point,
    # and failing the whole run would leave nothing to install.
    if ((Get-Item -LiteralPath $baseJar).Length -ge $targetBytes) {
        Write-Warn2 ("skipping {0} KiB: probe.jar is already {1} bytes" -f `
                     $sizeKiB, (Get-Item -LiteralPath $baseJar).Length)
        $skipped += $sizeKiB
        continue
    }

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
MIDlet-Name: J2MEgram Probe ${sizeKiB}k
MIDlet-Version: 0.1.0
MIDlet-Vendor: smbdsbrain
MIDlet-1: J2MEgram Probe,,tg.app.ProbeMidlet
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
if ($skipped.Count -gt 0) {
    Write-Warn2 ("rungs not built: {0} KiB - below the size of probe.jar itself" -f `
                 ($skipped -join ", "))
}
Write-Host "size ladder OK: dist/size-ladder" -ForegroundColor Green
