# Registered in the current user's Windows Run key; does not require administrator rights.
[CmdletBinding()]
param([ValidateRange(30,900)][int]$EngineTimeoutSeconds = 300)
$ErrorActionPreference = 'Stop'
$dockerDirectory = Join-Path $env:ProgramFiles 'Docker/Docker'
$dockerExe = Join-Path $dockerDirectory 'resources/bin/docker.exe'
$desktopExe = Join-Path $dockerDirectory 'Docker Desktop.exe'
$logPath = Join-Path $PSScriptRoot 'target/oncall-startup.log'
New-Item -ItemType Directory -Force -Path (Split-Path $logPath) | Out-Null

function Test-DockerReady {
    try {
        & $dockerExe info --format '{{.ServerVersion}}' 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch { return $false }
}

try {
    if (!(Test-Path -LiteralPath $dockerExe) -or !(Test-Path -LiteralPath $desktopExe)) {
        throw 'Docker Desktop is not installed at the configured path.'
    }
    if (!(Test-DockerReady)) {
        Start-Process -FilePath $desktopExe -WindowStyle Hidden | Out-Null
        $deadline = (Get-Date).AddSeconds($EngineTimeoutSeconds)
        while (!(Test-DockerReady)) {
            if ((Get-Date) -ge $deadline) { throw 'Docker engine did not become ready before the startup timeout.' }
            Start-Sleep -Seconds 2
        }
    }
    # Use the installed Docker binary even when Windows startup has an older PATH.
    $env:PATH = (Split-Path $dockerExe) + ';' + $env:PATH
    & (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild *> $logPath
    Add-Content -LiteralPath $logPath -Value ('Startup completed: ' + (Get-Date -Format o))
} catch {
    Add-Content -LiteralPath $logPath -Value ('Startup failed: ' + $_.Exception.Message)
    exit 1
}
