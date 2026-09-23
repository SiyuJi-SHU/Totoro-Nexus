[CmdletBinding()]
param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent

function Test-DemoApp {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:9900/actuator/health' -TimeoutSec 5
        return $health.status -eq 'UP'
    } catch { return $false }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is missing. Install Docker Desktop first, then reopen PowerShell.'
}
& (Join-Path $PSScriptRoot 'ensure-docker-desktop.ps1')

if (-not (Test-DemoApp)) {
    Write-Host 'Starting the local platform from the existing image...'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild
    if ($LASTEXITCODE -ne 0) { throw 'Local platform startup failed. Public demo was not verified.' }
    if (-not (Test-DemoApp)) { throw 'Local platform is not healthy. Check its logs before sharing.' }
} else {
    Write-Host 'Local platform is already healthy; keeping it running.'
}

Write-Host 'Local platform ready: http://localhost:9900/'
if (-not $NoBrowser) { Start-Process 'http://localhost:9900/' }

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'deploy/public-demo/.env.ngrok'))) {
    Write-Warning 'Local platform is ready. No ngrok settings found; public access was not started. See docs/ngrok-demo.md to configure it.'
    return
}

try {
    Write-Host 'Preparing the local guarded gateway...'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $projectRoot 'deploy/public-demo/share-demo.ps1') -Action Prepare
    if ($LASTEXITCODE -ne 0) { throw 'Local gateway preparation failed.' }
    Write-Host 'Starting ngrok in the background...'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $projectRoot 'deploy/public-demo/share-ngrok.ps1') -Action Background
    if ($LASTEXITCODE -ne 0) { throw 'ngrok startup failed. See runtime/logs/ngrok.stderr.log and ngrok.stdout.log.' }

    $tunnels=Invoke-RestMethod 'http://127.0.0.1:9903/api/tunnels' -TimeoutSec 5
    $endpoint=@($tunnels.tunnels | Where-Object { $_.config.addr -eq 'http://127.0.0.1:9902' -and $_.public_url -match '^https://' })
    if($endpoint.Count -ne 1){throw 'Expected one HTTPS endpoint for the gateway.'}
    $publicUrl=$endpoint[0].public_url
    $publicBuild=Invoke-RestMethod ($publicUrl+'/build-info.json') -Headers @{'ngrok-skip-browser-warning'='startup-check'} -TimeoutSec 30
    $localBuild=Invoke-RestMethod 'http://127.0.0.1:9900/build-info.json' -TimeoutSec 5
    if(!$localBuild.sourceHash -or $publicBuild.sourceHash -ne $localBuild.sourceHash){throw 'Public and local builds differ.'}

    Write-Host "Public access verified: $publicUrl (first-time visitors may see the ngrok Visit Site page)."
    Write-Host 'Keep this computer awake, Docker running, and the network connected.'
} catch {
    Write-Warning ('Public access could not be verified: ' + $_.Exception.Message)
    Write-Host 'The local platform remains available at http://localhost:9900/ . Rerun the same launcher to retry public access.'
}
