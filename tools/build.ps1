<#
.SYNOPSIS
    Build the J2ME MTProto client.

.DESCRIPTION
    Two profiles compile the same src/ tree:

      device   javac -source 1.3 -target 1.1 against the CLDC/MIDP
               bootclasspath -> check-api.py -> ProGuard -microedition
               (preverify + shrink) -> dist/<target>.jar + .jad

      desktop  plain JDK 8 build of src/ plus test/, so the crypto, TL and
               MTProto layers can be exercised - and pointed at a real Telegram
               DC - without an emulator or a handset.

    Everything is driven from the command line; no IDE is involved and the
    output is deterministic (no timestamps are baked in).

.PARAMETER Target
    probe  - hardware-reconnaissance and crypto suite (tg.app.ProbeMidlet)
    tg     - full Telegram client (tg.app.TgMidlet)

.PARAMETER Profile
    device (default) or desktop.

.PARAMETER Release
    Turn on ProGuard optimisation and obfuscation. Off by default: readable
    stack traces matter more than bytes until we have hardware results.

.PARAMETER EmbedDevSecrets
    Bake the development report collector (secrets/dev-sink.yaml) and the default
    MTProxy (secrets/proxy.yaml) into the artifact.

    Off unless asked for, even when those files exist. They are not build inputs
    the way api_id/api_hash are: the collector token grants read access to every
    diagnostic uploaded to it, and the proxy link carries its secret. A JAR is
    not a private thing - anyone holding it can read both straight out of the
    constant pool.

    The default used to be "embed whatever is in secrets/", which is invisible
    when it is right and invisible when it is wrong. CI never has those files, so
    every published release was clean by accident rather than by design - until a
    release was built locally and published carrying both. Now the JAR is scanned
    after packaging and the build fails if anything got in without this flag.

    Use it for handset sessions, which is what it is for:

        ./tools/build.ps1 -Target probe -EmbedDevSecrets

    Never for anything published. A versioned -ArtifactName is refused outright.

.PARAMETER ArtifactName
    Base name for dist/<name>.jar and dist/<name>.jad. Defaults to the target.
    The JAD's MIDlet-Jar-URL is derived from it, so renaming the files after the
    build would break the install - use this instead. Release builds pass a
    versioned name, e.g. TelegramJ2ME-0.1.0.

.EXAMPLE
    ./tools/build.ps1 -Target probe
    ./tools/build.ps1 -Profile desktop
    ./tools/build.ps1 -Target tg -Env production -ArtifactName TelegramJ2ME-0.1.0
#>
[CmdletBinding()]
param(
    # 'crypto' was a third target holding the vectors, the modPow benchmark and
    # PBKDF2. It is folded into 'probe' - one suite to install, one Upload all to
    # run - see tg.app.ProbeMidlet for what that cost.
    [ValidateSet('probe', 'tg')][string]$Target = 'probe',
    # Named BuildProfile because PowerShell already defines $Profile as an
    # automatic variable (the profile script path). The -Profile alias keeps the
    # command line reading the way the docs describe it.
    [Alias('Profile')]
    [ValidateSet('device', 'desktop')][string]$BuildProfile = 'device',
    # Which Telegram data centres the build targets. Test DCs first: no flood
    # limits and no way to disturb a real account while the handshake is being
    # debugged.
    [ValidateSet('test', 'production')][string]$Env = 'test',
    [switch]$Release,
    [switch]$SkipApiCheck,
    [switch]$Clean,
    # Bake secrets/dev-sink.yaml and secrets/proxy.yaml into the artifact.
    # Off by default: see the .PARAMETER block above for why that default is not
    # a convenience.
    [switch]$EmbedDevSecrets,
    [string]$ArtifactName = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

# Single source of truth for the released version. .github/workflows/release.yml
# reads this line and refuses to publish if it disagrees with the git tag, so a
# mistyped tag cannot ship a JAD whose MIDlet-Version is wrong.
$AppVersion = "0.8.1"
$AppVendor  = "smbdsbrain"

$MidletClass = @{
    probe  = "tg.app.ProbeMidlet"
    tg     = "tg.app.TgMidlet"
}[$Target]
# What the phone's application menu shows under the icon.
$MidletName = @{
    probe  = "TelegramJ2ME Probe"
    tg     = "TelegramJ2ME"
}[$Target]

# A test build talks to Telegram's test data centres and carries the test server
# key modulus, so it cannot complete a handshake against production - and going
# through an MTProxy it will reach production regardless of what the build
# thinks, which surfaces as an opaque key-mismatch error rather than anything
# that names the environment.
#
# The suffix makes the two impossible to confuse in two places at once: the
# handset's application list shows which one is installed, and because MIDlet
# suite identity is Name plus Vendor, a test build installs alongside a
# production one instead of overwriting it and inheriting its record stores.
if ($Env -eq 'test' -and $Target -ne 'probe') {
    $MidletName = "$MidletName (test)"
}

if (-not $ArtifactName) { $ArtifactName = $Target }

# A versioned artifact name is what a release is called - the workflow builds
# TelegramJ2ME-<version>[-min]. Nothing with a version in its name should ever
# carry the collector token or the proxy secret, and the one time it did, it was
# a local build using exactly this naming. Refused rather than warned about: by
# the time a warning scrolls past, the file exists and is about to be uploaded.
if ($EmbedDevSecrets -and $ArtifactName -match '^TelegramJ2ME-\d') {
    Write-Bad "-EmbedDevSecrets cannot be combined with a release artifact name ($ArtifactName)."
    Write-Host "         Those names are what gets published. Drop the flag, or build" -ForegroundColor Red
    Write-Host "         under a local name for a handset session." -ForegroundColor Red
    exit 1
}

if (-not $Jdk8Home) {
    Write-Bad "JDK 8 not found. Run ./tools/bootstrap.ps1 first."
    exit 1
}

function Invoke-Checked ($exe, $argList, $what) {
    $out = & $exe @argList 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host $out
        Write-Bad "$what failed (exit $LASTEXITCODE)"
        exit 1
    }
    return $out
}

function Get-JavaSources ($paths) {
    $files = @()
    foreach ($p in $paths) {
        $full = Join-Path $RepoRoot $p
        if (Test-Path $full) {
            $files += @(Get-ChildItem $full -Recurse -Filter *.java |
                        ForEach-Object { $_.FullName })
        }
    }
    return $files
}

# Emits generated/tg/app/{BuildInfo,Secrets}.java. Both profiles need them, so
# this runs before the profile split - otherwise a clean checkout doing
# `-Profile desktop` first would fail to compile.
function Write-GeneratedSources ($bootMode, $buildId) {
    $genDir = Join-RepoPath "generated" "tg" "app"
    New-Item -ItemType Directory -Force -Path $genDir | Out-Null

    # Deterministic on purpose: version + git hash only, never a timestamp.
    @"
package tg.app;

/** Generated by tools/build.ps1 - do not edit. */
public final class BuildInfo
{
    public static final String VERSION  = "$AppVersion";
    public static final String BUILD    = "$buildId";
    public static final String TARGET   = "$Target";
    public static final String BOOTMODE = "$bootMode";

    /** "test" or "production" - which Telegram data centres this build talks to. */
    public static final String ENV      = "$Env";

    private BuildInfo() { }
}
"@ | Set-Content -Path (Join-Path $genDir "BuildInfo.java") -Encoding UTF8

    # Telegram application identity. Written into generated/ (gitignored) rather
    # than src/, so credentials cannot be committed by accident. The values are
    # never echoed to the console.
    $secrets = Get-TelegramSecrets
    $escapedHash  = $secrets.apiHash -replace '\\', '\\' -replace '"', '\"'
    $escapedTitle = $secrets.title   -replace '\\', '\\' -replace '"', '\"'
    $escapedName  = $secrets.name    -replace '\\', '\\' -replace '"', '\"'
    $configured = if ($secrets.apiId -gt 0 -and $secrets.apiHash) { "true" } else { "false" }

    @"
package tg.app;

/**
 * Generated by tools/build.ps1 from $($secrets.source) - do not edit, do not commit.
 *
 * api_id / api_hash identify this application to Telegram. They are not
 * cryptographic secrets - every third-party client necessarily ships them - but
 * they are tied to a specific Telegram account, so the file they come from is
 * gitignored, and so is this one.
 */
public final class Secrets
{
    public static final int    API_ID    = $($secrets.apiId);
    public static final String API_HASH  = "$escapedHash";
    public static final String APP_TITLE = "$escapedTitle";
    public static final String APP_NAME  = "$escapedName";

    /** False when the build had no credentials; API-layer calls will fail. */
    public static final boolean CONFIGURED = $configured;

    private Secrets() { }
}
"@ | Set-Content -Path (Join-Path $genDir "Secrets.java") -Encoding UTF8

    if ($configured -eq "true") {
        Write-Ok ("credentials from {0}: api_id={1}, api_hash {2}" -f `
                  $secrets.source, $secrets.apiId, (Format-SecretDigest $secrets.apiHash))
    } else {
        Write-Warn2 "no Telegram credentials found - API-layer calls will fail."
        Write-Warn2 "put api_id / api_hash in secrets/telegram.yaml (see config/app.properties.example)"
    }

    # Development report sink. Same treatment as the credentials above and for a
    # stronger reason: this one points at private infrastructure, so a build
    # without secrets/dev-sink.yaml must produce empty strings rather than fail.
    # That is the normal case - CI, a fresh clone and every published artifact.
    #
    # Opt-in, and deliberately not "whatever is lying around in secrets/". A
    # local build that silently absorbs the collector token produced a published
    # release carrying it; CI never had the file, so nothing upstream noticed.
    # -EmbedDevSecrets is the whole difference now, and the check after packaging
    # verifies the result rather than trusting this branch.
    $sink = if ($EmbedDevSecrets) { Get-DevSink } else { Get-EmptyDevSink }
    $sinkConfigured = if ($sink.host -and $sink.token) { "true" } else { "false" }
    $sinkBase = if ($sinkConfigured -eq "true") {
        "http://$($sink.host):$($sink.httpPort)/r/$($sink.token)"
    } else { "" }
    $escapedBase   = $sinkBase     -replace '\\', '\\' -replace '"', '\"'
    $escapedHost   = $sink.host    -replace '\\', '\\' -replace '"', '\"'
    $escapedToken  = $sink.token   -replace '\\', '\\' -replace '"', '\"'
    $escapedDevice = $sink.device  -replace '\\', '\\' -replace '"', '\"'

    @"
package tg.app;

/**
 * Generated by tools/build.ps1 from $($sink.source) - do not edit, do not commit.
 *
 * Where this build uploads diagnostic reports. Empty in any build that had no
 * secrets/dev-sink.yaml, which is every public artifact: CONFIGURED is then
 * false and the upload commands report "no sink configured" instead of
 * dialling somewhere.
 *
 * Carries no Telegram material. The token authenticates the handset to a
 * development collector and has nothing to do with the account.
 */
public final class DevSink
{
    /** POST target is HTTP_BASE + "/" + target + "/" + device. */
    public static final String HTTP_BASE = "$escapedBase";

    public static final String TCP_HOST  = "$escapedHost";
    public static final int    TCP_PORT  = $($sink.tcpPort);

    /** First field of the TCP greeting line. */
    public static final String TOKEN     = "$escapedToken";

    /** Handset label the collector files reports under. */
    public static final String DEVICE    = "$escapedDevice";

    public static final boolean CONFIGURED = $sinkConfigured;

    private DevSink() { }
}
"@ | Set-Content -Path (Join-Path $genDir "DevSink.java") -Encoding UTF8

    if ($sinkConfigured -eq "true") {
        Write-Warn2 ("EMBEDDED report sink from {0}: device={1}, token {2}" -f `
                     $sink.source, $sink.device, (Format-SecretDigest $sink.token))
    } elseif ((Test-Path (Join-RepoPath "secrets" "dev-sink.yaml")) -and -not $EmbedDevSecrets) {
        Write-Ok "report sink present but NOT embedded (pass -EmbedDevSecrets to include it)."
    } else {
        # Not a warning: no sink is the correct state for every public build.
        Write-Ok "no report sink configured - upload commands will be inert."
    }

    # Default MTProxy. Same treatment again: a tg://proxy link carries a secret
    # and names private infrastructure, so it lives in secrets/ and is compiled
    # into generated/, never into src/.
    $proxy = if ($EmbedDevSecrets) { Get-DevProxy } else { Get-EmptyDevProxy }
    $proxyConfigured = if ($proxy.link) { "true" } else { "false" }
    $escapedLink = $proxy.link -replace '\\', '\\' -replace '"', '\"'

    @"
package tg.app;

/**
 * Generated by tools/build.ps1 from $($proxy.source) - do not edit, do not commit.
 *
 * Default MTProxy for this build, as a tg://proxy link. Empty in any build that
 * had no secrets/proxy.yaml, which is every public artifact.
 *
 * Used only when the handset has no proxy stored yet. Anything entered in
 * Settings wins and is what gets persisted, so this is a starting value and
 * never an override.
 */
public final class DevProxy
{
    public static final String LINK = "$escapedLink";

    public static final boolean CONFIGURED = $proxyConfigured;

    private DevProxy() { }
}
"@ | Set-Content -Path (Join-Path $genDir "DevProxy.java") -Encoding UTF8

    if ($proxyConfigured -eq "true") {
        Write-Warn2 ("EMBEDDED default MTProxy from {0}: {1}, secret {2}" -f `
                     $proxy.source, $proxy.server, (Format-SecretDigest $proxy.link))
    } elseif ((Test-Path (Join-RepoPath "secrets" "proxy.yaml")) -and -not $EmbedDevSecrets) {
        Write-Ok "default MTProxy present but NOT embedded (pass -EmbedDevSecrets to include it)."
    } else {
        Write-Ok "no default MTProxy configured - enter one in Settings on the device."
    }
}

<#
.SYNOPSIS
    The shape Get-DevSink / Get-DevProxy return when there is nothing to read.

.DESCRIPTION
    Returning an empty record rather than skipping the generation keeps DevSink
    and DevProxy present with CONFIGURED = false, which is what every public
    artifact has always contained. The generator below is then identical in both
    modes and there is one less branch to get wrong.
#>
function Get-EmptyDevSink {
    [PSCustomObject]@{
        source = "no source (dev secrets not embedded)"
        host = ""; httpPort = 80; tcpPort = 8443; token = ""; device = ""
    }
}

function Get-EmptyDevProxy {
    [PSCustomObject]@{
        source = "no source (dev secrets not embedded)"
        link = ""; server = ""
    }
}

<#
.SYNOPSIS
    Prove the packaged JAR carries no development secret the build did not embed.

.DESCRIPTION
    The flag above is a promise about a code path; this is a check on the
    artifact, and it exists because the failure it guards against has happened:
    a locally built release was published carrying the collector token and the
    MTProxy secret, because the build read whatever was in secrets/ and the
    release workflow - which never has those files - could not see the
    difference.

    Reads the JAR's entries and looks for the literal values. Constant strings
    are inlined by javac into every use site and survive ProGuard, obfuscated or
    not, so a hit here is real and an absence is meaningful.
#>
function Assert-NoUnembeddedSecrets ($jarPath) {
    $wanted = @()
    if (-not $EmbedDevSecrets) {
        $sink = Get-DevSink
        if ($sink.token) { $wanted += @{ What = "collector token"; Value = $sink.token } }
        if ($sink.host)  { $wanted += @{ What = "collector host";  Value = $sink.host } }
        $proxy = Get-DevProxy
        if ($proxy.link) { $wanted += @{ What = "MTProxy link";    Value = $proxy.link } }
    }
    if ($wanted.Count -eq 0) { return }

    # One read of the whole archive: these are small JARs and the alternative is
    # unzipping to disk to grep something that is 400 KB in memory.
    $bytes = [System.IO.File]::ReadAllBytes($jarPath)
    $text  = [System.Text.Encoding]::GetEncoding(28591).GetString($bytes)

    $found = @($wanted | Where-Object { $text.Contains($_.Value) })
    if ($found.Count -gt 0) {
        Write-Bad ("$(Split-Path -Leaf $jarPath) contains a development secret that " +
                   "was not asked for: " + (($found | ForEach-Object { $_.What }) -join ", "))
        Write-Host "         This build ran without -EmbedDevSecrets, so nothing from" -ForegroundColor Red
        Write-Host "         secrets/dev-sink.yaml or secrets/proxy.yaml should be in it." -ForegroundColor Red
        Write-Host "         Do not publish this artifact. Report it as a build defect." -ForegroundColor Red
        exit 1
    }
    Write-Ok ("no unembedded development secrets in the JAR ({0} checked)" -f $wanted.Count)
}

# ==========================================================================
# desktop profile
# ==========================================================================
if ($BuildProfile -eq 'desktop') {
    $desktopDir = Join-RepoPath "build" "desktop"
    $outSrc  = Join-Path $desktopDir "classes"
    $outTest = Join-Path $desktopDir "test-classes"
    if ($Clean -and (Test-Path $desktopDir)) {
        Remove-Item $desktopDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $outSrc, $outTest | Out-Null

    # Same bootclasspath as the device profile. tg.ui / tg.plat reference
    # javax.microedition, and they still have to compile here even though the
    # desktop tests never instantiate them - the point of this profile is that
    # it builds the *same* sources, not a subset.
    $desktopBoot = Get-DeviceBootClassPath

    Write-Step "desktop :: generated sources"
    Write-GeneratedSources (Get-BootClassPathMode) (Get-BuildId)

    Write-Step "desktop :: compiling src/ + generated/ (source 1.3, same as device)"
    $srcFiles = Get-JavaSources @("src", "generated")
    if ($srcFiles.Count -eq 0) { Write-Bad "no sources under src/"; exit 1 }
    $listFile = Join-Path $desktopDir "sources.txt"
    $srcFiles | Set-Content -Path $listFile -Encoding UTF8
    Invoke-Checked $Jdk8Javac @(
        "-source", "1.3", "-target", "1.1", "-nowarn",
        "-bootclasspath", $desktopBoot,
        "-encoding", "UTF-8", "-d", $outSrc, "@$listFile"
    ) "javac (src)" | Out-Null
    Write-Ok "$($srcFiles.Count) file(s) -> build/desktop/classes"

    $testFiles = Get-JavaSources @("test")
    if ($testFiles.Count -gt 0) {
        Write-Step "desktop :: compiling test/ (source 1.6, desktop-only APIs allowed)"
        $testList = Join-Path $desktopDir "test-sources.txt"
        $testFiles | Set-Content -Path $testList -Encoding UTF8
        # MIDP jars stay on the classpath so a test may reference a device type
        # if it ever needs to; java.net and friends come from the default JDK 8
        # bootclasspath, which is what makes SeTransport possible.
        $testCp = (@($outSrc) + $MicroEmuJars) -join $PathSep
        Invoke-Checked $Jdk8Javac @(
            "-source", "1.6", "-target", "1.6", "-nowarn",
            "-cp", $testCp, "-encoding", "UTF-8", "-d", $outTest, "@$testList"
        ) "javac (test)" | Out-Null
        Write-Ok "$($testFiles.Count) file(s) -> build/desktop/test-classes"
    } else {
        Write-Warn2 "no sources under test/ yet"
    }

    Write-Host ""
    Write-Host ("desktop build OK. Run tests with:  {0}" -f $(if ($OnWindows) { ".\tools\test.ps1" } else { "./tools/test.sh" })) -ForegroundColor Green
    exit 0
}

# ==========================================================================
# device profile
# ==========================================================================
$buildDir  = Join-RepoPath "build" "device"
$classDir  = Join-Path $buildDir "classes"
$preverDir = Join-Path $buildDir "preverified"
$distDir   = Join-Path $RepoRoot "dist"

if ($Clean -and (Test-Path $buildDir)) { Remove-Item $buildDir -Recurse -Force }
if (Test-Path $classDir)  { Remove-Item $classDir  -Recurse -Force }
if (Test-Path $preverDir) { Remove-Item $preverDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classDir, $preverDir, $distDir | Out-Null

$bootCp   = Get-DeviceBootClassPath
$bootMode = Get-BootClassPathMode
$buildId  = Get-BuildId

Write-Host ""
Write-Host "target=$Target  env=$Env  profile=device  bootclasspath=$bootMode  build=$buildId" -ForegroundColor White
if ($Env -eq 'test' -and $Target -ne 'probe') {
    # Loud, because -Env defaults to test and a handset session almost always
    # wants production. Getting this wrong costs a reinstall and looks like a
    # crypto fault rather than a build mistake.
    Write-Warn2 "TEST data centres. Installs as `"$MidletName`" - it will NOT reach a real account."
    Write-Warn2 "For a real handset session: -Env production"
}

# -- 0. generated sources ---------------------------------------------------
Write-GeneratedSources $bootMode $buildId

# -- 1. compile -------------------------------------------------------------
Write-Step "javac -source 1.3 -target 1.1"
$srcFiles = Get-JavaSources @("src", "generated")
if ($srcFiles.Count -eq 0) { Write-Bad "no sources under src/"; exit 1 }
$listFile = Join-Path $buildDir "sources.txt"
$srcFiles | Set-Content -Path $listFile -Encoding UTF8

$javacOut = & $Jdk8Javac `
    -source 1.3 -target 1.1 `
    -bootclasspath $bootCp `
    -encoding UTF-8 -d $classDir "@$listFile" 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host $javacOut
    Write-Bad "javac failed"
    exit 1
}
# The "source/target 1.x is obsolete" notes are expected and not interesting.
$realWarnings = ($javacOut -split "`r?`n") |
    Where-Object { $_ -and $_ -notmatch 'is obsolete|Xlint:-options|^\d+ warning' }
if ($realWarnings) { $realWarnings | ForEach-Object { Write-Warn2 $_ } }
Write-Ok "$($srcFiles.Count) source file(s) compiled"

# -- 2. CLDC subset enforcement --------------------------------------------
if (-not $SkipApiCheck) {
    Write-Step "check-api.py (CLDC 1.1 / MIDP 2.0 subset)"
    if ($bootMode -eq 'wtk') {
        Write-Ok "javac already enforced the subset via the WTK bootclasspath; re-checking anyway"
    }
    $py = Get-PythonCommand
    if (-not $py) { Write-Bad "python 3 not found on PATH (looked for python3, then python)"; exit 1 }
    & $py (Join-Path $PSScriptRoot "check-api.py") $classDir
    if ($LASTEXITCODE -ne 0) { Write-Bad "API check failed"; exit 1 }
}

# -- 3. preverify + shrink --------------------------------------------------
$pgWhat = if ($Release) { "preverify + shrink + optimise + obfuscate" } else { "preverify + shrink" }
Write-Step "ProGuard (-microedition: $pgWhat)"
if (-not (Test-Path $ProGuardJar)) { Write-Bad "proguard.jar missing - run bootstrap.ps1"; exit 1 }

$pgArgs = @(
    "-jar", $ProGuardJar,
    "@$(Join-RepoPath 'config' 'proguard-common.pro')",
    "@$(Join-RepoPath 'config' ("proguard-{0}.pro" -f $Target))"
)
# -dontoptimize / -dontobfuscate are boolean: once ProGuard has seen them no
# later option can undo them. So the release path omits the file rather than
# trying to override it.
if (-not $Release) {
    $pgArgs += "@$(Join-RepoPath 'config' 'proguard-debug.pro')"
}
$pgArgs += @("-injars", $classDir, "-outjars", $preverDir)
foreach ($lib in ($bootCp -split [regex]::Escape($PathSep))) {
    if ($lib) { $pgArgs += @("-libraryjars", $lib) }
}
if ($Release) { $pgArgs += @("-optimizationpasses", "3") }

# ProGuard 7 needs Java 8+; prefer JDK 8 so it sees the same runtime as javac,
# fall back to whatever java is on PATH.
$pgJava = if ($Jdk8Java -and (Test-Path $Jdk8Java)) { $Jdk8Java } else { "java" }
$pgOut = & $pgJava @pgArgs 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host $pgOut
    Write-Bad "ProGuard failed"
    exit 1
}
$kept = @(Get-ChildItem $preverDir -Recurse -Filter *.class).Count
Write-Ok "$kept class(es) preverified"

# Runtime PNGs and similar MIDP resources are not inputs to ProGuard. Copy them
# into the preverified tree so the ordinary jar step packages them at root.
$resourceDir = Join-Path $RepoRoot "res"
if (Test-Path $resourceDir) {
    Copy-Item -Path (Join-Path $resourceDir "*") -Destination $preverDir -Recurse -Force
}
# Third-party licences travel with the code they cover. Both the Bouncy Castle
# licence and Apache 2.0 require the notice to reach whoever receives the
# binary, and the attribution in the source headers does not survive
# compilation - a .class file carries no comments. Shipping the JAR without
# these texts does not comply with either licence.
#
# Which ones apply is a property of the target, because each
# config/proguard-<target>.pro keeps a different entry point and ProGuard
# shrinks to what that entry point reaches. Verified against the built JARs:
# probe draws emoji and - since the crypto suite was folded into it - carries
# bigint; only tg decodes photos. The check below re-confirms it on every
# non-obfuscated build rather than trusting this comment to stay true.
$licences = @{
    probe  = @("noto-emoji", "bc")
    tg     = @("noto-emoji", "bc", "pdfjs")
}[$Target]

# Src is forward-slashed: Join-Path accepts that on every platform, where a
# backslash literal would become part of the file name on Linux.
$licenceFiles = @{
    "noto-emoji" = @{ Src = "third_party/noto-emoji/OFL.txt";           Dest = "emoji-OFL.txt";                Class = "EmojiText" }
    "bc"         = @{ Src = "third_party/bc/LICENSE.html";              Dest = "bouncycastle-LICENSE.html";    Class = "BigInteger" }
    "pdfjs"      = @{ Src = "third_party/pdfjs/LICENSE-APACHE-2.0.txt"; Dest = "pdfjs-LICENSE-APACHE-2.0.txt"; Class = "JpegDecoder" }
}

foreach ($id in $licences) {
    $src = Join-Path $RepoRoot $licenceFiles[$id].Src
    if (-not (Test-Path $src)) {
        Write-Bad "missing licence text: $($licenceFiles[$id].Src)"
        exit 1
    }
    Copy-Item -LiteralPath $src `
        -Destination (Join-Path $preverDir $licenceFiles[$id].Dest) -Force
}
Write-Ok ("licences packaged: {0}" -f ($licences -join ", "))

# Obfuscation renames classes, so this can only be checked on a readable build.
# It catches the one failure that matters: code shipping without its licence.
if (-not $Release) {
    foreach ($id in $licenceFiles.Keys) {
        $cls = $licenceFiles[$id].Class
        $present = @(Get-ChildItem $preverDir -Recurse -Filter "$cls*.class").Count -gt 0
        if ($present -and ($licences -notcontains $id)) {
            Write-Bad "$cls ships in $Target but its licence ($id) does not."
            Write-Host ("         Add '{0}' to the `$licences table for '{1}' in tools/build.ps1." -f $id, $Target) -ForegroundColor Red
            exit 1
        }
    }
}

# StackMap is the whole point of -microedition; verify it actually landed.
# Sampled by size rather than by name: preverification only attaches StackMap to
# methods with code, so an interface carries none and is not evidence either
# way. The largest class in the build certainly has code.
$sample = Get-ChildItem $preverDir -Recurse -Filter *.class |
          Sort-Object Length -Descending | Select-Object -First 1
if ($sample) {
    $bytes = [System.IO.File]::ReadAllBytes($sample.FullName)
    $text  = [System.Text.Encoding]::ASCII.GetString($bytes)
    if ($text -match 'StackMap') { Write-Ok "StackMap attribute present (CLDC preverification confirmed)" }
    else { Write-Warn2 "no StackMap attribute found in $($sample.Name) - the phone's verifier may reject this JAR" }
}

# -- 4. package JAR ---------------------------------------------------------
Write-Step "packaging"
$manifest = Join-Path $buildDir "MANIFEST.MF"
@"
Manifest-Version: 1.0
MIDlet-Name: $MidletName
MIDlet-Version: $AppVersion
MIDlet-Vendor: $AppVendor
MIDlet-1: $MidletName,,$MidletClass
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
"@ -replace "`r`n", "`n" | Set-Content -Path $manifest -Encoding ASCII -NoNewline
Add-Content -Path $manifest -Value "`n" -Encoding ASCII -NoNewline

$jarPath = Join-Path $distDir "$ArtifactName.jar"
if (Test-Path $jarPath) {
    try {
        Remove-Item $jarPath -Force -ErrorAction Stop
    } catch {
        Write-Bad "cannot replace $jarPath - a running emulator still has it open."
        Write-Host "         Close the emulator window (or stop run-emulator.ps1) and rebuild." -ForegroundColor Yellow
        exit 1
    }
}
Push-Location $preverDir
try {
    Invoke-Checked $Jdk8Jar @("cfm", $jarPath, $manifest, ".") "jar" | Out-Null
} finally { Pop-Location }

$jarSize = (Get-Item $jarPath).Length

# The artifact answers for itself before a JAD is written for it.
Assert-NoUnembeddedSecrets $jarPath

# -- 5. JAD -----------------------------------------------------------------
# MIDlet-Jar-Size must match the JAR byte-for-byte or the AMS aborts the install.
#
# MIDlet-Jar-URL stays relative on purpose. The realistic install path is
# "copy both files to the phone and open the .jad", where relative is the only
# thing that works. An absolute GitHub release URL would not help either: the
# download redirects to another host, and a 2011 handset's TLS stack cannot
# negotiate with it anyway.
$jadPath = Join-Path $distDir "$ArtifactName.jad"
@"
MIDlet-Name: $MidletName
MIDlet-Version: $AppVersion
MIDlet-Vendor: $AppVendor
MIDlet-1: $MidletName,,$MidletClass
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
MIDlet-Jar-URL: $ArtifactName.jar
MIDlet-Jar-Size: $jarSize
MIDlet-Description: Direct MTProto 2.0 client, build $buildId
"@ -replace "`r`n", "`n" | Set-Content -Path $jadPath -Encoding ASCII

Write-Ok "dist/$ArtifactName.jar  ($([math]::Round($jarSize / 1KB, 1)) KB)"
Write-Ok "dist/$ArtifactName.jad  (MIDlet-Jar-Size: $jarSize)"

# Last thing on screen, because the last thing on screen is what gets read
# before a file is copied somewhere. Only when it is true.
if ($EmbedDevSecrets) {
    Write-Host ""
    Write-Warn2 "this artifact carries development secrets - collector token and/or"
    Write-Warn2 "MTProxy link. It is for a handset session. DO NOT PUBLISH IT."
}

Write-Host ""
$runCmd = if ($OnWindows) { ".\tools\run-emulator.ps1" } else { "pwsh -File tools/run-emulator.ps1" }
Write-Host "build OK. Run it with:  $runCmd -Target $Target" -ForegroundColor Green
