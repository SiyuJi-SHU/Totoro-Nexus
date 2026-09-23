# Registered in the current user's Windows Run key; does not require administrator rights.
[CmdletBinding()]
param([ValidateRange(30,900)][int]$EngineTimeoutSeconds = 300)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$dockerDirectory = Join-Path $env:ProgramFiles 'Docker/Docker'
$dockerExe = Join-Path $dockerDirectory 'resources/bin/docker.exe'
$desktopExe = Join-Path $dockerDirectory 'Docker Desktop.exe'
if (!(Test-Path -LiteralPath $desktopExe)) { $desktopExe = Join-Path $dockerDirectory 'frontend/Docker Desktop.exe' }
$logPath = Join-Path $projectRoot 'runtime/logs/startup.log'
New-Item -ItemType Directory -Force -Path (Split-Path $logPath) | Out-Null

try {
    if (!(Test-Path -LiteralPath $dockerExe) -or !(Test-Path -LiteralPath $desktopExe)) {
        throw 'Docker Desktop is not installed at the configured path.'
    }
    & (Join-Path $PSScriptRoot 'ensure-docker-desktop.ps1') -TimeoutSeconds $EngineTimeoutSeconds *>> $logPath
    # Use the installed Docker binary even when Windows startup has an older PATH.
    $env:PATH = (Split-Path $dockerExe) + ';' + $env:PATH
    & (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild *>> $logPath
    Add-Content -LiteralPath $logPath -Value ('Startup completed: ' + (Get-Date -Format o))
} catch {
    Add-Content -LiteralPath $logPath -Value ('Startup failed: ' + $_.Exception.Message)
    exit 1
}
