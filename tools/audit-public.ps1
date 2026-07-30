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
. "$PSScriptRoot\_env.ps1"

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
                    if ($key -match "(?i)(api.?id|api.?hash|phone|auth|password|token|secret|session)" -and
                            $value.Length -ge 6 -and
                            $value -notmatch "^(0|REPLACE_ME|CHANGE_ME|example)$") {
                        if (-not $localValues.Contains($value)) {
                            $localValues.Add($value)
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

    if ($failures.Count -gt 0) {
        Write-Bad "public audit found $($failures.Count) issue(s); contents are hidden:"
        $failures | Sort-Object | ForEach-Object { Write-Host "    $_" }
        exit 1
    }

    Write-Ok "public audit passed: $($candidates.Count) file(s)"
    Write-Ok "private paths excluded; no local secret reuse or common credential formats found"
    exit 0
} finally {
    Pop-Location
}
