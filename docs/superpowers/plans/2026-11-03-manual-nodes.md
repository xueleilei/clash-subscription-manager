# 手动节点维护 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 支持单个本地节点新增、编辑和删除，只有用户手动发布相关方案才影响订阅。

**Architecture:** 复用现有本地节点、加密存储、版本锁与发布快照。新增节点维护服务和复用的 Vue 编辑器；自动刷新固定已发布本地节点参数。

**Tech Stack:** Java 21 / Spring Boot 4.1.2 / MySQL 8.4 / Vue 3 Composition API / TypeScript。

**Spec:** `docs/superpowers/specs/2026-11-03-manual-nodes-design.md`

## Global Constraints

- 不新增运行时依赖或远程转换服务；协议范围沿用当前支持矩阵。
- 本地连接参数加密存储；秘密响应 no-store，日志不含输入。
- 单节点输入完整校验后提交；修改及删除不能提前影响已发布订阅。
- 不访问真实上游，不修改现有真实节点和用户方案；本地验证仅用合成数据。

## Review Focus

- 连续两次远程刷新之后，尚未发布的本地编辑或删除仍不能泄漏到发布产物。
- 节点经其他本地节点间接引用时，草稿版本和待发布状态也要更新。
- 详情读取乱序、关闭后返回、会话失效重登不能清空或串改当前编辑输入。
- 编辑已有 YAML 高级参数时保留 ECH、smux、transport 和链式引用。
- 旧版本删除预览、跨用户 ID、把远程节点当成本地修改必须失败且不写数据。

## Task 1: 本地节点接口与发布隔离

**Files:**
- Create: `backend/src/main/java/dev/clashsub/node/LocalNodeController.java`, `LocalNodeService.java`, `LocalNodeCommitter.java`, `LocalNodeInputParser.java` 及小型请求/结果 record。
- Modify: `node/NodeRepository.java`, `NodeMapper.java`, `NodeQuery.java`, `NodeController.java`。
- Modify: `release/PreparedRelease.java`, `ReleaseService.java`, `ReleaseRepository.java`, `sync/RefreshPlanner.java`, `profile/ProfileService.java`, `ProfileView.java`。
- Test: `backend/src/test/java/dev/clashsub/node/LocalNodeIT.java`, `LocalNodeInputParserTest.java`。

**Interfaces:**
- Input: `LocalNodeCommand(expectedVersion, name, protocol, connection, dialerRef)`。
- Output: `LocalNodeView(node, version, affectedProfiles, publishedProfiles)`；写入结果 `LocalNodeResult(id, version, affectedProfiles)`。
- Routes: POST `/local-nodes/parse` `{text}` 返回可编辑字段；POST `/local-nodes`；GET/PUT `/local-nodes/{id}`；GET `/local-nodes/{id}/removal` 返回版本、ownerRevision、草稿/发布影响及依赖；DELETE `/local-nodes/{id}` `{expectedVersion, ownerRevision}`。
- 列表新增 `localOnly=true`；`ProfileView` 新增 `hasUnpublishedNodeChanges`。
- `PreparedRelease` 保存本次编译目录；`ReleaseService.preparePublished` 使用旧发布的本地节点快照，`RefreshPlanner` 调用此方法。

- [x] 写接口失败测试，POST 尚不存在的路由，期待 200；运行 `./scripts/mvn.sh -s .cache/dev-tools/maven-network-settings.xml -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LocalNodeIT verify`，确认因接口不存在失败。
- [x] 实现本地节点 CRUD、文本解析、版本检查、草稿影响和发布快照隔离。不得绕过现有字段或 Mihomo 校验。
- [x] 扩充测试：单链接/单 YAML、多节点拒绝；加密及 no-store；同一 ID 改参数和名称；非法字段/内核拒绝；跨用户和远程节点拒绝；间接引用；删除预览冲突；依赖阻止删除；两轮远程刷新和后续手动发布。
- [x] 运行后端 `verify`，Expected: 所有单元和集成测试通过；记录结果并提交。

## Task 2: 两个页面的手动节点维护入口

**Files:**
- Create: `frontend/src/features/nodes/ManualNodesPanel.vue`, `LocalNodeEditor.vue`, `LocalNodeForm.vue`, `LocalNodeActions.vue`, `localNodeForm.ts`。
- Modify: `features/sources/SourcesView.vue`, `NodePoolPage.vue`, `NodePoolView.vue`, `useSources.ts`；`features/workspace/WorkspaceView.vue`；`types/domain.ts`。
- Test: `features/nodes/LocalNodeEditor.test.ts` 与现有 sources 测试。

**Interfaces:**
- `LocalNodeActions` 暴露 `create() / edit(NodeSummary) / remove(NodeSummary)`，发出 `changed(LocalNodeResult)`，自己管理弹窗和请求生命周期。
- `LocalNodeForm` 接收独立输入副本，提交 `LocalNodeCommand`；表单不直接请求 API。
- `ManualNodesPanel` 列出 `localOnly` 节点，复用列表和操作组件；上游页通过标签切换加载。
- `NodePoolView` 发出 edit/remove，仅在本地且启用维护能力时显示。

- [ ] 写浏览器失败测试：页面“手动节点”入口、新增与编辑成功、链接解析；Expected: 入口/编辑器不存在导致失败。
- [ ] 实现常用表单和高级 JSON；分享链接/单节点 YAML 先解析再编辑。保持所有未修改连接字段，不向日志写秘密。
- [ ] 接入新增、详情、保存、删除预览与确认；版本冲突保留输入；载入请求取消与序列号防串数据；关闭清除凭据。
- [ ] 方案状态读取 `hasUnpublishedNodeChanges`；变更后刷新方案元数据，显示受影响方案与手动发布提示。
- [ ] 覆盖高级字段往返、异步详情乱序和失效、删除影响及当前 ID、取消不写入、错误保留输入。
- [ ] 运行 `pnpm --dir frontend test --run`、`typecheck`、`build`；Expected: 全通过；记录结果并提交。

## Task 3: 端到端验证与交付

**Files:** `docs/operations/manual-nodes.md`、`verification.md`；必要的合成浏览器冒烟测试。

**Interfaces:** 使用 Task 1 API 和 Task 2 界面，开发运行环境继续采用现有 MySQL、密钥和管理员账户。

- [ ] 运行 `.cache/dev-tools/verify-local.sh`；Expected: 全部前后端及实际 Mihomo 检查通过。
- [ ] 以合成节点完成新增、加入组、发布、编辑、旧产物不变、再发布、删除和再次发布；清理本次合成数据。
- [ ] 确认 UI 可操作、窄屏无溢出、错误可见且输入保留；重启后端到新 JAR，保留已有数据与私密环境配置。
- [ ] 更新操作文档和验收记录，提交；完成独立整段代码审查，处理重要问题并复测。
- [ ] 更新源码包和摘要，报告新增入口、发布行为及验证结果。
