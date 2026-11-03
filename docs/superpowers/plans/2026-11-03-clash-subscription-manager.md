# Clash Subscription Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建可 Docker 自托管的个人订阅管理器，将多个来源的节点编排为独立配置，安全刷新并向 Clash Verge 提供稳定订阅链接。

**Architecture:** Vue 单页应用＋Spring Boot 模块化单体＋MySQL。节点池使用关系记录，草稿与发布使用版本化类型文档；纯编译器生成配置，经固定 Mihomo 校验后保存不可变产物。所有人工写入和自动同步通过版本检查提交，订阅请求只读取发布产物。

**Tech Stack:** Java 21、Spring Boot 4.1.2、Maven、MyBatis-Plus 3.5.18、Flyway、MySQL 8.4；Vue 3、TypeScript、Vite、Element Plus、CodeMirror；JUnit、Testcontainers、Vitest、Playwright；Mihomo 1.19.33。

**Spec:** [已确认设计](../specs/2026-11-03-clash-subscription-manager-design.md)，用户于 2026-11-03 确认。本文是执行计划，勾选项目前均未完成；未安装生产依赖、运行正式应用或宣称测试通过。

## Global Constraints

- “第一版一个管理员；数据模型预留用户归属，后续扩展各用户独立的数据”。
- “第一版支持多套配置方案；上游订阅和节点统一管理，分组、规则、规则集、基础配置、链接按方案独立”。
- “同一订阅来源＋上游原始节点名称；参数改变保留分组，改名视为旧节点消失和新节点出现”。
- “网络、认证、解析或部分节点解析失败：整体保留上次成功数据及组引用，显示具体错误”。
- “提供‘保留旧数据’和‘清空节点’两个选择；未选择前保留，用户可以选择不清空”。
- “保留代理组和规则引用，默认拒绝连接 REJECT，并提示补充成员”。
- “保留匹配条件和顺序，将目标改为 REJECT，记录变更并提示重新选择节点或代理组”。
- “第一版支持 select、url-test、组间引用，禁止循环引用”。
- “单个手动刷新、全部手动刷新、按订阅独立设置定时刷新；默认 6 小时”。
- “编辑草稿→校验并发布；后台同步节点可以更新已发布配置，但不能夹带未发布的人工编辑”。
- “MySQL，同时支持连接现有实例和 Docker Compose 启动数据库容器”。
- “A 三栏工作台：左侧代理组、中间当前成员、右侧统一节点池”。
- “生产依赖不使用浮动 SNAPSHOT 或 latest”；精确版本、锁文件和镜像摘要进入版本库。
- 原始 `全部节点合并.yaml` 含私有凭据，保持忽略；生产代码重新实现，不复制一次性原型的解析和模拟发布逻辑。

## Review Focus

1. 名称中的 emoji、大小写、组合 Unicode、尾随空格和同源重名：精确区分身份，冲突整体失败；任务 2、4。
2. 压缩炸弹、YAML 重复键/自定义标签、重定向到私网、DNS 重绑定：受限读取，失败保留数据；任务 4、6。
3. 旧空结果确认与后台恢复交错：过期确认不能清掉新节点；任务 11。
4. 刷新期间另建方案、关联节点、保存草稿或另一个来源提交：不能漏清理、丢更新或泄露草稿；任务 10、11。
5. 未发布本地编辑遇到过期 HTTP 响应、切换方案和会话过期：不能串方案或静默丢失输入；任务 14、16。

---

## 执行约定与版本基线

当前目录没有 Git 仓库或正式应用，仅有设计、忽略的私有 YAML 和一次性原型。执行时按选定执行技能处理 Git 初始化/隔离工作区；先核实忽略规则，只提交任务列出的文件，不使用 `git add .`。每个任务通过其检查后做一次描述交付行为的本地提交；不创建远程仓库或推送。

本机只读检查：已安装 Java 21.0.12.1，但默认 Java/Maven 运行于 Java 8；Maven 3.9.9、Node 24.19.0、pnpm 11.25.0 可用；Docker CLI 27.5.1 存在，但 daemon 当前未运行。使用项目脚本选择 Java 21，不修改全局 jenv。任务执行时启动/核实 Docker，数据库测试不可因 daemon 不可用而假报通过。

| 用途 | 锁定基线 | 依据与处理 |
| --- | --- | --- |
| Java / Maven | Java 21；Wrapper 3.9.9 | Java 编译 release=21；Wrapper 校验下载 SHA-256 |
| Spring Boot | 4.1.2 | [官方要求](https://docs.spring.io/spring-boot/system-requirements.html)；Security、Jackson、Flyway、HTTP Client、JUnit、Testcontainers 使用该 BOM 管理的精确版本 |
| MyBatis-Plus | 3.5.18，`mybatis-plus-spring-boot4-starter` | [官方安装说明](https://baomidou.com/en/getting-started/install/)；不混用 Boot 3 starter |
| MySQL | 8.4.13 | [官方发布记录](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-13.html)；现有 MySQL 8.x 单独验证 |
| Node / pnpm | 24.19.0 / 11.25.0 | 与当前可用工具一致；固定 `packageManager` 和锁文件 |
| Vue / Router / Element Plus | 3.5.44 / 5.4.0 / 2.14.7 | [Vue](https://registry.npmjs.org/vue/3.5.44)、[Router](https://registry.npmjs.org/vue-router/5.4.0)、[Element Plus](https://registry.npmjs.org/element-plus/2.14.7) 官方包元数据 |
| TypeScript / vue-tsc | 5.9.3 / 3.3.14 | [TS](https://registry.npmjs.org/typescript/5.9.3)、[vue-tsc](https://registry.npmjs.org/vue-tsc/3.3.14)；暂用已核实的 TS 5 工具链，避免默认引入 TS 7 的编译器迁移 |
| Vite / Vue plugin | 8.3.5 / 6.0.10 | [Vite](https://registry.npmjs.org/vite/8.3.5)、[插件](https://registry.npmjs.org/@vitejs/plugin-vue/6.0.10)；Node 与 Vue peer 范围匹配，实际构建在任务 14 验证 |
| 前端测试 | Vitest 5.1.0、Vue Test Utils 2.5.1、Playwright 1.64.1 | [Vitest](https://registry.npmjs.org/vitest/5.1.0)、[VTU](https://registry.npmjs.org/@vue/test-utils/2.5.1)、[Playwright](https://registry.npmjs.org/@playwright/test/1.64.1)；组件使用 browser mode，浏览器依赖同版锁定 |
| YAML 编辑 | CodeMirror 6.0.2、lang-yaml 6.1.3 | [编辑器](https://registry.npmjs.org/codemirror/6.0.2)、[语言包](https://registry.npmjs.org/@codemirror/lang-yaml/6.1.3)；服务端校验为最终依据 |
| Mihomo | 1.19.33 | [正式发行](https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.33)；按平台下载并校验官方摘要，不用 Alpha |

这张表确认的是发布版本及声明的要求，不等于集成测试已通过。镜像与校验数据的实际摘要在下载时写入 `deploy/images.lock` 和 `tools/mihomo/resources.lock`；不得编造摘要。BOM 外的辅助依赖仅在所属任务需要时加入并保存精确锁定值。补丁级兼容调整须记录原因及通过的验证，不改变已确认产品范围。

统一命令：根目录 `./scripts/mvn.sh` 调用 `backend/mvnw`；单元测试 `./scripts/mvn.sh -Dtest=ClassName test`，集成测试 `./scripts/mvn.sh -Dit.test=ClassName verify`（Failsafe）；前端 `pnpm --dir frontend test --run <file>`、`pnpm --dir frontend typecheck`、`pnpm --dir frontend build`。初次缺少工程/类导致的失败用于红灯，环境故障单独说明。测试片段固定关键断言；普通数据准备和导入语句由对应测试文件补齐。

## 文件与模块地图

| 目录 | 责任 |
| --- | --- |
| `backend/src/main/java/dev/clashsub/auth/` | 登录会话、管理员初始化、当前用户归属 |
| `backend/src/main/java/dev/clashsub/common/` | 诊断/分页契约、加密、时钟和版本事务工具 |
| `backend/src/main/java/dev/clashsub/node/` | 节点身份、完整连接参数、节点池查询 |
| `backend/src/main/java/dev/clashsub/source/` | 来源 CRUD、受控 HTTP 抓取、来源/快照存储 |
| `backend/src/main/java/dev/clashsub/parse/` | YAML、URI、Base64 输入适配和协议检查 |
| `backend/src/main/java/dev/clashsub/profile/` | 草稿类型文档、组与引用、方案生命周期 |
| `backend/src/main/java/dev/clashsub/rule/` | 规则 AST、文本导入、规则集和基础 YAML |
| `backend/src/main/java/dev/clashsub/compile/` | 输出名称、依赖闭包、YAML 编译、内核检查 |
| `backend/src/main/java/dev/clashsub/release/` | 发布、产物读取、订阅 Token |
| `backend/src/main/java/dev/clashsub/sync/` | 差异、原子刷新、空结果决定、持久任务 |
| `backend/src/main/java/dev/clashsub/importer/` | 完整导入、本地节点、绑定预览 |
| `backend/src/main/resources/db/migration/` | Flyway 版本迁移；Mapper/DO 与所属业务目录相邻 |
| `backend/src/test/java/dev/clashsub/` | 同业务目录组织单元及 MySQL 集成测试 |
| `frontend/src/features/` | auth、sources、workspace、rules、settings、import、publish；视图组合组件，状态放 composable |
| `frontend/src/api/`、`frontend/src/types/` | 请求、会话/版本冲突处理和共享类型 |
| `frontend/e2e/`、`tests/fixtures/` | 浏览器完整流程与人工构造的脱敏输入 |
| `tools/mihomo/`、`deploy/`、`scripts/`、`docs/operations/` | 内核/资源锁定、两种部署、验证命令、运维文档 |

不引入 Redis、MQ、微服务或多个后端 Maven 子模块。Java 业务 DTO 有明确类型；只有经过模式检查的 Mihomo 扩展字段允许使用 JSON 对象保存，不能用无约束 Map 代替整个业务模型。

## 跨任务契约

- 所有实体 ID 使用 UUID，HTTP 中是字符串；Java 版本号用 long，HTTP 中用十进制字符串；时间为 UTC ISO-8601，界面按本地时区展示。分页默认 50、上限 200。
- `Diagnostic(severity, code, path, line, column, message)` 不包含敏感值；`ValidationReport(errors, warnings)`；诊断位置能定位到来源节点或方案字段。
- `TargetRef(kind, id, unresolvedName)`：kind 为 NODE/GROUP/DIRECT/REJECT/UNRESOLVED；NODE/GROUP 必须有 id，内置策略无 id，未解析引用保留原名称并阻止发布。
- `NodeSpec(id, sourceId, originalName, protocol, connection, dialerRef)`：connection 为完整、经协议模式检查的 JSON 对象；本地节点 sourceId 为空；dialerRef 单独结构化，避免输出改名时丢失链式依赖。
- `ProfileDocument(schemaVersion=1, baseYaml, groups, rules, providers, importIssues)`；有序集合中的组、规则和规则集均有稳定 UUID。`GroupDefinition(id, name, type, members, options)` 的 type 保存原字符串，合法发布仅 select/url-test；`RuleDefinition(id, expression, target, modifiers, rawText, issues)` 的 expression 包含类型和完整匹配表达式，不含目标/尾部参数；`ProviderDefinition(id, name, type, url, behavior, format, interval, options)`。未知类型/原文在导入草稿中保留并附阻断诊断。
- `DraftSnapshot(profileId, version, document)` 与 `ReleaseSnapshot(id, profileId, version, definition, sourceVersions, yaml, createdAt, reason)` 分离；`ProfileState(profileId, profileVersion, draft, published)` 中 published 可为空。`NodeCatalog` 是以 UUID 为 key 的 NodeSpec 集合；`ReferenceIndex` 提供当前方案组/规则集/可用节点的名称与内部 ID 映射，不跨方案解析组。
- `OwnerSnapshot(ownerId, dataRevision, sources, nodes, profiles)` 是在短只读事务中取得的一致性读视图，其中 sources 包含 configVersion/有效 snapshotVersion，profiles 为 ProfileState。`app_user.data_revision` 在任何影响编译输入的写入中递增，补足“计算期间新增一个方案”的竞态；事务先 CAS 此行，再按稳定 ID 顺序更新来源/方案，并验证各对象版本。失败整体回滚，最多重新计算 3 次，仍冲突返回 409。
- `CompiledProfile(yaml, digest, usedNodeIds, warnings, sourceVersions)` 是纯编译结果；`PreparedRelease` 是已通过内核检查、尚未提交的结果；已保存 YAML 不在下载请求中重新生成。
- `ApiResult<T>(code, message, data, traceId)`；成功 code=00000，HTTP 400=A0001，401=A0002，409=A0003，404=A0004，422=A0005，内部错误=B0001，上游失败=C0001。字段详情用 Diagnostic；跨用户对象统一 404，权限/CSRF 拒绝 403。下载成功直接 YAML，失败非 200，不能伪装空配置。
- API 根 `/api/v1`；管理请求用 Session＋CSRF；`/sub/{token}` 仅 Token 鉴权。浏览器普通 GET 不修改业务数据。

类型归属：任务 1 定义 ApiResult/当前用户及异常 HTTP 映射；任务 2 定义上面的纯 record/enum、NodeKey、EncryptedValue、ValidationReport 和 NodeCatalog/ReferenceIndex；任务 4 定义 ParseResult/ParsedNode/InputFormat；任务 6、7、8、10—13 各自定义其 Interfaces 中的命令、View 和结果 DTO，字段按契约与相应任务行为建立，同名类型只定义一次。DTO/DO/Mapper 按所属业务目录拆文件，Controller 不直接操作 Mapper。

## Task 1: 可运行的后端与管理员登录

**Files:** 创建 `backend/pom.xml`、`backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/maven-wrapper.properties`、`scripts/mvn.sh`、`backend/src/main/java/dev/clashsub/ClashSubApplication.java`、`backend/src/main/java/dev/clashsub/auth/SecurityConfig.java`、`backend/src/main/java/dev/clashsub/auth/AdminBootstrap.java`、`backend/src/main/java/dev/clashsub/auth/SessionController.java`、`backend/src/main/java/dev/clashsub/auth/CurrentUser.java`、`backend/src/main/java/dev/clashsub/common/ApiResult.java`、`backend/src/main/java/dev/clashsub/common/ApiExceptionHandler.java`、`backend/src/main/resources/application.yml`、`backend/src/main/resources/db/migration/V1__admin.sql`、`backend/src/test/java/dev/clashsub/auth/AuthenticationIT.java`；修改 `.gitignore`。

**Interfaces:** 消费环境变量 ADMIN_USERNAME、ADMIN_PASSWORD_FILE 和数据库连接；提供 `CurrentUser.requireId(): UUID`，GET `/api/v1/csrf`、GET/POST/DELETE `/api/v1/session`。POST 使用表单 username/password，成功和失败均为 JSON，不跳转 HTML；登录后轮换 Session 并刷新 CSRF。

- [x] 写 AuthenticationIT：匿名 GET session=401；有 CSRF 的正确登录=200；错误密码=401；无 CSRF 的修改=403；注销后=401；第二次启动不得重置既有管理员密码。检查数据库只有密码散列。
- [x] 运行 `./scripts/mvn.sh -Dit.test=AuthenticationIT verify`，记录契约尚未实现的红灯。
- [x] 在上述文件实现初始化、SecurityFilterChain、健康检查和 MySQL/Flyway 测试连接。V1 创建 app_user（id、username、password_hash、data_revision、create_time、update_time）。显式使用 Boot 4 starter 和 MyBatis-Plus Boot 4 starter；不添加注册接口。Session Cookie 为 HttpOnly，生产 Secure，SameSite=Lax。
- [x] 同命令通过，并运行 `./scripts/mvn.sh dependency:tree` 检查 starter/Jackson/MyBatis 版本混用。项目脚本以 Java 21 运行，Docker 缺失是环境失败，不跳过 MySQL 测试。
- [x] 提交任务文件：`feat: add authenticated application foundation`。

## Task 2: 稳定节点身份、类型模型和加密边界

**Files:** 创建 `backend/src/main/java/dev/clashsub/node/NodeSpec.java`、`backend/src/main/java/dev/clashsub/node/NodeIdentity.java`、`backend/src/main/java/dev/clashsub/profile/ProfileDocument.java`、`backend/src/main/java/dev/clashsub/profile/TargetRef.java`、`backend/src/main/java/dev/clashsub/common/Diagnostic.java`、`backend/src/main/java/dev/clashsub/common/SecretCipher.java`、`backend/src/main/java/dev/clashsub/common/OwnerRevisionGuard.java`、`backend/src/test/java/dev/clashsub/node/NodeIdentityTest.java`、`backend/src/test/java/dev/clashsub/common/SecretCipherTest.java`。契约中其余 record/enum 按类型同目录独立文件创建。

**Interfaces:** 提供 `NodeIdentity.key(UUID sourceId, String exactName): NodeKey`；`SecretCipher.encrypt(byte[] plaintext, String context): EncryptedValue`、`decrypt(EncryptedValue, String context): byte[]`；`OwnerRevisionGuard.compareAndIncrement(UUID ownerId, long expected): void`（必须在调用方事务中，冲突抛出版本异常）。

- [x] 写身份/加密红灯：相同来源名称同 key；不同来源、大小写、组合 Unicode、尾随空格不同 key；同文两次加密密文不同，篡改/context 变更解密失败。
  ~~~java
  assertThat(key(sourceA, "香港")).isNotEqualTo(key(sourceA, "香港 "));
  assertThat(key(sourceA, "é")).isNotEqualTo(key(sourceA, "e\u0301"));
  assertThat(cipher.decrypt(cipher.encrypt(secret, "node:1"), "node:1")).isEqualTo(secret);
  ~~~
- [x] 运行 `./scripts/mvn.sh -Dtest=NodeIdentityTest,SecretCipherTest test`，确认失败。
- [x] 实现 SHA-256 名称索引＋原 UTF-8 值核验，不 trim/大小写折叠/NFC；AES-256-GCM、随机 96-bit nonce、AAD context、带 keyId 的密文版本；APP_ENCRYPTION_KEY_FILE 提供 32-byte 密钥，不自动生成后丢失。加密覆盖节点、来源请求设置、草稿、快照、发布 YAML 与可恢复 Token。
- [x] 同命令通过；补充错误密钥启动/读取失败的断言，禁止敏感 record 自动 toString 泄露连接配置。
- [x] 提交：`feat: define stable identities and encrypted domain storage`。

## Task 3: 先验证 Mihomo 检查器可安全封装

**Files:** 创建 `tools/mihomo/fetch.sh`、`tools/mihomo/resources.lock`、`backend/src/main/java/dev/clashsub/parse/SafeYaml.java`、`backend/src/main/java/dev/clashsub/compile/MihomoValidator.java`、`backend/src/main/java/dev/clashsub/compile/ValidationCopyFactory.java`、`backend/src/test/java/dev/clashsub/compile/MihomoValidatorIT.java`、`tests/fixtures/mihomo/minimal.yaml`、`docs/operations/validation.md`。

**Interfaces:** `MihomoValidator.validate(String outputYaml): ValidationReport`；`ValidationCopyFactory.create(String outputYaml, Path workDir): ValidationInput`（校验副本＋资源清单）；`SafeYaml.load(String): YamlDocument`、`dump(YamlDocument): String`。输入原文始终保持不变；任务 4、8、9 复用安全 YAML 边界，任务 10、11 消费检查器。

- [x] 写真实内核红灯：有效配置成功；错误协议参数/循环/规则目标缺失失败；空 select/url-test 只含 REJECT 可通过；超时可终止；URL/路径诱饵不会被读取或请求，原配置摘要不变。
- [x] 运行 `./scripts/mvn.sh -Dit.test=MihomoValidatorIT verify`，确认尚缺检查器。
- [x] 建立 SafeYaml 的安全构造/序列化，禁止重复 key/自定义标签，深度 50、别名引用 50、文本 10 MiB；YamlDocument 是 JSON 兼容的映射/序列/标量树，解析错误带行列。该组件先于校验副本实现，任务 4 扩展其坏输入覆盖。
- [x] 固定 1.19.33 及 SHA-256；清理子进程继承的 CLASH_* 和代理环境，参数数组执行 `mihomo -t -d <独立目录> -f -`，从 stdin 输入副本，避开命令行明文和默认配置目录初始化。最多并行 2 个、单次 10 秒、输出上限 64 KiB，结束清理临时目录。
- [x] 基于该 tag 的解析路径建立允许/替换表：规则提供者改为受控同 behavior 测试资源；GeoSite/GeoIP 使用固定资源；所有文件/证书/UI/脚本类字段禁止访问宿主路径，不能安全验证的字段给阻断诊断。schema/URL/行为格式先由管理器校验，副本替换不能掩盖这些错误。用无网络容器及请求诱饵验证资源完整性；保留产物与副本的差异报告，不宣称已验证远端内容。
- [x] 同命令全部通过，写明最低测试内核与验证限制。若副作用无法控制，先解决封装再继续发布链路，禁止用假返回代替。
- [x] 提交：`feat: validate generated configs with pinned mihomo`。

## Task 4: 严格解析 Clash/Mihomo YAML

**Files:** 创建 `backend/src/main/java/dev/clashsub/parse/SubscriptionParser.java`、`backend/src/main/java/dev/clashsub/parse/ClashYamlParser.java`、`backend/src/main/java/dev/clashsub/parse/ProtocolFieldValidator.java`、`backend/src/test/java/dev/clashsub/parse/ClashYamlParserTest.java`、`tests/fixtures/subscriptions/clash.yaml`、`docs/operations/protocol-support.md`；复用任务 3 的 `backend/src/main/java/dev/clashsub/parse/SafeYaml.java`。

**Interfaces:** `SubscriptionParser.parse(byte[] body, InputFormat format): ParseResult`；ParseResult 含全部 ParsedNode（原名、协议、完整 connection、原始引用）、诊断、`validEmpty`，有 error 时不暴露可提交的部分成功列表。`SafeYaml.load(String): YamlDocument`；只解析来源中的 proxies，完整配置导入另见任务 13。

- [ ] 写覆盖 ss/vless/hysteria2/tuic 的合法和非法夹具；保留 TLS/Reality/传输字段；断言重复名称、重复 YAML key、自定义标签、超深锚点、任意一个坏节点导致整个结果不可提交。
  ~~~java
  assertThat(parser.parse("proxies: []".getBytes(UTF_8), CLASH_YAML).validEmpty()).isTrue();
  assertThat(parser.parse(new byte[0], AUTO).hasErrors()).isTrue();
  assertThat(parseFixture("one-bad-node.yaml").committable()).isFalse();
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dtest=ClashYamlParserTest test`，确认红灯。
- [ ] 复用任务 3 的 YAML 限制，增加节点 20,000 上限。按固定内核字段模式检查参数，保留有明确模式的扩展；未知影响连接行为的字段报错。provider-only 输入报不支持，不当空订阅。ParseResult 中 committable() 等价于 errors 为空，空 body/纯空白/无可识别格式不算 validEmpty。
- [ ] 同命令通过；用任务 3 对协议夹具构造的受控节点集合做真实检查，记录协议矩阵。地区推断仅依据可识别名称标记，未识别为“未知”。
- [ ] 提交：`feat: parse clash subscriptions without partial updates`。

## Task 5: URI 与 Base64 订阅适配

**Files:** 创建 `backend/src/main/java/dev/clashsub/parse/UriSubscriptionParser.java`、`backend/src/main/java/dev/clashsub/parse/Base64SubscriptionDecoder.java`、`backend/src/main/java/dev/clashsub/parse/uri/ShadowsocksAdapter.java`、`ShadowsocksRAdapter.java`、`VmessAdapter.java`、`VlessAdapter.java`、`TrojanAdapter.java`、`Hysteria2Adapter.java`、`TuicAdapter.java`（后六个同一 uri 目录）；测试 `backend/src/test/java/dev/clashsub/parse/UriSubscriptionParserTest.java`、`tests/fixtures/subscriptions/uris.txt`。

**Interfaces:** 消费任务 4 的 ParseResult/字段验证；`UriNodeAdapter.supports(String scheme): boolean`、`parse(String uri, int line): ParsedNodeResult`。AUTO 先识别 YAML mapping/节点 scheme，再尝试严格 Base64；拒绝多重任意递归解码。

- [ ] 写每种协议的成功/失败参数用例，含标准/URL-safe Base64、缺 padding、IPv6 authority、转义密码/名称、VMess JSON、VLESS TLS/Reality/WS/gRPC、TUIC UUID、hy2 别名。未知 scheme 或一个未知连接参数整体失败。
  ~~~java
  assertThat(parseFixture("same-list.txt").nodes()).isEqualTo(parseFixture("same-list.base64").nodes());
  assertThat(parseFixture("mixed-valid-invalid.txt").committable()).isFalse();
  assertThat(parseFixture("nameless-twice.txt").errors()).extracting(Diagnostic::code).contains("DUPLICATE_NODE_NAME");
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dtest=UriSubscriptionParserTest test`，确认失败。
- [ ] 在各 adapter 依据协议维护方规范实现字段映射；无名链接命名为协议/规范 host/port，不放凭据；相同合成名冲突拒绝。协议/传输不支持则具体报错，不猜测字段含义，不调用第三方转换。
- [ ] 同命令及 `./scripts/mvn.sh -Dit.test=MihomoValidatorIT verify` 通过；protocol-support.md 列出支持/拒绝的 URI 变体和原始规范链接，保证承诺的 7 类均有真实检查夹具。
- [ ] 提交：`feat: support uri and base64 subscription formats`。

## Task 6: 来源、节点池存储与受控抓取

**Files:** 创建 `backend/src/main/java/dev/clashsub/source/SourceService.java`、`SourceRepository.java`、`SourceController.java`、`UpstreamFetcher.java`、`SafeDnsResolver.java`（均在 source 目录）；`backend/src/main/java/dev/clashsub/node/NodeRepository.java`、`NodeController.java`；`backend/src/main/resources/db/migration/V2__source_and_node.sql`；`backend/src/test/java/dev/clashsub/source/UpstreamFetcherTest.java`、`SourceRepositoryIT.java`。

**Interfaces:** `SourceService.save(UUID ownerId, SourceCommand command): SourceView`（更新含 expectedVersion）；`UpstreamFetcher.fetch(SourceRequest request): FetchResult`（bytes/status/安全响应元信息）；`NodeRepository.list(UUID ownerId, NodeQuery query): Page<NodeSummary>`。V2 建 subscription_source、source_snapshot、node；唯一键 owner/source/name_hash，原名核验，连接参数密文。

- [ ] 写来源归属/默认周期/凭据加密集成测试；抓取测试覆盖 401、超时、HTML、gzip 超限、每一跳私网检查、IPv4-mapped IPv6、DNS 重绑定；跨 host 跳转不携带认证头。
  ~~~java
  assertThat(created.refreshIntervalSeconds()).isEqualTo(21_600);
  assertThat(fetchRedirectTo("http://127.0.0.1/").errorCode()).isEqualTo("UPSTREAM_ADDRESS_BLOCKED");
  assertThat(rawDatabaseRow).doesNotContain("fixture-password", "secret-query");
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dtest=UpstreamFetcherTest test` 及 `./scripts/mvn.sh -Dit.test=SourceRepositoryIT verify`，确认失败。
- [ ] 实现 GET/POST/PATCH `/api/v1/sources`、GET `/api/v1/nodes`；按名称/来源/协议/地区筛选，source view 区分最后尝试和最后成功。HTTP 限 http/https，TLS 校验，连接 5 秒、响应 30 秒、总时长 45 秒、最多 3 跳、压缩前后各 10 MiB；固定本次已检查的解析地址用于连接。代理模式使用同等限制或明确可信出站代理配置，不绕过地址检查。私网仅显式部署 allowlist。
- [ ] 两组测试通过；请求设置支持加密 header/UA/出站代理；GET `/api/v1/sources/{id}/credentials`、`/api/v1/nodes/{id}/connection` 仅归属管理员显式查看且 no-store，列表不携带秘密。编辑时“未提供秘密字段”表示保留，禁止把脱敏占位符当成新凭据。删除来源接口暂不实现破坏性行为，任务 13 完成两种处理。所有输入变更增加来源 configVersion 和 owner revision。
- [ ] 提交：`feat: persist sources and fetch upstreams with bounded requests`。

## Task 7: 方案草稿、代理组与版本化编辑

**Files:** 创建 `backend/src/main/java/dev/clashsub/profile/ProfileService.java`、`ProfileRepository.java`、`ProfileController.java`、`ReferenceValidator.java`（同 profile 目录）；`backend/src/main/resources/db/migration/V3__profile_draft.sql`；`backend/src/test/java/dev/clashsub/profile/ProfileDraftIT.java`、`ReferenceValidatorTest.java`。

**Interfaces:** `ProfileService.create(UUID ownerId, String name): DraftSnapshot`；`save(UUID ownerId, UUID profileId, long expectedVersion, ProfileDocument document): DraftSnapshot`；`ReferenceValidator.validate(ProfileDocument document, NodeCatalog catalog): ValidationReport`。GET/POST `/api/v1/profiles`，GET/PUT `/api/v1/profiles/{id}/draft`，PATCH/DELETE `/api/v1/profiles/{id}`。

- [ ] 写红灯：两方案组和规则独立，跨方案/用户 group ID 拒绝；环和悬空引用被定位；重复成员拒绝；组重命名保持 ID；旧 expectedVersion=409；保存未完成 base YAML 不改变已发布内容；删除被引用组须先替换/解除所有引用。
  ~~~java
  assertThat(validate(cycleDocument).errors()).extracting(Diagnostic::code).contains("GROUP_CYCLE");
  assertThat(saveWithOldVersion.status()).isEqualTo(409);
  assertThat(load(otherProfile).document()).isEqualTo(otherProfileBefore);
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dtest=ReferenceValidatorTest test` 和 `./scripts/mvn.sh -Dit.test=ProfileDraftIT verify`，确认红灯。
- [ ] V3 建 configuration_profile、profile_draft；save 使用明确 schemaVersion 文档、期望版本和 owner revision。类型合法但尚未完成的草稿可保存并返回诊断；跨用户引用不允许持久化。新方案 baseYaml 为 `mode: rule`，rules 为一个 `MATCH,REJECT`，组为空。元数据删除先返回影响信息，删除方案撤销订阅访问；组编辑始终通过内部 ID。
- [ ] 两组命令通过。组、规则、provider 等类型结构定义在任务 2，避免控制器之间用不同 JSON 字段表示同一概念。
- [ ] 提交：`feat: persist independent versioned profile drafts`。

## Task 8: 规则文本、远程规则集与基础 YAML 校验

**Files:** 创建 `backend/src/main/java/dev/clashsub/rule/RuleParser.java`、`RuleImportService.java`、`RuleController.java`、`ProviderValidator.java`、`BaseSettingsValidator.java`（同 rule 目录）；`backend/src/test/java/dev/clashsub/rule/RuleParserTest.java`、`RuleImportTest.java`、`BaseSettingsValidatorTest.java`。

**Interfaces:** `RuleParser.parse(String text, ReferenceIndex references): RuleParseResult`（有序 RuleDefinition＋逐行诊断）；`RuleImportService.preview(DraftSnapshot draft, String text, MatchPolicy policy): RuleImportPreview`；`ProviderValidator.validate(List<ProviderDefinition>): ValidationReport`；`BaseSettingsValidator.validate(String yaml, ReferenceIndex refs): ValidationReport`。POST `/api/v1/profiles/{id}/rule-import/preview` 不写库；应用预览走任务 7 的版本化 save。

- [ ] 写红灯：常用 9 类及 RULE-SET、no-resolve、带括号 AND/OR/NOT 和引号中的逗号；错误行不部分应用；MATCH 只能最后一个，替换需显式 policy；规则直接节点目标成为 TargetRef。
  ~~~java
  assertThat(preview.rules().getLast().expression()).isEqualTo("MATCH");
  assertThat(previewWithBadLine.applicable()).isFalse();
  assertThat(validateBase("proxies: []").errors()).extracting(Diagnostic::code).contains("MANAGED_FIELD");
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dtest=RuleParserTest,RuleImportTest,BaseSettingsValidatorTest test`，确认失败。
- [ ] 实现词法分隔与外层目标解析，AST 只作为解析工具，持久化保留原表达式、内部目标、附加参数和原文。未理解的高级表达式保留为带诊断草稿，不能通过发布。provider 支持 HTTP、domain/ipcidr/classical：yaml/text 合法，mrs 只用于内核支持的行为；interval 单位秒且为正值；引用用 provider ID，输出时转名。
- [ ] BaseSettingsValidator 拒绝 proxies/proxy-groups/rules/rule-providers/proxy-providers 托管字段；检测 sub-rules、DNS 策略等高级字段中的引用，无法安全理解/改写时阻断发布并定位。测试高级引用遇到组改名不能输出旧名。原 YAML 未修改字段保持语义。
- [ ] 同命令通过，追加含 2,124 条规则的人工生成夹具检查顺序与导入原子性，不复制私有原文。
- [ ] 提交：`feat: validate ordered rules providers and base settings`。

## Task 9: 确定性的配置编译器

**Files:** 创建 `backend/src/main/java/dev/clashsub/compile/ProfileCompiler.java`、`OutputNameAllocator.java`、`DependencyResolver.java`；`backend/src/test/java/dev/clashsub/compile/ProfileCompilerTest.java`、`ProfileCompilerIT.java`；`tests/fixtures/profiles/compiled.expected.yaml`。

**Interfaces:** `ProfileCompiler.compile(ProfileDocument definition, NodeCatalog catalog, Map<UUID, Long> sourceVersions): CompileResult`；成功含 CompiledProfile，失败含 Diagnostic。`DependencyResolver.resolve(ProfileDocument, NodeCatalog): UsedDependencies`；`OutputNameAllocator.allocate(ProfileDocument, UsedDependencies): OutputNames`。消费任务 7、8 校验；本层不访问 DB/网络。

- [ ] 写红灯：同名跨源节点同时输出；未使用节点不输出；全部定义组、嵌套组、规则直连节点、dialer-proxy 依赖纳入闭包；引用环/丢失链式依赖失败；空组 REJECT；同输入输出字节一致；基础 YAML 的其他字段保持。
  ~~~java
  assertThat(compiled.usedNodeIds()).containsExactlyInAnyOrder(nodeA, nodeB, dialerNode);
  assertThat(parsedGroup("空组").proxies()).containsExactly("REJECT");
  assertThat(compile(input).yaml()).isEqualTo(compile(input).yaml());
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dtest=ProfileCompilerTest test`，确认失败。
- [ ] 节点输出名固定为原名＋分隔符＋UUID 的无 padding base64url（不受排序/来源显示名变更影响）；组保留用户名称，名称冲突明确阻断，不任意绑定同名对象。按文档顺序输出组/规则，节点按内部 ID 稳定排序；链式引用通过 ID 改写。空组输出 `proxies: [REJECT]`；设置 `empty-fallback: REJECT`，在固定内核验证实际行为。动态 include-all/use 等与手工成员冲突的能力列为不支持诊断。
- [ ] 编译只合入管理器拥有的字段；provider path 生成方案/规则集 ID 的相对隔离路径，规则集实际下载仍由客户端处理。输出 YAML 使用安全序列化，名称中特殊字符不能注入新键/规则。
- [ ] 单元命令及 `./scripts/mvn.sh -Dit.test=ProfileCompilerIT verify` 通过；后者调用真实 Mihomo 并覆盖 select/url-test 两种空组、规则集与 geodata 配置。
- [ ] 提交：`feat: compile deterministic dependency-complete mihomo profiles`。

## Task 10: 校验发布、不可变产物与订阅 Token

**Files:** 创建 `backend/src/main/java/dev/clashsub/release/ReleaseService.java`、`ReleaseRepository.java`、`SubscriptionTokenService.java`、`SubscriptionController.java`、`PublishController.java`；`backend/src/main/java/dev/clashsub/profile/OwnerSnapshotReader.java`；`backend/src/main/resources/db/migration/V4__release_and_token.sql`；`backend/src/test/java/dev/clashsub/release/PublicationIT.java`、`SubscriptionTokenIT.java`。

**Interfaces:** `OwnerSnapshotReader.read(UUID ownerId): OwnerSnapshot`；`ReleaseService.prepare(ProfileDocument, OwnerSnapshot): PreparedRelease`；`publish(UUID ownerId, UUID profileId, long expectedDraftVersion, ProfileDocument submittedDocument): ReleaseView`；`SubscriptionTokenService.rotate(UUID ownerId, UUID profileId): SubscriptionLink`；`readPublished(String token): PublishedYaml`。

- [ ] 写 MySQL＋真实检查器红灯：发布失败产物/Token 不变；发布时提交当前编辑和创建 release 原子完成；校验期间另一来源提交导致重新计算/409；匿名管理=401，错误/旧 Token=404，未发布有效 Token=409；成功只返回此方案 YAML。
  ~~~java
  assertThat(download(oldToken).status()).isEqualTo(404);
  assertThat(download(validToken).body()).isEqualTo(savedRelease.yaml());
  assertThat(afterFailedPublish.currentReleaseId()).isEqualTo(before.currentReleaseId());
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dit.test=PublicationIT,SubscriptionTokenIT verify`，确认失败。
- [ ] V4 建 profile_release，profile 存 token hash、加密 Token、当前发布指针。Token 为 SecureRandom 32 bytes 的 base64url，散列查找，轮换使旧 Token 立即无效。prepare 在事务外编译/验证；只在短事务 CAS owner/source/draft 版本、保存完整定义/节点快照及 YAML、切换指针。
- [ ] 实现 POST `/api/v1/profiles/{id}/validate`、`/publish`、`/token/rotate`，GET `/releases`、`/subscription-link`、`/sub/{token}`；订阅下载不调用抓取或编译。敏感响应 no-store，禁止缓存旧 Token 成功结果；PUBLIC_BASE_URL 生成链接，不信任任意 Host 头。
- [ ] 扩展 ProfileRepository/ProfileController 的方案摘要：draftVersion、publishedVersion、draftDefinitionHash、publishedDefinitionHash。未保存/待发布/一致比较的是定义摘要，不能用含自动节点参数变化的 YAML 摘要代替。
- [ ] 同命令通过；保留历史定义而不是修改历史 release；加入跨用户访问所有这些接口的拒绝测试，后台日志/访问日志中无完整 Token/URL。
- [ ] 提交：`feat: publish immutable profiles and revocable subscription links`。

## Task 11: 原子刷新、删除传播和空结果决定

**Files:** 创建 `backend/src/main/java/dev/clashsub/sync/NodeDiff.java`、`RefreshService.java`、`RefreshCommitter.java`、`ReferenceReconciler.java`、`EmptyDecisionService.java`；`backend/src/main/resources/db/migration/V5__refresh_and_empty_decision.sql`；`backend/src/test/java/dev/clashsub/sync/RefreshTransactionIT.java`、`EmptyDecisionIT.java`、`RefreshRaceIT.java`。

**Interfaces:** `RefreshService.refresh(UUID ownerId, UUID sourceId, UUID attemptId): RefreshOutcome`；`ReferenceReconciler.reconcile(ProfileDocument definition, Set<UUID> removedNodeIds): ReconcileResult`；`RefreshCommitter.commit(RefreshProposal proposal): CommitResult`；`EmptyDecisionService.decide(UUID ownerId, UUID decisionId, EmptyChoice choice): DecisionResult`，choice=KEEP/CLEAR。

- [ ] 写失败保留/成功变更的红灯：改参数保留 ID/成员；改名删旧添新但不自动入组；任何节点解析/内核校验失败不提交；多个方案发布全部成功才切换；在事务中注入失败，所有指针/节点保持原状。
  ~~~java
  assertThat(after.publishedRules()).doesNotContain(unpublishedDraftRule);
  assertThat(after.directNodeRule().target()).isEqualTo(REJECT);
  assertThat(after.directNodeRule().expression()).isEqualTo(before.directNodeRule().expression());
  assertThat(after.groupMembers()).doesNotContain(removedNode);
  ~~~
- [ ] 写 EmptyDecisionIT：合法空数组只建待确认；KEEP 保持；CLEAR 清理并编译；未选择不动；重复空合并；恢复/来源配置改变后旧确认=409；清空导致任一编译失败也不部分清空。
- [ ] 写 RefreshRaceIT（RF3/RF4）：用 latch 交错而非 sleep，覆盖计算期间新增方案引用、保存草稿、规则先改目标、两个来源更新同一方案、发布同时发生。断言无漏清理/丢更新，草稿已改有效目标保留，已发布旧目标单独回退 REJECT。
- [ ] 运行 `./scripts/mvn.sh -Dit.test=RefreshTransactionIT,EmptyDecisionIT,RefreshRaceIT verify`，确认红灯。
- [ ] 实现下载/解析/全部候选节点检查在事务外，差异只对成功候选计算。先基于旧发布定义 prepare 新 release，再单独 reconcile 当前草稿；不使用草稿作为自动发布模板。节点移除作 tombstone，恢复只恢复节点数据、不恢复成员或规则目标。无内容变化记录成功尝试，不制造重复 release。
- [ ] V5 建 refresh_attempt、pending_empty_decision；绑定 configVersion、有效 snapshotVersion 和候选摘要；成功更新/来源编辑使旧确认过期。owner revision 防止扫描后新增引用，来源/方案 CAS 防止覆盖，最多 3 次重算。依赖丢失且不能安全编译（如 dialer-proxy）整次刷新失败，绝不删除链式参数来直连。
- [ ] 同命令通过，并断言网络/内核耗时阶段没有持有写事务；所有被影响的发布产物、草稿自动补丁、有效快照指针在同一提交中生效。
- [ ] 提交：`feat: refresh sources atomically without leaking draft edits`。

## Task 12: 持久调度、任务 API 与变更记录

**Files:** 创建 `backend/src/main/java/dev/clashsub/sync/RefreshScheduler.java`、`RefreshTaskRunner.java`、`RefreshController.java`、`RetentionService.java`；`backend/src/main/resources/db/migration/V6__task_scheduling.sql`；`backend/src/test/java/dev/clashsub/sync/RefreshSchedulingIT.java`、`RetentionIT.java`。

**Interfaces:** `RefreshTaskRunner.enqueue(UUID ownerId, UUID sourceId, Trigger trigger): TaskView`；`RefreshScheduler.dispatchDue(Instant now): int`；`RetentionService.clean(Instant now): RetentionResult`。POST `/api/v1/sources/{id}/refresh`、`/sources/refresh-all`；GET `/tasks/{id}`、`/sources/{id}/history`、`/empty-decisions`；POST `/empty-decisions/{id}/decision`。

- [ ] 写红灯：默认 21,600 秒；同来源重复触发返回同活跃任务；暂停只禁自动刷新；多个来源独立成败；重启过期只补一次；中断的 RUNNING 任务可恢复，不重复提交；任务结果错误全部脱敏。
  ~~~java
  assertThat(enqueue(source).id()).isEqualTo(enqueue(source).id());
  assertThat(dispatchDue(afterLongDowntime)).isEqualTo(1);
  assertThat(currentSnapshotAfterPause).isEqualTo(snapshotBeforePause);
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dit.test=RefreshSchedulingIT,RetentionIT verify`，确认红灯。
- [ ] V6 为来源记录 next_run_at、active_attempt_id、任务租约/状态；Clock 可注入。4 个 worker、队列 64、同来源串行；队列满返回已有/待调度状态，不能丢任务；运行超过总超时释放并记失败。周期可配置，最小 300 秒，默认 21,600 秒。
- [ ] 清理默认保留最近 30 天尝试及每方案 100 个历史 release，当前指针/待确认/历史 release 使用的 source snapshot 不删除；最近历史不足时也能正常运行。历史敏感载荷加密，清理可重复执行；向界面提供差异计数、REJECT 回退、空组及错误定位。
- [ ] 同命令通过；用虚拟时钟和重建应用上下文验证恢复，不以长时间等待替代断言。
- [ ] 提交：`feat: schedule recoverable refresh jobs and expose change history`。

## Task 13: 完整 YAML 导入、本地节点绑定与来源删除

**Files:** 创建 `backend/src/main/java/dev/clashsub/importer/ConfigImportService.java`、`LocalBindingService.java`、`ImportController.java`；`backend/src/main/java/dev/clashsub/source/SourceRemovalService.java`；`backend/src/main/resources/db/migration/V7__import_preview.sql`；`backend/src/test/java/dev/clashsub/importer/ConfigImportIT.java`、`LocalBindingIT.java`、`backend/src/test/java/dev/clashsub/source/SourceRemovalIT.java`。

**Interfaces:** `ConfigImportService.preview(UUID ownerId, String yaml): ImportPreview`；`confirm(UUID ownerId, UUID previewId, Map<UUID, BindingChoice> choices): DraftSnapshot`；`LocalBindingService.preview(UUID ownerId, UUID localNodeId, UUID remoteNodeId): BindingPreview`、`apply(UUID ownerId, UUID previewId): BindingResult`；`SourceRemovalService.preview(UUID ownerId, UUID sourceId): RemovalPreview`、`apply(UUID ownerId, UUID previewId, RemovalMode mode): RemovalResult`（CLEAR/KEEP_LOCAL）。

- [ ] 写红灯：节点/基础设置/22 组的嵌套引用/2,124 规则/14 provider 无丢失；按完整连接配置唯一匹配才建议关联，仍需人工确认；重名不同参数、多个来源同参数不能擅自关联；未匹配节点本地保留；不支持字段/组类型/provider 依赖留待处理草稿。
- [ ] 写绑定与删除断言：
  ~~~java
  assertThat(publishedYamlAfterBinding).isEqualTo(publishedYamlBeforeBinding);
  assertThat(affectedDraft.nodeRefs()).contains(remoteNodeId).doesNotContain(localNodeId);
  assertThat(keptLocalNode.connection()).isEqualTo(remoteConnectionBeforeRemoval);
  ~~~
- [ ] 运行 `./scripts/mvn.sh -Dit.test=ConfigImportIT,LocalBindingIT,SourceRemovalIT verify`，确认红灯。
- [ ] V7 同时建立 import_preview 与 import_batch：预览有效期 30 分钟、绑定 owner revision；确认后将原 YAML 加密保留于方案关联的导入批次，未支持内容不随预览过期消失。提交前重新核实关联对象/版本，只创建新草稿，不覆盖既有方案。按原名称映射重建组、直接规则目标、dialer-proxy 和 provider 的 ID 引用；未知类型保留原定义并列入 importIssues，必须显式处理后才能发布。提供人工构造的同规模脱敏夹具。
- [ ] 本地绑定只改变相关草稿；发布继续引用原本地节点快照直至重新发布。删除来源必须预览影响：CLEAR 复用任务 11 的原子清理/REJECT 规则；KEEP_LOCAL 将当前节点转本地并保留 ID/连接配置，更新定义中的来源归属，输出连接行为保持；两者均受版本保护。与发布/刷新竞争时重试或冲突，不复活已删除来源。
- [ ] 同命令通过；实现 POST `/api/v1/imports/preview`、`/imports/{id}/confirm`、`/nodes/{id}/binding/preview`、`/bindings/{id}/apply`、`/sources/{id}/removal/preview`、`/source-removals/{id}/apply`。
- [ ] 提交：`feat: import full configs and preserve unmatched local nodes`。

## Task 14: Vue 应用、登录和统一请求状态

**Files:** 创建 `frontend/package.json`、`frontend/pnpm-lock.yaml`、`frontend/vite.config.ts`、`frontend/tsconfig.json`、`frontend/src/main.ts`、`frontend/src/App.vue`、`frontend/src/router.ts`、`frontend/src/api/client.ts`、`frontend/src/types/domain.ts`、`frontend/src/features/auth/useSession.ts`、`frontend/src/features/auth/LoginForm.vue`、`frontend/src/layout/AppShell.vue`、`frontend/src/assets/main.css`、`frontend/src/features/auth/LoginForm.test.ts`、`frontend/src/api/client.test.ts`、`docs/api/contracts.md`。

**Interfaces:** `request<T>(path: string, options: RequestOptions): Promise<T>`；RequestOptions 包含 method/body/signal，修改请求自动使用最新 CSRF。`useSession(): {user, status, login(credentials), logout(), resume()}`；状态只存内存，同源 cookie，不存 Token 到 localStorage。domain.ts 对齐 Java JSON 契约。

- [ ] 写行为红灯：登录失败有可读信息，成功进入方案；401 触发重登而不重试写操作；403/409/422 区别展示；AbortError 不报失败；源名称含 HTML 按纯文本显示。
  ~~~ts
  expect(loginResponse.status).toBe('authenticated')
  expect(submittedRequests.filter(r => r.method === 'PUT')).toHaveLength(1)
  expect(document.querySelector('script[data-untrusted]')).toBeNull()
  ~~~
- [ ] 运行 `pnpm --dir frontend test --run src/features/auth/LoginForm.test.ts src/api/client.test.ts`，确认红灯；初次先建立必要 runner 配置。
- [ ] 锁定版本基线，采用 Composition API、`<script setup lang="ts">`。App/路由只组合功能组件；登录表单 props=busy/error、emit=submit(credentials)，AppShell 负责侧栏与方案入口。Vite 开发代理转发 API/sub，生产由 Java 提供同源静态文件；SPA fallback 不吞掉 API/sub 的 404。
- [ ] 同命令、`pnpm --dir frontend typecheck`、`pnpm --dir frontend build` 通过；导出前后端字段/版本/错误响应说明，用真实登录接口完成一次手动流程。
- [ ] 提交：`feat: add typed vue application shell and login`。

## Task 15: 上游订阅与统一节点池界面

**Files:** 创建 `frontend/src/features/sources/SourcesView.vue`、`SourceForm.vue`、`SourceList.vue`、`RefreshStatus.vue`、`EmptyDecisionDialog.vue`、`SourceRemovalDialog.vue`、`NodePoolView.vue`、`useSources.ts`、`useRefreshTasks.ts`、`SourcesView.test.ts`（均同 sources 目录）。

**Interfaces:** `useSources(): {sources, nodes, query, save, reload}`；`useRefreshTasks(): {tasks, refreshOne, refreshAll, decideEmpty, stopPolling}`。SourceForm props=source/busy、emit=submit(SourceCommand)；EmptyDecisionDialog props=decision、emit=choose('KEEP'|'CLEAR')；NodePoolView props=nodes/query、emit=query-change/reveal/bind。

- [ ] 写红灯：添加两来源独立显示；单个/全部刷新任务状态；有效空仅提供保留/清空，关闭对话不发 CLEAR；重复空不会反复弹；部分解析失败仍显示旧节点；周期暂停不清空。
  ~~~ts
  expect(emptyDialog.getByRole('button', {name: '保留旧数据'})).toBeVisible()
  expect(emptyDialog.getByRole('button', {name: '清空节点'})).toBeVisible()
  expect(calls.filter(c => c.choice === 'CLEAR')).toHaveLength(0)
  ~~~
- [ ] 运行 `pnpm --dir frontend test --run src/features/sources/SourcesView.test.ts`，确认失败。
- [ ] 实现列表、脱敏编辑/显式凭据查看、格式/周期/请求高级设置、节点搜索与筛选、历史差异和空结果待办。轮询仅在活跃任务/页面可见时运行，卸载取消；过期确认 409 提醒重新读取。删除来源展示影响方案与 CLEAR/KEEP_LOCAL 两种选择。
- [ ] 同命令和 typecheck/build 通过；任务错误定位到来源/节点/字段，不显示原始订阅响应或凭据。
- [ ] 提交：`feat: manage sources nodes and empty refresh decisions`。

## Task 16: A 三栏代理组工作台与草稿冲突恢复

**Files:** 创建 `frontend/src/features/workspace/WorkspaceView.vue`、`GroupList.vue`、`GroupMemberList.vue`、`NodePicker.vue`、`GroupEditorDialog.vue`、`DraftStatusBar.vue`、`ConflictDialog.vue`、`useProfileDraft.ts`、`useNodeSelection.ts`、`WorkspaceView.test.ts`、`useProfileDraft.test.ts`（同 workspace 目录）。

**Interfaces:** `useProfileDraft(profileId: MaybeRefOrGetter<string>): {draft, version, dirty, serverChanged, save, reload, keepLocalEdits}`；GroupList props=groups/selectedId、emit=select/create/edit；GroupMemberList props=group/nodes、emit=remove/reorder；NodePicker props=nodes/disabledIds/selectedIds、emit=toggle/add；所有写入通过 workspace 的动作修改工作副本。

- [ ] 写红灯：跨来源多选加入、同节点不同组、重复禁止、嵌套组、排序/删除/空组提示；选中节点在筛选/分页中保持，主动切换目标组清空待加入选择；来源刷新清理后不复活旧成员。
- [ ] 写 RF5 用例：A 方案请求慢于 B 时不覆盖 B；HTTP 409 保留本地输入；后台修改版本时无脏数据可重载，有脏数据只提示；会话过期重登同用户后保留内存工作副本，显式退出先提示未保存内容。
  ~~~ts
  expect(workspace.currentProfileId).toBe(profileB)
  expect(workspace.draft.rules).toContainEqual(localUnsavedRule)
  expect(workspace.serverChanged).toBe(true)
  ~~~
- [ ] 运行 `pnpm --dir frontend test --run src/features/workspace/WorkspaceView.test.ts src/features/workspace/useProfileDraft.test.ts`，确认红灯。
- [ ] 参照设计截图实现 A：左组列表、中间成员、右节点池；宽屏同时可见，窄屏按此顺序折叠。使用 ID 做 key/selection，computed 做筛选，不在模板重复排序。草稿按 owner/profile 隔离；请求序号＋AbortController 防止旧响应覆盖；只显式保存/发布提交，不自动覆盖冲突。三个状态为“未保存”“已保存待发布”“与已发布版本一致”。
- [ ] 同命令和 typecheck/build 通过；浏览器检查键盘多选、滚动、长名称、344 节点规模、空状态。正式版没有 A/B/C 切换器或模拟刷新按钮。
- [ ] 提交：`feat: build three-column proxy group workspace`。

## Task 17: 规则、规则集及基础 YAML 编辑界面

**Files:** 创建 `frontend/src/features/rules/RulesView.vue`、`RuleEditor.vue`、`RuleImportDialog.vue`、`RuleProviderEditor.vue`、`useRuleOrder.ts`、`RulesView.test.ts`；`frontend/src/features/settings/BaseYamlEditor.vue`、`BaseYamlEditor.test.ts`。

**Interfaces:** RulesView props=document/diagnostics、emit=update-rules/update-providers；RuleImportDialog emit=apply(RuleImportPreview)；`useRuleOrder(rules): {moveById, insertBeforeMatch}`；BaseYamlEditor 使用 `v-model:string` 与 diagnostics prop，编辑器实例 markRaw/shallowRef 并在卸载销毁。

- [ ] 写红灯：增加/编辑常用类型和 RULE-SET 选择；批量文本/文件预览有行号；有错整批不应用；MATCH 显式替换；筛选下移动按绝对顺序不扰动未显示规则；节点目标失效显示 REJECT 原因。
  ~~~ts
  expect(allRules.map(r => r.id)).toEqual(expectedAbsoluteOrder)
  expect(importedWithErrors.applied).toBe(false)
  expect(baseEditor.text).toBe(invalidButUnsavedYaml)
  ~~~
- [ ] 运行 `pnpm --dir frontend test --run src/features/rules/RulesView.test.ts src/features/settings/BaseYamlEditor.test.ts`，确认失败。
- [ ] 规则使用分页/虚拟表，按 ID 操作，上下移及指定绝对位置；提供标准文本导出。高级规则原文可见、不能理解的项明确标为待处理。provider 字段合法组合及时提示；CodeMirror 编辑 base YAML，服务端返回行列错误，基础设置字段冲突指向对应管理页面。
- [ ] 同命令和 typecheck/build 通过；2,124 规则的筛选、移动和撤回未应用的批量导入保持顺序，方案切换不共享规则集或 YAML。
- [ ] 提交：`feat: edit ordered rules providers and base yaml`。

## Task 18: 完整导入、绑定、校验发布与订阅链接界面

**Files:** 创建 `frontend/src/features/import/ImportWizard.vue`、`ImportPreview.vue`、`NodeBindingDialog.vue`、`ImportWizard.test.ts`；`frontend/src/features/publish/PublishPanel.vue`、`ValidationSummary.vue`、`ReleaseHistory.vue`、`SubscriptionLink.vue`、`PublishPanel.test.ts`。

**Interfaces:** ImportWizard 步骤 read/preview/resolve/createDraft，调用任务 13；NodeBindingDialog props=localNode、emit=applied；PublishPanel props=draft/version/dirty、emit=published(ReleaseView)/conflict；SubscriptionLink 仅管理员显式显示/复制/重置链接。

- [ ] 写红灯：实际文件导入；歧义匹配可保留本地；本地关联显示受影响草稿且不声称立即发布；有阻断诊断不可发布；空组/REJECT 回退可警告后发布；发布失败仍显示上一成功版本；重置提示旧链接失效。
  ~~~ts
  expect(importResult.profileId).not.toBe(existingProfileId)
  expect(publishPanel.currentVersion).toBe(previousVersion)
  expect(publishPanel.getByText('旧订阅链接将立即失效')).toBeVisible()
  ~~~
- [ ] 运行 `pnpm --dir frontend test --run src/features/import/ImportWizard.test.ts src/features/publish/PublishPanel.test.ts`，确认红灯。
- [ ] 实现校验摘要、输出节点数、差异、YAML 预览、历史原因和固定订阅 URL；“发布”一次提交当前工作副本＋expectedVersion。预览 YAML 和复制属于管理员动作，不能把敏感内容放 URL/浏览器持久缓存；预览中解释远程规则集/节点可达性未测试。
- [ ] 同命令和 typecheck/build 通过；结合实际后端完成“导入→保留本地→编组→发布→复制链接→绑定→再次发布”的完整操作，下载内容与发布预览一致。
- [ ] 提交：`feat: import publish and share configuration subscriptions`。

## Task 19: Docker 交付、完整流程和恢复演练

**Files:** 创建 `Dockerfile`、`.dockerignore`、`compose.yaml`、`compose.mysql.yaml`、`.env.example`、`deploy/images.lock`、`deploy/compose.acceptance.yaml`、`scripts/verify.sh`、`scripts/acceptance.sh`、`scripts/backup.sh`、`scripts/restore.sh`、`frontend/playwright.config.ts`、`frontend/e2e/subscription-lifecycle.spec.ts`、`frontend/e2e/import-publish.spec.ts`、`tests/upstream-fixture/server.mjs`、`docs/operations/deployment.md`、`docs/operations/backup-restore.md`、`README.md`；修改 `backend/pom.xml` 静态资源打包及 `.gitignore`。

**Interfaces:** 单镜像包含 Vue 产物、Java 应用、固定内核和受控验证资源；`compose.yaml` 连接现有 MySQL，叠加 `compose.mysql.yaml` 启动私有网络数据库/持久卷。`scripts/acceptance.sh external|bundled` 在独立测试项目执行部署和完整流程；backup/restore 明确目标数据库及输入目录，不操作未指定实例。

- [ ] 写真实端到端红灯：管理员登录→添加两个可控 HTTP 来源→建两个方案→跨源分组/规则/provider/YAML→发布并读取真实订阅；修改上游触发参数更新/节点消失，下载内容同步、草稿规则不泄露；部分失败保持、空结果保留/清空、旧确认失效、Token 轮换。第二套覆盖完整导入、本地绑定、重启恢复。
- [ ] 运行 `./scripts/acceptance.sh bundled`，记录交付链路尚缺的红灯。fixture 服务仅测试 Compose 使用，不提供生产“演示后门”，访问 allowlist 仅授权此测试来源。
- [ ] 多阶段构建：Node/pnpm、Maven/Java 21、非 root JRE 21 运行；镜像摘要及 Mihomo 平台校验和锁定，支持 linux/amd64 和 linux/arm64 构建。无 Docker socket/TUN/NET_ADMIN，临时目录受限，连接数据库和上游所需网络正常；readiness 校验迁移及必要资源。生产只运行一个应用实例。
- [ ] 实现两种启动命令：`docker compose -f compose.yaml up -d` 与 `docker compose -f compose.yaml -f compose.mysql.yaml up -d`。说明 PUBLIC_BASE_URL、APP_BIND_ADDRESS（默认 127.0.0.1）、APP_PORT（默认 8080）、DB 凭据、管理员初始化、加密密钥和 HTTPS 反向代理；不打印 secret。数据库容器不映射公网端口，现有实例使用专用账号/库。
- [ ] 在隔离测试库跑备份→删除测试栈→新栈恢复；数据库与密钥分别备份，恢复后 Token/节点参数/任务配置不变；缺失/错误密钥明确失败。更新、回退镜像和数据库迁移限制写入文档；无需实现历史配置一键回滚。
- [ ] 运行 `./scripts/verify.sh`（全部后端单元/真实 MySQL/内核检查＋前端测试/typecheck/build），再分别 `./scripts/acceptance.sh bundled`、`./scripts/acceptance.sh external`；external 使用独立测试 MySQL 模拟已有实例，不宣称已接入用户的实际数据库。检查无人为 skip、没有凭据/Token 的日志泄漏。
- [ ] 用浏览器复核正式 A 布局和完整业务流程，保存脱敏截图/测试结果；记录测试过的应用、MySQL、Mihomo、平台架构版本。仅提供部署产物和命令，不自动部署用户服务器。
- [ ] 提交：`feat: deliver docker deployment and verified subscription workflows`。

## 覆盖核对与交付边界

| 已确认设计 | 执行任务与证据 |
| --- | --- |
| 单管理员、预留归属、Session/CSRF、敏感存储 | 1、2、6、7、10；真实 MySQL 归属及加密测试 |
| 多订阅、精确身份、完整解析、三种输入格式 | 2、4、5、6；协议/坏输入及真实内核夹具 |
| 多方案、手工组、select/url-test、循环/空组拒绝 | 7、9、16；纯编译＋Mihomo＋浏览器 |
| 规则文本/规则集/基础 YAML | 8、9、17；复杂表达式、顺序、引用、字段冲突 |
| 自动刷新不夹带草稿、失败不变、清理引用/直接规则 REJECT | 10、11；事务故障注入及并发交错 |
| 有效空结果两选、旧决定失效、持久定时 | 11、12、15；时钟/版本/重启测试 |
| 完整导入、本地保留与后续绑定、仅导出依赖 | 9、13、18；迁移/输出/发布流程 |
| 固定订阅 Token、重置失效、历史、删除影响 | 10、12、13、18；下载/清理/归属测试 |
| A 三栏、长列表、未保存输入与冲突恢复 | 14—18；组件行为及真实浏览器测试 |
| 现有 MySQL / Compose 数据库、备份恢复 | 19；两种隔离部署与恢复演练 |

关键交付顺序：1—6 建立可解析、可验证的数据边界；7—10 形成草稿到真实订阅的链路；11—13 完成刷新一致性和迁移；14—18 接上完整界面；19 完成部署与验收。它们共享节点身份、草稿和发布契约，作为一个计划实施。

本轮只完成计划和静态自查。执行时各任务的检查结果随复选框记录；未通过的环境/行为检查必须明确列出，不以原型截图、文档或模拟测试代替真实交付证据。
