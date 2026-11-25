$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$secrets = Get-Content -LiteralPath infra/.env
$testPassword = ($secrets | Where-Object { $_ -like 'UNITASK_TEST_DB_PASSWORD=*' } | Select-Object -First 1) -replace '^UNITASK_TEST_DB_PASSWORD=', ''
if (!$testPassword) {
    $testPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    Add-Content -LiteralPath infra/.env -Value "UNITASK_TEST_DB_PASSWORD=$testPassword"
}
if ($testPassword -notmatch '^[0-9a-fA-F]{48}$') { throw 'Expected generated test password' }
function Invoke-TestDatabaseSql([string] $script) {
    $script | docker compose --env-file infra/.env -p unitask-devkit -f infra/compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --default-character-set=utf8mb4 --batch'
    if ($LASTEXITCODE) { throw 'Test database initialization failed' }
}
Invoke-TestDatabaseSql @"
CREATE DATABASE IF NOT EXISTS unitask_test CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'unitask_test'@'%' IDENTIFIED BY '$testPassword';
GRANT ALL ON unitask_test.* TO 'unitask_test'@'%';
"@
$tableCount = Invoke-TestDatabaseSql "SELECT count(*) FROM information_schema.tables WHERE table_schema='unitask_test';"
if ($tableCount[-1] -eq '0') {
    Invoke-TestDatabaseSql ("USE unitask_test;" + [char]10 + (Get-Content -Raw database/migration/V1__initial_schema.sql))
} elseif ($tableCount[-1] -ne '12') {
    throw 'Unexpected test schema; inspect it before applying migrations.'
}
'unitask_test ready'
