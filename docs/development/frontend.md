# 前端结构与本地验证

使用 Vue Composition API、TypeScript 和 `<script setup>`。正式界面沿用已确认的 A 三栏工作台，颜色和间距基于设计截图。组件通过 props/emit 传递数据，业务请求与异步状态放在 composable。

| 组件或模块 | 职责与边界 |
| --- | --- |
| App / router | 应用启动、登录状态与页面组合，不持有业务编辑表单 |
| AppShell | 全局侧栏、方案入口；profiles/username 输入，create/logout 输出 |
| LoginPage / useSession | 登录请求与路由；会话仅在内存中保存 |
| LoginForm | busy/error 输入；submit(credentials) 输出 |
| api/client | 同源请求、最新 CSRF、HTTP 分类；写操作不自动重试 |
| SourcesView / useSources / useRefreshTasks | 来源与节点加载、任务轮询；表单和列表独立组件 |
| WorkspaceView / useProfileDraft | owner/profile 工作副本、版本冲突；组合组列表、成员列表、节点选择器 |
| RulesView / BaseYamlEditor | 有序规则、规则集、基础 YAML；通过事件更新当前方案 |
| ImportWizard / PublishPanel | 导入预览、明确关联、校验、发布与订阅链接 |

开发：`pnpm --dir frontend install --frozen-lockfile`，`pnpm --dir frontend dev`。API 和订阅下载代理至本机 8080，浏览器始终使用同源地址。生产静态资源由 Java 提供。

验证：`pnpm --dir frontend exec playwright install chromium --only-shell`，`pnpm --dir frontend test --run`，`pnpm --dir frontend typecheck`，`pnpm --dir frontend build`。组件测试使用 Vitest + Playwright 的真实 Chromium 环境，不以模拟 DOM 代替焦点和事件行为。配置依据 [Vitest Browser Mode](https://vitest.dev/guide/browser/)。

敏感数据不写 localStorage、sessionStorage、IndexedDB 或 URL。会话过期保留内存工作副本；刷新整个页面会丢失尚未保存的输入。返回 409 时保留用户输入并要求处理版本差异，不能后台覆盖或自动重发写入。
