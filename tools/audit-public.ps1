<#
.SYNOPSIS
    Audit the complete would-be commit set before publishing.

.DESCRIPTION
    Checks tracked and untracked non-ignored files. Reports file names only:
    matching values and lines are deliberately never printed.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

Push-Location $RepoRoot
try {
    $candidateOutput = & git ls-files --cached --others --exclude-standard
    if ($LASTEXITCODE -ne 0) {
        Write-Bad "cannot enumerate the Git publication set"
        exit 1
    }
    $candidates = @($candidateOutput | Where-Object {
        $_ -and (Test-Path -LiteralPath (Join-Path $RepoRoot $_))
    })
    $failures = New-Object System.Collections.Generic.List[string]

    function Add-Failure([string]$category, [string]$path) {
        $entry = "$category :: $path"
        if (-not $failures.Contains($entry)) {
            $failures.Add($entry)
        }
    }

    # Only the placeholder files from generated/output directories may be
    # published. All actual output is local and reproducible.
    $privateRoots = @(
        "secrets/", "local/", "private/",
        "build/", "dist/", "generated/", "sdk/"
    )
    foreach ($path in $candidates) {
        $normalized = $path.Replace("\", "/")
        foreach ($root in $privateRoots) {
            if ($normalized.StartsWith($root) -and
                    -not $normalized.EndsWith("/.gitkeep")) {
                Add-Failure "private or generated path" $path
            }
        }
    }

    $textExtensions = @(
        ".java", ".md", ".txt", ".ps1", ".py", ".sh", ".toml", ".in",
        ".json", ".yaml", ".yml", ".properties", ".xml", ".html",
        ".dockerfile", ".gitignore", ".gitattributes", ""
    )
    $patterns = @(
        "-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
        "\bgh[pousr]_[A-Za-z0-9]{20,}\b",
        "\bgithub_pat_[A-Za-z0-9_]{20,}\b",
        "\bAKIA[0-9A-Z]{16}\b",
        "\b[0-9]{6,12}:[A-Za-z0-9_-]{30,}\b",
        "(?i)\b(api[_-]?hash|auth[_-]?key|password|access[_-]?token)\s*[:=]\s*(['""][A-Za-z0-9_+/\-]{16,}['""]|[0-9a-f]{32,})"
    )
    $personalPatterns = @(
        "(?i)\b[A-Z]:\\Users\\[^\\/\s]+",
        "(?i)/(Users|home)/[^/\s]+"
    )

    $candidateText = @{}
    foreach ($path in $candidates) {
        $extension = [IO.Path]::GetExtension($path).ToLowerInvariant()
        $leaf = [IO.Path]::GetFileName($path).ToLowerInvariant()
        if ($leaf -eq "dockerfile") { $extension = ".dockerfile" }
        if (-not $textExtensions.Contains($extension)) { continue }
        try {
            $text = [IO.File]::ReadAllText((Join-Path $RepoRoot $path))
            $candidateText[$path] = $text
        } catch {
            Add-Failure "unreadable publication file" $path
            continue
        }
        foreach ($pattern in $patterns) {
            if ($text -match $pattern) {
                Add-Failure "possible credential" $path
                break
            }
        }
        foreach ($pattern in $personalPatterns) {
            if ($text -match $pattern) {
                Add-Failure "machine-specific home path" $path
                break
            }
        }
    }

    # Compare only values associated with sensitive-looking keys. This avoids
    # printing them and avoids false positives from harmless options such as
    # environment names and public data-centre addresses.
    $localValues = New-Object System.Collections.Generic.List[string]
    $secretRoot = Join-Path $RepoRoot "secrets"
    if (Test-Path $secretRoot) {
        foreach ($file in Get-ChildItem $secretRoot -File -Recurse) {
            foreach ($line in Get-Content -LiteralPath $file.FullName) {
                if ($line -match "^\s*[#;]" -or $line.Trim().Length -eq 0) {
                    continue
                }
                if ($line -match "^\s*([^:=]+)\s*[:=]\s*(.*?)\s*$") {
                    $key = $Matches[1].Trim()
                    $value = $Matches[2].Trim().Trim('"').Trim("'")
                    # host/address/endpoint/url are here because secrets/ now also
                    # holds a development report sink: an IP under "host:" is not a
                    # credential, but publishing it exposes private infrastructure
                    # just as effectively as publishing a token would.
                    if ($key -match "(?i)(api.?id|api.?hash|phone|auth|password|token|secret|session|host|address|endpoint|url|ingest|link|server|proxy)" -and
                            $value.Length -ge 6 -and
                            $value -notmatch "^(0|REPLACE_ME|CHANGE_ME|PENDING|example|localhost|127\.0\.0\.1)$") {
                        if (-not $localValues.Contains($value)) {
                            $localValues.Add($value)
                        }
                        # A secret is often one field inside a larger value -
                        # a tg://proxy link is stored whole, but what would
                        # leak is the secret= parameter on its own. Harvest the
                        # parts as well as the whole.
                        if ($value -match '\?') {
                            foreach ($pair in ($value -split '\?', 2)[1] -split '&') {
                                $parts = $pair -split '=', 2
                                if ($parts.Count -eq 2 -and $parts[1].Length -ge 8) {
                                    if (-not $localValues.Contains($parts[1])) {
                                        $localValues.Add($parts[1])
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    foreach ($entry in $candidateText.GetEnumerator()) {
        foreach ($value in $localValues) {
            if ($entry.Value.Contains($value)) {
                Add-Failure "exact local secret value" $entry.Key
                break
            }
        }
    }

    # ----------------------------------------------------------------------
    # Everything above audits the working tree. That is not the whole attack
    # surface: a force-push does not delete the commit it replaced. GitHub keeps
    # the orphan reachable at /commit/<sha> forever, with nothing in the UI to
    # hint it exists, and the push is mirrored into the public events archive.
    # Rewriting history to strip a file therefore does not unpublish it.
    #
    # The reflog for a remote-tracking branch records one entry per push, so
    # more than one entry means at least one push was not a fast-forward.
    # Reported as a warning, not a failure: on a repository that has always been
    # public and never rewritten there is nothing to do, and a fresh clone has
    # no reflog at all.
    # ----------------------------------------------------------------------
    $orphanWarnings = New-Object System.Collections.Generic.List[string]
    $branch = (& git rev-parse --abbrev-ref HEAD 2>$null)
    if ($LASTEXITCODE -eq 0 -and $branch -and $branch -ne "HEAD") {
        $remoteRef = "origin/$branch"
        $reflog = @(& git reflog show $remoteRef 2>$null)
        if ($LASTEXITCODE -eq 0 -and $reflog.Count -gt 1) {
            $tip = (& git rev-parse $remoteRef 2>$null)
            foreach ($line in $reflog) {
                if ($line -match '^([0-9a-f]{7,40})\s') {
                    $sha = (& git rev-parse $Matches[1] 2>$null)
                    if ($LASTEXITCODE -ne 0 -or -not $sha -or $sha -eq $tip) { continue }
                    # Still in the published history? Then it was a plain
                    # fast-forward and nothing was orphaned.
                    & git merge-base --is-ancestor $sha $tip 2>$null | Out-Null
                    if ($LASTEXITCODE -ne 0 -and -not $orphanWarnings.Contains($sha)) {
                        $orphanWarnings.Add($sha)
                    }
                }
            }
        }
    }

    if ($failures.Count -gt 0) {
        Write-Bad "public audit found $($failures.Count) issue(s); contents are hidden:"
        $failures | Sort-Object | ForEach-Object { Write-Host "    $_" }
        exit 1
    }

    Write-Ok "public audit passed: $($candidates.Count) file(s)"
    Write-Ok "private paths excluded; no local secret reuse or common credential formats found"

    if ($orphanWarnings.Count -gt 0) {
        Write-Host ""
        Write-Warn2 "$($orphanWarnings.Count) commit(s) were pushed and then force-pushed away."
        Write-Warn2 "A clean working tree does not unpublish them - GitHub still serves each one:"
        foreach ($sha in $orphanWarnings) {
            Write-Host "         https://github.com/<owner>/<repo>/commit/$sha" -ForegroundColor DarkGray
        }
        Write-Warn2 "Check what they contain (git show --stat <sha>). To actually remove them,"
        Write-Warn2 "delete and recreate the repository, or ask GitHub Support to expire the objects."
    }

    exit 0
} finally {
    Pop-Location
}
