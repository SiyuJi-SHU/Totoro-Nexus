[CmdletBinding()]
param([ValidateSet('Configure','Start','Background','Status')][string]$Action='Status')
$ErrorActionPreference='Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$binary=Join-Path $projectRoot 'runtime/tools/ngrok-client/ngrok.exe'
$settings=Join-Path $projectRoot 'deploy/public-demo/.env.ngrok'
$config=Join-Path $projectRoot 'deploy/public-demo/ngrok.yml'
if($Action -eq 'Background'){
    function Get-ExistingEndpoint {
        try { $state=Invoke-RestMethod 'http://127.0.0.1:9903/api/tunnels' -TimeoutSec 2 }
        catch { return $null }
        # The local API becomes available before the first tunnel finishes connecting.
        if(@($state.tunnels).Count -eq 0){return $null}
        $endpoint=@($state.tunnels | Where-Object {
            $_.config.addr -eq 'http://127.0.0.1:9902' -and $_.public_url -match '^https://'
        })
        if($endpoint.Count -eq 1){return $endpoint[0].public_url}
        throw 'Port 9903 is occupied by an ngrok agent without the expected gateway endpoint.'
    }
    $existing=Get-ExistingEndpoint
    if($existing){Write-Host "ngrok already running: $existing";return}
    if(Get-NetTCPConnection -LocalPort 9903 -State Listen -ErrorAction SilentlyContinue){
        throw 'Port 9903 is already occupied; no second agent was started.'
    }
    if(!(Test-Path -LiteralPath $settings)){throw 'Configure the ngrok Authtoken first.'}
    $logDir=Join-Path $projectRoot 'runtime/logs'
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"'),'-Action','Start')
    $child=Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir 'ngrok.stdout.log') -RedirectStandardError (Join-Path $logDir 'ngrok.stderr.log')
    $deadline=[DateTime]::UtcNow.AddSeconds(35)
    do {
        $endpoint=Get-ExistingEndpoint
        if($endpoint){Write-Host "ngrok running in background: $endpoint";return}
        if($child.HasExited){throw "ngrok startup failed. See $logDir/ngrok.stderr.log and ngrok.stdout.log."}
        Start-Sleep -Seconds 1
    } while([DateTime]::UtcNow -lt $deadline)
    throw "ngrok has not connected yet. See $logDir/ngrok.stdout.log; do not launch duplicate agents."
}
if($Action -eq 'Configure'){
    # Enter locally, without exposing the token in chat or shell history.
    $secure=Read-Host 'Paste your ngrok Authtoken (hidden input)' -AsSecureString
    $credential=New-Object System.Net.NetworkCredential('', $secure)
    $token=$credential.Password.Trim()
    if($token -notmatch '^[A-Za-z0-9_-]{20,}$'){throw 'Paste only the Authtoken, not a command or API key.'}
    [IO.File]::WriteAllText($settings,'NGROK_AUTHTOKEN='+$token+"`n",[Text.UTF8Encoding]::new($false))
    $token=$null;$credential=$null;$secure=$null
    Write-Host 'ngrok token saved in the Git-ignored private settings file.'
    exit
}
if($Action -eq 'Status'){
    try {
        $state=Invoke-RestMethod 'http://127.0.0.1:9903/api/tunnels' -TimeoutSec 5
        if(@($state.tunnels).Count -eq 0){throw 'The agent has no connected tunnels.'}
        foreach($tunnel in $state.tunnels){Write-Host ($tunnel.public_url+' -> '+$tunnel.config.addr)}
    } catch {throw 'The ngrok local agent is not reachable on port 9903.'}
    exit
}
if(-not (Test-Path -LiteralPath $binary)){throw 'The official ngrok Windows client has not been prepared.'}
if((Get-AuthenticodeSignature $binary).Status -ne 'Valid'){throw 'The ngrok executable signature is not valid.'}
if(-not (Test-Path -LiteralPath $settings)){throw 'Run ./deploy/public-demo/share-ngrok.ps1 -Action Configure with your ngrok Authtoken first.'}
$line=[IO.File]::ReadAllText($settings).Trim()
if($line -notmatch '^NGROK_AUTHTOKEN=([A-Za-z0-9_-]{20,})$'){throw 'Invalid private ngrok settings.'}
$token=$matches[1]
$page=Invoke-WebRequest 'http://127.0.0.1:9902/login.html' -UseBasicParsing -TimeoutSec 10
if($page.StatusCode -ne 200 -or $page.Content -notmatch '/api/auth/login'){
    throw 'The local guarded gateway is not ready on port 9902.'
}
$local=Invoke-RestMethod 'http://127.0.0.1:9900/build-info.json' -TimeoutSec 10
$gateway=Invoke-RestMethod 'http://127.0.0.1:9902/build-info.json' -TimeoutSec 10
if(!$local.sourceHash -or $local.sourceHash -ne $gateway.sourceHash){throw 'Local application and gateway builds differ.'}
$previousToken=[Environment]::GetEnvironmentVariable('NGROK_AUTHTOKEN','Process')
try {
    [Environment]::SetEnvironmentVariable('NGROK_AUTHTOKEN',$token,'Process')
    & $binary config check --config $config
    if($LASTEXITCODE -ne 0){throw 'ngrok configuration validation failed.'}
    Write-Host 'Starting the free ngrok endpoint through gateway 9902. Ctrl+C stops this tunnel.'
    Write-Host 'HTTP request inspection is disabled. Verify the HTTPS URL and speed before sharing.'
    & $binary http http://127.0.0.1:9902 --config $config --inspect=false
    if($LASTEXITCODE -ne 0){throw "ngrok exited with code $LASTEXITCODE."}
} finally {
    [Environment]::SetEnvironmentVariable('NGROK_AUTHTOKEN',$previousToken,'Process')
    $token=$null
}
