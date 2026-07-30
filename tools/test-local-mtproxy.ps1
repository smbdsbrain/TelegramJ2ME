<#
.SYNOPSIS
    Live-test classic, dd and ee MTProxy client routes.

.DESCRIPTION
    Uses pinned Telemt 3.4.25 in direct-to-DC mode so the test works behind
    ordinary Docker Desktop NAT. The generated secret and config are deleted
    in finally and are never printed.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\_env.ps1"

$dockerVersion = & docker version --format '{{.Server.Version}}' 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Docker daemon is unavailable. Start Docker Desktop and retry."
}

$secretBytes = New-Object byte[] 16
$crypto = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $crypto.GetBytes($secretBytes) } finally { $crypto.Dispose() }
$secretHex = ([BitConverter]::ToString($secretBytes)).Replace("-", "").ToLowerInvariant()
$domain = "www.google.com"
$domainHex = ([BitConverter]::ToString(
    [System.Text.Encoding]::ASCII.GetBytes($domain))).Replace("-", "").ToLowerInvariant()
$image = "ghcr.io/telemt/telemt:3.4.25"
$container = "telegramj2me-telemt-test"
$configPath = Join-Path ([IO.Path]::GetTempPath()) (
    "telegramj2me-telemt-{0}.toml" -f [Guid]::NewGuid().ToString("N"))

try {
    $template = Get-Content -Raw (Join-Path $PSScriptRoot "telemt\test-config.toml.in")
    [IO.File]::WriteAllText(
        $configPath,
        $template.Replace("__SECRET__", $secretHex),
        [Text.UTF8Encoding]::new($false))

    & docker pull $image
    if ($LASTEXITCODE -ne 0) { throw "Telemt Docker image pull failed" }

    & docker run --rm -d --name $container `
        -p "127.0.0.1:8443:443" `
        --mount "type=bind,source=$configPath,target=/app/test-config.toml,readonly" `
        $image "/app/test-config.toml" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Telemt container failed to start" }

    $ready = $false
    for ($attempt = 0; $attempt -lt 30 -and -not $ready; $attempt++) {
        $tcp = [System.Net.Sockets.TcpClient]::new()
        try {
            $pending = $tcp.BeginConnect("127.0.0.1", 8443, $null, $null)
            if ($pending.AsyncWaitHandle.WaitOne(1000)) {
                $tcp.EndConnect($pending)
                $ready = $true
            }
        }
        catch {
            # Telemt may still be preparing its DC connections.
        }
        finally {
            $tcp.Dispose()
        }
        if (-not $ready) { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) {
        & docker logs --tail 120 $container
        throw "Telemt did not open localhost:8443"
    }
    # Docker's published port can accept before Telemt finishes its startup
    # probes and starts the protocol listeners.
    Start-Sleep -Seconds 12

    $routes = @(
        @{ Name = "classic"; Secret = $secretHex },
        @{ Name = "dd"; Secret = "dd$secretHex" },
        @{ Name = "ee"; Secret = "ee$secretHex$domainHex" }
    )
    foreach ($route in $routes) {
        Write-Host ("==> local Telemt :: {0}" -f $route.Name)
        $env:TG_PROXY_URI =
            "tg://proxy?server=127.0.0.1&port=8443&secret=$($route.Secret)"
        & (Join-Path $PSScriptRoot "live.ps1") proxy-config -Env production
        if ($LASTEXITCODE -ne 0) {
            & docker logs $container 2>&1 |
                Select-String "listener|accept|handshake|client|error|failed|relay|protocol" |
                Select-Object -Last 120
            throw "local Telemt $($route.Name) live E2E failed"
        }
    }
    Write-Host "local Telemt: classic, dd and ee E2E passed" -ForegroundColor Green
}
finally {
    Remove-Item Env:TG_PROXY_URI -ErrorAction SilentlyContinue
    & docker stop $container 2>$null | Out-Null
    Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
}
