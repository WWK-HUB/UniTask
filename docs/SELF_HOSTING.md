# 自托管与小组分享

## 本机部署

1. 启动 Docker Desktop 的 Linux 容器引擎，准备 Java 17 和 PowerShell 7.4+。
2. 在仓库根目录运行 `.\scripts\prepare-local.ps1`。脚本使用 `infra/.env` 和独立 Compose 项目 `unitask-devkit`，启动 MySQL 8.4 和 Redis 7.4。
3. 运行 `. .\scripts\local-env.ps1`、`.\mvnw.cmd -B -ntp clean package` 和 `java -jar target/unitask.war`。
4. 访问 `http://127.0.0.1:8080/login`。如 Maven 镜像解析失败，可加 `-s scripts/maven-central-settings.xml` 使用 Maven Central。

空 MySQL 卷会自动应用 `database/schema.sql`。手动部署使用 MySQL 8.4 空库、仅授权该库的专用账号与 `database/migration/V1__initial_schema.sql`。后续数据库变更使用编号迁移。要使用 `dev` 生成虚构数据，可执行 `.\scripts\seed-demo.ps1`，账号和一次性显示的演示密码仅用于本机试用。

| 配置 | 作用 |
|---|---|
| `UNITASK_DB_URL` | JDBC URL，包含 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8` |
| `UNITASK_DB_USER` / `UNITASK_DB_PASSWORD` | 应用数据库账号和密码 |
| `UNITASK_REDIS_HOST` / `UNITASK_REDIS_PORT` / `UNITASK_REDIS_PASSWORD` | Redis 地址、端口、密码 |
| `UNITASK_UPLOAD_DIR` | 附件持久化目录 |
| `UNITASK_PORT` / `UNITASK_BIND_ADDRESS` | 默认 `8080` / `127.0.0.1` |
| `UNITASK_REGISTRATION_CODE` | 启用 `share` profile 时使用的注册口令，16–128 字符 |

## 免费 HTTPS 分享

适用于少量成员、能维持一台在线主机的使用场景。Quick Tunnel 提供一个临时 HTTPS 域名，WAR、MySQL、Redis 和附件均运行或保存在自托管主机。

1. 从 [Cloudflare 官方下载页](https://developers.cloudflare.com/tunnel/downloads/) 获取 Windows 64 位 `cloudflared`，放到仓库的 `var/toolchain/cloudflared.exe`。可通过 `-Cloudflared <路径>` 指定其他本地文件。
2. 先完成 WAR 构建，再运行 `.\scripts\share.ps1`。脚本使用 Compose 项目 `unitask-share`、独立 MySQL 数据卷、Redis 及 `var/share/uploads`。应用、MySQL、Redis 只绑定本机的 18081、13307、16380。
3. 把输出的 HTTPS 登录地址和 `var/share/registration.txt` 中的口令分别发给组员。成员通过注册页创建自己的账号。加入某个项目仍需接受该项目发出的邀请。
4. 运行 `.\scripts\share.ps1 -Stop` 关闭共享入口。此操作保留数据卷和附件。再次启动会沿用数据，Quick Tunnel 域名会改变。
5. 如需轮换注册口令，先关闭入口，更新本机口令文件，再启动脚本。组员已注册的密码和项目权限继续生效。

分享启用 Secure Session Cookie，Tomcat 从本机隧道代理读取 HTTPS 协议和 Cloudflare 客户端地址。单文件和批次 90 MiB；本机默认仍为单文件 100 MiB 和批次 200 MiB。电脑关机、休眠、Docker 关闭或隧道进程结束时，外部入口会中断。多人长期稳定访问可在有持久磁盘的主机上部署同一个 WAR 和 MySQL/Redis，并配置固定域名与 HTTPS。[Quick Tunnel 限制](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

## 数据与备份

- 本机和分享实例使用独立 Compose 项目。`unitask-share` 的 MySQL 数据在项目专用 Docker 卷，附件在 `var/share/uploads`。私有密码和注册口令保存在 `var/share` 的忽略文件中。
- 在停止写入后配套备份 MySQL dump 和上传目录。恢复先在隔离数据库核验，再切换应用连接和附件路径。Redis 可从 MySQL 重建。
- `.\scripts\verify-backup-restore.ps1` 适用于本机默认开发实例。它创建随机名隔离库，比较 12 张表行数与文件 SHA-256。对分享实例进行同类备份时使用 `unitask-share` Compose 项目和该实例的上传目录。
- 按 `README` 的测试步骤创建独立 `unitask_test` 数据库。全量测试使用浏览器启动实际 WAR，并使用临时上传目录。
