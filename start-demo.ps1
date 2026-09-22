[CmdletBinding()]
param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'

function Test-DemoDocker {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker info --format '{{.ServerVersion}}' 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    } finally { $ErrorActionPreference = $previousPreference }
}

function Test-DemoApp {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:9900/actuator/health' -TimeoutSec 5
        return $health.status -eq 'UP'
    } catch { return $false }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is missing. Install Docker Desktop first, then reopen PowerShell.'
}
if (-not (Test-DemoDocker)) {
    $desktopPath = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktopPath)) {
        throw 'Start Docker Desktop manually, then run this script again.'
    }
    Write-Host 'Starting Docker Desktop; waiting for the engine...'
    Start-Process -FilePath $desktopPath -WindowStyle Hidden
    $dockerDeadline = [DateTime]::UtcNow.AddMinutes(3)
    $dockerReady = $false
    do {
        Start-Sleep -Seconds 2
        $dockerReady = Test-DemoDocker
    } while (-not $dockerReady -and [DateTime]::UtcNow -lt $dockerDeadline)
    if (-not $dockerReady) { throw 'Docker did not become ready. Check Docker Desktop and rerun.' }
}

if (-not (Test-DemoApp)) {
    Write-Host 'Starting the local platform from the existing image...'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild
    if ($LASTEXITCODE -ne 0) { throw 'Local platform startup failed. Public demo was not verified.' }
    if (-not (Test-DemoApp)) { throw 'Local platform is not healthy. Check its logs before sharing.' }
} else {
    Write-Host 'Local platform is already healthy; keeping it running.'
}

Write-Host 'Starting and verifying public HTTPS access...'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'share-demo.ps1') -Action Start
if ($LASTEXITCODE -ne 0) { throw 'Public demo setup or verification failed. See the message above and rerun.' }

Write-Host 'Demo ready. Local workspace: http://localhost:9900/'
Write-Host 'Share the verified public URL printed above, plus a member account, with the interviewer.'
Write-Host 'Keep this computer awake, Docker running, and the required network connection active.'
if (-not $NoBrowser) { Start-Process 'http://localhost:9900/' }
