param(
    [string]$JavaHome = 'D:\Codex\software\jdk-21.0.12.1+1',
    [ValidateRange(1, 65535)][int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'JDK is missing. Pass -JavaHome with your JDK 21 directory.'
}

$oldJavaHome = $env:JAVA_HOME
$oldMavenUserHome = $env:MAVEN_USER_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $env:MAVEN_USER_HOME = 'D:\Codex\software\maven-user-home'
    Push-Location $projectDir
    try {
        # 前台运行，Ctrl+C 停止；不修改系统全局 JAVA_HOME，也不把密码放到命令行。
        & (Join-Path $projectDir 'mvnw.cmd') '-B' '-ntp' `
            '-Dmaven.repo.local=D:/Codex/software/maven-repository' `
            'spring-boot:run' `
            "-Dspring-boot.run.arguments=--spring.profiles.active=local --server.port=$Port"
        if ($LASTEXITCODE -ne 0) { throw ('Backend stopped with exit code ' + $LASTEXITCODE) }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:MAVEN_USER_HOME = $oldMavenUserHome
}
