<script setup lang="ts">
import { onScopeDispose, shallowRef } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import { errorMessage, request } from '../../api/client'
import type { LocalNodeGroup } from '../../types/domain'
const props = defineProps<{ ids: readonly string[]; groups: readonly LocalNodeGroup[] }>()
const emit = defineEmits<{ close: []; moved: [name:string] }>()
const target = shallowRef(''), saving = shallowRef(false), error = shallowRef('')
const lifetime = new AbortController(); onScopeDispose(() => lifetime.abort())
async function move() {
 if (!target.value || saving.value) return
 const name = target.value === 'ungrouped' ? '未分组' : props.groups.find(g => g.id === target.value)?.name
 if (!name) return
 saving.value = true; error.value = ''
 try {
  await request('/local-node-groups/'+target.value+'/members', {method:'PUT', body:{ids:props.ids}, signal:lifetime.signal})
  if (!lifetime.signal.aborted) emit('moved', name)
 } catch (cause) { if (!lifetime.signal.aborted) error.value = errorMessage(cause) }
 finally { if (!lifetime.signal.aborted) saving.value = false }
}
function close() { if (!saving.value) emit('close') }
</script>
<template>
 <ElDialog :model-value="true" title="移动到来源分组" width="460px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="v => { if (!v) close() }">
  <p>将选中的 <strong>{{ ids.length }} 个节点</strong> 归入同一个来源分组。方案中的代理组保持原样。</p>
  <label class="target-label">目标来源分组<select v-model="target" aria-label="目标来源分组" :disabled="saving"><option disabled value="">请选择分组</option><option value="ungrouped">未分组</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select></label>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <template #footer><div class="actions move-footer"><ElButton :disabled="saving" @click="close">取消移动</ElButton><ElButton type="primary" :disabled="!target" :loading="saving" @click="move">确认移动</ElButton></div></template>
 </ElDialog>
</template>
<style scoped>.target-label{margin:16px 0 8px}.move-footer{justify-content:flex-end}</style>
