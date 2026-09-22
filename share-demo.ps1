[CmdletBinding()]
param([ValidateSet('Start','Status','Stop')][string]$Action='Status')
$ErrorActionPreference='Stop'
$demoCompose=Join-Path $PSScriptRoot 'deploy/public-demo/compose.yaml'
$demoReadme=Join-Path $PSScriptRoot 'README.md'
$publicHostname='totoronexus'

function Invoke-DemoCompose {
    param([string[]]$Arguments)
    & docker compose -f $demoCompose @Arguments
    if($LASTEXITCODE -ne 0){throw "Demo Compose failed (exit $LASTEXITCODE)."}
}

function Get-DemoState {
    $raw=& docker compose -f $demoCompose exec -T tailscale tailscale status --json
    if($LASTEXITCODE -ne 0){throw 'Cannot read Tailscale state. Run this script with -Action Start.'}
    return ($raw -join "`n" | ConvertFrom-Json)
}

function Set-DemoReadme {
    param([string]$Message)
    $content=[IO.File]::ReadAllText($demoReadme)
    $pattern='(?s)<!-- public-demo-status:start -->.*?<!-- public-demo-status:end -->'
    if(-not [regex]::IsMatch($content,$pattern)){throw 'Public demo status markers are missing from README.md.'}
    $replacement="<!-- public-demo-status:start -->`n$Message`n<!-- public-demo-status:end -->"
    $updated=[regex]::Replace($content,$pattern,[Text.RegularExpressions.MatchEvaluator]{param($match) $replacement})
    [IO.File]::WriteAllText($demoReadme,$updated,[Text.UTF8Encoding]::new($false))
}

if($Action -eq 'Stop'){
    # Retain device identity and its HTTPS hostname for the next demo.
    Invoke-DemoCompose -Arguments @('stop')
    Set-DemoReadme -Message '公网演示已关闭。再次运行 share-demo.ps1 -Action Start，验证成功后会在此写入访问 URL。'
    Write-Host 'Public demo stopped. The local app and evaluation services are unchanged.'
    exit
}

if($Action -eq 'Start'){
    $health=Invoke-RestMethod 'http://127.0.0.1:9900/actuator/health' -TimeoutSec 10
    if($health.status -ne 'UP'){throw 'The local app is not healthy. Start the platform first.'}
    Invoke-DemoCompose -Arguments @('up','-d')
    $ready=$false
    for($attempt=0;$attempt -lt 15;$attempt++){
        & docker compose -f $demoCompose exec -T tailscale tailscale status --json 1>$null 2>$null
        if($LASTEXITCODE -eq 0){$ready=$true;break}
        Start-Sleep -Seconds 1
    }
    if(-not $ready){throw 'Tailscale daemon is not ready. Check docker compose logs.'}
    $state=Get-DemoState
    if($state.BackendState -ne 'Running'){
        # A fresh device prints its personal sign-in link. Never store that link
        # or an auth key in the README. The user completes browser sign-in.
        & docker compose -f $demoCompose exec -T tailscale tailscale up --hostname=$publicHostname --accept-dns=false --timeout=20s
        $state=Get-DemoState
        if($state.BackendState -ne 'Running'){
            if($state.AuthURL){
                Write-Host ('Tailscale sign-in: '+$state.AuthURL)
                Write-Host 'Complete browser sign-in, then run -Action Start again.'
            } else {
                Write-Host 'Tailscale could not obtain a sign-in link. Check access to controlplane.tailscale.com.'
                foreach($issue in $state.Health){Write-Host $issue}
                Write-Host 'If needed, configure DEMO_HTTPS_PROXY for the demo container, then rerun Start.'
            }
            exit 2
        }
    }
    # Funnel is opt-in. If HTTPS/Funnel needs approval, this prints the account
    # setup link; complete it in a browser and rerun Start.
    & docker compose -f $demoCompose exec -T tailscale tailscale funnel --bg --yes --https=443 http://127.0.0.1:8080
    if($LASTEXITCODE -ne 0){throw 'Enable HTTPS/Funnel using the Tailscale link above, then rerun Start.'}
}

$state=Get-DemoState
if($state.BackendState -eq 'Running'){
    # Keep the single public demo on the current Totoro hostname even when an
    # older persisted device state used the former knowledge-agents name.
    & docker compose -f $demoCompose exec -T tailscale tailscale set --hostname=$publicHostname
    if($LASTEXITCODE -ne 0){throw 'Tailscale is connected but could not set the Totoro demo hostname.'}
    $state=Get-DemoState
}
& docker compose -f $demoCompose exec -T tailscale tailscale funnel status
if($LASTEXITCODE -ne 0){throw 'Cannot read Funnel status.'}
if($state.BackendState -ne 'Running'){throw 'Tailscale is not connected. Run -Action Start.'}
$hostname=([string]$state.Self.DNSName).TrimEnd('.')
if($hostname -notmatch '^[a-zA-Z0-9.-]+\.ts\.net$'){throw 'Tailscale has not assigned an HTTPS hostname yet.'}
$url='https://'+$hostname+'/'
Write-Host "Assigned demo URL: $url"

if($Action -eq 'Start'){
    $verified=$false
    for($attempt=0;$attempt -lt 6;$attempt++){
        try {
            $page=Invoke-WebRequest ($url+'login.html') -UseBasicParsing -TimeoutSec 15
            if($page.StatusCode -eq 200 -and $page.Content -match '/api/auth/login'){$verified=$true;break}
        } catch {Write-Host 'Waiting for public HTTPS/DNS to become available...'}
        Start-Sleep -Seconds 3
    }
    if(-not $verified){throw 'Public HTTPS verification is pending. README has not been marked as available. Rerun Start later.'}
    $stamp=Get-Date -Format 'yyyy-MM-dd HH:mm zzz'
    Set-DemoReadme -Message "面试演示 URL：[$url]($url)`n`n此地址已在 $stamp 验证可返回项目登录页。需要项目普通成员账号，账号密码由项目所有者单独提供；不要使用或公开管理员账号。在线状态取决于本机、Docker、项目及 Tailscale 网络连接。"
    Write-Host 'Public HTTPS verified; README.md updated.'
}
