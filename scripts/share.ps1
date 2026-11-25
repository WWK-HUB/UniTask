[CmdletBinding()]
param(
    [switch] $Stop,
    [string] $Cloudflared = 'var/toolchain/cloudflared.exe'
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$root = (Get-Location).Path
$data = Join-Path $root 'var/share'
New-Item -ItemType Directory -Force $data | Out-Null
$statePath = Join-Path $data 'running.json'
$lock = [IO.File]::Open((Join-Path $data 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')

function Get-OwnedProcess($record) {
    if ($null -eq $record) { return }
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().ToFileTimeUtc() -eq [long]$record.started) { $process }
}

function Save-ProcessInfo($process) {
    @{ id = $process.Id; started = $process.StartTime.ToUniversalTime().ToFileTimeUtc() }
}

try {
    $state = if (Test-Path -LiteralPath $statePath) {
        Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    }
    $oldApp = Get-OwnedProcess $state.app
    $oldTunnel = Get-OwnedProcess $state.tunnel
    if ($Stop) {
        foreach ($process in @($oldTunnel, $oldApp)) { if ($process) { $process.Kill() } }
        if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
        '共享入口已关闭。数据库和附件已保留。'
        return
    }

    if ($oldApp -and $oldTunnel) {
        "已运行：$($state.url)/login"
        return
    }
    foreach ($process in @($oldTunnel, $oldApp)) { if ($process) { $process.Kill(); $process.WaitForExit(5000) | Out-Null } }

    if (!(Test-Path -LiteralPath target/unitask.war)) { throw '请先构建 target/unitask.war' }
    $tunnelExe = (Resolve-Path -LiteralPath $Cloudflared).Path
    . ./scripts/local-env.ps1
    if ($env:JAVA_HOME) {
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $portableJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
        if (Test-Path -LiteralPath $portableJava) { $java = $portableJava }
    } else { $java = (Get-Command java.exe -ErrorAction Stop).Source }

    $secrets = Join-Path $data 'infra.env'
    if (!(Test-Path -LiteralPath $secrets)) {
        $dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $rootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        [IO.File]::WriteAllLines($secrets, @(
            "UNITASK_DB_PASSWORD=$dbPassword", "MYSQL_ROOT_PASSWORD=$rootPassword",
            'UNITASK_DB_PORT=13307', 'UNITASK_REDIS_PORT=16380'))
    }
    $envConfig = Get-Content -LiteralPath $secrets -Raw | ConvertFrom-StringData
    $registrationPath = Join-Path $data 'registration.txt'
    if (!(Test-Path -LiteralPath $registrationPath)) {
        $code = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
        [IO.File]::WriteAllText($registrationPath, $code + [char]10)
    }
    $registration = (Get-Content -Raw -LiteralPath $registrationPath).Trim()
    if ($registration.Length -lt 16 -or $registration.Length -gt 128) { throw '注册口令长度必须为 16–128 个字符' }
    $compose = @('--env-file', $secrets, '-p', 'unitask-share', '-f', 'infra/compose.yml')
    # Compose gives the process environment precedence over --env-file.
    $previous = @{}
    foreach ($key in $envConfig.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $envConfig[$key], 'Process')
    }
    try {
        docker compose @compose up -d --wait --wait-timeout 45
        if ($LASTEXITCODE) { throw '共享实例 MySQL / Redis 未能启动' }
    } finally {
        foreach ($key in $previous.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
        }
    }
    if (Get-NetTCPConnection -State Listen -LocalPort 18081 -ErrorAction SilentlyContinue) { throw '共享实例端口 18081 已被占用' }

    $publicEnv = @{
        SPRING_PROFILES_ACTIVE = 'share'
        UNITASK_BIND_ADDRESS = '127.0.0.1'
        UNITASK_PORT = '18081'
        UNITASK_DB_URL = "jdbc:mysql://127.0.0.1:$($envConfig.UNITASK_DB_PORT)/unitask?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
        UNITASK_DB_USER = 'unitask'
        UNITASK_DB_PASSWORD = $envConfig.UNITASK_DB_PASSWORD
        UNITASK_REDIS_HOST = '127.0.0.1'
        UNITASK_REDIS_PORT = $envConfig.UNITASK_REDIS_PORT
        UNITASK_REDIS_PASSWORD = ''
        UNITASK_UPLOAD_DIR = Join-Path $data 'uploads'
        UNITASK_REGISTRATION_CODE = $registration
    }
    $app = $null
    $tunnel = $null
    try {
        $app = Start-Process $java -ArgumentList '-jar','target/unitask.war' -WorkingDirectory $root -Environment $publicEnv -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $data 'app.log') -RedirectStandardError (Join-Path $data 'app-error.log')
        $origin = 'http://127.0.0.1:18081'
        $ready = $false
        $end = (Get-Date).AddSeconds(40)
        while ((Get-Date) -lt $end -and !$app.HasExited) {
            try {
                if ((Invoke-WebRequest "$origin/login" -TimeoutSec 2).StatusCode -eq 200) { $ready = $true; break }
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (!$ready) { throw '共享 WAR 启动失败，请查看 var/share/app.log' }

        $tunnelLog = Join-Path $data 'tunnel-error.log'
        $tunnel = Start-Process $tunnelExe -ArgumentList 'tunnel','--no-autoupdate','--protocol','http2','--url',$origin -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $data 'tunnel.log') -RedirectStandardError $tunnelLog
        $end = (Get-Date).AddSeconds(50)
        $public = $null
        while ((Get-Date) -lt $end -and !$tunnel.HasExited) {
            $log = Get-Content -Raw -LiteralPath $tunnelLog -ErrorAction SilentlyContinue
            if ($log -match 'https://[a-z0-9-]+\.trycloudflare\.com') {
                $public = $Matches[0]
                try {
                    if ((Invoke-WebRequest "$public/login" -TimeoutSec 4).StatusCode -eq 200) { break }
                } catch {}
            }
            Start-Sleep -Seconds 1
        }
        if (!$public -or $tunnel.HasExited) { throw 'Cloudflare Quick Tunnel 启动失败，请查看 var/share/tunnel-error.log' }
        $result = Invoke-WebRequest "$public/login" -TimeoutSec 8
        if ($result.StatusCode -ne 200 -or $result.Content -notmatch 'UniTask') { throw '公网入口校验失败' }
        @{ url = $public; app = Save-ProcessInfo $app; tunnel = Save-ProcessInfo $tunnel } |
            ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath $statePath
        "共享入口：$public/login"
        '注册口令文件：var/share/registration.txt'
    } catch {
        foreach ($process in @($tunnel, $app)) { if ($process -and !$process.HasExited) { $process.Kill() } }
        throw
    }
} finally {
    $lock.Dispose()
}
