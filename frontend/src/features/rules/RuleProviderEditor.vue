<script setup lang="ts">
import { reactive, shallowRef } from 'vue'
import { ElDialog, ElButton } from 'element-plus'
import type { JsonObject, ProviderDefinition } from '../../types/domain'
const props = defineProps<{ provider: ProviderDefinition | null; providers: readonly ProviderDefinition[] }>()
const emit = defineEmits<{ apply: [provider: ProviderDefinition]; close: [] }>()
const form = reactive({name:props.provider?.name ?? '',url:props.provider?.url ?? '',behavior:props.provider?.behavior ?? 'domain',format:props.provider?.format ?? 'yaml',interval:props.provider?.interval ?? 86400,options:JSON.stringify(props.provider?.options ?? {},null,2)})
const error = shallowRef('')
function apply() {
 error.value = ''
 if (!form.name.trim() || /[,\x00-\x1f\x7f]/.test(form.name) || props.providers.some(provider => provider.id !== props.provider?.id && provider.name === form.name)) { error.value = '规则集名称不能为空、重复或包含逗号及控制字符'; return }
 if (form.format === 'mrs' && form.behavior === 'classical') { error.value = 'mrs 仅支持 domain / ipcidr'; return }
 try { const url = new URL(form.url); if (!['http:','https:'].includes(url.protocol)) throw new Error() } catch { error.value = '规则集地址需要使用 HTTP / HTTPS'; return }
 if (!Number.isInteger(form.interval) || form.interval < 1 || form.interval > 2147483647) { error.value = '更新周期需为正整数秒'; return }
 try { const options: unknown = JSON.parse(form.options); if (!options || typeof options !== 'object' || Array.isArray(options)) throw new Error(); emit('apply',{id:props.provider?.id ?? crypto.randomUUID(),name:form.name,type:'http',url:form.url,behavior:form.behavior,format:form.format,interval:form.interval,options:options as JsonObject}) }
 catch { error.value = '下载参数必须是 JSON 对象' }
}
</script>
<template><ElDialog :model-value="true" :title="provider ? '编辑远程规则集' : '添加远程规则集'" width="600px" :close-on-click-modal="false" @close="emit('close')"><form class="form-grid" @submit.prevent="apply"><label>规则集名称<input v-model="form.name" aria-label="规则集名称" maxlength="256" required></label><label>规则集地址<input v-model="form.url" aria-label="规则集地址" type="url" required placeholder="https://…"></label><div class="form-row"><label>行为<select v-model="form.behavior" aria-label="规则集行为"><option value="domain">domain · 域名</option><option value="ipcidr">ipcidr · IP 段</option><option value="classical">classical · 标准规则</option></select></label><label>格式<select v-model="form.format" aria-label="规则集格式"><option value="yaml">yaml</option><option value="text">text</option><option value="mrs">mrs</option></select></label></div><label>更新周期（秒）<input v-model.number="form.interval" aria-label="规则集更新周期" type="number" min="1" max="2147483647" required></label><details><summary>下载参数（JSON）</summary><textarea v-model="form.options" rows="5" aria-label="规则集下载参数" spellcheck="false"></textarea><p class="muted">可配置 header、size-limit 和内置下载代理。缓存路径按方案生成。</p></details><p class="muted">规则内容由 Clash / Mihomo 客户端下载；请确认地址、格式与行为匹配。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton @click="emit('close')">取消</ElButton><ElButton type="primary" native-type="submit">应用规则集</ElButton></div></form></ElDialog></template>
