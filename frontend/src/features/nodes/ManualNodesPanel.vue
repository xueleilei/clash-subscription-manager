<script setup lang="ts">
import { computed, onMounted, onScopeDispose, shallowRef, useTemplateRef } from 'vue'
import { ElButton } from 'element-plus'
import LocalNodeActions from './LocalNodeActions.vue'
import LocalNodeBatchRemovalDialog from './LocalNodeBatchRemovalDialog.vue'
import LocalNodeGroupBar from './LocalNodeGroupBar.vue'
import LocalNodeGroupManager from './LocalNodeGroupManager.vue'
import LocalNodeMoveDialog from './LocalNodeMoveDialog.vue'
import NodePoolView from '../sources/NodePoolView.vue'
import { useSources, type NodeFilters } from '../sources/useSources'
import { errorMessage, request } from '../../api/client'
import { useProfiles } from '../../layout/useProfiles'
import { onSessionReset } from '../auth/useSession'
import type { LocalNodeBatchResult, LocalNodeGroupDirectory, NodeSummary } from '../../types/domain'
const { nodes, query, reloadNodes } = useSources(); query.sourceId = '__LOCAL__'
const busy = shallowRef(false), error = shallowRef(''), actions = useTemplateRef('actions')
const selectedIds = shallowRef<ReadonlySet<string>>(new Set()), removing = shallowRef<readonly string[] | null>(null), notice = shallowRef('')
const selectionLimit = 500, profiles = useProfiles(), lifetime = new AbortController()
const directory = shallowRef<LocalNodeGroupDirectory | null>(null), managing = shallowRef(false), moving = shallowRef<readonly string[] | null>(null)
const locked = computed(() => busy.value || !!removing.value || !!moving.value || managing.value)
let sequence = 0, disposed = false
function clearSelection() { selectedIds.value = new Set() }
const unregister = onSessionReset(() => { clearSelection(); removing.value = null; moving.value = null; managing.value = false; directory.value = null; query.groupFilter = ''; notice.value = ''; error.value = ''; sequence++ })
onScopeDispose(() => { disposed = true; lifetime.abort(); unregister(); sequence++ })
async function reload(withGroups = true) {
 const current = ++sequence; busy.value = true; error.value = ''
 try {
  if (withGroups) {
   try {
    const result = await request<LocalNodeGroupDirectory>('/local-node-groups', {signal:lifetime.signal})
    if (disposed || current !== sequence) return
    directory.value = result
    if (query.groupFilter && query.groupFilter !== 'ungrouped' && !result.groups.some(g => g.id === query.groupFilter)) {
     query.groupFilter = 'ungrouped'; query.page = 1; clearSelection()
    }
   } catch (cause) {
    if (disposed || current !== sequence) return
    directory.value = null; error.value = errorMessage(cause)
   }
  }
  await reloadNodes()
 } catch (cause) {
  if (!disposed && current === sequence) {
   nodes.value = {items:[], total:0, page:query.page, size:query.size}; clearSelection(); error.value = errorMessage(cause)
  }
 }
 finally { if (!disposed && current === sequence) busy.value = false }
}
function refresh() { clearSelection(); notice.value = ''; void reload() }
function changed() { query.page = 1; refresh() }
function search(filters: NodeFilters) {
 if (locked.value) return
 if (filters.groupFilter !== query.groupFilter || filters.name !== query.name || filters.protocol !== query.protocol || filters.region !== query.region || filters.size !== query.size) clearSelection()
 Object.assign(query, filters, { sourceId: '__LOCAL__' }); void reload(false)
}
function filterGroup(id: string) {
 if (id !== query.groupFilter) { notice.value = ''; search({...query, groupFilter:id, page:1}) }
}
function moveSelected() {
 if (!locked.value && selectedIds.value.size) { moving.value = [...selectedIds.value].sort(); notice.value = '' }
}
function moved(name: string) {
 notice.value = `已将 ${moving.value?.length ?? 0} 个节点移到「${name}」。`
 moving.value = null; clearSelection(); query.page = 1; void reload()
}
function toggleNode(node: NodeSummary) {
 if (locked.value || node.sourceId) return
 const next = new Set(selectedIds.value)
 if (next.has(node.id)) next.delete(node.id)
 else if (next.size < selectionLimit) next.add(node.id)
 selectedIds.value = next
}
function togglePage(selected: boolean) {
 if (locked.value) return
 const next = new Set(selectedIds.value)
 for (const node of nodes.value.items) {
  if (node.sourceId) continue
  if (!selected) next.delete(node.id)
  else next.add(node.id)
 }
 if (next.size <= selectionLimit) selectedIds.value = next
}
function removeSelected() {
 if (!locked.value && selectedIds.value.size) { notice.value = ''; removing.value = [...selectedIds.value].sort() }
}
async function deleted(result: LocalNodeBatchResult) {
 removing.value = null; clearSelection(); query.page = 1
 notice.value = `已删除 ${result.ids.length} 个手动节点。${result.affectedProfiles.length ? '相关方案草稿已更新，请检查后手动发布。' : '节点池已更新。'}`
 const outcomes = await Promise.allSettled([reload(), profiles.reload(lifetime.signal)])
 if (!disposed && outcomes[1]?.status === 'rejected') notice.value += ' 方案状态读取失败，请重新读取。'
}
onMounted(() => void reload())
</script>
<template>
 <div class="toolbar manual-heading"><div><h2>手动节点</h2><p class="muted">按来源整理自己维护的节点，可在多套方案中复用。</p></div><div class="actions"><ElButton :disabled="locked" @click="refresh">重新读取节点</ElButton><ElButton type="primary" :disabled="locked" @click="actions?.create()">添加节点</ElButton></div></div>
 <p v-if="notice" class="notice maintenance-notice" role="status">{{ notice }}</p>
 <p v-if="error" class="error maintenance-error" role="alert">{{ error }}</p>
 <LocalNodeActions ref="actions" :default-group-id="query.groupFilter === 'ungrouped' ? '' : query.groupFilter" @changed="changed" />
 <LocalNodeGroupBar :directory="directory" :filter="query.groupFilter" :busy="locked" @filter="filterGroup" @manage="managing = true" />
 <div class="selection-toolbar"><div><strong aria-live="polite">已选 {{ selectedIds.size }} 个节点</strong><span class="muted">可跨页选择；全选仅选本页，最多 {{ selectionLimit }} 个。</span></div><div class="actions"><ElButton :disabled="!selectedIds.size || locked" @click="clearSelection">清空选择</ElButton><ElButton :disabled="!selectedIds.size || locked || !directory" @click="moveSelected">移动到分组</ElButton><ElButton type="danger" plain :disabled="!selectedIds.size || locked" @click="removeSelected">批量删除</ElButton></div></div>
 <NodePoolView :nodes="nodes" :query="query" :sources="[]" :busy="locked" :local-only="true" :allow-maintenance="true" :allow-selection="true" :selected-ids="selectedIds" :selection-limit="selectionLimit" @toggle-selection="toggleNode" @toggle-page="togglePage" @query-change="search" @reveal="node => actions?.edit(node)" @edit="node => actions?.edit(node)" @remove="node => actions?.remove(node)" />
 <LocalNodeBatchRemovalDialog v-if="removing" :key="removing.join(',')" :ids="removing" @close="removing = null" @deleted="deleted" />
 <LocalNodeMoveDialog v-if="moving" :ids="moving" :groups="directory?.groups ?? []" @close="moving = null" @moved="moved" />
 <LocalNodeGroupManager v-if="managing" :groups="directory?.groups ?? null" :loading="busy" @close="managing = false" @changed="reload()" />
</template>
<style scoped>.manual-heading{margin:22px 0 20px}.manual-heading p{margin-top:6px}.maintenance-error,.maintenance-notice{margin-bottom:18px}.selection-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin:0 0 14px}.selection-toolbar>div:first-child{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.selection-toolbar .muted{font-size:12px}</style>
