[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if(-not (Get-Command ssh.exe -ErrorAction SilentlyContinue)){
    throw 'Windows OpenSSH client is required.'
}
# Connect only to the guarded gateway, never expose the application port directly.
$page=Invoke-WebRequest 'http://127.0.0.1:9902/login.html' -UseBasicParsing -TimeoutSec 10
if($page.StatusCode -ne 200 -or $page.Content -notmatch '/api/auth/login'){
    throw 'The local demo gateway is not ready. Run deploy/public-demo/share-demo.ps1 -Action Prepare first.'
}
$local=Invoke-RestMethod 'http://127.0.0.1:9900/build-info.json' -TimeoutSec 10
$gateway=Invoke-RestMethod 'http://127.0.0.1:9902/build-info.json' -TimeoutSec 10
if(!$local.sourceHash -or $local.sourceHash -ne $gateway.sourceHash){
    throw 'The gateway does not match the local application.'
}
$trialDir=Join-Path $projectRoot 'target/public-access-trial'
New-Item -ItemType Directory -Path $trialDir -Force | Out-Null
$knownHosts='target/public-access-trial/known_hosts'
Write-Host 'Starting a temporary free HTTPS tunnel. No signup or payment is required.'
Write-Host 'The provider limits speed and changes the hostname. This is not a permanent resume URL.'
Write-Host 'Keep this terminal open. Press Ctrl+C to close public access; Docker remains running.'
# No private SSH identity or SSH agent is offered to the service.
Push-Location $projectRoot
try {
& ssh.exe -T -F none -o BatchMode=yes -o IdentityAgent=none -o IdentityFile=none `
    -o StrictHostKeyChecking=accept-new -o "UserKnownHostsFile=$knownHosts" `
    -o ConnectTimeout=12 -o ExitOnForwardFailure=yes `
    -o ServerAliveInterval=30 -o ServerAliveCountMax=3 `
    -R 80:127.0.0.1:9902 nokey@localhost.run -- --output json |
    ForEach-Object {
        $line=[string]$_
        if($line.StartsWith('{')){
            try {$event=$line | ConvertFrom-Json} catch {Write-Output $line;return}
            if($event.event -eq 'tcpip-forward' -and $event.status -eq 'success'){
                Write-Host ('Temporary URL: https://'+$event.address+'/login.html')
                Write-Host 'Tunnel creation alone does not verify HTTPS or the application. Check the URL before sharing.'
            }elseif($event.event -eq 'error' -or $event.status -eq 'error'){
                Write-Output $line
            }
        }else{Write-Output $line}
    }
if($LASTEXITCODE -ne 0){throw "Temporary tunnel exited with code $LASTEXITCODE."}
} finally {Pop-Location}
