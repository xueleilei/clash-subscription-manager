<script setup lang="ts">
import { onScopeDispose, shallowRef } from 'vue'
import { ElButton } from 'element-plus'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import { errorMessage, request } from '../../api/client'
import { downloadText } from '../rules/ruleText'
import type { Diagnostic } from '../../types/domain'
const props=defineProps<{profileId:string;issues:readonly Diagnostic[]}>(),emit=defineEmits<{resolved:[]}>()
const original=shallowRef<string|null>(null),error=shallowRef(''),acknowledged=shallowRef(false),busy=shallowRef(false),controller=new AbortController()
onScopeDispose(()=>{controller.abort();original.value=null})
async function reveal(){busy.value=true;error.value='';try{const result=await request<{yaml:string}>(`/profiles/${props.profileId}/import-batch`,{signal:controller.signal});if(!controller.signal.aborted)original.value=result.yaml}catch(cause){error.value=errorMessage(cause)}finally{busy.value=false}}
</script>
<template><section v-if="issues.length" class="panel import-issues"><header class="panel-header"><h2>导入内容待处理</h2><ElButton :loading="busy" @click="reveal">查看导入原文</ElButton></header><div class="panel-body"><p class="warning">请根据以下记录迁移或明确舍弃不支持的内容。原始配置保留在服务器，可下载留存。</p><DiagnosticsList :items="issues.slice(0,100)" /><p v-if="issues.length>100">共 {{issues.length}} 条，当前显示前 100 条，请查看完整原文。</p><template v-if="original!==null"><pre class="original-yaml">{{original}}</pre><ElButton @click="downloadText('original-import.yaml',original,'application/yaml')">下载导入原文</ElButton><ElButton @click="original=null">隐藏原文</ElButton></template><p v-if="error" class="error" role="alert">{{error}}</p><label class="resolution"><input v-model="acknowledged" type="checkbox">我已逐项迁移或明确舍弃上述内容</label><ElButton :disabled="!acknowledged" @click="emit('resolved')">清除已处理的导入记录</ElButton><p class="muted resolution-note">此操作只更新当前工作副本，配置仍需通过全部结构和内核校验才能发布。</p></div></section></template>
<style scoped>.import-issues{margin-bottom:20px}.original-yaml{max-height:350px;overflow:auto;padding:14px;background:var(--canvas)}.resolution{margin:22px 0 14px}.resolution-note{margin-top:12px;font-size:11px}</style>
