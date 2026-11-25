$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. ./scripts/local-env.ps1
$compose = @('--env-file','infra/.env','-p','unitask-devkit','-f','infra/compose.yml')
$suffix = [Guid]::NewGuid().ToString('N').Substring(0,8)
$schema = "unitask_restore_$suffix"
$bundle = Join-Path (Get-Location) "var/backups/$schema"
$objects = Join-Path $bundle 'uploads/objects'
New-Item -ItemType Directory -Path $objects -Force | Out-Null
$uploadRoot = if ($env:UNITASK_UPLOAD_DIR) { $env:UNITASK_UPLOAD_DIR } else { Join-Path (Get-Location) 'var/uploads' }
$sourceObjects = Join-Path $uploadRoot 'objects'
$dump = Join-Path $bundle 'unitask.sql'

function Invoke-RestoreSql([string] $query, [string] $database = 'unitask') {
    $query | docker compose @compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root -N --batch --default-character-set=utf8mb4 "$@"' sh $database
    if ($LASTEXITCODE) { throw "SQL failed for $database" }
}

docker compose @compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --single-transaction --default-character-set=utf8mb4 --set-gtid-purged=OFF --no-tablespaces unitask' > $dump
if ($LASTEXITCODE -or (Get-Item -LiteralPath $dump).Length -le 0) { throw 'mysqldump failed' }

$attachmentSql = 'SELECT storage_key,sha256,size_bytes FROM attachments ORDER BY storage_key;'
$sourceFiles = @(Invoke-RestoreSql $attachmentSql)
foreach ($row in $sourceFiles) {
    $key,$sha,$size = $row -split [char]9
    if ($key -notmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') { throw 'Invalid attachment storage key' }
    $source = Get-Item -LiteralPath (Join-Path $sourceObjects $key)
    if ($source.LinkType -or $source.Length -ne [long]$size) { throw "Invalid source object $key" }
    Copy-Item -LiteralPath $source.FullName -Destination (Join-Path $objects $key)
}
if (!$sourceFiles.Count) { throw '需要至少一个附件来证明配套恢复。请先生成 dev 数据。' }

Invoke-RestoreSql "CREATE DATABASE $schema CHARACTER SET utf8mb4;"
$OutputEncoding = [Text.UTF8Encoding]::new($false)
Invoke-RestoreSql (Get-Content -Raw -Encoding utf8 -LiteralPath $dump) $schema
$tables = @('users','projects','project_members','project_invitations','tasks','task_assignees','task_comments',
    'task_reviews','attachments','notifications','task_logs','allocation_previews')
$countsSql = ($tables | ForEach-Object { "SELECT '$_',COUNT(*) FROM $_" }) -join ' UNION ALL '
$sourceCounts = @(Invoke-RestoreSql "$countsSql;")
$restoreCounts = @(Invoke-RestoreSql "$countsSql;" $schema)
if (Compare-Object $sourceCounts $restoreCounts) { throw 'Restored table row counts differ' }
$restoreFiles = @(Invoke-RestoreSql $attachmentSql $schema)
if (Compare-Object $sourceFiles $restoreFiles) { throw 'Restored attachment metadata differs' }
foreach ($row in $restoreFiles) {
    $key,$sha,$size = $row -split [char]9
    $file = Join-Path $objects $key
    if ((Get-Item -LiteralPath $file).Length -ne [long]$size -or
            (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash -ne $sha) { throw "Restored hash differs for $key" }
}
"恢复通过。隔离库 $schema，$($tables.Count) 张表行数一致，$($restoreFiles.Count) 个对象的大小与 SHA-256 一致。"
