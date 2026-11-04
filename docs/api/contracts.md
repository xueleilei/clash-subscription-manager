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
| 节点地区选项 | GET `/nodes/regions`，返回配置顺序的 `[{name,regions:[{code,name}]}]`；节点查询按两位地区代码或 `UNKNOWN` 筛选 |
| 手动节点地区 | POST `/local-nodes`、PUT `/local-nodes/{id}`、POST `/local-nodes/batch` 支持字段 `regionOverride`。值可为当前地区代码、`UNKNOWN` 或 `AUTO`。省略或 null 表示新增时自动识别，编辑时沿用原设置。GET `/local-nodes/{id}` 返回已选代码，自动识别时返回 null。列表中的 `region` 为实际筛选值 |
| 手动节点来源分组 | GET `/local-node-groups` 返回 `{groups:[{id,name,version,nodeCount}],ungroupedCount}`。POST 同路径 `{name}` 创建；PATCH `/local-node-groups/{id}` 提交 `{name,expectedVersion}` 重命名；DELETE 同路径带 `?expectedVersion=` 删除分组并保留节点。创建/重命名返回 `{id,name,version}` |
| 分组筛选与移动 | GET `/nodes?localGroupId={id}` 或 `/nodes?ungrouped=true`，支持原有搜索与分页。PUT `/local-node-groups/{id}/members` 或 `/local-node-groups/ungrouped/members`，提交 `{ids:[...]}` 一次移动 1–500 个本账号的手动节点 |
| 添加到来源分组 | POST `/local-nodes` 和 POST `/local-nodes/batch` 可在原请求体中附加 `localGroupId`；省略或 null 为未分组。批量预览不写入，确认时检查目标归属并与节点一起提交。编辑已有节点参数保留来源分组；用移动接口单独修改归属 |
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

节点摘要的 `localGroupId` 和 `localGroupName` 是本地来源分组元信息，远程和未分组节点均为 null。分组筛选隐含仅取本地节点，不能同时指定上游 `sourceId`。分组名在当前账号下唯一（忽略大小写），长度为 1–64 个字符，保留「未分组」「全部分组」。分组改名、节点移组或删除含节点的分组会更新账号修订号，旧的规则文本预览需重新读取。导出的手动节点使用发布时的分组名，节点连接版本和草稿版本独立于分组操作。
