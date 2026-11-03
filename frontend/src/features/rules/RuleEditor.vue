<script setup lang="ts">
import { computed, reactive, shallowRef } from 'vue'
import { ElDialog, ElButton } from 'element-plus'
import type { NodeSummary, ProfileDocument, RuleDefinition, TargetRef } from '../../types/domain'
import { referenceKey } from './ruleText'
const props = defineProps<{ rule: RuleDefinition | null; document: ProfileDocument; nodes: readonly NodeSummary[] }>()
const emit = defineEmits<{ apply: [rule: RuleDefinition]; close: [] }>()
const form = reactive({ type: props.rule?.expression.type ?? 'DOMAIN-SUFFIX', value: props.rule?.expression.value ?? '', providerId: props.rule?.expression.providerId ?? '', target: props.rule ? referenceKey(props.rule.target) : 'REJECT', modifiers: props.rule?.modifiers.join(',') ?? '' })
const types = ['DOMAIN','DOMAIN-SUFFIX','DOMAIN-KEYWORD','DOMAIN-WILDCARD','DOMAIN-REGEX','IP-CIDR','IP-CIDR6','GEOIP','GEOSITE','PROCESS-NAME','PROCESS-PATH','DST-PORT','SRC-PORT','NETWORK','RULE-SET','AND','OR','NOT']
if (props.rule?.expression.type && !types.includes(props.rule.expression.type)) types.push(props.rule.expression.type)
if (!props.document.rules.some(rule => rule.expression.type === 'MATCH') && !types.includes('MATCH')) types.push('MATCH')
const choices = computed(() => [{key:'REJECT',label:'REJECT · 拒绝连接'},{key:'DIRECT',label:'DIRECT · 直连'}, ...props.document.groups.map(group => ({key:`GROUP:${group.id}`,label:`组 · ${group.name}`})), ...props.nodes.map(node => ({key:`NODE:${node.id}`,label:`节点 · ${node.originalName} · ${node.sourceId ? node.id.slice(-6) : '本地'}`}))])
const error = shallowRef('')
function apply() {
 error.value = ''
 if (!choices.value.some(choice => choice.key === form.target)) { error.value = '请选择有效目标'; return }
 if (form.type === 'RULE-SET' && !props.document.providers.some(provider => provider.id === form.providerId)) { error.value = '请选择当前方案的规则集'; return }
 if (!['MATCH','RULE-SET'].includes(form.type) && !form.value.trim()) { error.value = '匹配内容不能为空'; return }
 const [kind, id] = form.target.split(':')
 emit('apply', { id: props.rule?.id ?? crypto.randomUUID(), expression: {type:form.type,value:form.type === 'MATCH' ? '' : form.type === 'RULE-SET' ? props.document.providers.find(provider => provider.id === form.providerId)!.name : form.value,providerId:form.type === 'RULE-SET' ? form.providerId : null}, target:{kind:kind as TargetRef['kind'],id:id ?? null,unresolvedName:null}, modifiers:form.type === 'MATCH' ? [] : form.modifiers.split(',').map(value => value.trim()).filter(Boolean),rawText:null,issues:[] })
}
</script>
<template><ElDialog :model-value="true" :title="rule ? '编辑规则' : '添加规则'" width="620px" :close-on-click-modal="false" @close="emit('close')"><form class="form-grid" @submit.prevent="apply"><label>规则类型<select v-model="form.type" aria-label="规则类型"><option v-for="type in types" :key="type" :value="type">{{ type }}</option></select></label><label v-if="form.type === 'RULE-SET'">引用规则集<select v-model="form.providerId" aria-label="引用规则集"><option value="" disabled>选择本方案规则集</option><option v-for="provider in document.providers" :key="provider.id" :value="provider.id">{{ provider.name }}</option></select></label><label v-else-if="form.type !== 'MATCH'">匹配内容<textarea v-model="form.value" rows="3" aria-label="匹配内容" placeholder="例如 example.com 或 192.168.0.0/16"></textarea></label><label>规则目标<select v-model="form.target" aria-label="规则目标"><option v-for="choice in choices" :key="choice.key" :value="choice.key">{{ choice.label }}</option><option v-if="!choices.some(choice => choice.key === form.target)" :value="form.target" disabled>原目标待处理，请重新选择</option></select></label><label v-if="form.type !== 'MATCH'">附加参数<input v-model="form.modifiers" aria-label="规则附加参数" placeholder="可留空；支持的类型可填 no-resolve 或 src"></label><details v-if="rule?.rawText"><summary>查看导入原文</summary><pre>{{ rule.rawText }}</pre></details><p v-if="error" class="error" role="alert">{{ error }}</p><p class="muted">规则按从上到下的顺序匹配，最终 MATCH 保持在末尾。发布前将进行完整校验。</p><div class="dialog-actions"><ElButton @click="emit('close')">取消</ElButton><ElButton type="primary" native-type="submit">应用规则编辑</ElButton></div></form></ElDialog></template>
