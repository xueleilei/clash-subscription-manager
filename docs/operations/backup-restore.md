# 备份与恢复

数据库与持久加密密钥是配对的恢复材料；仅数据库无法解密订阅地址、节点参数、草稿、已发布配置与 Token。原始导入文件也加密保存在数据库中。受控 Mihomo 和 geodata 已包含在对应镜像及仓库，保留镜像标签/ID、源码提交和配置即可重现。

## 备份

先停止 app，阻止编辑与自动刷新；数据库保持运行。以下以固定项目名 `clash-manager`、配套 MySQL 为例，所有命令需与你实际启动时的项目名一致：

```sh
docker compose -p clash-manager -f compose.yaml -f compose.mysql.yaml stop app
```

在受保护目录建立 MySQL 客户端配置 `client.cnf`，权限 0600。内容为 `[client]` 段下的 `host`、`user`、`password`；配套数据库的 host 为 `mysql`，已有 MySQL 使用实际地址。不要在命令行参数中传密码，也不要复用应用的 JDBC URL 作为客户端 host。

```sh
./scripts/backup.sh \
  --database clashsub \
  --connection /protected/client.cnf \
  --network clash-manager_default \
  --output /protected/backups/clashsub-2026-11-03
```

已有 MySQL 若可从普通 Docker 网络访问，可省略 `--network`；也可显式指定现有网络。脚本只备份指定库，拒绝覆盖已有备份，输出压缩 SQL、数据库名和 SHA-256。账号需要该数据库的导出权限；如果环境收紧了事件/例程权限，应给予相应备份账号权限。

将 `secrets/encryption.key` 另存到独立受保护位置，保留原始 32 字节，不做编码或换行转换。记录应用镜像 ID、迁移版本、`.env`、数据库连接配置与管理员口令的保管位置。数据库备份目录不包含密钥。

备份完成后重新启动 app：

```sh
docker compose -p clash-manager -f compose.yaml -f compose.mysql.yaml up -d app
```

不要对生产运行 `down --volumes`，那会删除数据库卷。验收脚本只对自己新建的测试项目执行此动作。

## 恢复到新空库

创建专用空数据库与账号，先启动数据库，保持 app 停止。目标库名必须与备份记录一致。使用目标数据库的 `client.cnf`：

```sh
./scripts/restore.sh \
  --database clashsub \
  --connection /protected/restore-client.cnf \
  --network clash-manager_default \
  --input /protected/backups/clashsub-2026-11-03
```

脚本先检查输入完整性、校验和、目标库名和目标库为空；不会先删表或覆盖非空库。SQL 导入失败时停止，需重新准备干净目标后排查恢复，不在半恢复状态启动应用。只使用受信的本项目数据库备份。

恢复匹配的 `encryption.key`，配置 `APP_ENCRYPTION_KEY_FILE` 和数据库连接，启动与备份兼容的应用镜像。原管理员密码由备份里的 bcrypt 哈希决定；设置新 `ADMIN_PASSWORD_FILE` 不会覆盖现有账号。缺失密钥或使用另一把密钥时启动明确失败。

恢复后检查：

- `/actuator/health` 返回 200，并能使用原账号登录。
- 原订阅链接可下载，输出与备份前摘要一致。
- 草稿、代理组、规则、节点连接参数和订阅刷新设置保持一致。
- 等待未结束任务的持久租约恢复，避免人为重复提交清空动作。

## 已覆盖的演练

`scripts/acceptance.sh bundled|external` 会先运行真实浏览器/接口流程，再停止应用、备份数据库、单独复制密钥、删除测试栈与卷、重建空数据库、恢复并启动。它比较 Token、发布 YAML、草稿、节点参数和刷新配置的摘要，并检查非空库拒绝恢复、错误/缺失密钥拒绝启动。external 使用新建测试 MySQL 模拟已有数据库，从不连接用户的实际实例。
