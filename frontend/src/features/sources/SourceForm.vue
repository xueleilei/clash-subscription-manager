<script setup lang="ts">
import { reactive, shallowRef } from 'vue'
import { ElButton } from 'element-plus'
import { errorMessage, request } from '../../api/client'
import type { InputFormat, SourceCommand, SourceRequest, SourceView } from '../../types/domain'
const props = defineProps<{ source: SourceView | null; busy: boolean; proxyOptions: readonly string[] }>()
const emit = defineEmits<{ submit: [command: SourceCommand]; cancel: [] }>()
const defaultProxy = props.proxyOptions.includes('default') ? 'default' : props.proxyOptions[0] ?? ''
const form = reactive({ name: props.source?.name ?? '', enabled: props.source?.enabled ?? true, interval: props.source?.refreshIntervalSeconds ?? 21600, format: props.source?.inputFormat ?? 'AUTO' as InputFormat, url: '', headers: '', userAgent: '', outboundProxy: props.source ? '__KEEP__' : defaultProxy })
const error = shallowRef(''), revealing = shallowRef(false), userAgentChanged = shallowRef(false)
async function reveal() {
 if (!props.source) return
 revealing.value = true
 try { const value = await request<SourceRequest>(`/sources/${props.source.id}/credentials`); form.url = value.url; form.headers = JSON.stringify(value.headers, null, 2); form.userAgent = value.userAgent; form.outboundProxy = value.outboundProxy; userAgentChanged.value = true }
 catch (cause) { error.value = errorMessage(cause) } finally { revealing.value = false }
}
function submit() {
 error.value = ''
 let headers: Record<string, string> | undefined
 if (form.headers.trim()) {
  try { const parsed: unknown = JSON.parse(form.headers); if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || Object.values(parsed).some(value => typeof value !== 'string')) throw new Error(); headers = parsed as Record<string, string> }
  catch { error.value = '请求头必须是名称与字符串值组成的 JSON 对象'; return }
 }
 const command: SourceCommand = { ...(props.source ? { id: props.source.id, expectedVersion: props.source.configVersion } : {}), name: form.name, enabled: form.enabled, refreshIntervalSeconds: form.interval, inputFormat: form.format }
 if (form.url || !props.source) command.url = form.url
 if (headers !== undefined) command.headers = headers
 if (userAgentChanged.value || !props.source) command.userAgent = form.userAgent
 if (form.outboundProxy !== '__KEEP__') command.outboundProxy = form.outboundProxy
 emit('submit', command)
}
</script>
<template><form class="form-grid" @submit.prevent="submit">
 <label>来源名称<input v-model="form.name" name="source-name" maxlength="128" required placeholder="例如：常用订阅"></label>
 <label>订阅地址<input v-model="form.url" name="source-url" type="url" :required="!source" autocomplete="off" :placeholder="source ? '留空保留已保存的地址' : 'https://example.com/subscribe'"></label>
 <p v-if="source" class="muted">已保存地址位于 {{ source.hostHint }}。<button type="button" class="text-button" :disabled="revealing" @click="reveal">显示已保存凭据</button></p>
 <label>出站代理<select v-model="form.outboundProxy" name="source-proxy"><option v-if="source" value="__KEEP__">保持现有设置</option><option value="">直接连接</option><option v-for="proxy in proxyOptions" :key="proxy" :value="proxy">{{ proxy }}（可信代理）</option></select></label>
 <p class="muted">{{ proxyOptions.length ? '新增订阅默认使用可信代理，也可手动选择直接连接。此选项仅影响获取订阅时的连接方式。' : '未配置可信代理，新增订阅使用直接连接。' }}</p>
 <div class="form-row"><label>订阅格式<select v-model="form.format"><option value="AUTO">自动识别</option><option value="CLASH_YAML">Clash / Mihomo YAML</option><option value="URI_LIST">节点链接列表</option><option value="BASE64">Base64 节点列表</option></select></label><label>刷新周期（秒）<input v-model.number="form.interval" type="number" min="300" max="2678400" required></label></div>
 <label><input v-model="form.enabled" type="checkbox">启用定时刷新</label><p class="muted">默认每 6 小时刷新。暂停定时任务会保留现有节点。</p>
 <details><summary>高级请求设置</summary><div class="form-grid advanced"><label>请求头（JSON）<textarea v-model="form.headers" rows="4" spellcheck="false" :placeholder="source ? '留空保留；输入 {} 清空请求头' : '{}'"></textarea></label><label>User-Agent<input v-model="form.userAgent" autocomplete="off" placeholder="留空使用默认；编辑时未修改则保留" @input="userAgentChanged = true"></label></div></details>
 <p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton @click="emit('cancel')">取消</ElButton><ElButton native-type="submit" type="primary" :loading="busy">保存订阅</ElButton></div>
</form></template>
<style scoped>.advanced{margin-top:15px}</style>
