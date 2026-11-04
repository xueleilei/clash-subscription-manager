<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { ElButton } from 'element-plus'
import NodeRegionSelect from '../nodes/NodeRegionSelect.vue'
import type { NodeSummary, SourceView } from '../../types/domain'
import { nodeSourceLabel } from '../../utils/display'
const props = defineProps<{ nodes: readonly NodeSummary[]; sources: readonly Pick<SourceView, 'id' | 'name'>[]; disabledIds: ReadonlySet<string>; selectedIds: ReadonlySet<string>; hasGroup: boolean }>()
const emit = defineEmits<{ toggle: [id: string]; add: []; selectPage: [ids: string[]] }>()
const keyword = shallowRef(''), source = shallowRef(''), protocol = shallowRef(''), region = shallowRef(''), page = shallowRef(1)
const localSource = '__LOCAL__'
const ungrouped = 'ungrouped'
const localGroup = shallowRef('')
const localOnly = computed(() => source.value === localSource)
const sourceNames = computed(() => new Map(props.sources.map(source => [source.id, source.name])))
const localGroups = computed(() => {
 const groups = new Map<string, string>()
 for (const node of props.nodes) {
  if (!node.sourceId && node.localGroupId && node.localGroupName) groups.set(node.localGroupId, node.localGroupName)
 }
 return Array.from(groups, ([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
})
const filtered = computed(() => {
 const search = keyword.value.toLocaleLowerCase()
 return props.nodes.filter(node => {
  if (localOnly.value) {
   if (node.sourceId || (localGroup.value && (node.localGroupId || ungrouped) !== localGroup.value)) return false
  } else if (source.value && node.sourceId !== source.value) return false
  return (!search || node.originalName.toLocaleLowerCase().includes(search))
   && (!protocol.value || node.protocol === protocol.value)
   && (!region.value || (node.region ?? 'UNKNOWN') === region.value)
 })
})
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / 50))), visible = computed(() => filtered.value.slice((page.value - 1) * 50, page.value * 50))
const regions = computed(() => new Set(props.nodes.map(node => node.region ?? 'UNKNOWN'))), protocols = computed(() => [...new Set(props.nodes.map(node => node.protocol))].sort())
const available = computed(() => visible.value.filter(node => !props.disabledIds.has(node.id)).map(node => node.id))
watch([localOnly, localGroups], ([isLocal, groups]) => {
 if (!isLocal || (localGroup.value && localGroup.value !== ungrouped && !groups.some(group => group.id === localGroup.value))) {
  localGroup.value = ''
 }
})
watch([keyword, source, localGroup, protocol, region], () => { page.value = 1 })
watch(pageCount, count => { if (page.value > count) page.value = count })
</script>
<template>
 <section class="panel picker-column">
  <header class="panel-header"><div><h2>统一节点池</h2><p class="muted">节点可同时属于多个代理组</p></div><span class="tag">{{ nodes.length }} 个节点</span></header>
  <div class="picker-filters">
   <input v-model="keyword" aria-label="筛选候选节点" placeholder="⌕  搜索节点名称">
   <div class="picker-filter-row" :class="{ 'local-filters': localOnly }">
    <select v-model="source" aria-label="候选节点来源">
     <option value="">全部来源</option>
     <option :value="localSource">本地节点</option>
     <option v-for="item in sources" :key="item.id" :value="item.id">{{ item.name }}</option>
    </select>
    <select v-if="localOnly" v-model="localGroup" aria-label="候选节点本地分组">
     <option value="">全部本地分组</option>
     <option :value="ungrouped">未分组</option>
     <option v-for="group in localGroups" :key="group.id" :value="group.id">{{ group.name }}</option>
    </select>
    <NodeRegionSelect v-model="region" label="候选节点地区" :available="regions" />
    <select v-model="protocol" aria-label="候选节点协议">
     <option value="">全部协议</option>
     <option v-for="item in protocols" :key="item" :value="item">{{ item }}</option>
    </select>
   </div>
   <div class="picker-meta"><span>匹配 {{ filtered.length }} 个 · 第 {{ page }} / {{ pageCount }} 页</span><button class="text-button" :disabled="!hasGroup || !available.length" @click="emit('selectPage', available)">选择本页可用</button></div>
  </div>
  <div class="picker-list scroll-area">
   <label v-for="node in visible" :key="node.id" class="picker-row" :class="{added: disabledIds.has(node.id)}">
    <input type="checkbox" :data-node-id="node.id" :aria-label="`${node.originalName}（${nodeSourceLabel(node, sourceNames)}）`"
     :checked="selectedIds.has(node.id)" :disabled="!hasGroup || disabledIds.has(node.id)" @change="emit('toggle', node.id)">
    <span class="node-region">{{ node.region === 'UNKNOWN' ? '—' : node.region ?? '—' }}</span>
    <span class="picker-node"><strong>{{ node.originalName }}</strong><small>{{ nodeSourceLabel(node, sourceNames) }}</small></span>
    <span class="tag" :class="{success: disabledIds.has(node.id)}">{{ disabledIds.has(node.id) ? '已加入' : node.protocol }}</span>
   </label>
   <div v-if="!visible.length" class="empty-state"><h2>没有匹配的节点</h2><p>{{ localOnly ? '请选择其他本地分组或调整筛选条件。' : '调整筛选条件或添加上游订阅。' }}</p></div>
  </div>
  <div v-if="pageCount > 1" class="picker-pagination"><button class="text-button" aria-label="上一页候选节点" :disabled="page === 1" @click="page--">← 上一页</button><button class="text-button" aria-label="下一页候选节点" :disabled="page === pageCount" @click="page++">下一页 →</button></div>
  <footer class="panel-footer"><span class="muted">已选 <strong>{{ selectedIds.size }}</strong> 个节点</span><ElButton type="primary" :disabled="!hasGroup || !selectedIds.size" @click="emit('add')">加入当前组</ElButton></footer>
 </section>
</template>
<style scoped>.picker-filters{padding:14px 16px 0}.picker-filters>input{font-size:12px}.picker-filter-row{display:grid;grid-template-columns:1.2fr 1fr 1fr;gap:7px;margin-top:9px}.picker-filter-row.local-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.picker-filter-row :deep(select){font-size:10px;padding:7px 5px}.picker-meta{display:flex;justify-content:space-between;align-items:center;padding:10px 0;color:var(--muted);font-size:10px}.picker-list{min-height:292px;height:min(425px,50vh);padding:0 16px}.picker-row{display:flex;align-items:center;gap:10px;border-bottom:1px solid var(--line-soft);padding:13px 1px;min-height:60px;cursor:pointer}.picker-row.added{background:var(--field);color:var(--muted)}.picker-row input{flex:none}.node-region{display:grid;place-items:center;width:31px;height:31px;flex:none;background:var(--surface-raised);color:var(--muted);border-radius:6px;font-size:10px}.picker-node{flex:1;min-width:0}.picker-node strong{display:block;font-size:11px;font-weight:500;overflow-wrap:anywhere}.picker-node small{display:block;overflow-wrap:anywhere;font-size:10px;color:var(--muted);margin-top:3px}.picker-row .tag{background:none;font-size:9px}.picker-row .tag.success{background:var(--success-bg)}.picker-pagination{display:flex;justify-content:space-between;padding:8px 16px;border-top:1px solid var(--line);font-size:11px}.panel-footer{font-size:11px}.panel-footer strong{color:var(--accent)}</style>
