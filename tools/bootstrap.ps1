<#
.SYNOPSIS
    One-time environment bootstrap for the J2ME MTProto client.

.DESCRIPTION
    Resolves JDK 8, downloads the pinned toolchain artifacts listed in
    tools/sdk.lock.json into sdk/ and third_party/, verifies their SHA-256,
    unpacks ProGuard, ports the Bouncy Castle CLDC BigInteger into
    src/tg/crypto/bigint/, and reports whether an optional Sun WTK install was
    found.

    No SDK binary is ever committed to git (see .gitignore).

.PARAMETER UpdateLock
    Recompute and write the sha256 fields in tools/sdk.lock.json instead of
    verifying them. Run once, review the diff, then commit the lock file.

.PARAMETER Force
    Re-download artifacts even if they already exist locally.

.EXAMPLE
    ./tools/bootstrap.ps1
    ./tools/bootstrap.ps1 -UpdateLock
#>
[CmdletBinding()]
param(
    [switch]$UpdateLock,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_env.ps1"

$lockPath = Join-Path $PSScriptRoot "sdk.lock.json"
$problems = New-Object System.Collections.ArrayList

Write-Host ""
Write-Host "TelegramJ2ME :: bootstrap" -ForegroundColor White
Write-Host "repo: $RepoRoot"
Write-Host ""

# ==========================================================================
# 1. JDK 8
# ==========================================================================
Write-Step "JDK 8 (device profile compiler)"
if ($Jdk8Home) {
    $v = (& $Jdk8Javac -version 2>&1 | Out-String).Trim()
    Write-Ok "$Jdk8Home  ($v)"
    if (Test-Path $Jdk8RtJar) { Write-Ok "rt.jar: $Jdk8RtJar" }
    else {
        Write-Bad "rt.jar not found under $Jdk8Home - a JRE-less JDK image will not work as a fallback bootclasspath."
        [void]$problems.Add("jdk8-rtjar")
    }
} else {
    Write-Bad "No JDK 8 found."
    Write-Host ""
    Write-Host "  JDK 9+ removed -source 1.3 / -target 1.1, which CLDC requires." -ForegroundColor Yellow
    Write-Host "  Install one of:" -ForegroundColor Yellow
    Write-Host "      winget install --id EclipseAdoptium.Temurin.8.JDK --exact" -ForegroundColor Yellow
    Write-Host "      https://adoptium.net/temurin/releases/?version=8" -ForegroundColor Yellow
    Write-Host "  Or point JDK8_HOME at an existing install and re-run." -ForegroundColor Yellow
    Write-Host ""
    [void]$problems.Add("jdk8")
}

# ==========================================================================
# 2. Pinned downloads
# ==========================================================================
Write-Host ""
Write-Step "Pinned artifacts (tools/sdk.lock.json)"

$lock = Get-Content $lockPath -Raw | ConvertFrom-Json
$lockChanged = $false

foreach ($a in $lock.artifacts) {
    $dest = Join-Path $RepoRoot $a.dest
    $destDir = Split-Path $dest -Parent
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }

    if ($Force -and (Test-Path $dest)) { Remove-Item $dest -Force }

    if (-not (Test-Path $dest)) {
        Write-Host "    get  $($a.id) ..." -NoNewline
        try {
            Invoke-WebRequest -Uri $a.url -OutFile $dest -UseBasicParsing -TimeoutSec 300
            Write-Host " $([math]::Round((Get-Item $dest).Length / 1KB)) KB"
        } catch {
            Write-Host ""
            Write-Bad "$($a.id): download failed - $($_.Exception.Message)"
            Write-Host "         url: $($a.url)" -ForegroundColor Red
            [void]$problems.Add($a.id)
            continue
        }
    }

    $actual = Get-Sha256 $dest
    if ($UpdateLock -or -not $a.sha256) {
        if ($a.sha256 -ne $actual) { $a.sha256 = $actual; $lockChanged = $true }
        Write-Ok "$($a.id)  sha256=$actual  (pinned)"
    } elseif ($a.sha256 -ne $actual) {
        Write-Bad "$($a.id): SHA-256 MISMATCH"
        Write-Host "         expected $($a.sha256)" -ForegroundColor Red
        Write-Host "         actual   $actual" -ForegroundColor Red
        Write-Host "         Delete $dest and re-run, or use -UpdateLock if the pin is intentionally changing." -ForegroundColor Red
        [void]$problems.Add($a.id)
    } else {
        Write-Ok "$($a.id)"
    }

    # optional unpack
    if ($a.PSObject.Properties.Name -contains 'unzipTo' -and $a.unzipTo) {
        $unzipDir = Join-Path $RepoRoot $a.unzipTo
        if ($Force -and (Test-Path $unzipDir)) { Remove-Item $unzipDir -Recurse -Force }
        if (-not (Test-Path $unzipDir)) {
            Expand-Archive -Path $dest -DestinationPath (Split-Path $unzipDir -Parent) -Force
            Write-Ok "  unpacked -> $($a.unzipTo)"
        }
    }
}

if ($lockChanged) {
    ($lock | ConvertTo-Json -Depth 10) | Set-Content -Path $lockPath -Encoding UTF8
    Write-Warn2 "sdk.lock.json updated with computed hashes - review and commit it."
}

# ==========================================================================
# 3. ProGuard sanity
# ==========================================================================
Write-Host ""
Write-Step "ProGuard (CLDC preverifier)"
if (Test-Path $ProGuardJar) {
    Write-Ok $ProGuardJar
    # ProGuard has no -version flag - asking for one prints a ParseException.
    # It does print a version banner on every run though, so invoke it with an
    # empty configuration and look for the banner. The "no -injars" error that
    # follows is expected and is not a failure; what matters is that the jar
    # loaded and ran on this JDK.
    if ($Jdk8Java) {
        $probeCfg = Join-Path $env:TEMP "proguard-selfcheck.pro"
        "-dontnote" | Set-Content $probeCfg -Encoding ASCII
        $out = (& $Jdk8Java -jar $ProGuardJar "@$probeCfg" 2>&1 | Out-String)
        Remove-Item $probeCfg -Force -ErrorAction SilentlyContinue

        if ($out -match 'ProGuard,\s*version\s*(\S+)') {
            Write-Ok "version $($Matches[1]), runs under $(Split-Path $Jdk8Home -Leaf)"
        } else {
            Write-Bad "proguard.jar did not run on this JDK:"
            Write-Host ($out.Trim()) -ForegroundColor DarkGray
            [void]$problems.Add("proguard-run")
        }
    }
} else {
    # layout differs between releases; find it
    $found = Get-ChildItem (Join-Path $RepoRoot "sdk") -Recurse -Filter "proguard.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) {
        Write-Warn2 "proguard.jar at unexpected path: $($found.FullName)"
        Write-Warn2 "update `$ProGuardJar in tools/_env.ps1"
    } else {
        Write-Bad "proguard.jar not found under sdk/"
        [void]$problems.Add("proguard")
    }
}

# ==========================================================================
# 4. MicroEmulator jars must actually carry the MIDP API we compile against
# ==========================================================================
Write-Host ""
Write-Step "MIDP/CLDC compile-time API"

$requiredApi = @(
    "javax.microedition.midlet.MIDlet",
    "javax.microedition.io.Connector",
    "javax.microedition.io.SocketConnection",
    "javax.microedition.io.StreamConnection",
    "javax.microedition.lcdui.Display",
    "javax.microedition.lcdui.Canvas",
    "javax.microedition.lcdui.Form",
    "javax.microedition.lcdui.List",
    "javax.microedition.lcdui.TextBox",
    "javax.microedition.lcdui.Image",
    "javax.microedition.rms.RecordStore"
)

if ($WtkHome) {
    Write-Ok "WTK found: $WtkHome  (reference API + preverifier will be used)"
    foreach ($j in @("lib\cldcapi11.jar", "lib\midpapi20.jar", "bin\preverify.exe", "bin\emulator.exe")) {
        $p = Join-Path $WtkHome $j
        if (Test-Path $p) { Write-Ok "  $j" } else { Write-Warn2 "  missing: $j" }
    }
} else {
    Write-Warn2 "WTK not found - using microemu jars + JDK 8 rt.jar as bootclasspath."
    Write-Warn2 "tools/check-api.py is what enforces the CLDC subset in this mode."
    Write-Host  "         Optional, improves confidence on real hardware:" -ForegroundColor DarkGray
    Write-Host  "           1. https://www.oracle.com/java/technologies/java-archive-downloads-javame-downloads.html" -ForegroundColor DarkGray
    Write-Host  "           2. sun_java_wireless_toolkit-2.5.2_01-win.exe (free Oracle account required)" -ForegroundColor DarkGray
    Write-Host  "           3. setx WTK_HOME `"C:\WTK2.5.2_01`"  then re-run bootstrap" -ForegroundColor DarkGray
}

$haveClasses = @{}
foreach ($j in @($MicroEmuCldc, $MicroEmuMidp)) {
    if (Test-Path $j) { foreach ($c in (Get-JarClassList $j)) { $haveClasses[$c] = $true } }
}
$missingApi = @($requiredApi | Where-Object { -not $haveClasses.ContainsKey($_) })
if ($missingApi.Count -eq 0) {
    Write-Ok "microemu jars expose all $($requiredApi.Count) required API classes"
} else {
    if ($WtkHome) {
        Write-Warn2 "microemu jars miss $($missingApi.Count) class(es) - harmless, WTK jars are in use:"
    } else {
        Write-Bad "microemu jars miss $($missingApi.Count) required class(es):"
        [void]$problems.Add("midp-api")
    }
    foreach ($m in $missingApi) { Write-Host "         $m" -ForegroundColor DarkGray }
}

# ==========================================================================
# 5. Port the Bouncy Castle CLDC BigInteger
# ==========================================================================
Write-Host ""
Write-Step "Bouncy Castle BigInteger port"
$porter = Join-Path $PSScriptRoot "port-bc-bigint.py"
$origBi = Join-Path $RepoRoot "third_party\bc\BigInteger.java.orig"
if ((Test-Path $porter) -and (Test-Path $origBi)) {
    $py = (Get-Command python -ErrorAction SilentlyContinue)
    if ($py) {
        & $py.Source $porter
        if ($LASTEXITCODE -ne 0) { Write-Bad "port-bc-bigint.py failed"; [void]$problems.Add("bc-port") }
    } else {
        Write-Bad "python not found on PATH"
        [void]$problems.Add("python")
    }
} elseif (-not (Test-Path $porter)) {
    Write-Warn2 "tools/port-bc-bigint.py not present yet - skipping"
}

# ==========================================================================
# 6. Generate the TL layer
# ==========================================================================
# tg.api.Api and tg.api.TlSchema are generated into generated/, which is
# gitignored, so a fresh clone has no TL layer and src/ does not compile until
# this has run. Inputs are all committed (schema/*.json, config/tl-whitelist.txt)
# and the output is deterministic, which is why they are generated rather than
# checked in.
Write-Host ""
Write-Step "TL layer (generated/tg/api)"
$tlGen = Join-Path $PSScriptRoot "generate-tl.py"
if (Test-Path $tlGen) {
    $py = (Get-Command python -ErrorAction SilentlyContinue)
    if ($py) {
        & $py.Source $tlGen
        if ($LASTEXITCODE -ne 0) {
            Write-Bad "generate-tl.py failed"
            [void]$problems.Add("tl-gen")
        }
    } else {
        Write-Bad "python not found on PATH"
        [void]$problems.Add("python")
    }
} else {
    Write-Warn2 "tools/generate-tl.py not present - skipping"
}

# ==========================================================================
# 7. Telegram credentials
# ==========================================================================
Write-Host ""
Write-Step "Telegram application identity"
$secrets = Get-TelegramSecrets
if ($secrets.apiId -gt 0 -and $secrets.apiHash) {
    Write-Ok "$($secrets.source): api_id=$($secrets.apiId), api_hash $(Format-SecretDigest $secrets.apiHash)"
    if ($secrets.source -eq "environment") {
        # TG_API_ID / TG_API_HASH came from the environment (CI), so there is no
        # file on disk that could be committed and nothing to gitignore.
        Write-Ok "credentials come from the environment - nothing written to disk"
    } else {
        $ignored = & git -C $RepoRoot check-ignore $secrets.source 2>$null
        if ($ignored) {
            Write-Ok "$($secrets.source) is gitignored"
        } else {
            Write-Bad "$($secrets.source) is NOT gitignored - it will be committed."
            [void]$problems.Add("secrets-not-ignored")
        }
    }
} else {
    Write-Warn2 "no credentials yet - everything up to auth_key generation still works."
    Write-Host  "         Copy-Item config/telegram.yaml.example secrets/telegram.yaml" -ForegroundColor DarkGray
    Write-Host  "         then fill in api_id / api_hash from https://my.telegram.org" -ForegroundColor DarkGray
}

# ==========================================================================
# Summary
# ==========================================================================
Write-Host ""
Write-Host "-------------------------------------------------------------" -ForegroundColor White
Write-Host " toolchain summary" -ForegroundColor White
Write-Host "-------------------------------------------------------------" -ForegroundColor White
$fmt = "{0,-22} {1}"
Write-Host ($fmt -f "JDK 8",            $(if ($Jdk8Home) { $Jdk8Home } else { "MISSING" }))
Write-Host ($fmt -f "WTK 2.5.2",        $(if ($WtkHome)  { $WtkHome }  else { "not installed (optional)" }))
Write-Host ($fmt -f "bootclasspath mode", (Get-BootClassPathMode))
Write-Host ($fmt -f "ProGuard",         $(if (Test-Path $ProGuardJar) { "sdk/proguard-7.4.2" } else { "MISSING" }))
Write-Host ($fmt -f "MicroEmulator",    $(if (Test-Path $MicroEmuSwing) { "sdk/microemu-*-2.0.4.jar" } else { "MISSING" }))
Write-Host ($fmt -f "Python",           $(if (Get-Command python -ErrorAction SilentlyContinue) { (Get-Command python).Source } else { "MISSING" }))
Write-Host "-------------------------------------------------------------" -ForegroundColor White

if ($problems.Count -gt 0) {
    Write-Host ""
    Write-Bad "bootstrap incomplete: $($problems -join ', ')"
    exit 1
}
Write-Host ""
Write-Host "bootstrap OK. Next:  ./tools/build.ps1 -Target probe" -ForegroundColor Green
exit 0
