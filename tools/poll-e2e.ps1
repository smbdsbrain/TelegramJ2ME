<#
.SYNOPSIS
    Run the exact-packaged two-session poll flow and retain the final poll.

.DESCRIPTION
    For each supplied normal/minified artifact, copies the authorized `live`
    client and `bigchats` fixture profiles into isolated MicroEmulator homes.
    The packaged JAR opens the target group first; the distinct fixture account
    then creates a unique multiple-choice poll, waits for the packaged client
    to vote, and changes its own vote.
    Screenshots and a terse evidence file are stored below local/poll-e2e/.
    Intermediate fixture polls are deleted; the final artifact's poll is left
    in its final state intentionally.
#>
[CmdletBinding()]
param(
    [string[]]$ArtifactName = @(
        'J2MEgram-1.3.0-poll',
        'J2MEgram-1.3.0-poll-min'
    ),
    [string]$Profile = 'live',
    [string]$FixtureProfile = 'bigchats',
    [string]$ChatTitle = 'Моя любимая группа',
    [string[]]$JavaArgs = @('-Xmx32m')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

if (-not $Jdk8Home) {
    Write-Bad 'JDK 8 not found. Run tools/bootstrap.ps1 first.'
    exit 1
}
if ($ArtifactName.Count -lt 1) {
    Write-Bad 'At least one packaged artifact is required.'
    exit 1
}
foreach ($artifact in $ArtifactName) {
    if ($artifact -notmatch '^J2MEgram-(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:-min)?$') {
        Write-Bad "Invalid packaged artifact name: $artifact"
        exit 1
    }
    if (-not (Test-Path -LiteralPath (Join-RepoPath 'dist' "$artifact.jar"))) {
        Write-Bad "dist/$artifact.jar not found"
        exit 1
    }
}

foreach ($profileName in @($Profile, $FixtureProfile)) {
    $keys = Join-Path (Join-RepoPath 'local' 'microemulator' $profileName) `
            '.microemulator/suite-null/tgkeys.rs'
    if (-not (Test-Path -LiteralPath $keys) -or
            (Get-Item -LiteralPath $keys).Length -eq 0) {
        Write-Bad "MicroEmulator profile '$profileName' has no saved authorization"
        exit 1
    }
}
$baseProfile = Join-RepoPath 'local' 'microemulator' $Profile
$baseFixtureProfile = Join-RepoPath 'local' 'microemulator' $FixtureProfile

& (Join-Path $PSScriptRoot 'build.ps1') -Profile desktop -Env production
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$root = Join-RepoPath 'local' 'poll-e2e'
New-Item -ItemType Directory -Force -Path $root | Out-Null
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$run = Join-Path $root $runStamp
New-Item -ItemType Directory -Path $run | Out-Null
$classes = Join-RepoPath 'build' 'desktop' 'classes'
$tests = Join-RepoPath 'build' 'desktop' 'test-classes'
$res = Join-RepoPath 'res'
$fixtureCp = (@($classes, $tests, $res) + $MicroEmuJars) -join $PathSep

function Wait-Signal([string]$path, [Diagnostics.Process]$process,
                     [int]$timeoutMs) {
    $until = [DateTime]::UtcNow.AddMilliseconds($timeoutMs)
    while ([DateTime]::UtcNow -lt $until) {
        if (Test-Path -LiteralPath $path) { return }
        if ($process.HasExited) {
            throw "packaged client exited before $(Split-Path $path -Leaf)"
        }
        Start-Sleep -Milliseconds 100
    }
    throw "timed out waiting for $(Split-Path $path -Leaf)"
}

function Quote-ProcessArg([string]$value) {
    return '"' + $value.Replace('"', '\"') + '"'
}

$previousState = $null
$previousFixtureProfile = $null
for ($index = 0; $index -lt $ArtifactName.Count; $index++) {
    $artifact = $ArtifactName[$index]
    $state = Join-Path $run ("{0:D2}-{1}" -f ($index + 1), $artifact)
    New-Item -ItemType Directory -Path $state | Out-Null
    $marker = 'J2ME-POLL-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' +
            ([guid]::NewGuid().ToString('N').Substring(0, 8))
    [IO.File]::WriteAllText((Join-Path $state 'marker'), $marker,
            [Text.Encoding]::UTF8)
    [IO.File]::WriteAllText((Join-Path $state 'chat-title'), $ChatTitle,
            [Text.Encoding]::UTF8)

    $safeArtifact = $artifact -replace '[^A-Za-z0-9._-]', '-'
    $clientProfile = Join-Path $run "$safeArtifact-client-profile"
    $fixtureProfile = Join-Path $run "$safeArtifact-fixture-profile"
    Copy-Item -LiteralPath $baseProfile -Destination $clientProfile -Recurse
    Copy-Item -LiteralPath $baseFixtureProfile `
        -Destination $fixtureProfile -Recurse

    $artifactJar = Join-RepoPath 'dist' "$artifact.jar"
    $clientCp = (@($artifactJar, $tests, $res) + $MicroEmuJars) -join $PathSep
    $clientOut = Join-Path $state 'client.out'
    $clientErr = Join-Path $state 'client.err'
    [string[]]$clientArgs = @(
        '-Djava.awt.headless=true',
        "-Duser.home=$clientProfile",
        '-Dtg.driver.expectenv=production'
    ) + $JavaArgs + @(
        '-cp', $clientCp, 'tgtest.PackagedRcE2EDriver',
        'poll-client', $state, 'a'
    )
    $clientLine = ($clientArgs | ForEach-Object {
        Quote-ProcessArg ([string] $_)
    }) -join ' '

    Write-Step "poll E2E :: $artifact"
    # Put this otherwise old dialog onto Telegram's first page without using
    # Refresh inside the client. The marked wake message is removed after the
    # actual poll has completed; it is not the fixture under test.
    & $Jdk8Java "-Duser.home=$fixtureProfile" '-Djava.awt.headless=true' `
        -cp $fixtureCp tgtest.PollFixtureDriver bump $state $ChatTitle
    if ($LASTEXITCODE -ne 0) { throw 'could not bump the target dialog' }
    $client = Start-Process -FilePath $Jdk8Java `
        -ArgumentList $clientLine -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $clientOut -RedirectStandardError $clientErr
    try {
        Wait-Signal (Join-Path $state 'client-ready') $client 150000
        & $Jdk8Java "-Duser.home=$fixtureProfile" '-Djava.awt.headless=true' `
            -cp $fixtureCp tgtest.PollFixtureDriver `
            create-change $state $ChatTitle
        if ($LASTEXITCODE -ne 0) { throw 'poll fixture failed' }
        if (-not $client.WaitForExit(150000)) {
            Stop-Process -Id $client.Id -Force
            throw 'packaged poll client did not finish'
        }
        if ($client.ExitCode -ne 0) {
            throw "packaged poll client failed with exit $($client.ExitCode)"
        }
    }
    finally {
        if (-not $client.HasExited) { Stop-Process -Id $client.Id -Force }
    }

    $evidence = @(
        "artifact=$artifact",
        'exact-packaged-jar=pass',
        'live-arrival-without-refresh=pass',
        'multiple-picker=pass',
        'authoritative-local-vote=pass',
        'unsolicited-revote-repaint=pass',
        'screenshots=4'
    )
    [IO.File]::WriteAllText((Join-Path $state 'evidence.txt'),
            ($evidence -join "`n") + "`n", [Text.Encoding]::UTF8)

    & $Jdk8Java "-Duser.home=$fixtureProfile" '-Djava.awt.headless=true' `
        -cp $fixtureCp tgtest.PollFixtureDriver delete-bump $state $ChatTitle
    if ($LASTEXITCODE -ne 0) {
        Write-Warn2 'could not remove the dialog wake message'
    }

    # Leave exactly the newest scenario poll. Once the next artifact has
    # passed, its predecessor is no longer the retained final fixture.
    if ($previousState) {
        & $Jdk8Java "-Duser.home=$previousFixtureProfile" `
            '-Djava.awt.headless=true' -cp $fixtureCp `
            tgtest.PollFixtureDriver delete $previousState $ChatTitle
        if ($LASTEXITCODE -ne 0) {
            Write-Warn2 'could not remove the preceding poll fixture'
        }
    }
    $previousState = $state
    $previousFixtureProfile = $fixtureProfile
    Write-Ok "poll E2E passed: $artifact"
}

[IO.File]::WriteAllText((Join-Path $run 'evidence.txt'),
        "artifacts=$($ArtifactName.Count)`nfinal-poll=retained`n",
        [Text.Encoding]::UTF8)
Write-Ok "poll E2E passed; final uniquely marked poll retained"
Write-Host "         private screenshots/evidence: $run" -ForegroundColor Yellow
