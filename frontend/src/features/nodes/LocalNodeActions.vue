<script setup lang="ts">
import { computed, onScopeDispose, shallowRef } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import LocalNodeEditor from './LocalNodeEditor.vue'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import { ApiError, errorMessage, request } from '../../api/client'
import { readNodeSummaries } from '../../api/nodes'
import { useProfiles } from '../../layout/useProfiles'
import { onSessionReset } from '../auth/useSession'
import type { Diagnostic, LocalNodeBatchCommand, LocalNodeBatchResult, LocalNodeCommand, LocalNodeGroup, LocalNodeGroupDirectory, LocalNodeRemoval, LocalNodeResult, LocalNodeView, NodeSummary, ValidationReport } from '../../types/domain'
const props = defineProps<{ defaultGroupId?: string }>()
const emit = defineEmits<{ changed: [result: LocalNodeResult | LocalNodeBatchResult] }>()
type Selection = { kind: 'create' | 'edit' | 'remove'; node: NodeSummary | null }
const selection = shallowRef<Selection | null>(null), detail = shallowRef<LocalNodeView | null>(null), removal = shallowRef<LocalNodeRemoval | null>(null)
const nodes = shallowRef<NodeSummary[]>([]), loading = shallowRef(false), saving = shallowRef(false), error = shallowRef(''), diagnostics = shallowRef<Diagnostic[]>([]), notice = shallowRef('')
const groups = shallowRef<LocalNodeGroup[]>([]), targetGroup = shallowRef(''), ready = shallowRef(false)
const missingGroup = computed(() => !!targetGroup.value && !groups.value.some(g => g.id === targetGroup.value))
const profiles = useProfiles(), names = computed(() => new Map(profiles.profiles.value.map(profile => [profile.id, profile.name])))
const nodeNames = computed(() => new Map(nodes.value.map(node => [node.id, node.originalName])))
let sequence = 0, operation: AbortController | null = null, disposed = false
const title = computed(() => selection.value?.kind === 'remove' ? '删除手动节点' : selection.value?.kind === 'edit' ? '编辑手动节点' : '添加手动节点')
function clear() { sequence++; operation?.abort(); selection.value = null; detail.value = null; removal.value = null; nodes.value = []; groups.value = []; targetGroup.value = ''; ready.value = false; loading.value = false; saving.value = false; error.value = ''; diagnostics.value = [] }
function close() { if (!saving.value) clear() }
const unregister = onSessionReset(() => { clear(); notice.value = '' })
onScopeDispose(() => { disposed = true; unregister(); clear(); notice.value = '' })
function showError(cause: unknown) { error.value = errorMessage(cause); diagnostics.value = cause instanceof ApiError ? (cause.data as ValidationReport | null)?.errors ?? [] : [] }
async function open(next: Selection) {
 if (saving.value || (next.node && next.node.sourceId)) return
 clear(); const current = sequence; selection.value = next; operation = new AbortController(); const signal = operation.signal
 loading.value = true; notice.value = ''
 try {
  const [metadata, value, directory] = await Promise.all([
   readNodeSummaries(signal),
   next.kind === 'create' ? Promise.resolve(null) : request<LocalNodeView | LocalNodeRemoval>(`/local-nodes/${next.node!.id}${next.kind === 'remove' ? '/removal' : ''}`, { signal }),
   next.kind === 'create' ? request<LocalNodeGroupDirectory>('/local-node-groups', {signal}) : Promise.resolve(null),
   profiles.reload(signal),
  ])
  if (current !== sequence || disposed) return
  groups.value = directory?.groups ?? []; targetGroup.value = next.kind === 'create' ? props.defaultGroupId ?? '' : ''; ready.value = true
  nodes.value = metadata
  if (next.kind === 'remove') removal.value = value as LocalNodeRemoval
  else detail.value = value as LocalNodeView | null
 } catch (cause) { if (current === sequence && !disposed) showError(cause) }
 finally { if (current === sequence && !disposed) loading.value = false }
}
async function write(method: 'POST' | 'PUT' | 'DELETE', path: string, body: unknown) {
 if (saving.value) return
 const current = sequence, signal = operation!.signal; saving.value = true; error.value = ''; diagnostics.value = []
 try {
  const result = await request<LocalNodeResult | LocalNodeBatchResult>(path, { method, body, signal })
  if (current !== sequence || disposed) return
  const affected = result.affectedProfiles.map(id => names.value.get(id) ?? id).join('、')
  const folder = selection.value?.kind === 'create' ? groups.value.find(g => g.id === targetGroup.value)?.name ?? '未分组' : null
  clear(); notice.value = affected ? `已更新相关方案草稿：${affected}。请检查后手动发布，现有订阅保持已发布版本。` : '节点池已更新。'
  if (folder) notice.value = ('ids' in result ? `已导入 ${result.ids.length} 个手动节点` : '已添加节点') + `到「${folder}」。将节点加入方案代理组，检查后手动发布。`
  emit('changed', result)
  try { await profiles.reload() } catch { if (!disposed) notice.value += ' 方案状态读取失败，请重新读取。' }
 } catch (cause) {
  if (current !== sequence || disposed) return
  showError(cause)
  if (selection.value?.kind === 'create' && targetGroup.value && cause instanceof ApiError && cause.status === 404) {
   try {
    const latest = await request<LocalNodeGroupDirectory>('/local-node-groups', {signal})
    if (current === sequence && !disposed) groups.value = latest.groups
   } catch { /* Keep the input and original save error if the directory cannot be reloaded. */ }
  }
 }
 finally { if (current === sequence && !disposed) saving.value = false }
}
function save(command: LocalNodeCommand) {
 const selected = selection.value; if (!selected || selected.kind === 'remove') return
 if (selected.node) return write('PUT', `/local-nodes/${selected.node.id}`, command)
 return create('/local-nodes', command)
}
function importNodes(command: LocalNodeBatchCommand) {
 if (selection.value?.kind !== 'create') return
 return create('/local-nodes/batch', command)
}
function create(path: string, command: LocalNodeCommand | LocalNodeBatchCommand) {
 if (missingGroup.value) { error.value = '来源分组已不存在，请重新选择'; return }
 return write('POST', path, targetGroup.value ? {...command, localGroupId:targetGroup.value} : command)
}
function removeConfirmed() {
 const preview = removal.value
 if (!preview || preview.dependentNodes.length) return
 return write('DELETE', `/local-nodes/${preview.id}`, { expectedVersion: preview.version, ownerRevision: preview.ownerRevision })
}
defineExpose({ create: () => open({ kind: 'create', node: null }), edit: (node: NodeSummary) => open({ kind: 'edit', node }), remove: (node: NodeSummary) => open({ kind: 'remove', node }) })
</script>
<template>
 <p v-if="notice" class="notice maintenance-notice" role="status">{{ notice }}</p>
 <ElDialog :model-value="!!selection" :title="title" width="700px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" destroy-on-close @update:model-value="value => { if (!value) close() }">
  <template v-if="selection">
   <p v-if="loading" class="muted" role="status">正在读取节点和关联方案…</p>
   <p v-if="error" class="error maintenance-notice" role="alert">{{ error }}</p><DiagnosticsList :items="diagnostics" />
   <template v-if="!loading && selection.kind === 'remove' && removal">
    <p>确定删除 <strong>{{ selection.node?.originalName }}</strong>？</p>
    <div class="impact"><h3>将更新以下方案草稿</h3><p>{{ removal.affectedProfiles.map(id => names.get(id) ?? id).join('、') || '没有方案草稿引用此节点' }}</p><p class="muted">从代理组移除该节点；直接指向该节点的规则将改为 REJECT。</p></div>
    <p v-if="removal.publishedProfiles.length" class="notice">{{ removal.publishedProfiles.map(id => names.get(id) ?? id).join('、') }} 的已发布订阅仍保留旧节点，检查后手动发布才会更新。</p>
    <p v-if="removal.dependentNodes.length" class="warning">以下节点仍将它用作链式代理，请先解除依赖：{{ removal.dependentNodes.map(id => nodeNames.get(id) ?? id).join('、') }}</p>
    <div class="dialog-actions"><ElButton :disabled="saving" @click="close">取消</ElButton><ElButton v-if="error" :disabled="saving" @click="open(selection)">重新检查影响</ElButton><ElButton type="danger" :loading="saving" :disabled="!!removal.dependentNodes.length" @click="removeConfirmed">确认删除节点</ElButton></div>
   </template>
   <template v-else-if="ready && !loading && selection.kind !== 'remove'">
    <label v-if="selection.kind === 'create'" class="group-target">保存到来源分组<select v-model="targetGroup" aria-label="保存到来源分组" :disabled="saving"><option value="">未分组</option><option v-if="missingGroup" :value="targetGroup" disabled>原分组已不存在，请重新选择</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select></label>
    <LocalNodeEditor :initial="detail" :nodes="nodes" :busy="saving" @submit="save" @import="importNodes" @reset-errors="error='';diagnostics=[]" @cancel="close" />
   </template>
   <div v-else-if="!loading && error" class="dialog-actions"><ElButton @click="close">取消</ElButton><ElButton @click="open(selection)">重新读取节点</ElButton></div>
  </template>
 </ElDialog>
</template>
<style scoped>.maintenance-notice{margin:0 0 18px}.impact{display:grid;gap:8px;margin:20px 0}.group-target{margin:0 0 16px}</style>
