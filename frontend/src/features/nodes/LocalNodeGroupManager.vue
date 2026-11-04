<script setup lang="ts">
import { onScopeDispose, shallowRef } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import { errorMessage, request, type RequestOptions } from '../../api/client'
import type { LocalNodeGroup } from '../../types/domain'

const props = defineProps<{ groups: readonly LocalNodeGroup[] | null; loading: boolean }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const name = shallowRef(''), editing = shallowRef<LocalNodeGroup | null>(null), draft = shallowRef(''), removing = shallowRef<LocalNodeGroup | null>(null)
const saving = shallowRef(false), error = shallowRef(''), lifetime = new AbortController()
onScopeDispose(() => lifetime.abort())
function reset() { editing.value = null; removing.value = null; error.value = '' }
function edit(group: LocalNodeGroup) { reset(); editing.value = group; draft.value = group.name }
function remove(group: LocalNodeGroup) { reset(); removing.value = group }
async function write(path: string, options: RequestOptions) {
 if (saving.value || props.loading) return
 saving.value = true; error.value = ''
 try {
  await request('/local-node-groups' + path, {...options, signal: lifetime.signal})
  if (lifetime.signal.aborted) return
  if (options.method === 'POST') name.value = ''
  reset(); emit('changed')
 } catch (cause) { if (!lifetime.signal.aborted) error.value = errorMessage(cause) }
 finally { if (!lifetime.signal.aborted) saving.value = false }
}
function create() { if (name.value.trim()) void write('', { method:'POST', body:{name:name.value.trim()} }) }
function rename(group: LocalNodeGroup) { if (draft.value.trim()) void write('/'+group.id, { method:'PATCH', body:{name:draft.value.trim(),expectedVersion:group.version} }) }
function confirm(group: LocalNodeGroup) { void write('/'+group.id+'?expectedVersion='+group.version, {method:'DELETE'}) }
function close() { if (!saving.value) emit('close') }
</script>
<template>
 <ElDialog :model-value="true" title="管理来源分组" width="560px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="v => { if (!v) close() }">
  <p class="muted">每个手动节点可以归入一个来源分组，例如「自建服务器」或「朋友分享」。</p>
  <form class="group-form" @submit.prevent="create"><input v-model="name" aria-label="新分组名称" placeholder="输入新分组名称" required maxlength="64" :disabled="saving || loading"><ElButton native-type="submit" type="primary" :disabled="!name.trim() || saving || loading">新建分组</ElButton></form>
  <p v-if="error" class="error" role="alert">{{ error }} <button class="text-button" :disabled="saving || loading" @click="reset(); emit('changed')">重新读取分组</button></p>
  <p v-if="!groups" class="error" role="alert">未能读取分组列表。<button class="text-button" :disabled="saving || loading" @click="emit('changed')">重新读取分组</button></p>
  <ul v-else-if="groups.length" class="group-list" aria-label="可管理的来源分组">
   <li v-for="g in groups" :key="g.id">
    <form v-if="editing?.id === g.id" class="group-form" @submit.prevent="rename(editing)"><input v-model="draft" aria-label="修改分组名称" required maxlength="64" :disabled="saving || loading"><ElButton native-type="submit" :disabled="!draft.trim() || saving || loading">保存名称</ElButton><button type="button" class="text-button" :disabled="saving" @click="reset">取消</button></form>
    <template v-else-if="removing?.id === g.id"><p class="warning">删除「{{ removing.name }}」后，节点会回到「未分组」，仍可在原方案中使用。</p><div class="group-buttons"><ElButton :disabled="saving" @click="reset">保留分组</ElButton><ElButton type="danger" plain :disabled="saving || loading" @click="confirm(removing)">确认删除分组</ElButton></div></template>
    <div v-else class="group-row"><span class="group-name">{{ g.name }} <small class="muted">{{ g.nodeCount }} 个节点</small></span><div class="actions"><button class="text-button" :aria-label="'重命名分组 '+g.name" :disabled="saving || loading" @click="edit(g)">重命名</button><button class="text-button danger" :aria-label="'删除分组 '+g.name" :disabled="saving || loading" @click="remove(g)">删除分组</button></div></div>
   </li>
  </ul>
  <p v-else class="muted no-groups">还没有自定义分组。新建后即可归类现有节点。</p>
  <template #footer><ElButton :disabled="saving" @click="close">完成</ElButton></template>
 </ElDialog>
</template>
<style scoped>.group-form,.group-row,.group-buttons{display:flex;gap:8px;align-items:center}.group-form>input{min-width:0}.group-form{margin:14px 0}.group-buttons{margin-top:8px;justify-content:flex-end}.group-row{justify-content:space-between}.group-name{min-width:0;overflow-wrap:anywhere}.group-name small{white-space:nowrap;margin-left:5px}.group-list{list-style:none;margin:12px 0 0;padding:0;max-height:44vh;overflow:auto}.group-list li{padding:10px 0;border-top:1px solid var(--line)}.group-list .group-form{margin:0}.group-row .actions,.group-form button{flex:none}.no-groups{padding:16px 0 4px}</style>
