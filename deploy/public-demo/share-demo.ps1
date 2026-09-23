[CmdletBinding()]
param([ValidateSet('Prepare','Start','Status','Stop')][string]$Action='Status')
$ErrorActionPreference='Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$demoDir=Join-Path $projectRoot 'deploy/public-demo'
$composeFile=Join-Path $demoDir 'compose.yaml'
$privateEnv=Join-Path $demoDir '.env'
$composeOptions=@('--project-directory',$demoDir,'-f',$composeFile)
if(Test-Path -LiteralPath $privateEnv){$composeOptions+=@('--env-file',$privateEnv)}

function Invoke-DemoCompose {
    param([string[]]$Arguments)
    & docker compose @composeOptions @Arguments
    if($LASTEXITCODE -ne 0){throw "Demo Compose failed (exit $LASTEXITCODE)."}
}
function Set-DemoReadme {
    param([string]$Message)
    $path=Join-Path $projectRoot 'README.md'
    $content=[IO.File]::ReadAllText($path)
    $pattern='(?s)<!-- public-demo-status:start -->.*?<!-- public-demo-status:end -->'
    if(-not [regex]::IsMatch($content,$pattern)){throw 'README status markers are missing.'}
    $replacement="<!-- public-demo-status:start -->`n$Message`n<!-- public-demo-status:end -->"
    $content=[regex]::Replace($content,$pattern,[Text.RegularExpressions.MatchEvaluator]{param($match) $replacement})
    [IO.File]::WriteAllText($path,$content,[Text.UTF8Encoding]::new($false))
}

if($Action -eq 'Stop'){
    Invoke-DemoCompose -Arguments @('--profile','tunnel','stop','openfrp','gateway','guard')
    Set-DemoReadme 'OpenFrp public access is stopped. Local application: http://localhost:9900/ . See docs/public-demo.md.'
    exit
}
if($Action -eq 'Status'){
    Invoke-DemoCompose -Arguments @('--profile','tunnel','ps','gateway','guard','openfrp')
    Write-Host 'Local gateway: http://127.0.0.1:9902/login.html . Container status alone does not verify public HTTPS.'
    exit
}
if($Action -eq 'Prepare'){
    # Prepare is a local check only. Never recreate an active public gateway.
    if(Test-Path -LiteralPath $privateEnv){throw 'Private tunnel settings exist; use Start or Status instead of Prepare.'}
    $previousConfig=[Environment]::GetEnvironmentVariable('CADDY_CONFIG','Process')
    try {
        [Environment]::SetEnvironmentVariable('CADDY_CONFIG','Caddyfile.local','Process')
        Invoke-DemoCompose -Arguments @('config','--quiet')
        Invoke-DemoCompose -Arguments @('up','-d','--wait','--wait-timeout','60','gateway')
    } finally {
        [Environment]::SetEnvironmentVariable('CADDY_CONFIG',$previousConfig,'Process')
    }
    Write-Host 'Local gateway ready: http://127.0.0.1:9902/login.html . This step does not start a public tunnel.'
    exit
}

if(!(Test-Path -LiteralPath $privateEnv)){
    throw 'OpenFrp is not configured. See docs/public-demo.md and deploy/public-demo/.env.example. The retired public link will not be restarted.'
}
# Read only the four documented settings; never print resolved secrets.
$settings=@{}
foreach($line in [IO.File]::ReadAllLines($privateEnv)){
    if($line -match '^\s*(OPENFRP_TOKEN|OPENFRP_TUNNEL_ID|DEMO_HOSTNAME|CADDY_CONFIG)\s*=\s*(.*?)\s*$'){
        $settings[$matches[1]]=$matches[2].Trim().Trim([char]39).Trim([char]34)
    }
}
if(!$settings['OPENFRP_TOKEN'] -or $settings['OPENFRP_TUNNEL_ID'] -notmatch '^\d+$'){
    throw 'Set your OpenFrp token and numeric tunnel ID in deploy/public-demo/.env.'
}
$hostname=$settings['DEMO_HOSTNAME']
if($hostname -notmatch '^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$'){
    throw 'DEMO_HOSTNAME must be a public DNS hostname, without a scheme, port or path.'
}
if($settings['CADDY_CONFIG'] -ne 'Caddyfile.https'){throw 'CADDY_CONFIG must be Caddyfile.https for public access.'}
$clientImage=& docker image ls --quiet 'totoro-nexus-openfrp:0.68.0-37f78258'
if($LASTEXITCODE -ne 0){throw 'Cannot inspect local Docker images.'}
if(-not $clientImage){
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $projectRoot 'deploy/public-demo/prepare-demo-client.ps1')
    if($LASTEXITCODE -ne 0){throw 'OpenFrp client preparation failed.'}
}
# Export the values validated above so inherited process settings cannot select a different target.
$old=@{}
try {
    foreach($name in $settings.Keys){$old[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
    Set-DemoReadme 'OpenFrp public HTTPS verification is pending. Local application: http://localhost:9900/ . See docs/public-demo.md.'
    Invoke-DemoCompose -Arguments @('--profile','tunnel','config','--quiet')
    Invoke-DemoCompose -Arguments @('--profile','tunnel','up','-d','--wait','--wait-timeout','60','gateway','openfrp')
    $url='https://'+$hostname+'/'
    $page=Invoke-WebRequest ($url+'login.html') -UseBasicParsing -TimeoutSec 20
    if($page.StatusCode -ne 200 -or $page.Content -notmatch '/api/auth/login'){throw 'Public URL did not return the application login page.'}
    $local=Invoke-RestMethod 'http://127.0.0.1:9900/build-info.json' -TimeoutSec 10
    $remote=Invoke-RestMethod ($url+'build-info.json') -TimeoutSec 20
    if(!$local.sourceHash -or $local.sourceHash -ne $remote.sourceHash){throw 'Public and local builds differ.'}
    $stamp=Get-Date -Format 'yyyy-MM-dd HH:mm zzz'
    Set-DemoReadme "OpenFrp demo URL: [$url]($url)`n`nHTTPS login page and local build identity verified at $stamp. A member account is required. Attachment, SSE and external-network performance acceptance remain separate checks. This computer and Docker must stay online."
    Write-Host "Verified HTTPS login page: $url"
} finally {
    foreach($name in $old.Keys){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')}
}
