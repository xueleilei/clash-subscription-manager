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

敏感数据不写 localStorage、sessionStorage、IndexedDB 或 URL。会话过期通过全屏重认证对话框保留已挂载的编辑页面及未应用表单，后台工作区隐藏且不可交互，轮询暂停；同账号重新登录恢复原路由，换账号或显式退出清除旧视图及缓存。刷新整个页面会丢失尚未保存的输入。返回 409 时保留用户输入并要求处理版本差异，不能后台覆盖或自动重发写入。

三栏工作台的边界：`WorkspaceView` 只组合页面和动作；`useProfileDraft` 管理按 owner/profile 隔离的内存草稿及版本；`useNodeSelection` 持有候选节点选择。`GroupList` 接收 groups/selectedId 并发出 select/create/edit；`GroupMemberList` 接收 group/节点与组名称索引，发出 remove/reorder；`NodePicker` 接收节点、disabledIds/selectedIds，发出 toggle/add。`GroupEditorDialog` 提交完整组定义；`ConflictDialog` 提供明确的重读或保留编辑动作。候选节点采用每页 50 条的客户端分页，筛选和翻页不清除选择，切换目标组清除选择。

规则以 ID 和完整列表中的绝对位置移动，分页每页 50 条；搜索只改变可见行。批量导入调用服务端只读预览，文本、MATCH 策略、草稿或版本变化会使预览失效；存在任一错误时整批不可应用。导出标准文本保留高级表达式；直接节点使用原名称，同名节点再导入需重新确认。规则集通过独立页面管理，已引用规则集必须先解除引用才能删除。

基础 YAML 使用 [CodeMirror 的视图生命周期](https://codemirror.net/docs/guide/)：实例保持非响应式，输入立即进入内存工作副本，外部文本更新通过事务同步，卸载时销毁。服务端语法错误带 `baseYaml` 路径及行列；无效文本可以留在草稿中，不能通过发布校验。自动失效的直接节点规则带 `REMOVED_NODE_TARGET` 警告，编辑规则后清除旧原因。

完整导入按“读取→预览与关联→新建草稿”执行。节点默认本地保留；候选匹配仅提示，不自动关联。原文可在导入问题面板显式显示/下载，迁移或舍弃未知内容后才可清理对应记录；结构与内核检查始终保留。本地关联显示影响的草稿，已发布配置不变。

发布一次提交当前工作副本与 expectedVersion。预览绑定当前文档和版本，编辑后失效；阻断错误禁止发布，警告需要确认。发布成功后重新读取保存基线，保留请求期间的新输入。订阅链接仅显式显示/复制；重置需要确认旧链接失效。YAML 和链接只在内存中显示，不放入路由参数或浏览器持久存储。
