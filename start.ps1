[CmdletBinding()]
param(
    [string]$CredentialsCsv,
    [switch]$SkipBuild,
    [switch]$ValidateOnly,
    [ValidateRange(30,1800)][int]$WaitTimeout = 300
)
$ErrorActionPreference = 'Stop'
$core = $PSScriptRoot
$credentialNames = @('DASHSCOPE_API_KEY', 'DASHSCOPE_WORKSPACE_ID', 'DASHSCOPE_RERANK_ENDPOINT')
$savedEnvironment = @{}
foreach ($name in $credentialNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$temporaryEnv = $null

function Invoke-Compose {
    param([string[]]$ComposeArguments)
    # Compose writes normal progress to stderr. Windows PowerShell 5.1 converts
    # redirected stderr into ErrorRecords; use the process exit code for failure.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker compose @script:composeOptions @ComposeArguments
        $composeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($composeExit -ne 0) { throw "Docker Compose failed (exit $composeExit)." }
}

try {
    $composeOptions = @('--project-directory', $core, '-f', (Join-Path $core 'docker-compose.yml'))
    $localEnv = Join-Path $core '.env'
    if (Test-Path -LiteralPath $localEnv) {
        # Retain local non-secret settings such as an existing volume directory.
        $composeOptions += @('--env-file', $localEnv)
    }
    if ($CredentialsCsv) {
        # Alibaba's export has an id column and one credential-specific value column.
        $rows = @(Import-Csv -LiteralPath (Resolve-Path -LiteralPath $CredentialsCsv).Path)
        if ($rows.Count -eq 0) { throw 'Credential CSV is empty.' }
        $valueColumns = @($rows[0].PSObject.Properties.Name | Where-Object { $_ -ne 'id' })
        if ($valueColumns.Count -ne 1) { throw 'Expected a key/value Alibaba credential CSV.' }
        $valueColumn = $valueColumns[0]
        $keyRows = @($rows | Where-Object id -eq 'apiKey')
        $workspaceRows = @($rows | Where-Object id -eq 'workspaceId')
        $hostRows = @($rows | Where-Object id -eq 'apiHost')
        if ($keyRows.Count -ne 1 -or $workspaceRows.Count -ne 1 -or $hostRows.Count -ne 1) {
            throw 'Credential CSV must contain one apiKey, workspaceId and apiHost row.'
        }
        $apiHost = [string]$hostRows[0].$valueColumn
        if ($apiHost -notmatch '^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+aliyuncs\.com$') {
            throw 'Credential CSV API hostname must be a subdomain of aliyuncs.com.'
        }
        $env:DASHSCOPE_API_KEY = [string]$keyRows[0].$valueColumn
        $env:DASHSCOPE_WORKSPACE_ID = [string]$workspaceRows[0].$valueColumn
        $env:DASHSCOPE_RERANK_ENDPOINT = 'https://' + $apiHost + '/api/v1/services/rerank/text-rerank/text-rerank'
    }

    if ($env:DASHSCOPE_API_KEY) {
        # Store only for this Compose invocation, outside the build context.
        # Restrict permissions before writing any credential bytes.
        $temporaryEnv = [IO.Path]::GetTempFileName()
        if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
            $acl = Get-Acl -LiteralPath $temporaryEnv
            $acl.SetAccessRuleProtection($true, $false)
            $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
            $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'Allow')
            $acl.SetAccessRule($rule)
            Set-Acl -LiteralPath $temporaryEnv -AclObject $acl
        } else {
            & chmod 600 $temporaryEnv
            if ($LASTEXITCODE -ne 0) { throw 'Cannot restrict temporary credential file permissions.' }
        }
        $lines = foreach ($name in $credentialNames) {
            $value = [Environment]::GetEnvironmentVariable($name, 'Process')
            if ($value -match "[\r\n'\\]") { throw 'Credentials contain unsupported dotenv characters.' }
            "${name}='$value'"
        }
        [IO.File]::WriteAllLines($temporaryEnv, $lines, [Text.UTF8Encoding]::new($false))
        $composeOptions += @('--env-file', $temporaryEnv)
    } elseif (!(Test-Path -LiteralPath (Join-Path $core '.env'))) {
        throw 'Set DASHSCOPE_API_KEY, pass -CredentialsCsv, or create a private .env from .env.example.'
    }

    # Quiet validation never renders resolved secrets.
    Invoke-Compose -ComposeArguments @('config', '--quiet')
    if ($ValidateOnly) {
        Write-Host 'Compose configuration is valid. No containers were changed.'
        return
    }
    if (!$SkipBuild) {
        & (Join-Path $core 'scripts/write-build-info.ps1')
        & mvn -B -f (Join-Path $core 'pom.xml') package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven package failed ($LASTEXITCODE)." }
        if (!(Test-Path -LiteralPath (Join-Path $core 'target/super-biz-agent-1.0-SNAPSHOT.jar'))) {
            throw 'Maven completed without the expected executable JAR.'
        }
        Invoke-Compose -ComposeArguments @('build', 'app')
    }
    Invoke-Compose -ComposeArguments @('up', '-d', '--wait', '--wait-timeout', [string]$WaitTimeout)
    Invoke-Compose -ComposeArguments @('ps')
    Write-Host 'Application: http://localhost:9900 ; health: http://localhost:9900/actuator/health'
    Write-Host 'Console: http://localhost:9900/console ; initial account: admin'
    Write-Host 'Generated bootstrap password: uploads/.platform/admin-initial-password.txt (change it in Account after login).'
} finally {
    foreach ($name in $credentialNames) {
        if ($null -eq $savedEnvironment[$name]) {
            # Recent PowerShell/.NET preserves empty env values; remove absent ones.
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
    }
    if ($temporaryEnv -and (Test-Path -LiteralPath $temporaryEnv)) {
        Remove-Item -LiteralPath $temporaryEnv -Force
    }
}
