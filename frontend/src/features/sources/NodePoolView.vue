<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { ElButton } from 'element-plus'
import NodeRegionSelect from '../nodes/NodeRegionSelect.vue'
import type { NodeSummary, Page, SourceView } from '../../types/domain'
import type { NodeFilters } from './useSources'
const props = defineProps<{ nodes: Page<NodeSummary>; query: NodeFilters; sources: readonly SourceView[]; busy: boolean; allowBinding?: boolean; allowMaintenance?: boolean; localOnly?: boolean; allowSelection?: boolean; selectedIds?: ReadonlySet<string>; selectionLimit?: number }>()
const emit = defineEmits<{ queryChange: [query: NodeFilters]; reveal: [node: NodeSummary]; bind: [node: NodeSummary]; edit: [node: NodeSummary]; remove: [node: NodeSummary]; toggleSelection: [node: NodeSummary]; togglePage: [selected: boolean] }>()
const keyword = shallowRef(props.query.name)
watch(() => props.query.name, name => { keyword.value = name })
const names = computed(() => new Map(props.sources.map(source => [source.id, source.name])))
const pageCount = computed(() => Math.max(1, Math.ceil(props.nodes.total / props.nodes.size)))
const selectable = computed(() => props.nodes.items.filter(node => !node.sourceId))
const pageSelected = computed(() => selectable.value.filter(node => props.selectedIds?.has(node.id)).length)
const allSelected = computed(() => selectable.value.length > 0 && pageSelected.value === selectable.value.length)
const pageExceedsLimit = computed(() => (props.selectedIds?.size ?? 0) + selectable.value.length - pageSelected.value > (props.selectionLimit ?? 500))
function selectionDisabled(node: NodeSummary) {
 return props.busy || !!node.sourceId || (!props.selectedIds?.has(node.id) && (props.selectedIds?.size ?? 0) >= (props.selectionLimit ?? 500))
}
function change(patch: Partial<NodeFilters>) { emit('queryChange', { ...props.query, page: 1, ...patch }) }
function value(event: Event) { return (event.target as HTMLSelectElement).value }
</script>
<style scoped>.source-cell{max-width:220px;overflow-wrap:anywhere}</style>
<template><section class="panel"><div class="node-filters"><form class="search-form" @submit.prevent="change({name: keyword})"><input v-model="keyword" aria-label="搜索节点名称" placeholder="搜索节点名称"><ElButton native-type="submit">搜索</ElButton></form><div class="filter-selects" :class="{'local-filters': localOnly}"><select v-if="!localOnly" :value="query.sourceId" aria-label="筛选来源" @change="change({sourceId: value($event)})"><option value="">全部来源</option><option value="__LOCAL__">本地节点</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select><select :value="query.protocol" aria-label="筛选协议" @change="change({protocol: value($event)})"><option value="">全部协议</option><option v-for="protocol in ['ss','ssr','vmess','vless','trojan','hysteria2','tuic','http','socks5']" :key="protocol" :value="protocol">{{ protocol }}</option></select><NodeRegionSelect :model-value="query.region" label="筛选地区" :disabled="busy" @update:model-value="region => change({region})" /></div></div>
 <div class="scroll-area"><table class="node-table">
  <thead><tr>
   <th v-if="allowSelection" class="selection-cell"><input type="checkbox" aria-label="全选本页手动节点" title="全选本页手动节点" :checked="allSelected" :indeterminate="pageSelected > 0 && !allSelected" :disabled="busy || !selectable.length || (!allSelected && pageExceedsLimit)" @change="emit('togglePage', ($event.target as HTMLInputElement).checked)"></th>
   <th>节点名称</th><th>{{ localOnly ? '来源分组' : '来源' }}</th><th>协议</th><th>地区提示</th><th>操作</th>
  </tr></thead>
  <tbody><tr v-for="node in nodes.items" :key="node.id" :class="{'selected-row': allowSelection && selectedIds?.has(node.id)}">
   <td v-if="allowSelection" class="selection-cell"><input type="checkbox" :aria-label="`选择节点 ${node.originalName}`" :checked="selectedIds?.has(node.id) ?? false" :disabled="selectionDisabled(node)" @change="emit('toggleSelection', node)"></td>
   <td class="node-name">{{ node.originalName }}</td><td class="source-cell">{{ node.sourceId ? names.get(node.sourceId) ?? '上游订阅' : localOnly ? node.localGroupName ?? '未分组' : node.localGroupName ? '本地 · ' + node.localGroupName : '本地节点' }}</td><td><span class="tag">{{ node.protocol }}</span></td><td>{{ node.region === 'UNKNOWN' || !node.region ? '未知' : node.region }}</td>
   <td class="row-actions"><button v-if="!localOnly" class="text-button" @click="emit('reveal', node)">查看参数</button><button v-if="allowBinding && !node.sourceId" class="text-button" @click="emit('bind', node)">关联上游</button><template v-if="allowMaintenance && !node.sourceId"><button class="text-button" :disabled="busy" :aria-label="`编辑 ${node.originalName}`" @click="emit('edit', node)">编辑</button><button class="text-button danger" :disabled="busy" :aria-label="`删除节点 ${node.originalName}`" @click="emit('remove', node)">删除</button></template></td>
  </tr></tbody>
 </table></div>
 <div v-if="!nodes.items.length" class="empty-state"><h2>{{ busy ? '正在读取节点…' : '没有匹配的节点' }}</h2><p>{{ localOnly ? '点击“添加节点”录入连接信息，或调整搜索与筛选条件。' : '添加并刷新订阅，或调整搜索与筛选条件。' }}</p></div>
 <footer class="panel-footer"><span class="muted">共 {{ nodes.total }} 个节点 · 第 {{ nodes.page }} / {{ pageCount }} 页</span><div class="actions"><ElButton :disabled="busy || nodes.page <= 1" @click="change({page: nodes.page - 1})">上一页</ElButton><ElButton :disabled="busy || nodes.page >= pageCount" @click="change({page: nodes.page + 1})">下一页</ElButton></div></footer></section><p class="muted region-note">手动节点优先使用指定地区，其余节点按名称识别。</p></template>
<style scoped>.node-filters{display:grid;gap:12px;padding:18px;border-bottom:1px solid var(--line)}.search-form{display:flex;gap:10px}.filter-selects{display:grid;grid-template-columns:2fr 1fr 1fr;gap:10px}.filter-selects.local-filters{grid-template-columns:repeat(2,minmax(0,1fr))}.node-table{min-width:650px}.node-name{max-width:400px;overflow-wrap:anywhere}.row-actions{white-space:nowrap}.row-actions button+button{margin-left:10px}.region-note{margin-top:16px;font-size:11px}</style>
<style scoped>.selection-cell{width:48px;text-align:center}.selection-cell input{width:16px;height:16px;padding:0;margin:0;vertical-align:middle;accent-color:var(--accent);cursor:pointer}.selection-cell input:disabled{cursor:default}.selected-row{background:var(--accent-soft)}.row-actions button:disabled{opacity:.45;cursor:default}</style>
