<script setup lang="ts">
import { computed, onMounted, onScopeDispose, shallowRef } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import { errorMessage, request } from '../../api/client'
import { useProfiles } from '../../layout/useProfiles'
import type { LocalNodeBatchRemoval, LocalNodeBatchResult } from '../../types/domain'

const props = defineProps<{ ids: readonly string[] }>()
const emit = defineEmits<{ close: []; deleted: [result: LocalNodeBatchResult] }>()
const profiles = useProfiles(), names = computed(() => new Map(profiles.profiles.value.map(profile => [profile.id, profile.name])))
const preview = shallowRef<LocalNodeBatchRemoval | null>(null), loading = shallowRef(false), saving = shallowRef(false), error = shallowRef('')
const lifetime = new AbortController()
let disposed = false
const canDelete = computed(() => !!preview.value && !loading.value && !saving.value && !error.value && !preview.value.dependentNodes.length)
onScopeDispose(() => { disposed = true; lifetime.abort() })

async function inspect() {
 if (loading.value || saving.value) return
 loading.value = true; error.value = ''; preview.value = null
 try {
  const [result] = await Promise.all([
   request<LocalNodeBatchRemoval>('/local-nodes/batch/removal', { method: 'POST', body: { ids: props.ids }, signal: lifetime.signal }),
   profiles.reload(lifetime.signal),
  ])
  if (!disposed) preview.value = result
 } catch (cause) { if (!disposed) error.value = errorMessage(cause) }
 finally { if (!disposed) loading.value = false }
}
async function remove() {
 const captured = preview.value
 if (!canDelete.value || !captured) return
 saving.value = true
 try {
  const result = await request<LocalNodeBatchResult>('/local-nodes/batch', {
   method: 'DELETE', signal: lifetime.signal,
   body: { nodes: captured.nodes.map(node => ({ id: node.id, expectedVersion: node.version })), ownerRevision: captured.ownerRevision },
  })
  if (!disposed) emit('deleted', result)
 } catch (cause) { if (!disposed) error.value = errorMessage(cause) }
 finally { if (!disposed) saving.value = false }
}
function close() { if (!saving.value) emit('close') }
onMounted(() => void inspect())
</script>
<template>
 <ElDialog :model-value="true" title="批量删除手动节点" width="700px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="value => { if (!value) close() }">
  <p v-if="loading" class="muted" role="status">正在检查 {{ ids.length }} 个节点的删除影响…</p>
  <p v-if="error" class="error removal-error" role="alert">{{ error }}</p>
  <template v-if="preview">
   <p>确定删除以下 <strong>{{ preview.nodes.length }} 个手动节点</strong>？</p>
   <ul class="selected-nodes" aria-label="待删除的手动节点"><li v-for="node in preview.nodes" :key="node.id">{{ node.name }}</li></ul>
   <div class="impact"><h3>将更新以下方案草稿</h3><p>{{ preview.affectedProfiles.map(id => names.get(id) ?? id).join('、') || '没有方案草稿引用这些节点' }}</p><p class="muted">从代理组移除所选节点；直接指向这些节点的规则将改为 REJECT。</p></div>
   <p v-if="preview.publishedProfiles.length" class="notice">{{ preview.publishedProfiles.map(id => names.get(id) ?? id).join('、') }} 的已发布订阅仍保留旧节点，检查后手动发布才会更新。</p>
   <div v-if="preview.dependentNodes.length" class="warning dependencies" role="alert"><p>以下未选中的节点仍依赖所选节点，请先解除链式依赖，或返回列表一并选择这些手动节点：</p><ul><li v-for="node in preview.dependentNodes" :key="node.id">{{ node.name }}</li></ul></div>
  </template>
  <template #footer>
   <div class="dialog-actions"><ElButton :disabled="saving" @click="close">取消</ElButton><ElButton v-if="error" :disabled="loading || saving" @click="inspect">重新检查影响</ElButton><ElButton type="danger" :loading="saving" :disabled="!canDelete" @click="remove">确认删除 {{ ids.length }} 个节点</ElButton></div>
  </template>
 </ElDialog>
</template>
<style scoped>
.selected-nodes{max-height:180px;overflow:auto;padding:12px 18px 12px 34px;margin:14px 0;border:1px solid var(--line);border-radius:8px;background:var(--canvas);overflow-wrap:anywhere}
.selected-nodes li+li,.dependencies li+li{margin-top:6px}.impact{display:grid;gap:8px;margin:20px 0}.dependencies{margin-top:16px;overflow-wrap:anywhere}.dependencies ul{max-height:150px;overflow:auto;margin:8px 0 0;padding-left:20px}.removal-error{margin-bottom:16px}
</style>
