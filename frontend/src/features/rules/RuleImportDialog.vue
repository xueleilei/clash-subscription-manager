<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import { ApiError, errorMessage, request } from '../../api/client'
import type { ProfileDocument, RuleImportPreview } from '../../types/domain'
const props = defineProps<{ profileId: string; version: string; document: ProfileDocument }>()
const emit = defineEmits<{ apply: [preview: RuleImportPreview]; close: [] }>()
const text = shallowRef(''), replaceMatch = shallowRef(false), preview = shallowRef<RuleImportPreview | null>(null), busy = shallowRef(false), error = shallowRef('')
const signature = computed(() => JSON.stringify([props.profileId, props.version, props.document, text.value, replaceMatch.value]))
let requestSequence = 0, read: AbortController | null = null
watch(signature, () => { requestSequence++; read?.abort(); busy.value = false; preview.value = null; error.value = '' })
onScopeDispose(() => { requestSequence++; read?.abort() })
async function fileChanged(event: Event) {
 const file = (event.target as HTMLInputElement).files?.[0]; if (!file) return
 if (file.size > 10 * 1024 * 1024) { error.value = '规则文件不能超过 10 MiB'; return }
 try { text.value = new TextDecoder('utf-8', {fatal:true}).decode(await file.arrayBuffer()) } catch { error.value = '无法读取 UTF-8 规则文件' }
}
async function validate() {
 const current = ++requestSequence; read?.abort(); read = new AbortController(); busy.value = true; error.value = ''; preview.value = null
 try {
  const result = await request<RuleImportPreview>(`/profiles/${props.profileId}/rule-import/preview`, {method:'POST',body:{expectedVersion:props.version,text:text.value,matchPolicy:replaceMatch.value ? 'REPLACE' : 'KEEP',document:props.document},signal:read.signal})
  if (current === requestSequence) preview.value = result
 } catch (cause) { if (current === requestSequence) error.value = cause instanceof ApiError && cause.status === 409 ? '草稿版本已变化，请先关闭导入并处理版本冲突。' : errorMessage(cause) }
 finally { if (current === requestSequence) busy.value = false }
}
function apply() { if (preview.value?.applicable && !preview.value.report.errors.length && preview.value.expectedVersion === props.version && !busy.value) emit('apply', preview.value) }
</script>
<template><ElDialog :model-value="true" title="批量导入标准规则" width="760px" :close-on-click-modal="false" @close="emit('close')"><div class="form-grid"><label>读取文本或 YAML 规则列表文件<input type="file" accept=".txt,.yaml,.yml,text/plain" aria-label="导入规则文件" @change="fileChanged"></label><label>规则文本<textarea v-model="text" rows="9" class="rule-input" aria-label="规则文本" spellcheck="false" placeholder="DOMAIN-SUFFIX,example.com,DIRECT&#10;RULE-SET,规则集名称,代理组名称"></textarea></label><label><input v-model="replaceMatch" type="checkbox" aria-label="替换现有 MATCH">明确替换现有 MATCH（仅当导入文本包含 MATCH 时）</label><p class="muted">新规则追加在当前 MATCH 前。所有行完整解析成功后才可应用；预览不会保存草稿。</p><p v-if="error" class="error" role="alert">{{ error }}</p><template v-if="preview"><p class="notice" v-if="preview.applicable">共识别 {{ preview.importedRules.length }} 条规则，应用后共 {{ preview.rules.length }} 条。</p><DiagnosticsList :items="[...preview.report.errors,...preview.report.warnings]" /></template><div class="dialog-actions"><ElButton @click="emit('close')">取消导入</ElButton><ElButton :loading="busy" :disabled="!text.trim()" @click="validate">预览导入</ElButton><ElButton type="primary" :disabled="busy || !preview?.applicable || Boolean(preview.report.errors.length)" @click="apply">应用导入</ElButton></div></div></ElDialog></template>
<style scoped>.rule-input{font-family:monospace;white-space:pre;overflow:auto}</style>
