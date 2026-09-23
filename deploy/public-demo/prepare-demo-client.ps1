[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$version='0.68.0-37f78258'
$image='totoro-nexus-openfrp:'+$version
# Official distribution linked by the OpenFrp download console.
$source='https://staticassets.naids.com/client/OF_0.68.0_37f78258_260326/frpc_linux_amd64.tar.gz'
# Pins the archive downloaded over trusted HTTPS on 2026-09-22.
# This is a local reproducibility checksum, not a vendor signature.
$expected='1D227512E0FCD27F0DA5747FCF85ECD425545D55961111459AD8C8B976014E63'
$context=Join-Path $projectRoot 'target/openfrp-client'
$archive=Join-Path $context 'frpc_linux_amd64.tar.gz'
New-Item -ItemType Directory -Path $context -Force | Out-Null
if(-not (Test-Path -LiteralPath $archive)){
    Invoke-WebRequest $source -OutFile $archive -UseBasicParsing -TimeoutSec 60
}
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected){
    throw 'Client archive checksum mismatch. Review the official download before replacing the cached archive.'
}
$entries=@(& tar -tzf $archive)
if($LASTEXITCODE -ne 0 -or $entries.Count -ne 1 -or $entries[0] -ne 'frpc_linux_amd64'){
    throw 'Unexpected client archive contents.'
}
& tar -xzf $archive -C $context
if($LASTEXITCODE -ne 0){throw 'Client extraction failed.'}
[IO.File]::WriteAllText((Join-Path $context '.dockerignore'),"*`n!frpc_linux_amd64`n",[Text.UTF8Encoding]::new($false))
& docker build --pull=false -t $image -f (Join-Path $projectRoot 'deploy/public-demo/Client.Dockerfile') $context
if($LASTEXITCODE -ne 0){throw 'Client image build failed.'}
& docker run --rm $image --version
if($LASTEXITCODE -ne 0){throw 'Client executable verification failed.'}
Write-Host "Official OpenFrp client prepared: $image"
