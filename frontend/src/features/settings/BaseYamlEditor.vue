<script setup lang="ts">
import { markRaw, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { basicSetup, EditorView } from 'codemirror'
import { yaml } from '@codemirror/lang-yaml'
import { editorTheme } from '../../components/editorTheme'
import { ElButton } from 'element-plus'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import type { Diagnostic } from '../../types/domain'
const props = defineProps<{ modelValue: string; diagnostics: readonly Diagnostic[]; busy?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value:string]; validate: []; manage: [page:'groups'|'rules'|'providers'] }>()
const host = shallowRef<HTMLDivElement | null>(null), editor = shallowRef<EditorView | null>(null)
let applyingExternal = false
onMounted(() => { editor.value = markRaw(new EditorView({parent:host.value!,doc:props.modelValue,extensions:[basicSetup,yaml(),editorTheme,EditorView.lineWrapping,EditorView.contentAttributes.of({'aria-label':'基础 YAML 编辑器'}),EditorView.updateListener.of(update=>{if(update.docChanged&&!applyingExternal)emit('update:modelValue',update.state.doc.toString())}),EditorView.theme({'.cm-scroller':{minHeight:'420px',maxHeight:'65vh',overflow:'auto'}})]})) })
watch(()=>props.modelValue,value=>{const view=editor.value;if(view&&view.state.doc.toString()!==value){applyingExternal=true;try{view.dispatch({changes:{from:0,to:view.state.doc.length,insert:value}})}finally{applyingExternal=false}}})
onBeforeUnmount(()=>{editor.value?.destroy();editor.value=null})
</script>
<template><section class="panel"><header class="panel-header"><div><h2>基础 YAML</h2><p class="muted">DNS、端口、嗅探等设置；当前文本会随草稿保存。</p></div><ElButton :loading="busy" @click="emit('validate')">校验基础设置</ElButton></header><div class="base-help"><p>以下内容由专门页面维护：<button class="text-button" @click="emit('manage','groups')">proxies / proxy-groups → 代理组</button>、<button class="text-button" @click="emit('manage','rules')">rules → 分流规则</button>、<button class="text-button" @click="emit('manage','providers')">rule-providers → 远程规则集</button>。</p></div><div ref="host" class="yaml-host"></div><div class="panel-body" v-if="diagnostics.length"><DiagnosticsList :items="diagnostics" /></div></section></template>
<style scoped>.base-help{padding:14px 18px;border-bottom:1px solid var(--line);font-size:11px;color:var(--muted)}.yaml-host{min-height:420px;max-width:100%;overflow:hidden}</style>
