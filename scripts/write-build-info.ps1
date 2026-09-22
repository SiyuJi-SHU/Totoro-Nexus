$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sourceRoot = Join-Path $projectRoot 'src'
$manifest = foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File -Recurse | Where-Object { $_.Name -ne 'build-info.json' } | Sort-Object FullName) {
    $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\','/')
    "$relative $((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
}
$manifest += "pom.xml $((Get-FileHash -LiteralPath (Join-Path $projectRoot 'pom.xml') -Algorithm SHA256).Hash.ToLowerInvariant())"
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($manifest -join "`n")))).Replace('-','').ToLowerInvariant() }
finally { $sha.Dispose() }
$info = @{ sourceHash=$digest; builtAt=[DateTime]::UtcNow.ToString('o'); source='Totoro Nexus'; strategies=@('workflow','react','plan_execute_replan') } | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $sourceRoot 'main/resources/static/build-info.json'),$info,[Text.UTF8Encoding]::new($false))
Write-Host "Source fingerprint: $digest"
