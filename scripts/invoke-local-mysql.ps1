param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Query')][string]$Query,
    [Parameter(Mandatory = $true, ParameterSetName = 'File')][string]$SqlFile
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$mysqlExe = 'D:\Codex\software\mysql-8.4.11-winx64\bin\mysql.exe'
$envPath = Join-Path $projectDir '.env'
$passwordLine = @(Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^MYSQL_PASSWORD=' })
if ($passwordLine.Count -ne 1) { throw 'Expected one MYSQL_PASSWORD entry in the local .env.' }
$password = $passwordLine[0].Substring('MYSQL_PASSWORD='.Length)
if ($password -notmatch '^[a-fA-F0-9]{40}$') {
    throw 'Unexpected credential format; update the client configuration writer before continuing.'
}

$logDir = Join-Path $projectDir '.local-setup'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$clientConfig = Join-Path $logDir ('mysql-client-' + [Guid]::NewGuid().ToString('N') + '.cnf')
try {
    # Restrict permissions before putting a password in the temporary file.
    New-Item -ItemType File -Path $clientConfig | Out-Null
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')) {
        $identity = New-Object System.Security.Principal.SecurityIdentifier($sid)
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $clientConfig -AclObject $acl
    @('[client]', 'host=127.0.0.1', 'port=3306', 'user=wallpaper_app', ('password=' + $password)) |
        Set-Content -LiteralPath $clientConfig -Encoding ASCII

    $sql = if ($PSCmdlet.ParameterSetName -eq 'File') {
        Get-Content -LiteralPath $SqlFile -Raw -Encoding UTF8
    } else { $Query }
    $previousEncoding = $OutputEncoding
    try {
        $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        $sql | & $mysqlExe ('--defaults-extra-file=' + $clientConfig) '--database=zlowallpaper' '--default-character-set=utf8mb4' '--connect-timeout=5' '--batch' '--raw'
        if ($LASTEXITCODE -ne 0) { throw ('MySQL command failed, exit=' + $LASTEXITCODE) }
    } finally { $OutputEncoding = $previousEncoding }
} finally {
    if (Test-Path -LiteralPath $clientConfig) { Remove-Item -LiteralPath $clientConfig -Force }
    $password = $null
}
