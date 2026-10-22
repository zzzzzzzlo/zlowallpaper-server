param(
    [string]$RedisDirectory = 'D:\Codex\software\redis-8.10.2\Redis-8.10.2-Windows-x64-msys2'
)

# 开发者手动启动本机缓存；不会安装系统服务，也不会占用后端8080端口。
$ErrorActionPreference = 'Stop'
$server = Join-Path $RedisDirectory 'redis-server.exe'
$client = Join-Path $RedisDirectory 'redis-cli.exe'
if (!(Test-Path -LiteralPath $server) -or !(Test-Path -LiteralPath $client)) {
    throw "未找到Redis程序，请通过 -RedisDirectory 指定安装目录：$RedisDirectory"
}
if (Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue) {
    & $client -h 127.0.0.1 -p 6379 ping
    if ($LASTEXITCODE -ne 0) { throw '6379端口已被占用，未启动新的Redis。' }
    return
}
$config = Join-Path $PSScriptRoot 'redis-local.conf'
# 此Windows便携版使用/cygdrive形式的绝对路径读取配置。
$configForRedis = $config.Replace('\', '/')
if ($configForRedis -match '^([A-Za-z]):/(.*)$') {
    $configForRedis = '/cygdrive/' + $Matches[1].ToLower() + '/' + $Matches[2]
}
Start-Process -FilePath $server -ArgumentList @($configForRedis) -WorkingDirectory $RedisDirectory -WindowStyle Hidden | Out-Null
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 250
    if (Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue) {
        $reply = & $client -h 127.0.0.1 -p 6379 ping 2>$null
        if ($reply -eq 'PONG') { Write-Output "Redis已启动：127.0.0.1:6379，配置文件：$config"; return }
    }
}
throw 'Redis未能启动，请检查程序目录、配置和6379端口。'
