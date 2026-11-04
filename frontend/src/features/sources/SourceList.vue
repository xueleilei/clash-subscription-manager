<script setup lang="ts">
import ConsoleIcon from '../../components/ConsoleIcon.vue'
import { localTime, statusLabel, statusTone } from '../../utils/display'
import type { SourceView } from '../../types/domain'
defineProps<{ sources: readonly SourceView[]; busy: boolean }>()
const emit = defineEmits<{ edit: [source: SourceView]; refresh: [id: string]; toggle: [source: SourceView]; remove: [source: SourceView]; history: [source: SourceView] }>()
</script>
<template><div v-if="sources.length" class="source-grid"><article v-for="(source, index) in sources" :key="source.id" class="panel source-card" :data-source-id="source.id">
 <div class="feed-line"><small aria-hidden="true">FEED / {{ String(index + 1).padStart(2, '0') }}</small><span class="tag status-tag" :class="statusTone(source.syncStatus)">{{ statusLabel(source.syncStatus) }}</span></div>
 <header><div class="source-icon"><ConsoleIcon name="sources"/></div><div class="truncate"><h2 :title="source.name">{{ source.name }}</h2><p class="muted truncate">{{ source.hostHint }}</p></div></header>
 <div class="source-count"><strong>{{ source.nodeCount }}</strong><span class="muted">个节点</span><span class="format">{{ source.inputFormat === 'AUTO' ? '自动识别' : source.inputFormat }}</span></div>
 <dl><div><dt>连接方式</dt><dd>{{ source.outboundProxy === undefined ? '未读取' : source.outboundProxy ? `可信代理 · ${source.outboundProxy}` : '直接连接' }}</dd></div><div><dt>最近成功</dt><dd>{{ localTime(source.lastSuccessTime) }}</dd></div><div><dt>定时刷新</dt><dd>{{ source.enabled ? `每 ${source.refreshIntervalSeconds / 3600} 小时` : '已暂停' }}</dd></div><div><dt>下次刷新</dt><dd>{{ source.enabled ? localTime(source.nextRefreshTime) : '—' }}</dd></div></dl>
 <p v-if="source.lastErrorCode" class="error">{{ source.lastErrorCode }} · 已保留上次有效节点</p>
 <footer><button class="text-button" :aria-label="`刷新 ${source.name}`" :disabled="busy" @click="emit('refresh', source.id)">↻ 刷新</button><button class="text-button" :aria-label="`编辑 ${source.name}`" :disabled="busy" @click="emit('edit', source)">编辑</button><button class="text-button" :aria-label="`${source.enabled ? '暂停' : '启用'} ${source.name}`" :disabled="busy" @click="emit('toggle', source)">{{ source.enabled ? '暂停' : '启用' }}</button><button class="text-button" @click="emit('history', source)">记录</button><button class="text-button danger" :aria-label="`删除 ${source.name}`" :disabled="busy" @click="emit('remove', source)">删除</button></footer>
</article></div>
<div v-else class="panel empty-state"><div class="empty-icon"><ConsoleIcon name="sources"/></div><h2>添加你的第一个订阅</h2><p>多个来源的节点会汇入统一节点池，再由你分配到不同代理组。</p></div>
</template>
<style scoped>
.source-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 284px), 1fr)); gap: 16px; }
.source-card { display: flex; flex-direction: column; padding: 16px 18px 0; background: linear-gradient(145deg, #142335, var(--surface) 65%); }
.source-card:hover { border-color: #425d73; }
.feed-line, header, .source-count, dl div, footer { display: flex; align-items: center; }
.feed-line { justify-content: space-between; margin-bottom: 16px; }
.feed-line small, .format { font: 10px/1.4 var(--mono); color: var(--subtle); letter-spacing: .1em; }
header { gap: 10px; }
header h2 { overflow: hidden; text-overflow: ellipsis; }
header p { margin-top: 2px; font: 11px/1.5 var(--mono); }
.source-icon, .empty-icon { display: grid; place-items: center; flex: none; width: 36px; height: 36px; border: 1px solid var(--accent-line); border-radius: 8px; background: var(--accent-soft); color: var(--accent); }
.empty-icon { margin: 0 auto 14px; width: 44px; height: 44px; }
.source-count { align-items: baseline; gap: 7px; margin: 17px 0 14px; }
.source-count strong { font: 500 34px/1 var(--mono); letter-spacing: -.06em; }
.source-count .muted, .format { font-size: 11px; }
.format { margin-left: auto; letter-spacing: .03em; }
dl { display: grid; gap: 7px; margin: 0 0 16px; }
dl div { justify-content: space-between; align-items: baseline; gap: 10px; font-size: 12px; }
dt { flex: none; color: var(--muted); }
dd { margin: 0; text-align: right; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
footer { flex-wrap: wrap; gap: 12px; margin-top: auto; padding: 10px 0; border-top: 1px solid var(--line); font-size: 12px; }
footer .danger { margin-left: auto; }
.source-card > .error { margin: 0 0 12px; overflow-wrap: anywhere; font-size: 12px; }
</style>
