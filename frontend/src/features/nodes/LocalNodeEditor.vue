<script setup lang="ts">
import { onScopeDispose, shallowRef } from 'vue'
import { ElButton } from 'element-plus'
import LocalNodeForm from './LocalNodeForm.vue'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import { initialCommand } from './localNodeForm'
import { ApiError, errorMessage, request } from '../../api/client'
import type { Diagnostic, LocalNodeCommand, LocalNodeView, NodeSummary, ValidationReport } from '../../types/domain'
const props = defineProps<{ initial: LocalNodeView | null; nodes: readonly NodeSummary[]; busy: boolean }>()
const emit = defineEmits<{ submit: [value: LocalNodeCommand]; cancel: [] }>()
const command = shallowRef(initialCommand(props.initial)), mode = shallowRef<'form' | 'text'>('form'), input = shallowRef('')
const generation = shallowRef(0), parsing = shallowRef(false), error = shallowRef(''), diagnostics = shallowRef<Diagnostic[]>([])
const lifetime = new AbortController(); onScopeDispose(() => { lifetime.abort(); input.value = ''; command.value = initialCommand(null) })
async function parse() {
 if (parsing.value || props.busy || !input.value.trim()) return
 parsing.value = true; error.value = ''; diagnostics.value = []
 try {
  const result = await request<LocalNodeCommand>('/local-nodes/parse', { method: 'POST', body: { text: input.value }, signal: lifetime.signal })
  if (!lifetime.signal.aborted) { command.value = { ...result, ...(props.initial ? { expectedVersion: props.initial.version } : {}) }; generation.value++; mode.value = 'form'; input.value = '' }
 } catch (cause) {
  if (!lifetime.signal.aborted) { error.value = errorMessage(cause); if (cause instanceof ApiError) diagnostics.value = (cause.data as ValidationReport | null)?.errors ?? [] }
 } finally { parsing.value = false }
}
</script>
<template>
 <div class="editor-tabs"><ElButton :type="mode === 'form' ? 'primary' : 'default'" :disabled="parsing || busy" @click="mode = 'form'">参数表单</ElButton><ElButton :type="mode === 'text' ? 'primary' : 'default'" :disabled="parsing || busy" @click="mode = 'text'">分享链接 / YAML</ElButton></div>
 <p class="notice editor-notice">保存后更新节点池和相关方案草稿。订阅链接将在检查相关方案并手动发布后更新。</p>
 <LocalNodeForm v-show="mode === 'form'" :key="generation" :initial="command" :node-id="initial?.node.id ?? null" :nodes="nodes" :busy="busy || parsing" @submit="value => emit('submit', value)" @cancel="emit('cancel')" />
 <form v-if="mode === 'text'" class="form-grid" @submit.prevent="parse"><label>单个节点链接或 YAML<textarea v-model="input" name="node-input" rows="9" required :disabled="parsing" spellcheck="false" autocomplete="off" placeholder="粘贴 ss://、vmess://、vless:// 等节点链接，或一个 Clash / Mihomo 节点 YAML"></textarea></label><p class="muted">支持单个 Base64 节点；仅在本机服务解析，不会发送给第三方转换服务。解析成功将替换当前表单，保存前可继续编辑。</p><p v-if="error" class="error" role="alert">{{ error }}</p><DiagnosticsList :items="diagnostics" /><div class="dialog-actions"><ElButton :disabled="parsing" @click="emit('cancel')">取消</ElButton><ElButton type="primary" native-type="submit" :loading="parsing">解析为表单</ElButton></div></form>
</template>
<style scoped>.editor-tabs{display:flex;gap:8px}.editor-notice{margin:16px 0 20px}</style>
