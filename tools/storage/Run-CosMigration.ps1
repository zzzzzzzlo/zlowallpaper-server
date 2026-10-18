param([ValidateSet('plan','stage','apply','verify','rollback')][string]$Mode='plan')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if($projectRoot -ne 'E:\zlowallpaper_sever') {throw 'This one-time migration is configured for E:\zlowallpaper_sever only'}
Set-Location -LiteralPath $projectRoot
$javaRoot='D:\Codex\software\jdk-21.0.12.1+1'
$classpathFile=Join-Path $projectRoot '.local-setup\cos-classpath.txt'
if(-not (Test-Path -LiteralPath $classpathFile)) {throw 'Build the project and generate .local-setup/cos-classpath.txt first; see migration documentation'}
$migrationBin=Join-Path $projectRoot '.local-setup\cos-migration-bin'
New-Item -ItemType Directory -Path $migrationBin -Force | Out-Null
$migrationClasspath=(Join-Path $projectRoot 'target\classes')+';'+[IO.File]::ReadAllText($classpathFile).Trim()
& "$javaRoot\bin\javac.exe" -proc:none -encoding UTF-8 -cp $migrationClasspath -d $migrationBin (Join-Path $PSScriptRoot 'CosMigration.java')
if($LASTEXITCODE -ne 0) {exit $LASTEXITCODE}
& "$javaRoot\bin\java.exe" -cp "$migrationBin;$migrationClasspath" CosMigration $Mode
exit $LASTEXITCODE
