<script setup lang="ts">
import { computed, onMounted, onScopeDispose, shallowRef, watch } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import ManualNodesPanel from '../nodes/ManualNodesPanel.vue'
import SourceForm from './SourceForm.vue'
import SourceList from './SourceList.vue'
import RefreshStatus from './RefreshStatus.vue'
import EmptyDecisionDialog from './EmptyDecisionDialog.vue'
import SourceRemovalDialog from './SourceRemovalDialog.vue'
import { useSources } from './useSources'
import { useRefreshTasks } from './useRefreshTasks'
import { errorMessage, request } from '../../api/client'
import { useProfiles } from '../../layout/useProfiles'
import type { EmptyDecision, RemovalPreview, SourceCommand, SourceView } from '../../types/domain'
const tab = shallowRef<'sources' | 'local'>('sources')
const { sources, save, reload: reloadSources } = useSources()
const refresh = useRefreshTasks(() => reloadSources(false))
const { profiles } = useProfiles(), profileNames = computed(() => new Map(profiles.value.map(profile => [profile.id, profile.name])))
const busy = shallowRef(false), error = shallowRef(''), formOpen = shallowRef(false), editing = shallowRef<SourceView | null>(null), proxyOptions = shallowRef<string[]>([])
const decision = shallowRef<EmptyDecision | null>(null), decisionError = shallowRef(''), dismissed = new Set<string>()
const removal = shallowRef<{ preview: RemovalPreview; sourceName: string } | null>(null), removalError = shallowRef(''), historySource = shallowRef<string | null>(null)
const lifetime = new AbortController()
onScopeDispose(() => lifetime.abort())
const shownTasks = computed(() => refresh.tasks.value.filter(task => !historySource.value || task.sourceId === historySource.value).slice(0, 30))
const sourceNames = computed(() => new Map(sources.value.map(source => [source.id, source.name])))
watch(refresh.decisions, values => { if (!decision.value) decision.value = values.find(value => !dismissed.has(value.id)) ?? null })
async function act(action: () => Promise<unknown>) { if (busy.value) return; busy.value = true; error.value = ''; try { await action() } catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false } }
async function reload() { await reloadSources(false); await refresh.load(sources.value.map(source => source.id)) }
onMounted(() => { void act(async () => { await reload(); proxyOptions.value = await request<string[]>('/sources/proxy-options') }) })
function edit(source: SourceView | null) { if (busy.value) return; editing.value = source; formOpen.value = true }
async function submit(command: SourceCommand) { await act(async () => { await save(command); formOpen.value = false }) }
async function toggle(source: SourceView) { await act(() => save({ id: source.id, expectedVersion: source.configVersion, name: source.name, enabled: !source.enabled, inputFormat: source.inputFormat, refreshIntervalSeconds: source.refreshIntervalSeconds })) }
function closeEmpty() { if (decision.value) dismissed.add(decision.value.id); decision.value = null; decisionError.value = '' }
async function chooseEmpty(choice: 'KEEP' | 'CLEAR') {
 if (!decision.value || busy.value) return
 busy.value = true; decisionError.value = ''
 try { await refresh.decideEmpty(decision.value.id, choice); closeEmpty() } catch (cause) { decisionError.value = errorMessage(cause) } finally { busy.value = false }
}
async function previewRemoval(source: SourceView) {
 if (busy.value) return
 const selected = { id: source.id, name: source.name }; removalError.value = ''
 await act(async () => {
  const preview = await request<RemovalPreview>(`/sources/${selected.id}/removal/preview`, { method: 'POST', signal: lifetime.signal })
  if (!lifetime.signal.aborted && preview.sourceId === selected.id) removal.value = { preview, sourceName: selected.name }
 })
}
async function remove(mode: 'KEEP_LOCAL' | 'CLEAR') {
 if (!removal.value || busy.value) return
 busy.value = true; removalError.value = ''
 try { await request(`/source-removals/${removal.value.preview.id}/apply`, { method: 'POST', body: { mode }, signal: lifetime.signal }); removal.value = null; await reload() } catch (cause) { removalError.value = errorMessage(cause) } finally { busy.value = false }
}
</script>
<template>
 <header class="page-heading"><p class="eyebrow">SHARED RESOURCES</p><div class="toolbar"><div><h1>上游订阅</h1><p class="muted">统一管理来源，让每套方案使用同一个节点池。</p></div><div v-if="tab === 'sources'" class="actions"><ElButton :disabled="busy" @click="act(reload)">重新读取</ElButton><ElButton :disabled="busy" @click="act(refresh.refreshAll)">刷新全部</ElButton><ElButton type="primary" :disabled="busy" @click="edit(null)">添加订阅</ElButton></div></div></header>
 <div class="resource-tabs" role="tablist" aria-label="节点来源"><button role="tab" :aria-selected="tab === 'sources'" @click="tab = 'sources'">订阅来源</button><button role="tab" :aria-selected="tab === 'local'" @click="tab = 'local'">手动节点</button></div>
 <ManualNodesPanel v-if="tab === 'local'" />
 <template v-else>
 <p v-if="error || refresh.error.value" class="error view-notice" role="alert">{{ error || refresh.error.value }}</p>
 <div v-if="refresh.decisions.value.length" class="warning toolbar view-notice"><span>{{ refresh.decisions.value.length }} 个订阅返回空节点，旧数据仍保留。</span><button class="text-button" @click="decision = refresh.decisions.value[0] ?? null; decisionError = ''">处理空结果</button></div>
 <SourceList :sources="sources" :busy="busy" @edit="edit" @refresh="id => act(() => refresh.refreshOne(id))" @toggle="toggle" @remove="previewRemoval" @history="source => historySource = source.id" />
 <div v-if="historySource" class="history-filter"><span>正在查看 {{ sourceNames.get(historySource) }} 的记录</span><button class="text-button" @click="historySource = null">显示所有来源</button></div>
 <RefreshStatus :tasks="shownTasks" :sources="sources" />
 </template>
 <ElDialog v-model="formOpen" :title="editing ? '编辑订阅' : '添加订阅'" width="560px" :close-on-click-modal="false" destroy-on-close><p v-if="error" class="error view-notice" role="alert">{{ error }}</p><SourceForm v-if="formOpen" :source="editing" :busy="busy" :proxy-options="proxyOptions" @submit="submit" @cancel="formOpen = false" /></ElDialog>
 <EmptyDecisionDialog v-if="decision" :decision="decision" :source-name="sourceNames.get(decision.sourceId) ?? '订阅来源'" :busy="busy" :error="decisionError" @choose="chooseEmpty" @close="closeEmpty" />
 <SourceRemovalDialog v-if="removal" :preview="removal.preview" :source-name="removal.sourceName" :profile-names="profileNames" :busy="busy" :error="removalError" @choose="remove" @close="removal = null" />
</template>
<style scoped>.resource-tabs{display:flex;gap:24px;border-bottom:1px solid var(--line);margin-bottom:22px}.resource-tabs button{border:0;border-bottom:2px solid transparent;background:none;padding:10px 4px;color:var(--muted)}.resource-tabs button[aria-selected=true]{border-color:var(--accent);color:var(--accent);font-weight:600}.view-notice{margin-bottom:18px}.history-filter{display:flex;align-items:center;gap:12px;margin-top:24px;color:var(--muted);font-size:12px}</style>
