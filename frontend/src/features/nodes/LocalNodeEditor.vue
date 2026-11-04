<script setup lang="ts">
import { onScopeDispose, shallowRef, watch } from 'vue'
import { ElButton } from 'element-plus'
import LocalNodeForm from './LocalNodeForm.vue'
import NodeRegionSelect from './NodeRegionSelect.vue'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import { initialCommand } from './localNodeForm'
import { ApiError, errorMessage, request } from '../../api/client'
import type { Diagnostic, LocalNodeBatchCommand, LocalNodeBatchPreview, LocalNodeCommand, LocalNodeView, NodeSummary, ValidationReport } from '../../types/domain'
const props = defineProps<{ initial: LocalNodeView | null; nodes: readonly NodeSummary[]; busy: boolean }>()
const emit = defineEmits<{ submit: [value: LocalNodeCommand]; import: [value: LocalNodeBatchCommand]; cancel: []; 'reset-errors': [] }>()
const command = shallowRef(initialCommand(props.initial)), mode = shallowRef<'form' | 'text'>('form'), input = shallowRef('')
const region = shallowRef(props.initial?.regionOverride ?? '')
const generation = shallowRef(0), parsing = shallowRef(false), error = shallowRef(''), diagnostics = shallowRef<Diagnostic[]>([])
const batch = shallowRef<LocalNodeBatchPreview | null>(null)
let previewText = ''
watch(input, () => { batch.value = null; previewText = ''; error.value = ''; diagnostics.value = [] }, { flush: 'sync' })
watch(() => props.busy, (busy, previous) => { if (previous && !busy) { batch.value = null; previewText = '' } })
const lifetime = new AbortController(); onScopeDispose(() => { lifetime.abort(); input.value = ''; batch.value = null; command.value = initialCommand(null) })
async function parse() {
 if (parsing.value || props.busy || !input.value.trim()) return
 const submitted = input.value
 parsing.value = true; error.value = ''; diagnostics.value = []; batch.value = null; emit('reset-errors')
 try {
  const options = { method: 'POST' as const, body: { text: submitted }, signal: lifetime.signal }
  let result: LocalNodeCommand
  if (props.initial) result = await request<LocalNodeCommand>('/local-nodes/parse', options)
  else {
   const preview = await request<LocalNodeBatchPreview>('/local-nodes/batch/preview', options)
   if (lifetime.signal.aborted || input.value !== submitted) return
   if (preview.nodes.length > 1) { batch.value = preview; previewText = submitted; return }
   if (!preview.nodes[0]) throw new Error('未识别到节点，请检查输入')
   result = preview.nodes[0]
  }
  if (!lifetime.signal.aborted && input.value === submitted) { command.value = { ...result, ...(props.initial ? { expectedVersion: props.initial.version } : {}) }; generation.value++; mode.value = 'form'; input.value = '' }
 } catch (cause) {
  if (!lifetime.signal.aborted) { error.value = errorMessage(cause); if (cause instanceof ApiError) diagnostics.value = (cause.data as ValidationReport | null)?.errors ?? [] }
 } finally { parsing.value = false }
}
function regionSelection(): { regionOverride?: string } {
 if (region.value) return { regionOverride: region.value }
 if (props.initial?.regionOverride) return { regionOverride: 'AUTO' }
 return {}
}
function submit(value: LocalNodeCommand) {
 emit('submit', { ...value, ...regionSelection() })
}
function importBatch() {
 if (props.initial || props.busy || parsing.value || !batch.value || previewText !== input.value) return
 emit('import', { text: previewText, ownerRevision: batch.value.ownerRevision, ...regionSelection() })
}
</script>
<template>
 <div class="editor-tabs"><ElButton :type="mode === 'form' ? 'primary' : 'default'" :disabled="parsing || busy" @click="mode = 'form'">参数表单</ElButton><ElButton :type="mode === 'text' ? 'primary' : 'default'" :disabled="parsing || busy" @click="mode = 'text'">分享链接 / YAML</ElButton></div>
 <p class="notice editor-notice">修改连接参数后，检查相关方案并手动发布，订阅链接才会使用新参数。</p>
 <div class="region-choice">
  <label>节点地区<NodeRegionSelect v-model="region" label="节点地区" assignment :disabled="busy || parsing" /></label>
  <p class="muted">{{ mode === 'text' && !initial ? '自动识别会逐个判断节点名称。手动指定的地区用于本次导入的所有节点。' : '用于节点列表和代理组的地区筛选，保存后生效。' }}</p>
 </div>
 <LocalNodeForm v-show="mode === 'form'" :key="generation" :initial="command" :node-id="initial?.node.id ?? null" :nodes="nodes" :busy="busy || parsing" @submit="submit" @cancel="emit('cancel')" />
 <form v-if="mode === 'text'" class="form-grid" @submit.prevent="parse">
  <label>{{ initial ? '单个节点链接或 YAML' : '节点链接或 YAML（支持批量粘贴）' }}<textarea v-model="input" name="node-input" rows="9" required :disabled="parsing || busy" spellcheck="false" autocomplete="off" :placeholder="initial ? '粘贴一个节点链接或 Clash / Mihomo 节点 YAML' : '粘贴多个节点链接、YAML 列表、proxies: 配置，或每行一个 {name: ..., type: ...} 节点'"></textarea></label>
  <p class="muted">{{ initial ? '支持单个 Base64 节点，解析后可继续编辑表单。' : '支持 Base64、多行分享链接、YAML 列表和每行一个 {...} 节点。每次最多 500 个、256 KiB；单个节点进入表单，多个节点先预览后导入。' }} 仅在本机服务解析，不会发送给第三方转换服务。</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p><DiagnosticsList :items="diagnostics" />
  <section v-if="batch" class="batch-preview" aria-label="批量节点预览">
   <h3>已识别 {{ batch.nodes.length }} 个节点</h3><p class="muted">全部校验通过。确认后一次性新增为手动节点，不覆盖已有节点。</p>
   <div class="batch-list"><table><thead><tr><th>名称</th><th>协议</th><th>服务器 / 端口</th></tr></thead><tbody><tr v-for="(node, index) in batch.nodes" :key="index"><td>{{ node.name }}</td><td>{{ node.protocol }}</td><td>{{ node.connection.server }} : {{ node.connection.port ?? node.connection.ports ?? '—' }}</td></tr></tbody></table></div>
  </section>
  <div class="dialog-actions"><ElButton :disabled="parsing || busy" @click="emit('cancel')">取消</ElButton><ElButton :type="batch ? 'default' : 'primary'" native-type="submit" :loading="parsing" :disabled="busy">{{ initial ? '解析为表单' : '解析并预览' }}</ElButton><ElButton v-if="batch" type="primary" :loading="busy" :disabled="parsing" @click="importBatch">确认导入 {{ batch.nodes.length }} 个节点</ElButton></div>
 </form>
</template>
<style scoped>
.region-choice{display:grid;grid-template-columns:minmax(180px,1fr) 1fr;gap:12px;align-items:end;margin-bottom:16px}
.region-choice p{font-size:12px;padding-bottom:6px}
@media(max-width:600px){.region-choice{grid-template-columns:1fr;gap:4px}.region-choice p{padding-bottom:0}}
</style>
<style scoped>.editor-tabs{display:flex;gap:8px}.editor-notice{margin:16px 0 20px}.batch-preview{display:grid;gap:10px;min-width:0}.batch-list{max-height:260px;overflow:auto;border:1px solid var(--line);border-radius:8px}.batch-list table{width:100%;table-layout:fixed;border-collapse:collapse;font-size:12px}.batch-list th,.batch-list td{text-align:left;padding:9px;border-bottom:1px solid var(--line);overflow-wrap:anywhere}.batch-list th:nth-child(2){width:72px}.dialog-actions{flex-wrap:wrap}</style>
