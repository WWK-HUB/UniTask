$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if (!(Test-Path -LiteralPath infra/.env)) {
    $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $rootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    [IO.File]::WriteAllLines((Join-Path (Get-Location) 'infra/.env'),
        @("UNITASK_DB_PASSWORD=$appPassword","MYSQL_ROOT_PASSWORD=$rootPassword"))
}
if (Select-String -LiteralPath infra/.env -Pattern '^(\w+)=replace_with_' -Quiet) {
    throw '请在 infra/.env 中填写本机开发密码'
}
docker compose --env-file infra/.env -p unitask-devkit -f infra/compose.yml up -d --wait
exit $LASTEXITCODE
