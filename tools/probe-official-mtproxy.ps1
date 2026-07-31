<#
.SYNOPSIS
    Diagnose the pinned official MTProxy middle-end path.

.DESCRIPTION
    The official server validates the client-side header and packet before it
    attempts Telegram's legacy middle-end RPC path. That path may fail behind
    Docker Desktop/consumer NAT. For the passing full E2E oracle use
    test-local-mtproxy.ps1.

    The generated secret exists only in this process environment and is never
    printed or written. The container is removed in finally.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_env.ps1")

$dockerVersion = & docker version --format '{{.Server.Version}}' 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Docker daemon is unavailable. Start Docker Desktop and retry."
}

$secretBytes = New-Object byte[] 16
$crypto = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $crypto.GetBytes($secretBytes) } finally { $crypto.Dispose() }
$secretHex = ([BitConverter]::ToString($secretBytes)).Replace("-", "").ToLowerInvariant()
$image = "telegramj2me-mtproxy:cafc338"
$container = "telegramj2me-mtproxy-test"

try {
    $publicIpText = ([string](Invoke-RestMethod `
        -Uri "https://api.ipify.org" -TimeoutSec 10)).Trim()
    $publicIp = $null
    if (-not [System.Net.IPAddress]::TryParse($publicIpText, [ref]$publicIp) -or
        $publicIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
        throw "Could not determine the public IPv4 address required by MTProxy --nat-info"
    }

    & docker build -t $image -f (Join-RepoPath "tools" "mtproxy" "Dockerfile") $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "MTProxy Docker build failed" }

    & docker run --rm -d --name $container `
        -p "127.0.0.1:8443:443" `
        -e "MTPROXY_SECRET=$secretHex" `
        -e "MTPROXY_PUBLIC_IP=$publicIpText" $image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "MTProxy container failed to start" }

    $ready = $false
    for ($attempt = 0; $attempt -lt 10 -and -not $ready; $attempt++) {
        $tcp = [System.Net.Sockets.TcpClient]::new()
        try {
            $pending = $tcp.BeginConnect("127.0.0.1", 8443, $null, $null)
            if ($pending.AsyncWaitHandle.WaitOne(1000)) {
                $tcp.EndConnect($pending)
                $ready = $true
            }
        }
        catch {
            # The container may still be downloading Telegram's runtime config.
        }
        finally {
            $tcp.Dispose()
        }
        if (-not $ready) { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { throw "MTProxy did not open localhost:8443" }

    foreach ($encoded in @($secretHex, "dd$secretHex")) {
        $env:TG_PROXY_URI = "tg://proxy?server=127.0.0.1&port=8443&secret=$encoded"
        & (Join-Path $PSScriptRoot "live.ps1") proxy-config -Env production
        if ($LASTEXITCODE -ne 0) {
            throw "local MTProxy live E2E failed"
        }
    }
    Write-Host "local official MTProxy: 16-byte and dd E2E passed" -ForegroundColor Green
}
finally {
    Remove-Item Env:TG_PROXY_URI -ErrorAction SilentlyContinue
    & docker stop $container 2>$null | Out-Null
}
