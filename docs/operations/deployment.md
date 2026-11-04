# 部署与运行

应用镜像包含前端静态资源、Java 后端、固定 Mihomo 二进制与离线校验资源。应用以 UID/GID `10001:10001` 运行，根文件系统只读，临时目录受限；不需要 Docker socket、TUN、NET_ADMIN、Redis 或消息队列。生产只运行一个应用实例。

## 准备配置

需要 Docker Engine 与 Compose v2。两种部署方式使用同一应用镜像和数据库结构。应用内存上限 1536 MiB，配套数据库上限 1024 MiB，应为它们及宿主系统预留资源。

```sh
cp .env.example .env
./scripts/init-secrets.sh
```

脚本只创建缺少的文件，不覆盖已有密钥。`secrets` 目录为 0700；其中用于容器的文件为 0444，使非 root 容器能够读取，而主机上其他普通用户无法穿过父目录。若使用自定义目录，也需保证这一权限组合。Compose 通过单文件挂载传递 secret，见 [Docker Secrets 文档](https://docs.docker.com/compose/how-tos/use-secrets/)。

| 设置 | 用途 |
| --- | --- |
| `PUBLIC_BASE_URL` | 用户访问的完整根地址，例如 `https://clash.example.com`；用于生成订阅链接 |
| `APP_BIND_ADDRESS` / `APP_PORT` | 默认 `127.0.0.1` / `8080`，用于宿主端口映射；容器端口固定 8080 |
| `COOKIE_SECURE` | HTTPS 保持 `true`；仅 HTTP 本机调试使用 `false` |
| `DB_URL` | 已有 MySQL JDBC URL，包含目标库、UTC 时区与部署所需 TLS 设置 |
| `DB_USERNAME` / `DB_PASSWORD_FILE` | 专用数据库账号与密码文件 |
| `DB_NAME` | 配套 MySQL 创建的数据库名，默认 `clashsub` |
| `NODE_REGIONS_FILE` | 宿主机地区 YAML 的路径，默认 `./config/node-regions.yaml`；[配置示例与生效方式](node-regions.md) |
| `MYSQL_ROOT_PASSWORD_FILE` | 仅配套 MySQL 初始化使用的独立 root 密码文件 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD_FILE` | 首次初始化管理员；已有管理员不会被启动参数覆盖 |
| `APP_ENCRYPTION_KEY_FILE` | 必须持久保存的 32 字节二进制密钥；不能替换为 Base64 文本 |
| `UPSTREAM_PRIVATE_ALLOWLIST` | 默认空；确有私网订阅时允许的具体 CIDR，尽量使用单个地址 `/32` |
| `TRUSTED_OUTBOUND_PROXY_URL` | 部署者指定的可信出站 HTTP(S) 代理，表单可选择使用 |

不要把 URL 中的账号、订阅 Token 或数据库密码放进 Git。示例 `.env` 只包含 secret 文件路径。应用关闭访问日志，异常只返回安全诊断与跟踪标识。

## 已有 MySQL

准备专用空数据库和账号，授权该账号管理此库，以便 Flyway 建表迁移；不使用共享业务库或 root 账号。MySQL 8.4 为已验证版本。设定 `DB_URL` 和 `DB_USERNAME`，将已有密码写入 `secrets/db_password`，保持父目录 0700、文件 0444。

例如主机 MySQL 可使用 `host.docker.internal`，Compose 已配置 host-gateway。远程 MySQL 使用实际域名；示例 URL 要求 TLS，可信证书环境可使用 `sslMode=VERIFY_IDENTITY` 并提供 JVM 信任配置。不要为了订阅获取关闭 TLS 验证。

```sh
docker compose -f compose.yaml build app
docker compose -f compose.yaml up -d
docker compose -f compose.yaml ps
```

## 配套 MySQL

```sh
docker compose -f compose.yaml -f compose.mysql.yaml build app
docker compose -f compose.yaml -f compose.mysql.yaml up -d
docker compose -f compose.yaml -f compose.mysql.yaml ps
```

叠加文件改用私有 Compose 网络内的 `mysql:3306`，数据库保存在命名卷 `mysql_data`，不向主机映射端口。后续命令保持相同的项目名及 `-f` 顺序。路径按第一份 Compose 文件解析，详见 [Compose 合并规则](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/)。

## HTTPS 与初次登录

默认应用只绑定本机。把现有 HTTPS 反向代理指向 `127.0.0.1:8080`，将 `PUBLIC_BASE_URL` 设为域名，保持安全 Cookie。Nginx 的应用 location 示例：

```nginx
location / {
    client_max_body_size 16m;
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 75s;
    access_log off;
}
```

TLS 证书与 server 配置由现有代理管理。关闭此站访问日志，避免路径内的订阅 Token 出现在代理日志中；上层 CDN/负载均衡也应采用相同规则。初始密码仅在数据库首次启动时使用，直接读取本机口令文件登录，不粘贴到诊断日志。

`GET /actuator/health` 就绪时返回 200。启动需要数据库迁移成功和正确的持久密钥；就绪检查还验证内核可执行、固定资源可读且基本配置可通过真实内核。错误密钥会拒绝启动，不会重建或清空旧数据。

## 固定版本与架构

镜像基底按 `deploy/images.lock` 的官方多架构摘要固定；Mihomo 与资源校验和在 `tools/mihomo/resources.lock`。前端依赖使用锁文件，Java 依赖由固定 Spring Boot BOM 与 POM 管理。geodata 快照随仓库保存，更新需要明确修改校验和并重新验证。

```sh
docker build --platform linux/arm64 -t clash-subscription-manager:arm64 .
docker build --platform linux/amd64 -t clash-subscription-manager:amd64 .
```

构建阶段在本机架构运行 Node/Maven，最终镜像取目标架构 JRE 和 Mihomo。多架构镜像仓库发布可另行采用 [Docker 多平台构建](https://docs.docker.com/build/building/multi-platform/)，本项目不会自动推送镜像。

## 更新与回退

1. 停止应用写入，按备份文档备份数据库与独立密钥，并记录当前应用镜像 ID、源代码提交和数据库迁移版本。
2. 用新版本源码构建不同标签，修改 `APP_IMAGE` 后重新启动。不要同时运行两个版本的应用。
3. 等待健康检查成功，检查草稿、订阅下载与刷新任务。
4. 回退到旧镜像前确认数据库结构兼容。Flyway 不自动降级；有不兼容迁移时，应恢复升级前数据库备份与匹配密钥，再启动对应旧镜像。不能只回退镜像并假定新数据库可用。

自动刷新使用已发布定义；草稿编辑不会被加入自动发布。正常重启保留订阅、草稿、历史、Token 与任务配置；未结束任务通过持久租约恢复，默认租约 300 秒，重启后可能等待该期限。历史保留当前版本及最近 100 个版本，清理只移除不再被引用的历史数据。

## 本地开发与验收

本地可使用原生 Java 21 与前端 Vite。`scripts/mvn.sh` 支持 `CLASH_JAVA_HOME`；私有 Maven 网络配置可通过 `CLASH_MAVEN_SETTINGS=/path/settings.xml ./scripts/verify.sh` 传入。容器构建也支持可选 BuildKit secret `maven_settings`，不把凭据写入镜像：

```sh
docker build --secret id=maven_settings,src=/path/settings.xml -t clash-subscription-manager:local .
```

测试顺序：`scripts/verify.sh`；`scripts/acceptance.sh bundled`；`scripts/acceptance.sh external`。验收使用随机密码、独立 Compose 项目、合成节点与受控上游；只给该测试来源设置精确地址 allowlist，测试服务不出现在生产 Compose。脚本默认清理测试容器和卷，日志及备份留在被 Git 忽略的 `.cache/acceptance/`。需要排错时可设置 `KEEP_ACCEPTANCE_STACK=1`，之后仅删除相应 `clash-accept-*` 测试项目。
