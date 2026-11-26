# UniTask · 小组项目协作平台

UniTask 面向需要共同完成课程、小组实践和团队项目的成员。它把项目邀请、任务名额、交付材料、组长验收和截止提醒放在同一个中文工作台。一个人可同时加入多个项目，在不同项目担任组长或成员。系统以单个 Web 应用运行，浏览器通过同源接口与服务端交互。

## 在线访问

[打开 UniTask](https://adelaide-named-champion-properties.trycloudflare.com/login)

当前入口由 Cloudflare Quick Tunnel 提供，应用、MySQL 和附件保存在本机。需要主机保持开机联网，以及应用、数据库和隧道持续运行。隧道重建后地址会变化，已有项目和文件会保留。注册需要向组织者索取口令。

## 功能与实现技术

| 功能 | 使用方式与业务规则 | 实现技术 |
|---|---|---|
| 注册、登录、退出、账号设置 | 注册用户名不区分大小写，昵称可修改。修改密码需要验证原密码，成功后结束当前登录会话。公开小组分享可配置注册口令。 | Spring Security 表单登录、Session、BCrypt、CSRF，Spring MVC、JSP 表单、JDBC 用户记录 |
| 个人工作台 | 查看参与的项目、本人角色、有效任务总数、完成数、截止时间、个人待办和最近消息。 | JSP/JSTL 服务端视图、原生 JS Fetch、JdbcTemplate 分页与聚合 |
| 我的项目和项目概览 | 按名称或课程搜索、按状态筛选。组长创建和编辑项目名称、课程、简介、截止时间，可在创建时附参考文件。 | Spring MVC REST、MySQL、JSP、multipart 上传 |
| 项目归档与验收进度 | 进度只按已通过验收的有效任务计算，空项目为 0%。全部完成或空项目可归档。归档后保留历史与授权下载。 | Spring 事务、InnoDB 锁、JdbcTemplate 聚合、REST 状态约束 |
| 邀请和加入项目 | 按精确用户名查找成员，发出邀请后被邀请人可接受或拒绝。组长可查看和撤销待处理邀请。 | Spring MVC、MySQL 邀请状态、生成列和唯一约束、持久化通知、查找限流 |
| 成员管理和组长转让 | 组长可转让角色，移除没有未完成任务的成员。历史评论与附件保留，访问权限随成员状态更新。 | Spring 事务、InnoDB 行锁、统一项目权限服务 |
| 任务管理 | 发布和编辑标题、内容、人数、优先级、截止时间及创建时附件。空白待分配任务可软删。支持按标题、状态、负责人和优先级筛选。 | Spring MVC REST、JDBC 参数绑定、MySQL 任务状态及软删、JSP 任务页 |
| 任务看板和我的任务 | 五个工作状态的看板或列表。按负责人、状态等查看项目任务，按项目、状态或逾期筛选自己的跨项目任务。 | JSP 页面、CSS 响应式布局、原生 JS DOM、REST 分页筛选 |
| 认领与手动分配 | 有空位时成员可认领，符合历史成果限制时可取消。组长可原子替换完整负责人名单。 | InnoDB 行锁、关联表复合主键、Spring 事务、JdbcTemplate |
| 均衡随机分配 | 先预览再确认。选择任务范围和组长是否参与，保留原分工，优先为当前未完成任务较少的成员补位；预览展示缺额与预计任务量。 | Java 分配算法、SecureRandom、SHA-256、MySQL 预览快照和项目 revision |
| 评论与成果附件 | 项目成员可讨论，负责人普通进展逐条通知组长。评论支持文本、多个附件或仅附件，并按时间形成协作记录。 | 同源 REST、multipart、JavaBean、MySQL 时间线、事务内业务通知 |
| 提交验收、打回和再提交 | 名额满且有成果时由负责人发起验收。组长可通过或填写打回原因。重新提交需有打回后的新增进展或本次成果，审核绑定确切轮次。 | Spring 事务、任务状态机、审核轮次唯一约束、SQL 行锁 |
| 操作记录和催办 | 可查任务创建、变更、分工、审核和提醒操作。组长可按负责人催办，同一个任务和接收人每五分钟最多一次。 | MySQL 审计日志 JSON、任务锁内检查、Redis 近期提醒标记 |
| 消息中心 | 收到邀请、任务或分工变化、进展、审核、催办与截止提醒。通知正文可跳转项目、任务或对应评论，支持按已读状态筛选、逐条和一键已读。 | MySQL 持久化、事务提交后失效缓存、REST、原生 JS 未读数轮询 |
| 自动截止提醒 | 服务定期扫描未来 24 小时到期的项目和任务；同一接收人、对象和截止时间只发一次，改期后可提醒。 | Spring Scheduling、注入 Clock、MySQL 通知唯一去重键 |
| 文件中心 | 聚合项目参考文件、任务文件、评论及提交附件，可按任务、上传人和文件类别查询。 | Spring MVC、JdbcTemplate、MySQL 文件元数据 |
| 下载和在线预览 | 项目成员可授权下载，图片、PDF、兼容音视频可预览，媒体支持单 Range。附件保留原始名称和 SHA-256。 | 独立 Jakarta DownloadServlet、文件签名、UUID 存储键、Java NIO 流式响应 |
| 参与统计 | 显示各成员的负责任务数、完成数、普通评论数和附件数。 | 基于真实数据的 JDBC SQL 聚合、JSP 项目概览 |
| 缓存与降级 | 未读数缓存 60 秒；Redis 停机时仍可收发通知、读未读数并执行催办限流。 | Redis 7.4、Spring Data Redis、Lettuce、MySQL 回退 |

## 关键流程

1. 组长创建项目，按精确用户名邀请成员。成员接受后进入项目。
2. 组长发布带人数要求和截止时间的任务。组员认领，组长可调整负责人或预览随机补位。
3. 负责人发布评论和附件，与成员同步进展。任务满员且已有成果时可提交验收。
4. 组长通过或打回。被打回的任务补充新成果后再次提交。有效任务全部完成后归档，成员可继续查阅材料。
5. 整个过程中，消息中心记录关键事项，项目文件中心汇总附件，工作台呈现跨项目待办。

任务状态依次包含 **待分配、进行中、待审核、已打回、已完成**。首个负责人加入后进入进行中。待审核由本轮审核决定转为已完成或已打回。逾期是通过截止时间计算的标记，可以与工作状态同时显示。

## 系统设计

~~~text
浏览器（JSP / JSTL / HTML / CSS / 原生 JS）
         │ HTTPS + Session + CSRF
         ▼
Tomcat 10.1 / Jakarta Servlet 6 / Spring Security
         ├── Spring MVC 页面控制器与 REST 控制器
         └── DownloadServlet（授权下载、预览和 Range）
                        │
                 Spring Service 层
                        ├── JdbcTemplate → MySQL 8.4
                        ├── Spring Data Redis → Redis 7.4
                        └── Java NIO → 本地持久化附件目录
~~~

### 技术栈和模块

- **Java 17 / JavaWeb** 管理表单、会话、multipart 请求与文件流。领域类采用 JavaBean，提供无参构造与访问器。
- **Spring Boot 3.5.16 / Spring Framework 6.2** 负责应用配置及组件装配，使用 Spring 构造器注入、事务和调度。**Spring MVC** 服务端渲染 JSP 并处理 JSON REST 请求。
- **Spring Security 6.5** 管理用户名密码登录、Session、BCrypt 哈希、CSRF、认证和基础安全响应头。项目级权限由业务服务检查。
- **Tomcat 10.1 / Jakarta Servlet 6** 作为运行容器。独立的 `DownloadServlet` 通过 `ServletRegistrationBean` 注册，用于媒体响应和下载。Tomcat JSP 引擎与 JSTL 动态渲染页面。
- **JDBC / JdbcTemplate / MySQL 8.4** 负责参数化 SQL、实体映射、分页、审计、事务锁和外键一致性。使用 InnoDB、utf8mb4、UTC 会话，页面显示北京时间。
- **Redis 7.4 / Spring Data Redis / Lettuce** 用于未读缓存和催办提示。消息与限流真值保存在 MySQL。
- **Maven 3.9.16 Wrapper** 打包成可执行 WAR。**JUnit 5、MockMvc、Spring Security Test、真实 MySQL、Selenium/Chrome** 覆盖单测、集成和打包后浏览器验证。
- **PowerShell 与 Docker Compose** 管理本地 MySQL、Redis、测试库、数据恢复和小组 HTTPS 分享。

| 源码模块 | 职责 |
|---|---|
| `user` / `security` | 注册、凭证、身份、会话、注册口令和请求限流 |
| `project` | 项目概览、邀请、项目成员范围、转让、归档和统计 |
| `task` / `allocation` | 任务、看板查询、名额管理、随机预览与确认 |
| `comment` | 评论、成果提交、审核、操作记录和手动催办 |
| `attachment` | 文件检查、暂存、配额、事务补偿、下载、预览和维护 |
| `notification` | 持久化消息、未读缓存、截止扫描及去重 |
| `web` / `common` / `config` | JSP 页面、错误模型、分页和数据访问基础组件、启动检查与显式演示数据 |

### 数据模型与一致性

数据库的 12 张表分为五组。初始化 SQL 位于 [database/schema.sql](database/schema.sql) 与 [V1 迁移](database/migration/V1__initial_schema.sql)。

| 领域 | 数据表 |
|---|---|
| 用户与权限 | `users`、`projects`、`project_members`、`project_invitations` |
| 任务与分工 | `tasks`、`task_assignees`、`allocation_previews` |
| 协作与审核 | `task_comments`、`task_reviews`、`task_logs` |
| 文件 | `attachments` |
| 消息 | `notifications` |

项目相关写入先锁项目再锁任务，批量分配按任务 ID 加锁。随机确认还绑定操作者、随机令牌、项目 revision 和十分钟有效期。并发变更使预览失效，批次整体应用或回滚。通过项目、成员、任务、评论及附件之间的关系判断访问权限。

通知与业务写入共享 MySQL 事务，Redis 在提交后更新或失效。文件保存前检查数量、签名和容量，并计算 SHA-256；业务事务失败时清理本次文件，使用 UUID 路径保存对象。下载动态检查项目成员资格。JSP 转义用户内容，动态界面以文本节点呈现。

### 页面和 API

页面覆盖登录、注册、工作台、我的项目、项目概览、项目看板、成员、文件中心、任务详情、我的任务、消息中心、邀请和账号设置。

| 资源 | 接口 |
|---|---|
| 账号 | `GET/POST /login`、`GET/POST /register`、`POST /logout`、`GET/PATCH /api/me`、`POST /api/me/password`、`GET /api/users/lookup` |
| 项目 | `GET/POST /api/projects`、`GET/PATCH /api/projects/{p}`、`POST /api/projects/{p}/archive`、`GET /api/projects/{p}/stats` |
| 成员和邀请 | `GET /api/projects/{p}/members`、`DELETE /api/projects/{p}/members/{u}`、`POST /api/projects/{p}/transfer`、`GET/POST /api/projects/{p}/invitations`、`GET /api/invitations`、`POST /api/invitations/{i}/accept` 或 `/decline`、`POST /api/projects/{p}/invitations/{i}/revoke` |
| 任务和分配 | `GET/POST /api/projects/{p}/tasks`、`GET/PATCH/DELETE /api/tasks/{t}`、`POST/DELETE /api/tasks/{t}/claim`、`PUT /api/tasks/{t}/assignees`、`POST /api/projects/{p}/allocations/preview` 或 `/confirm`、`GET /api/my/tasks` |
| 协作与审核 | `GET/POST /api/tasks/{t}/comments`、`GET /api/tasks/{t}/comments/{c}/page`、`POST /api/tasks/{t}/submit`、`POST /api/tasks/{t}/review`、`POST /api/tasks/{t}/remind`、`GET /api/tasks/{t}/logs` |
| 文件 | `GET /api/projects/{p}/attachments`、`GET /api/projects/{p}/attachments/uploaders`、`GET /attachments/{a}/download` 或 `/preview` |
| 消息 | `GET /api/notifications`、`GET /api/notifications/unread-count`、`PATCH /api/notifications/{n}/read`、`POST /api/notifications/read-all` |

大部分查询支持 `page` 和 `size`，默认 20 条，最多 100 条，从第 0 页开始。REST 写入遵循 Session 和 CSRF，multipart 使用 `data` JSON 部件与一个或多个 `files`。正文和时间使用 UTF-8 与 ISO-8601。常见响应包括 400（校验）、401（未登录）、403/404（权限）、409（状态或并发冲突）、413（容量）、415（格式）、429（限流）。

### 附件类型和限额

| 类别 | 支持的常用扩展名 |
|---|---|
| 图片 | PNG、JPG/JPEG、GIF、WebP |
| 文档 | PDF、DOC/DOCX、XLS/XLSX、PPT/PPTX、ODT/ODS/ODP |
| 压缩包 | ZIP、RAR、7Z、TAR、GZ |
| 视频 | MP4、WebM、MOV、AVI、MKV |
| 音频 | MP3、WAV、M4A、OGG |
| 文本和源码 | TXT、MD、CSV、JSON、XML、HTML、CSS、TS、JS、JAVA、PY、SQL、C、CPP、H、CS、SH、YML、YAML |

图片、PDF 和兼容浏览器的音视频可预览，其他类型按附件下载。默认单文件 100 MiB、单次 10 个、批次 200 MiB、项目 2 GiB。HTTPS 分享模板的单文件和批次上限为 90 MiB，留出代理请求体开销。文件校验以服务端为准。

## 本地运行和 HTTPS 分享

要求 Java 17、Docker Desktop、Windows PowerShell 7.4+。启动本机服务：

~~~powershell
.\scripts\prepare-local.ps1
. .\scripts\local-env.ps1
.\mvnw.cmd -B -ntp clean package
java -jar target/unitask.war
~~~

本机入口为 `http://127.0.0.1:8080/login`。需要让少量成员跨网络访问时，可运行 `.\scripts\share.ps1`，通过 Cloudflare Quick Tunnel 发布独立的 HTTPS 入口。分享实例使用独立 MySQL、Redis、上传目录与注册口令。临时域名随隧道进程存在。完整配置、启停、数据持久化和备份请见 [自托管指南](docs/SELF_HOSTING.md)。

### 测试与维护

~~~powershell
.\scripts\prepare-test-db.ps1
. .\scripts\local-env.ps1
.\mvnw.cmd -B -ntp -Pit,e2e clean verify
~~~

单测运行 `.\mvnw.cmd test`，真实 MySQL 集成运行 `.\mvnw.cmd -Pit verify`，WAR 浏览器测试运行 `.\mvnw.cmd -Pe2e verify`。测试使用独立 `unitask_test` 和临时附件目录。可选 `.\scripts\seed-demo.ps1` 显式生成虚构账号和数据，便于本机试用。`.\scripts\verify-backup-restore.ps1` 在隔离库核对数据库与附件备份。
