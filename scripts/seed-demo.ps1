$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/local-env.ps1
if (!(Test-Path -LiteralPath target/unitask.war)) { throw '请先用 Java 17 执行 .\mvnw.cmd package' }
java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -jar target/unitask.war --spring.main.web-application-type=none --spring.profiles.active=dev --unitask-seed
exit $LASTEXITCODE
