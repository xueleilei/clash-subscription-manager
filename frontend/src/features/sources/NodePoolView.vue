<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { ElButton } from 'element-plus'
import type { NodeSummary, Page, SourceView } from '../../types/domain'
import type { NodeFilters } from './useSources'
const props = defineProps<{ nodes: Page<NodeSummary>; query: NodeFilters; sources: readonly SourceView[]; busy: boolean; allowBinding?: boolean }>()
const emit = defineEmits<{ queryChange: [query: NodeFilters]; reveal: [node: NodeSummary]; bind: [node: NodeSummary] }>()
const keyword = shallowRef(props.query.name)
watch(() => props.query.name, name => { keyword.value = name })
const names = computed(() => new Map(props.sources.map(source => [source.id, source.name])))
const pageCount = computed(() => Math.max(1, Math.ceil(props.nodes.total / props.nodes.size)))
function change(patch: Partial<NodeFilters>) { emit('queryChange', { ...props.query, page: 1, ...patch }) }
function value(event: Event) { return (event.target as HTMLSelectElement).value }
</script>
<template><section class="panel"><div class="node-filters"><form class="search-form" @submit.prevent="change({name: keyword})"><input v-model="keyword" aria-label="搜索节点名称" placeholder="搜索节点名称"><ElButton native-type="submit">搜索</ElButton></form><div class="filter-selects"><select :value="query.sourceId" aria-label="筛选来源" @change="change({sourceId: value($event)})"><option value="">全部来源</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select><select :value="query.protocol" aria-label="筛选协议" @change="change({protocol: value($event)})"><option value="">全部协议</option><option v-for="protocol in ['ss','ssr','vmess','vless','trojan','hysteria2','tuic','http','socks5']" :key="protocol" :value="protocol">{{ protocol }}</option></select><select :value="query.region" aria-label="筛选地区" @change="change({region: value($event)})"><option value="">全部地区</option><option v-for="region in ['HK','JP','SG','US','TW','KR','GB','DE','UNKNOWN']" :key="region" :value="region">{{ region === 'UNKNOWN' ? '未知地区' : region }}</option></select></div></div>
 <div class="scroll-area"><table class="node-table"><thead><tr><th>节点名称</th><th>来源</th><th>协议</th><th>地区提示</th><th>操作</th></tr></thead><tbody><tr v-for="node in nodes.items" :key="node.id"><td class="node-name">{{ node.originalName }}</td><td>{{ node.sourceId ? names.get(node.sourceId) ?? '上游订阅' : '本地节点' }}</td><td><span class="tag">{{ node.protocol }}</span></td><td>{{ node.region === 'UNKNOWN' || !node.region ? '未知' : node.region }}</td><td class="row-actions"><button class="text-button" @click="emit('reveal', node)">查看参数</button><button v-if="allowBinding && !node.sourceId" class="text-button" @click="emit('bind', node)">关联上游</button></td></tr></tbody></table></div>
 <div v-if="!nodes.items.length" class="empty-state"><h2>{{ busy ? '正在读取节点…' : '没有匹配的节点' }}</h2><p>添加并刷新订阅，或调整搜索与筛选条件。</p></div>
 <footer class="panel-footer"><span class="muted">共 {{ nodes.total }} 个节点 · 第 {{ nodes.page }} / {{ pageCount }} 页</span><div class="actions"><ElButton :disabled="busy || nodes.page <= 1" @click="change({page: nodes.page - 1})">上一页</ElButton><ElButton :disabled="busy || nodes.page >= pageCount" @click="change({page: nodes.page + 1})">下一页</ElButton></div></footer></section><p class="muted region-note">地区仅按节点名称提示；未知地区不推测，不代表测速或可用性。</p></template>
<style scoped>.node-filters{display:grid;gap:12px;padding:18px;border-bottom:1px solid var(--line)}.search-form{display:flex;gap:10px}.filter-selects{display:grid;grid-template-columns:2fr 1fr 1fr;gap:10px}.node-table{min-width:650px}.node-name{max-width:400px;overflow-wrap:anywhere}.row-actions{white-space:nowrap}.region-note{margin-top:16px;font-size:11px}</style>
