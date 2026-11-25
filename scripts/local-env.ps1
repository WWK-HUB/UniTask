# Dot-source from PowerShell to configure this session only.
$unitaskProjectRoot = Split-Path -Parent $PSScriptRoot
$unitaskPortableJdk = Join-Path $unitaskProjectRoot 'var/toolchain/jdk-17.0.21.1+2'
if (Test-Path -LiteralPath (Join-Path $unitaskPortableJdk 'bin/java.exe')) {
    $env:JAVA_HOME = $unitaskPortableJdk
    $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
}
$unitaskLocalSecrets = Join-Path $unitaskProjectRoot 'infra/.env'
if (Test-Path -LiteralPath $unitaskLocalSecrets) {
    Get-Content -LiteralPath $unitaskLocalSecrets | ForEach-Object {
        if (!$env:UNITASK_DB_PASSWORD -and $_ -match '^UNITASK_DB_PASSWORD=(.+)$') { $env:UNITASK_DB_PASSWORD = $Matches[1] }
        if (!$env:UNITASK_TEST_DB_PASSWORD -and $_ -match '^UNITASK_TEST_DB_PASSWORD=(.+)$') { $env:UNITASK_TEST_DB_PASSWORD = $Matches[1] }
    }
}
