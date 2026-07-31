# tools/_env.ps1
#
# Shared toolchain resolution. Dot-source this from every other script:
#     . (Join-Path $PSScriptRoot "_env.ps1")
#
# Resolves, without mutating anything:
#   $RepoRoot, $Jdk8Home, $Jdk8Javac, $Jdk8Java, $Jdk8Jar, $Jdk8RtJar,
#   $WtkHome (or $null), $SdkDir, $ProGuardJar, $MicroEmuJars, $BootClassPath,
#   $OnWindows, $ExeSuffix, $PathSep
#
# Everything is a plain file-system lookup so the build stays reproducible and
# IDE-free. Nothing here downloads; see bootstrap.ps1 for that.
#
# Cross-platform rules for anything added here or in a sibling script:
#   - never embed a separator in a path literal ("build\device"); use
#     Join-RepoPath or chained Join-Path, because Join-Path does not split a
#     backslash on Linux and would create one file with a backslash in its name
#   - join classpaths with $PathSep, never ";"
#   - append $ExeSuffix to a JDK binary, never ".exe"
#   - resolve Python through Get-PythonCommand, never a bare `python`

Set-StrictMode -Version Latest

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$RepoRoot        = $script:RepoRoot
$SdkDir          = Join-Path $RepoRoot "sdk"

# --------------------------------------------------------------------------
# Platform. These scripts run on Windows PowerShell 5.1 and on PowerShell 7
# for Linux and macOS; $IsWindows only exists on 6+, hence the probe rather
# than a bare reference, which Set-StrictMode would otherwise make fatal.
# --------------------------------------------------------------------------
$OnWindows  = if (Test-Path Variable:IsWindows) { $IsWindows } else { $true }
$ExeSuffix  = if ($OnWindows) { ".exe" } else { "" }
$PathSep    = [IO.Path]::PathSeparator

function Write-Step ($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok   ($m) { Write-Host "    OK   $m" -ForegroundColor Green }
function Write-Warn2($m) { Write-Host "    WARN $m" -ForegroundColor Yellow }
function Write-Bad  ($m) { Write-Host "    FAIL $m" -ForegroundColor Red }

# Repo-relative path from segments. Join-Path does not split a "a\b" literal on
# Linux, so it would silently create one file *named* "a\b" - use this instead
# of embedding a separator in a single string.
function Join-RepoPath {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Segments)
    $p = $RepoRoot
    foreach ($s in $Segments) { $p = Join-Path $p $s }
    return $p
}

# Path shown to a human: repo-relative, forward slashes, no leading root.
function Format-RepoRelative ($path) {
    $full = [IO.Path]::GetFullPath($path)
    $root = $RepoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar,
                              [IO.Path]::AltDirectorySeparatorChar)
    if ($full.StartsWith($root)) {
        $full = $full.Substring($root.Length).TrimStart('\', '/')
    }
    return $full.Replace('\', '/')
}

# Debian, Fedora and Arch all ship python3 without a bare `python`; Windows and
# the python.org installers give `python`. Try both rather than pick one.
function Get-PythonCommand {
    foreach ($n in @("python3", "python")) {
        $c = Get-Command $n -ErrorAction SilentlyContinue
        if ($c) {
            $v = (& $c.Source --version 2>&1 | Out-String)
            if ($v -match 'Python 3') { return $c.Source }
        }
    }
    return $null
}

# --------------------------------------------------------------------------
# JDK 8 -- mandatory for the device profile.
# JDK 9+ dropped -source 1.3 / -target 1.1, which CLDC needs.
# --------------------------------------------------------------------------
function Resolve-Jdk8 {
    $candidates = New-Object System.Collections.ArrayList

    if ($env:JDK8_HOME) { [void]$candidates.Add($env:JDK8_HOME) }

    if ($OnWindows) {
        $roots = @(
            "$env:ProgramFiles\Eclipse Adoptium",
            "$env:ProgramFiles\Java",
            "$env:ProgramFiles\Microsoft",
            "$env:ProgramFiles\Zulu",
            "$env:ProgramFiles\Amazon Corretto",
            "${env:ProgramFiles(x86)}\Eclipse Adoptium",
            "${env:ProgramFiles(x86)}\Java",
            "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
        )
    } else {
        # Debian/Ubuntu and Fedora both use /usr/lib/jvm; the rest are the
        # common manual and version-manager locations.
        $roots = @(
            "/usr/lib/jvm",
            "/usr/java",
            "/opt/java",
            "/Library/Java/JavaVirtualMachines",
            "$HOME/.sdkman/candidates/java",
            "$HOME/.jdks"
        )
    }
    foreach ($r in $roots) {
        if ($r -and (Test-Path $r)) {
            Get-ChildItem $r -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '(^|[-_.])(java-?8|jdk-?8|1\.8|8u|8($|[-_.]))' } |
                ForEach-Object { [void]$candidates.Add($_.FullName) }
        }
    }

    # macOS bundles land at <home>/Contents/Home; a Linux distro package does not.
    foreach ($c in @($candidates)) {
        $inner = Join-Path (Join-Path $c "Contents") "Home"
        if (Test-Path $inner) { [void]$candidates.Add($inner) }
    }

    # Last resort: whatever javac is on PATH, if it happens to be 8.
    $onPath = Get-Command "javac$ExeSuffix" -ErrorAction SilentlyContinue
    if ($onPath) {
        $bin = Split-Path (Split-Path $onPath.Source -Parent) -Parent
        if ($bin) { [void]$candidates.Add($bin) }
    }

    foreach ($c in $candidates) {
        $javac = Join-Path (Join-Path $c "bin") "javac$ExeSuffix"
        if (Test-Path $javac) {
            $v = (& $javac -version 2>&1 | Out-String).Trim()
            if ($v -match 'javac 1\.8') { return $c }
        }
    }
    return $null
}

$Jdk8Home = Resolve-Jdk8
if ($Jdk8Home) {
    $Jdk8Bin   = Join-Path $Jdk8Home "bin"
    $Jdk8Javac = Join-Path $Jdk8Bin "javac$ExeSuffix"
    $Jdk8Java  = Join-Path $Jdk8Bin "java$ExeSuffix"
    $Jdk8Jar   = Join-Path $Jdk8Bin "jar$ExeSuffix"
    $Jdk8RtJar = Join-Path (Join-Path (Join-Path $Jdk8Home "jre") "lib") "rt.jar"
    if (-not (Test-Path $Jdk8RtJar)) {
        $Jdk8RtJar = Join-Path (Join-Path $Jdk8Home "lib") "rt.jar"  # JDK-image layout
    }
} else {
    $Jdk8Javac = $null; $Jdk8Java = $null; $Jdk8Jar = $null; $Jdk8RtJar = $null
}

# --------------------------------------------------------------------------
# Sun WTK 2.5.2_01 -- optional. When present it is the reference CLDC/MIDP API
# and the reference preverifier, so we prefer it over the fallback path.
# --------------------------------------------------------------------------
function Resolve-Wtk {
    $candidates = @()
    if ($env:WTK_HOME) { $candidates += $env:WTK_HOME }
    if ($OnWindows) {
        $candidates += @(
            "C:\WTK2.5.2_01", "C:\WTK2.5.2",
            "$env:ProgramFiles\WTK2.5.2_01", "${env:ProgramFiles(x86)}\WTK2.5.2_01",
            "$env:USERPROFILE\WTK2.5.2_01"
        )
    } else {
        # The Linux installer (sun_java_wireless_toolkit-2.5.2_01-linux.bin) is
        # interactive and defaults to the home directory.
        $candidates += @(
            "$HOME/WTK2.5.2_01", "$HOME/WTK2.5.2",
            "/opt/WTK2.5.2_01", "/opt/WTK2.5.2",
            "/usr/local/WTK2.5.2_01"
        )
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path (Join-Path $c "lib") "cldcapi11.jar"))) { return $c }
    }
    return $null
}

$WtkHome = Resolve-Wtk

# --------------------------------------------------------------------------
# Downloaded artifacts
# --------------------------------------------------------------------------
$MicroEmuCldc  = Join-Path $SdkDir "microemu-cldc-2.0.4.jar"
$MicroEmuMidp  = Join-Path $SdkDir "microemu-midp-2.0.4.jar"
$MicroEmuJavase= Join-Path $SdkDir "microemu-javase-2.0.4.jar"
$MicroEmuSwing = Join-Path $SdkDir "microemu-javase-swing-2.0.4.jar"
$MicroEmuInject= Join-Path $SdkDir "microemu-injected-2.0.4.jar"
$Asm           = Join-Path $SdkDir "asm-3.1.jar"

# Order is load-bearing, twice over:
#   - microemu-injected MUST come after microemu-javase. MicroEmulator checks
#     this at startup and refuses with "Wrong Injected class detected".
#   - asm must be present at all. MicroEmulator's MIDletClassLoader rewrites
#     bytecode as it loads, and without ASM the MIDlet class fails to load with
#     a NoClassDefFoundError that only reaches stderr - the emulator window just
#     shows a blank white screen.
$MicroEmuJars  = @($MicroEmuCldc, $MicroEmuMidp, $MicroEmuJavase, $MicroEmuSwing,
                   $MicroEmuInject, $Asm)

$ProGuardDir = Join-Path $SdkDir "proguard-7.4.2"
$ProGuardJar = Join-Path (Join-Path $ProGuardDir "lib") "proguard.jar"

# --------------------------------------------------------------------------
# -bootclasspath for the device profile.
#
#   WTK present  -> real Sun cldcapi11.jar + midpapi20.jar. Exact: javac itself
#                   rejects any J2SE-only API.
#   WTK absent   -> microemu jars supply javax.microedition.*, JDK 8 rt.jar
#                   supplies java.lang/io/util. rt.jar is a superset of CLDC,
#                   so tools/check-api.py is MANDATORY in this mode - it is the
#                   thing that actually enforces the CLDC 1.1 subset.
# --------------------------------------------------------------------------
function Get-DeviceBootClassPath {
    if ($WtkHome) {
        $wtkLib = Join-Path $WtkHome "lib"
        return @(
            (Join-Path $wtkLib "cldcapi11.jar"),
            (Join-Path $wtkLib "midpapi20.jar")
        ) -join $PathSep
    }
    return @($MicroEmuCldc, $MicroEmuMidp, $Jdk8RtJar) -join $PathSep
}

function Get-BootClassPathMode {
    if ($WtkHome) { return "wtk" } else { return "fallback" }
}

# --------------------------------------------------------------------------
# Misc helpers shared by the scripts
# --------------------------------------------------------------------------
function Get-Sha256 ($path) {
    return (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-JarClassList ($jarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        return @($zip.Entries |
            Where-Object { $_.FullName.EndsWith(".class") } |
            ForEach-Object { $_.FullName.Substring(0, $_.FullName.Length - 6).Replace("/", ".") })
    } finally { $zip.Dispose() }
}

# --------------------------------------------------------------------------
# Telegram application identity.
#
# api_id / api_hash are not cryptographic secrets - every third-party client
# necessarily ships them - but they are tied to the developer's Telegram
# account, so they are read from a gitignored file and never committed. The
# generated Secrets.java lands in generated/, which is also gitignored.
#
# Precedence:
#   1. TG_API_ID / TG_API_HASH environment variables
#   2. secrets/telegram.yaml
#   3. config/app.properties
#
# The environment comes first so CI can inject repository secrets without ever
# writing them to the runner's disk - nothing to leave behind in a cache, an
# uploaded artifact or a `git status` diff.
# --------------------------------------------------------------------------
function Get-TelegramSecrets {
    $result = @{ apiId = 0; apiHash = ""; title = ""; name = ""; source = "none" }

    if ($env:TG_API_ID -and $env:TG_API_HASH) {
        $result.apiId   = [int]($env:TG_API_ID -replace '[^0-9]', '')
        $result.apiHash = $env:TG_API_HASH.Trim()
        $result.title   = if ($env:TG_APP_TITLE) { $env:TG_APP_TITLE.Trim() } else { "TelegramJ2ME" }
        $result.name    = if ($env:TG_APP_NAME)  { $env:TG_APP_NAME.Trim() }  else { "telegramj2me" }
        $result.source  = "environment"
        return $result
    }

    # Minimal flat "key: value" reader. The file is ours and one level deep, so
    # a real YAML parser would be a dependency bought for nothing.
    $yaml  = Join-RepoPath "secrets" "telegram.yaml"
    $props = Join-RepoPath "config" "app.properties"

    $path = $null
    $separator = $null
    if (Test-Path $yaml)       { $path = $yaml;  $separator = ':' }
    elseif (Test-Path $props)  { $path = $props; $separator = '=' }
    else { return $result }

    $pattern = "^\s*([A-Za-z_][A-Za-z0-9_.]*)\s*$separator\s*(.*?)\s*$"
    foreach ($line in (Get-Content $path)) {
        if ($line -match '^\s*#') { continue }
        if ($line -notmatch $pattern) { continue }
        $key = $Matches[1]
        $value = $Matches[2].Trim('"').Trim("'")
        switch ($key) {
            'api_id'   { $result.apiId = [int]($value -replace '[^0-9]', '') }
            'api_hash' { $result.apiHash = $value }
            'title'    { $result.title = $value }
            'name'     { $result.name = $value }
        }
    }
    $result.source = Format-RepoRelative $path
    return $result
}

# Safe to print: proves the value was loaded without disclosing it.
function Format-SecretDigest ($value) {
    if (-not $value) { return "not set" }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($value)
    $sha = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    $hex = ($sha | ForEach-Object { $_.ToString("x2") }) -join ""
    return "set (sha256 " + $hex.Substring(0, 8) + "..., $($value.Length) chars)"
}

function Get-BuildId {
    $sha = "nogit"
    try {
        Push-Location $RepoRoot
        $out = & git rev-parse --short HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $out) { $sha = $out.Trim() }
        $dirty = & git status --porcelain 2>$null
        if ($LASTEXITCODE -eq 0 -and $dirty) { $sha = "$sha+" }
    } catch { } finally { Pop-Location -ErrorAction SilentlyContinue }
    return $sha
}
