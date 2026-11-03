<script setup lang="ts">
import { computed, onMounted, onScopeDispose, shallowRef, watch } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import { ApiError, errorMessage, request } from '../../api/client'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import RuleTextCodeEditor from './RuleTextCodeEditor.vue'
import type { ProfileDocument, RuleDefinition, RuleImportPreview, RuleTextDocument } from '../../types/domain'
const props = defineProps<{ profileId: string; version: string; document: ProfileDocument }>()
const emit = defineEmits<{ apply: [rules: RuleDefinition[]]; close: [] }>()
const text = shallowRef(''), loaded = shallowRef(false), busy = shallowRef(false), stale = shallowRef(false), error = shallowRef('')
const originalCount = shallowRef(0), preview = shallowRef<RuleImportPreview | null>(null), validatedText = shallowRef<string | null>(null)
const ownerRevision = shallowRef('')
const context = computed(() => JSON.stringify([props.profileId, props.version, props.document]))
const lineCount = computed(() => text.value.split(/\r\n|\r|\n/).length)
const applicable = computed(() => !busy.value && !stale.value && preview.value?.applicable
 && !preview.value.report.errors.length && preview.value.expectedVersion === props.version && validatedText.value === text.value)
let sequence = 0, read: AbortController | null = null
function invalidate() { sequence++; read?.abort(); busy.value = false; preview.value = null; validatedText.value = null; error.value = '' }
watch(text, invalidate, { flush: 'sync' })
watch(context, () => { invalidate(); stale.value = true }, { flush: 'sync' })
onScopeDispose(() => { sequence++; read?.abort() })
function failure(cause: unknown) {
 if (cause instanceof ApiError && cause.status === 409) stale.value = true
 else error.value = errorMessage(cause)
}
async function loadText() {
 if (stale.value) return
 const current = ++sequence; read?.abort(); read = new AbortController(); busy.value = true; error.value = ''
 try {
  const result = await request<RuleTextDocument>(`/profiles/${props.profileId}/rule-text`, {
   method: 'POST', body: { expectedVersion: props.version, document: props.document }, signal: read.signal,
  })
  if (current !== sequence) return
  if (result.expectedVersion !== props.version) { stale.value = true; return }
  text.value = result.text; originalCount.value = result.ruleCount; ownerRevision.value = result.ownerRevision; loaded.value = true
 } catch (cause) { if (current === sequence) failure(cause) }
 finally { if (current === sequence) busy.value = false }
}
async function validate() {
 if (!loaded.value || stale.value || busy.value) return
 const current = ++sequence, submitted = text.value
 read?.abort(); read = new AbortController(); busy.value = true; error.value = ''; preview.value = null; validatedText.value = null
 try {
  const result = await request<RuleImportPreview>(`/profiles/${props.profileId}/rule-text/preview`, {
   method: 'POST', body: { expectedVersion: props.version, ownerRevision: ownerRevision.value, document: props.document, text: submitted }, signal: read.signal,
  })
  if (current === sequence && result.expectedVersion === props.version) { preview.value = result; validatedText.value = submitted }
 } catch (cause) { if (current === sequence) failure(cause) }
 finally { if (current === sequence) busy.value = false }
}
function apply() { if (applicable.value && preview.value) emit('apply', preview.value.rules) }
onMounted(loadText)
</script>
<template>
 <ElDialog :model-value="true" title="编辑完整规则文本" width="min(1100px, calc(100vw - 24px))" top="4vh" :close-on-click-modal="false" :close-on-press-escape="false" @close="emit('close')">
  <div class="rule-text-body">
   <p class="muted">编辑当前工作副本的全部规则，每行一条，末尾保留一条 MATCH。校验通过后整体替换规则列表，仍需保存草稿或发布。</p>
   <div v-if="loaded" class="toolbar text-meta"><span>原规则 {{ originalCount }} 条 · 当前 {{ lineCount }} 行</span><span><kbd>⌘F / Ctrl+F</kbd> 查找与替换 · 支持撤销</span></div>
   <RuleTextCodeEditor v-if="loaded" v-model="text" />
   <p v-else-if="busy" class="muted">正在读取全部规则…</p>
   <p v-if="stale" class="warning" role="alert">方案或节点来源已变化，输入已保留。请复制要保留的修改，关闭编辑并处理版本变化后重新打开。</p>
   <p v-if="error" class="error" role="alert">{{ error }}</p>
   <template v-if="preview"><p v-if="preview.applicable" class="notice">校验通过，应用后共 {{ preview.rules.length }} 条规则。</p><DiagnosticsList :items="[...preview.report.errors, ...preview.report.warnings]" /></template>
  </div>
  <template #footer><div class="dialog-actions"><ElButton @click="emit('close')">取消文本编辑</ElButton><ElButton v-if="!loaded" :loading="busy" :disabled="stale" @click="loadText">重新读取规则</ElButton><ElButton v-else :loading="busy" :disabled="stale" @click="validate">校验规则文本</ElButton><ElButton type="primary" :disabled="!applicable" @click="apply">应用文本修改</ElButton></div></template>
 </ElDialog>
</template>
<style scoped>.rule-text-body{display:grid;gap:14px;min-width:0}.text-meta{font-size:12px;color:var(--muted);gap:8px;flex-wrap:wrap}kbd{font-family:inherit;background:var(--bg);padding:2px 5px;border:1px solid var(--line);border-radius:4px}.dialog-actions{flex-wrap:wrap}</style>
