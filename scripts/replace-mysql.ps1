param(
    [Parameter(Mandatory = $true)][string]$OwnerSid
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $projectDir '.local-setup'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$statusPath = Join-Path $logDir 'mysql-replacement-status.json'
$mysqlBase = 'D:\Codex\software\mysql-8.4.11-winx64'
$mysqlData = 'D:\Codex\software\mysql-data'
$serverExe = Join-Path $mysqlBase 'bin\mysqld.exe'
$clientExe = Join-Path $mysqlBase 'bin\mysql.exe'
$adminExe = Join-Path $mysqlBase 'bin\mysqladmin.exe'
$configPath = Join-Path $mysqlBase 'my.ini'
$serviceName = 'ZloMySQL84'
$temporaryServer = $null
$secretFiles = @()
$removed = @()

function Save-Status([string]$Stage, [string]$ErrorText = '') {
    [ordered]@{
        stage = $Stage
        error = $ErrorText
        removed = $script:removed
        service = $script:serviceName
        host = '127.0.0.1'
        port = 3306
        database = 'zlowallpaper'
        username = 'wallpaper_app'
        updatedAt = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $script:statusPath -Encoding UTF8
}

function Protect-Secret([string]$Path) {
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($OwnerSid, 'S-1-5-18', 'S-1-5-32-544')) {
        $identity = New-Object System.Security.Principal.SecurityIdentifier($sid)
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function New-Password {
    $bytes = New-Object byte[] 20
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Invoke-Checked([string]$Exe, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $Exe @Arguments 2>&1
        $commandExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($commandExitCode -ne 0) { throw ('Command failed: ' + [IO.Path]::GetFileName($Exe) + ', exit=' + $commandExitCode) }
    return $output
}

function Remove-ExactOldDirectory([string]$Path) {
    $allowed = @('C:\Program Files\MySQL', 'C:\Program Files (x86)\MySQL', 'C:\ProgramData\MySQL')
    $absolute = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if ($absolute -notin $allowed) { throw 'Cleanup path is outside the explicit MySQL directory allowlist.' }
    if (-not (Test-Path -LiteralPath $absolute)) { return }
    $item = Get-Item -LiteralPath $absolute -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing to recursively delete a directory link.' }
    $links = @(Get-ChildItem -LiteralPath $absolute -Force -Recurse | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
    if ($links.Count -gt 0) { throw 'Refusing cleanup because a directory contains a filesystem link.' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}

try {
    Save-Status 'checking'
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator permission is required.' }
    if ($OwnerSid -notmatch '^S-1-5-21-\d+-\d+-\d+-\d+$') { throw 'Invalid owner SID.' }
    if (-not (Test-Path -LiteralPath $serverExe)) { throw 'New MySQL binaries have not been extracted.' }
    $version = Invoke-Checked $serverExe @('--version')
    if (($version -join ' ') -notmatch '8\.4\.11') { throw 'Unexpected new MySQL binary version.' }
    if (Test-Path -LiteralPath $mysqlData) { throw 'The new MySQL data directory already exists; refusing to overwrite it.' }
    if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) { throw 'The new service already exists; refusing duplicate setup.' }
    $envPath = Join-Path $projectDir '.env'
    if (Test-Path -LiteralPath $envPath) { throw 'An existing .env was found; refusing to overwrite credentials.' }

    Save-Status 'uninstalling-old-mysql'
    $oldService = Get-Service -Name MySQL80 -ErrorAction SilentlyContinue
    if ($oldService -and $oldService.Status -ne 'Stopped') {
        Stop-Service -Name MySQL80
        $oldService.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
    }
    $products = [ordered]@{
        '{48447F31-6B57-4B13-BA5F-4D019FA995E1}' = 'MySQL Server 8.0'
        '{6129E3AD-632C-4574-8575-653D9B4FF7C8}' = 'MySQL Workbench 8.0 CE'
        '{1ACB6B13-0380-4F98-B862-284318143CE9}' = 'MySQL Shell 8.0.18'
        '{3C0AB23E-7D55-4C9A-BA7D-160E074A5F28}' = 'MySQL Connector/ODBC 8.0'
        '{FC6EF205-E78F-41C8-AF64-2942E59EBB1E}' = 'MySQL Connector C++ 8.0'
        '{110C2446-5C3A-4855-BEE3-E98AACB9CD9C}' = 'MySQL Connector/ODBC 8.0 (32-bit)'
        '{354D7B08-0271-4FEB-932B-4323935ECCC7}' = 'MySQL Connector J'
        '{928D0887-5E6F-42B9-9C4E-506F382A769B}' = 'MySQL Installer - Community'
    }
    foreach ($product in $products.GetEnumerator()) {
        $uninstallLog = Join-Path $logDir ('uninstall-' + $product.Key.Trim('{}') + '.log')
        $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', $product.Key, '/qn', '/norestart', '/L*v', ('"' + $uninstallLog + '"')) -WindowStyle Hidden -Wait -PassThru
        if ($process.ExitCode -notin @(0, 1605, 3010)) { throw ('Uninstall failed for ' + $product.Value + ', exit=' + $process.ExitCode) }
        $removed += $product.Value
        Save-Status 'uninstalling-old-mysql'
    }
    if (Get-Service -Name MySQL80 -ErrorAction SilentlyContinue) {
        Invoke-Checked 'sc.exe' @('delete', 'MySQL80') | Out-Null
        Start-Sleep -Seconds 3
    }
    $remaining = @()
    foreach ($root in @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall')) {
        $remaining += @(Get-ItemProperty "$root\*" -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -match '^MySQL' })
    }
    if ($remaining.Count -gt 0) { throw 'MySQL uninstall entries remain; refusing to delete installation directories.' }
    Save-Status 'cleaning-old-directories'
    foreach ($oldPath in @('C:\Program Files\MySQL', 'C:\Program Files (x86)\MySQL', 'C:\ProgramData\MySQL')) { Remove-ExactOldDirectory $oldPath }

    Save-Status 'initializing-new-mysql'
    $listener = Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue
    if ($listener) { throw 'Port 3306 is still occupied.' }
    $baseSlash = $mysqlBase.Replace('\', '/')
    $dataSlash = $mysqlData.Replace('\', '/')
    @(
        '[mysqld]',
        ('basedir=' + $baseSlash),
        ('datadir=' + $dataSlash),
        'port=3306',
        'bind-address=127.0.0.1',
        'mysqlx=0',
        'character-set-server=utf8mb4',
        'collation-server=utf8mb4_0900_ai_ci',
        'max_connections=100',
        ('log-error=' + $dataSlash + '/mysql-error.log'),
        '[client]',
        'host=127.0.0.1',
        'port=3306',
        'default-character-set=utf8mb4'
    ) | Set-Content -LiteralPath $configPath -Encoding ASCII
    Invoke-Checked $serverExe @('--no-defaults', '--initialize-insecure', ('--basedir=' + $mysqlBase), ('--datadir=' + $mysqlData)) | Out-Null

    $rootPassword = New-Password
    $appPassword = New-Password
    @(('MYSQL_ROOT_PASSWORD=' + $rootPassword), ('MYSQL_PASSWORD=' + $appPassword)) | Set-Content -LiteralPath $envPath -Encoding ASCII
    Protect-Secret $envPath
    $initPath = Join-Path $mysqlBase 'bootstrap-local.sql'
    $rootClientPath = Join-Path $mysqlBase 'bootstrap-root.cnf'
    $appClientPath = Join-Path $mysqlBase 'bootstrap-app.cnf'
    $secretFiles = @($initPath, $rootClientPath, $appClientPath)
    @(
        "ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';",
        'CREATE DATABASE zlowallpaper CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;',
        "CREATE USER 'wallpaper_app'@'localhost' IDENTIFIED BY '$appPassword';",
        "CREATE USER 'wallpaper_app'@'127.0.0.1' IDENTIFIED BY '$appPassword';",
        "GRANT ALL PRIVILEGES ON zlowallpaper.* TO 'wallpaper_app'@'localhost';",
        "GRANT ALL PRIVILEGES ON zlowallpaper.* TO 'wallpaper_app'@'127.0.0.1';"
    ) | Set-Content -LiteralPath $initPath -Encoding ASCII
    @('[client]', 'user=root', ('password=' + $rootPassword), 'host=127.0.0.1', 'port=3306') | Set-Content -LiteralPath $rootClientPath -Encoding ASCII
    @('[client]', 'user=wallpaper_app', ('password=' + $appPassword), 'host=127.0.0.1', 'port=3306') | Set-Content -LiteralPath $appClientPath -Encoding ASCII
    foreach ($secretPath in $secretFiles) { Protect-Secret $secretPath }
    $temporaryServer = Start-Process -FilePath $serverExe -ArgumentList @(('--defaults-file=' + $configPath), ('--init-file=' + $initPath.Replace('\', '/'))) -WindowStyle Hidden -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 2
        if ($temporaryServer.HasExited) { throw 'The new MySQL bootstrap process exited unexpectedly.' }
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $null = & $clientExe ('--defaults-extra-file=' + $rootClientPath) '--connect-timeout=2' '--execute=SELECT 1' 2>$null
        $connectExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousPreference
        if ($connectExitCode -eq 0) { $ready = $true; break }
    }
    if (-not $ready) { throw 'New MySQL did not become ready within 120 seconds.' }
    Invoke-Checked $clientExe @(('--defaults-extra-file=' + $appClientPath), '--database=zlowallpaper', '--execute=SELECT VERSION(), DATABASE(), CURRENT_USER(); CREATE TABLE environment_probe (id INT PRIMARY KEY); INSERT INTO environment_probe VALUES (1); SELECT COUNT(*) FROM environment_probe; DROP TABLE environment_probe;') | Set-Content -LiteralPath (Join-Path $logDir 'connection-check.txt') -Encoding UTF8
    Remove-Item -LiteralPath $initPath -Force
    Invoke-Checked $adminExe @(('--defaults-extra-file=' + $rootClientPath), 'shutdown') | Out-Null
    if (-not $temporaryServer.WaitForExit(30000)) { throw 'Bootstrap server did not stop cleanly.' }
    $temporaryServer = $null

    Save-Status 'registering-new-service'
    Invoke-Checked $serverExe @('--install', $serviceName, ('--defaults-file=' + $configPath)) | Out-Null
    Start-Service -Name $serviceName
    (Get-Service -Name $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
    Invoke-Checked $clientExe @(('--defaults-extra-file=' + $appClientPath), '--database=zlowallpaper', '--execute=SELECT VERSION(), DATABASE(), CURRENT_USER();') | Set-Content -LiteralPath (Join-Path $logDir 'service-check.txt') -Encoding UTF8
    Save-Status 'complete'
} catch {
    Save-Status 'failed' $_.Exception.Message
    exit 1
} finally {
    if ($temporaryServer -and -not $temporaryServer.HasExited) { Stop-Process -Id $temporaryServer.Id -Force -ErrorAction SilentlyContinue }
    foreach ($secretPath in $secretFiles) {
        if (Test-Path -LiteralPath $secretPath) { Remove-Item -LiteralPath $secretPath -Force -ErrorAction SilentlyContinue }
    }
}
