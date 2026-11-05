<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { ElButton } from 'element-plus'
import type { GroupDefinition, NodeSummary, SourceView } from '../../types/domain'
import { nodeSourceLabel } from '../../utils/display'
import { targetKey } from './groupActions'
const props = defineProps<{ group: GroupDefinition | null; groups: readonly GroupDefinition[]; nodes: readonly NodeSummary[]; sources: readonly Pick<SourceView, 'id' | 'name'>[] }>()
const emit = defineEmits<{ remove: [indices: readonly number[]]; reorder: [from: number, to: number]; edit: []; reference: []; builtin: [kind: 'DIRECT' | 'REJECT'] }>()
const rows = computed(() => {
 const nodes = new Map(props.nodes.map(node => [node.id, node])), groups = new Map(props.groups.map(group => [group.id, group.name])), sources = new Map(props.sources.map(source => [source.id, source.name])), occurrences = new Map<string, number>()
 return (props.group?.members ?? []).map((target, index) => {
  const node = target.kind === 'NODE' ? nodes.get(target.id!) : undefined, base = targetKey(target), occurrence = occurrences.get(base) ?? 0; occurrences.set(base, occurrence + 1)
  return { key: `${base}:${occurrence}`, index, name: target.kind === 'NODE' ? node?.originalName ?? '已失效的节点' : target.kind === 'GROUP' ? groups.get(target.id!) ?? '未找到的代理组' : target.kind === 'UNRESOLVED' ? target.unresolvedName ?? '待处理引用' : target.kind,
   badge: node?.region && node.region !== 'UNKNOWN' ? node.region : target.kind === 'GROUP' ? '组' : '↗',
   description: node ? `${nodeSourceLabel(node, sources)} · ${node.protocol}` : target.kind === 'GROUP' ? '嵌套代理组' : target.kind === 'UNRESOLVED' ? '请移除后重新选择' : '内置策略', missing: target.kind === 'NODE' && !node || target.kind === 'UNRESOLVED' }
 })
})
const hasDirect = computed(() => props.group?.members.some(member => member.kind === 'DIRECT'))
const hasReject = computed(() => props.group?.members.some(member => member.kind === 'REJECT'))
const selectedKeys = shallowRef(new Set<string>())
const selectedRows = computed(() => rows.value.filter(row => selectedKeys.value.has(row.key)))
const allSelected = computed(() => rows.value.length > 0 && selectedRows.value.length === rows.value.length)
function clearSelection() { selectedKeys.value = new Set() }
function toggle(key: string) {
 const next = new Set(selectedKeys.value)
 if (next.has(key)) next.delete(key)
 else next.add(key)
 selectedKeys.value = next
}
function toggleAll() { selectedKeys.value = allSelected.value ? new Set() : new Set(rows.value.map(row => row.key)) }
function removeSelected() {
 if (!selectedRows.value.length) return
 emit('remove', selectedRows.value.map(row => row.index))
 clearSelection()
}
// Sorting preserves selection; additions/removals reset it, including ambiguous duplicate references.
watch([() => props.group?.id, () => JSON.stringify(props.group?.members.map(targetKey).sort())], clearSelection, { flush: 'sync' })
</script>
<template>
 <section class="panel member-column">
  <header class="panel-header">
   <div><h2>{{ group?.name ?? '当前组成员' }}</h2><p class="muted">{{ group ? `${group.type} · ${group.members.length} 个成员` : '从左侧选择或新建代理组' }}</p></div>
   <button v-if="group" class="icon-button" aria-label="编辑当前代理组" title="编辑代理组" @click="emit('edit')">⚙</button>
  </header>
  <div v-if="group" class="member-selection">
   <div class="member-selection-row">
    <label class="member-select-all"><input type="checkbox" aria-label="全选当前代理组成员" :checked="allSelected" :indeterminate="selectedRows.length > 0 && !allSelected" :disabled="!rows.length" @change="toggleAll"><span>全选</span></label>
    <span class="muted">已选 {{ selectedRows.length }} / {{ rows.length }}</span>
    <div class="member-selection-actions">
     <button class="text-button" aria-label="清空成员选择" :disabled="!selectedRows.length" @click="clearSelection">清空选择</button>
     <ElButton size="small" type="danger" plain aria-label="批量移除当前组成员" :disabled="!selectedRows.length" @click="removeSelected">批量移除</ElButton>
    </div>
   </div>
   <p class="member-selection-hint">仅移除当前组中的引用，节点仍保留在节点池。</p>
  </div>
  <div class="member-scroll scroll-area">
   <article v-for="row in rows" :key="row.key" data-member-row :data-member-key="row.key" class="member-row" :class="{ selected: selectedKeys.has(row.key) }">
    <input class="member-checkbox" type="checkbox" :aria-label="`选择成员 ${row.name}（${row.description}）`" :checked="selectedKeys.has(row.key)" @change="toggle(row.key)">
    <span class="node-badge" :class="{nested: row.description === '嵌套代理组'}">{{ row.badge }}</span>
    <div class="member-text"><strong :class="{danger: row.missing}">{{ row.name }}</strong><p class="muted">{{ row.description }}</p></div>
    <div class="member-actions">
     <button class="icon-button" :disabled="row.index === 0" :aria-label="`上移 ${row.name}`" @click="emit('reorder', row.index, row.index - 1)">↑</button>
     <button class="icon-button" :disabled="row.index === rows.length - 1" :aria-label="`下移 ${row.name}`" @click="emit('reorder', row.index, row.index + 1)">↓</button>
     <button class="icon-button" :aria-label="`移除 ${row.name}`" @click="emit('remove', [row.index])">×</button>
    </div>
   </article>
   <div v-if="!rows.length" class="empty-state"><h2>{{ group ? '还没有添加成员' : '选择一个代理组' }}</h2><p>{{ group ? '从右侧节点池选择，再加入当前组。' : '所有分组与规则仅属于当前方案。' }}</p><span v-if="group" class="tag empty-fallback">空组 → REJECT</span></div>
  </div>
  <footer v-if="group" class="panel-footer member-footer">
   <button class="text-button" @click="emit('reference')">引用其他组</button>
   <div class="builtin-actions"><button class="text-button" :disabled="hasDirect" @click="emit('builtin', 'DIRECT')">＋ DIRECT</button><button class="text-button" :disabled="hasReject" @click="emit('builtin', 'REJECT')">＋ REJECT</button></div>
  </footer>
 </section>
</template>
<style scoped>
.member-selection { padding: 12px 14px; border-bottom: 1px solid var(--line); }
.member-selection-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 11px; }
.member-select-all { display: flex; align-items: center; gap: 6px; margin: 0; font-size: inherit; cursor: pointer; }
.member-select-all input, .member-checkbox { flex: none; }
.member-selection-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.member-selection-hint { margin: 8px 0 0; color: var(--muted); font-size: 10px; line-height: 1.5; }
.member-row.selected { background: var(--accent-soft); }
.member-column{display:flex;flex-direction:column;align-self:start}.member-scroll{min-height:292px;max-height:520px;padding:6px 14px}.member-row{display:flex;align-items:center;gap:10px;padding:14px 0;border-bottom:1px solid var(--line-soft)}.member-row:last-child{border:0}.member-text{min-width:0;flex:1}.member-text strong{font-size:12px;display:block;overflow-wrap:anywhere;font-weight:500}.member-text p{overflow-wrap:anywhere;font-size:10px;margin-top:4px}.node-badge{display:grid;place-items:center;flex:none;background:var(--accent-soft);border-radius:7px;width:32px;height:32px;color:var(--accent);font-size:10px}.node-badge.nested{background:var(--surface-raised);color:var(--muted)}.member-actions{display:flex;gap:1px;color:var(--muted)}.member-actions .icon-button{font-size:13px;padding:4px}.member-footer{flex-wrap:wrap;font-size:11px}.builtin-actions{display:flex;gap:6px;font-size:10px}.empty-fallback{display:inline-block;margin-top:15px}.empty-state h2{font-size:13px}.empty-state p{font-size:11px;line-height:1.8}
</style>
