[CmdletBinding()]
param([ValidateRange(30,900)][int]$TimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'Docker/Docker'))
$dockerCli = Join-Path $desktopRoot 'resources/bin/docker.exe'
$desktopExe = Join-Path $desktopRoot 'Docker Desktop.exe'
$backendLog = Join-Path $env:LOCALAPPDATA 'Docker/log/host/com.docker.backend.exe.log'

function Test-EngineReady {
    $probe = New-Object System.Diagnostics.Process
    $probe.StartInfo.FileName = $dockerCli
    $probe.StartInfo.Arguments = 'info --format {{.ServerVersion}}'
    $probe.StartInfo.UseShellExecute = $false
    $probe.StartInfo.CreateNoWindow = $true
    $probe.StartInfo.RedirectStandardOutput = $true
    $probe.StartInfo.RedirectStandardError = $true
    try {
        [void]$probe.Start()
        $stdout = $probe.StandardOutput.ReadToEndAsync()
        $stderr = $probe.StandardError.ReadToEndAsync()
        if (!$probe.WaitForExit(8000)) { $probe.Kill(); $probe.WaitForExit(); return $false }
        return $probe.ExitCode -eq 0
    } finally { $probe.Dispose() }
}

function Get-DesktopProcesses {
    @(Get-Process -Name 'Docker Desktop','com.docker.backend' -ErrorAction SilentlyContinue)
}

function Test-CurrentSocketFailure {
    param([datetime]$Since)
    if (!(Test-Path -LiteralPath $backendLog)) { return $false }
    foreach ($line in (Get-Content -LiteralPath $backendLog -Tail 600)) {
        if ($line -match '^\[([^\]]+)\].*(?:backend cancelling with error|backend crashed|reporting error to user): starting services: initializing (?:Ingest server|Secrets Engine):.*(?:sailor-ingest\.sock|engine\.sock).*The file cannot be accessed by the system') {
            $timestamp = [DateTimeOffset]::Parse($matches[1]).UtcDateTime
            if ($timestamp -ge $Since.ToUniversalTime().AddSeconds(-2)) { return $true }
        }
    }
    return $false
}

if (!(Test-Path -LiteralPath $dockerCli) -or !(Test-Path -LiteralPath $desktopExe)) {
    throw 'Docker Desktop is not installed at the expected Windows location.'
}
if (Test-EngineReady) { Write-Host 'Docker engine is ready; no recovery needed.'; return }
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$desktopProcesses = @(Get-DesktopProcesses)
while ($desktopProcesses.Count) {
    $startedAt = ($desktopProcesses | Sort-Object StartTime | Select-Object -First 1).StartTime
    if (Test-CurrentSocketFailure -Since $startedAt) {
        if (Test-EngineReady) { return }
        if (Test-Path '\\.\pipe\dockerDesktopLinuxEngine') {
            throw 'An engine pipe still exists; refusing to stop a potentially active engine.'
        }
        # Only terminate the failed Desktop instance after a matching error from this launch.
        foreach ($desktopProcess in $desktopProcesses) {
            if (!$desktopProcess.Path.StartsWith($desktopRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Unexpected Docker process location; refusing recovery.'
            }
        }
        $desktopProcesses | Stop-Process -Force
        foreach ($desktopProcess in $desktopProcesses) {
            if (!$desktopProcess.WaitForExit(10000)) { throw 'Failed Docker process did not exit.' }
        }
        break
    }
    if ((Get-Date) -ge $deadline) { throw 'Docker startup timed out without a recognized socket failure; no files changed.' }
    Start-Sleep -Seconds 2
    if (Test-EngineReady) { Write-Host 'Docker engine is ready.'; return }
    $desktopProcesses = @(Get-DesktopProcesses)
}

# A delayed stop/restart command can otherwise stop the newly recovered engine.
$pending = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -in @('docker.exe','docker-desktop.exe') -and $_.CommandLine -match 'desktop\s+(start|stop|restart)\b'
})
if ($pending.Count) { throw 'A Docker start/stop/restart command is pending. Finish it before recovery.' }
if (@(Get-DesktopProcesses).Count -or (Test-EngineReady)) { throw 'Docker state changed; retry without moving any files.' }

$localRoot = [IO.Path]::GetFullPath($env:LOCALAPPDATA)
$socketDirectories = @{
    'Docker\run' = @('dockerEthernetVfkit','dockerInference','sailor-ingest.sock','userAnalyticsOtlpHttp.sock')
    'docker-secrets-engine' = @('engine.sock')
}
# Validate both directories before moving either. Unknown contents are never moved.
$repairTargets = @()
foreach ($relative in $socketDirectories.Keys) {
    $target = [IO.Path]::GetFullPath((Join-Path $localRoot $relative))
    if (!$target.StartsWith($localRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid recovery path.' }
    if (!(Test-Path -LiteralPath $target)) { continue }
    $directory = Get-Item -LiteralPath $target
    if (!$directory.PSIsContainer -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Unsafe socket directory: $target"
    }
    $entries = @(Get-ChildItem -LiteralPath $target -Force)
    foreach ($entry in $entries) {
        $baseName = $entry.Name -replace '\.stale$', ''
        if ($entry.PSIsContainer -or $baseName -notin $socketDirectories[$relative] -or $entry.Length -ne 0) {
            throw "Unexpected contents in $target; inspect manually."
        }
    }
    if ($entries.Count) { $repairTargets += $directory }
}
foreach ($directory in $repairTargets) {
    $backupName = $directory.Name + '-recovery-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    Rename-Item -LiteralPath $directory.FullName -NewName $backupName
    if (Test-Path -LiteralPath $directory.FullName) { throw 'Socket directory remained after rename; stop and inspect.' }
    New-Item -ItemType Directory -Path $directory.FullName | Out-Null
    Write-Host "Preserved stale sockets: $(Join-Path $directory.Parent.FullName $backupName)"
}
$launchTime = Get-Date
Start-Process -FilePath $desktopExe -WindowStyle Hidden | Out-Null
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while (!(Test-EngineReady)) {
    if (Test-CurrentSocketFailure -Since $launchTime) { throw 'Socket failure persisted after one recovery attempt; no further automatic retries.' }
    if ((Get-Date) -ge $deadline) { throw 'Docker did not become ready after recovery; inspect Desktop logs.' }
    Start-Sleep -Seconds 2
}
Write-Host 'Docker engine is ready. Existing images, volumes and application data were preserved.'
