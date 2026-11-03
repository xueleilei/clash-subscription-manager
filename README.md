# Clash 订阅管理器

自托管的 Clash Verge / Mihomo Web 配置管理器。统一维护上游订阅，每套方案独立编辑代理组、规则、远程规则集和基础 YAML，并提供固定订阅链接。

- 多来源节点池，按来源与原始名称识别节点；同名参数更新保留分组，节点消失自动清理引用。
- A 三栏工作台：代理组、当前成员、统一节点池。支持跨来源多选、嵌套组、select / url-test 和空组 REJECT。
- YAML、URI 列表与 Base64 订阅；URI 支持 ss、ssr、vmess、vless、trojan、hysteria2、tuic。解析失败整批保留旧数据。
- 合法空列表由用户选择保留或清空；直接规则目标失效时改为 REJECT，保留匹配条件与顺序。
- 多套草稿与独立发布；自动刷新仅更新已发布定义，不夹带未发布编辑。
- 可视化规则、标准文本批量导入、远程规则集和带校验的基础 YAML 编辑。
- 完整配置导入为新草稿，默认保留本地节点，可后续明确关联上游。
- 手动节点支持表单、多链接 / YAML 批量预览导入、多选批量删除和高级参数维护；编辑与删除经相关方案手动发布后生效。
- 单管理员登录、Session / CSRF、AES-GCM 敏感存储、可重置订阅 Token。

技术栈：Java 21 / Spring Boot / MyBatis-Plus / MySQL 8.4，Vue 3 / TypeScript / Element Plus / CodeMirror，固定 Mihomo 1.19.33 进行离线配置校验。首版单管理员、单应用实例，数据按 owner 隔离，为后续多用户扩展保留边界。

## Docker 启动

先准备配置和密钥：

```sh
cp .env.example .env
./scripts/init-secrets.sh
```

编辑 `.env` 的公开访问地址、数据库连接等；已有 MySQL 需将真实密码写入 `secrets/db_password`。管理员初始口令在 `secrets/admin_password`，只用于首次初始化。不要重新生成已有数据库的 `secrets/encryption.key`。

连接已有 MySQL：

```sh
docker compose -f compose.yaml build app
docker compose -f compose.yaml up -d
```

启动配套 MySQL 容器：

```sh
docker compose -f compose.yaml -f compose.mysql.yaml build app
docker compose -f compose.yaml -f compose.mysql.yaml up -d
```

默认仅映射到 `127.0.0.1:8080`。生产通过 HTTPS 反向代理访问，保持 `COOKIE_SECURE=true`；仅本机 HTTP 调试时改为 `false`，并调整 `PUBLIC_BASE_URL`。数据库容器不映射主机端口。

登录后添加上游并刷新 → 新建方案与代理组 → 编辑规则与基础设置 → 校验并发布 → 显示并复制订阅链接，在 Clash Verge 中添加远程配置。

## 文档与验证

- [部署、版本及升级](docs/operations/deployment.md)
- [备份与恢复](docs/operations/backup-restore.md)
- [验收结果与正式界面](docs/operations/verification.md)
- [手动节点维护](docs/operations/manual-nodes.md)
- [前端结构与本地开发](docs/development/frontend.md)
- [API 契约](docs/api/contracts.md)
- [已确认设计](docs/superpowers/specs/2026-11-03-clash-subscription-manager-design.md)

```sh
./scripts/verify.sh
./scripts/acceptance.sh bundled
./scripts/acceptance.sh external
```

验证需要 Java 21、Node 24、pnpm 11.25.0 和 Docker。验收脚本建立并删除独立测试项目；external 模式自行准备测试 MySQL 来模拟已有数据库，不会发现或连接用户的实际数据库。

首版不提供多用户注册、节点测速、历史版本一键回滚或任意第三方配置语法转换。不支持的高级内容会保留导入原文并阻止发布，待明确迁移或舍弃后重新校验。配置校验不代表节点可达，也不测试远程规则集下载。
