<script setup lang="ts">
import { reactive, shallowRef } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import type { GroupDefinition, JsonObject } from '../../types/domain'
const props = defineProps<{ group: GroupDefinition | null; groups: readonly GroupDefinition[]; references: readonly string[] }>()
const emit = defineEmits<{ save: [group: GroupDefinition]; remove: []; close: [] }>()
const options = { ...(props.group?.options ?? {}) }, url = options.url, interval = options.interval, tolerance = options.tolerance
for (const key of ['url', 'interval', 'tolerance']) delete options[key]
const form = reactive({ name: props.group?.name ?? '', type: props.group?.type ?? 'select', url: String(url ?? 'https://www.gstatic.com/generate_204'), interval: Number(interval ?? 300), tolerance: Number(tolerance ?? 50), options: JSON.stringify(options, null, 2) })
const error = shallowRef('')
function submit() {
 error.value = ''
 if (!form.name.trim() || props.groups.some(group => group.id !== props.group?.id && group.name === form.name)) { error.value = '请输入不重复的代理组名称'; return }
 try {
  const parsed: unknown = JSON.parse(form.options)
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error()
  const settings = parsed as JsonObject
  if (form.type === 'url-test') { settings.url = form.url; settings.interval = form.interval; settings.tolerance = form.tolerance }
  else { if (url !== undefined) settings.url = url; if (interval !== undefined) settings.interval = interval }
  emit('save', { id: props.group?.id ?? crypto.randomUUID(), name: form.name, type: form.type, members: props.group?.members ?? [], options: settings })
 } catch { error.value = '兼容参数必须是 JSON 对象' }
}
</script>
<template><ElDialog :model-value="true" :title="group ? '编辑代理组' : '新建代理组'" width="530px" :close-on-click-modal="false" @close="emit('close')"><form class="form-grid" @submit.prevent="submit"><label>代理组名称<input v-model="form.name" maxlength="256" required placeholder="例如：AI 服务"></label><label>类型<select v-model="form.type" aria-label="类型"><option value="select">select · 手动选择</option><option value="url-test">url-test · 自动测速选择</option><option v-if="!['select','url-test'].includes(form.type)" :value="form.type">{{ form.type }} · 待处理类型</option></select></label><template v-if="form.type === 'url-test'"><label>测试地址<input v-model="form.url" type="url" pattern="https?://.+" required></label><div class="form-row"><label>测试间隔（秒）<input v-model.number="form.interval" type="number" min="0" max="2147483647" required></label><label>容差（毫秒）<input v-model.number="form.tolerance" type="number" min="0" max="2147483647" required></label></div></template><details><summary>兼容参数（JSON）</summary><textarea v-model="form.options" class="options" rows="6" spellcheck="false" aria-label="代理组兼容参数"></textarea><p class="muted">保留导入的兼容字段。成员和空组回退由管理器维护，不能使用动态 use/filter。</p></details><p v-if="references.length" class="warning">删除前请解除引用：{{ references.join('、') }}</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><button v-if="group" type="button" class="text-button danger remove-group" :disabled="references.length > 0" @click="emit('remove')">删除此组</button><ElButton @click="emit('close')">取消</ElButton><ElButton type="primary" native-type="submit">保存代理组</ElButton></div></form></ElDialog></template>
<style scoped>.options{margin:10px 0;font-family:monospace}.remove-group{margin-right:auto}details p{font-size:11px}</style>
