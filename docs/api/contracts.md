# HTTP / JSON 契约

所有管理接口在 `/api/v1`，使用同源 Session Cookie。除登录、获取 CSRF 外需要管理员会话。响应结构为 `{code, message, data, traceId}`，成功码 `00000`，失败保留真实 HTTP 状态；不能仅检查 JSON code。订阅下载 `/sub/{token}` 返回 YAML，独立于登录会话。

ID 使用 UUID 字符串；版本使用十进制字符串，前端不转成 Number。请求中的 `expectedVersion` 同样传字符串，由 Java 转换为 long。时间是 UTC ISO-8601，页面转换到本地时区。分页响应 `{items,total,page,size}`，默认 50、最大 200。

| HTTP 状态 | 处理 |
| --- | --- |
| 400 | 输入格式错误；保留用户输入并显示消息 |
| 401 | 登录失效或凭据错误；转入登录，不重放写操作 |
| 403 | CSRF 请求校验失败；要求重新登录/刷新，不自动重发 |
| 404 | 对象不存在、其他 owner 的对象、已撤销链接 |
| 409 | 版本冲突/预览过期/尚未发布；保留本地编辑并处理冲突 |
| 422 | 校验未通过，data 为 `{errors,warnings}` |
| 500 | 通用错误消息与 traceId；不能显示原始响应、SQL 或凭据 |

`Diagnostic`：severity、code、path、可空 line/column、message。path 指向节点、组、规则或基础配置。普通错误的 data 可为空。主动取消的 AbortError 不显示为失败。

登录：先 `GET /csrf` 取得 `{headerName, parameterName, token}`，再 `POST /session` 发送 URL-encoded username/password，并使用返回的 CSRF header。成功后 `GET /session` 取得 `{id,username}`。每个修改请求重新获取 CSRF，以兼容登录后的 token 轮换。`DELETE /session` 退出。会话和工作副本只保存在内存，不存浏览器持久缓存。

| 功能 | 路径 |
| --- | --- |
| 来源列表/创建/修改 | GET/POST `/sources`，PATCH `/sources/{id}` |
| 来源明文凭据（显式操作） | GET `/sources/{id}/credentials`，no-store |
| 节点查询/明文参数 | GET `/nodes?name=&sourceId=&protocol=&region=&page=&size=`，GET `/nodes/{id}/connection` |
| 手动刷新/全部刷新 | POST `/sources/{id}/refresh`、`/sources/refresh-all`，返回 202 task |
| 任务与来源历史 | GET `/tasks/{id}`、`/sources/{id}/history` |
| 空结果选择 | GET `/empty-decisions`，POST `/empty-decisions/{id}/decision` `{choice:"KEEP"|"CLEAR"}` |
| 方案与草稿 | GET/POST `/profiles`，GET/PUT `/profiles/{id}/draft` |
| 重命名/删除方案 | PATCH `/profiles/{id}`，GET `/profiles/{id}/deletion-impact`，DELETE `/profiles/{id}?expectedVersion=` |
| 规则导入预览 | POST `/profiles/{id}/rule-import/preview`，提交 expectedVersion/text/matchPolicy/document |
| 草稿诊断/实际发布校验 | POST `/profiles/{id}/draft/validate`、`/profiles/{id}/validate` |
| 发布/发布历史 | POST `/profiles/{id}/publish`，GET `/profiles/{id}/releases` |
| 订阅链接/重置 | GET `/profiles/{id}/subscription-link`，POST `/profiles/{id}/token/rotate` |
| 完整导入 | POST `/imports/preview` `{yaml}`，POST `/imports/{id}/confirm` `{choices}` |
| 原始导入批次 | GET `/profiles/{id}/import-batch`，no-store |
| 本地节点绑定 | POST `/nodes/{id}/binding/preview` `{remoteNodeId}`，POST `/bindings/{id}/apply` |
| 来源删除 | POST `/sources/{id}/removal/preview`，POST `/source-removals/{id}/apply` `{mode:"CLEAR"|"KEEP_LOCAL"}` |

草稿保存/发布都提交 `{expectedVersion,document}`。`ProfileDocument` 由 schemaVersion、baseYaml、groups、rules、providers、importIssues 组成，字段定义见 `frontend/src/types/domain.ts`。TargetRef 使用 kind + id（NODE/GROUP）或 unresolvedName（UNRESOLVED）；DIRECT/REJECT 没有 id/name。引用不依赖展示名称。

来源列表不返回 URL 凭据或请求头。编辑未提交的 URL/headers/userAgent/outboundProxy 保持原值；显式传空值的语义由字段校验约束，禁止用脱敏占位符覆盖。节点列表只提供基本信息，连接参数仅通过显式明文查看接口获取。

发布预览与明文参数响应 no-store。生产 Java 只把已知页面路径转发至 index.html；API、订阅及缺失资源保持原有 401/404 状态，不以 SPA 页面代替错误。

来源列表还返回 `nodeCount`，按 owner/source 聚合当前有效节点，不包含已删除节点的身份记录。本地节点不计入任何来源数量。
