param([switch]$CheckOnly)

$ErrorActionPreference = 'Stop'
$clientPath = 'E:\zlowallpaper\ZloWallpaper.exe'
$apiBase = 'http://127.0.0.1:8080/api/v1'

if (-not (Test-Path -LiteralPath $clientPath)) {
    throw "Client executable is missing: $clientPath"
}

$connection = New-Object System.Net.Sockets.TcpClient
try {
    $pending = $connection.BeginConnect('127.0.0.1', 8080, $null, $null)
    if (-not $pending.AsyncWaitHandle.WaitOne(2000)) {
        throw 'Backend port 8080 is not ready. Start the Spring Boot application in IDEA first.'
    }
    try { $connection.EndConnect($pending) } catch {
        throw 'Backend port 8080 is not ready. Start the Spring Boot application in IDEA first.'
    }
} finally {
    $connection.Dispose()
}

Write-Output "Backend port is reachable. Configure the client connection URL as $apiBase"
Write-Output 'This checks TCP connectivity only; verify the actual APIs with the module test cases.'
if ($CheckOnly) { return }

Start-Process -FilePath $clientPath -WorkingDirectory (Split-Path -Parent $clientPath)
Write-Output 'Client launched without --demo. If already running, verify its connection settings and switch to real API mode.'
