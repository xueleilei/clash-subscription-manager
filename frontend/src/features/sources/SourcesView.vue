<script setup lang="ts">
import { computed, onMounted, onScopeDispose, shallowRef, watch } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import ConsoleIcon from '../../components/ConsoleIcon.vue'
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
const nodeCount = computed(() => sources.value.reduce((sum, source) => sum + source.nodeCount, 0))
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
 <header class="source-hero">
   <div class="hero-copy">
     <p class="eyebrow">SHARED RESOURCES</p>
     <h1>上游订阅</h1>
     <p class="muted">统一管理来源，让每套方案使用同一个节点池。</p>
     <dl v-if="sources.length" class="source-metrics">
       <div><dt>订阅来源</dt><dd>{{ sources.length }}</dd></div>
       <div><dt>订阅节点</dt><dd>{{ nodeCount }}</dd></div>
     </dl>
   </div>
   <div class="orbit-art" aria-hidden="true"><small>FIG. 01 <span>/</span> UPLINK</small></div>
 </header>
 <div class="source-toolbar">
   <div class="resource-tabs" role="tablist" aria-label="节点来源">
     <button role="tab" :aria-selected="tab === 'sources'" @click="tab = 'sources'"><ConsoleIcon name="sources"/>订阅来源</button>
     <button role="tab" :aria-selected="tab === 'local'" @click="tab = 'local'"><ConsoleIcon name="nodes"/>手动节点</button>
   </div>
   <div v-if="tab === 'sources'" class="actions">
     <ElButton :disabled="busy" @click="act(reload)">重新读取</ElButton>
     <ElButton :disabled="busy" @click="act(refresh.refreshAll)"><ConsoleIcon name="refresh"/>刷新全部</ElButton>
     <ElButton type="primary" :disabled="busy" @click="edit(null)"><ConsoleIcon name="plus"/>添加订阅</ElButton>
   </div>
 </div>
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
<style scoped>
.source-hero {
  position: relative; isolation: isolate; display: flex; align-items: center;
  min-height: 194px; overflow: hidden; padding: 22px clamp(20px, 2.3vw, 34px);
  border: 1px solid #30475d; border-radius: 12px;
  background: linear-gradient(105deg, #15283b 0%, #111f33 49%, #123045 100%);
}
.source-hero::before {
  position: absolute; z-index: -1; inset: 0; content: ""; pointer-events: none;
  background-image: linear-gradient(#86bde30d 1px, transparent 1px), linear-gradient(90deg, #86bde30d 1px, transparent 1px);
  background-size: 24px 24px; mask-image: linear-gradient(90deg, transparent, #000 75%);
}
.hero-copy { position: relative; z-index: 1; width: 53%; }
.hero-copy h1 { margin: 6px 0; font-size: clamp(30px, 2.8vw, 40px); letter-spacing: -.045em; }
.hero-copy > .muted { color: #b5c7d9; }
.source-metrics { display: flex; gap: 17px; margin: 17px 0 0; }
.source-metrics div { display: flex; align-items: baseline; gap: 8px; }
.source-metrics div + div { border-left: 1px solid #3b5266; padding-left: 17px; }
.source-metrics dt { color: var(--muted); font-size: 11px; }
.source-metrics dd { order: -1; margin: 0; font: 600 19px/1 var(--mono); color: #d6fcf5; }
.orbit-art { position: absolute; inset: 0 0 0 53%; pointer-events: none; background: url('../../assets/uplink-orbit.svg') center / auto 100% no-repeat; }
.orbit-art small { position: absolute; right: 14px; bottom: 10px; font: 9px var(--mono); letter-spacing: .12em; color: #8fadc0; }
.orbit-art small span { margin: 0 3px; color: var(--accent); }
.source-toolbar { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; margin: 20px 0; }
.resource-tabs { display: inline-flex; gap: 3px; padding: 3px; background: #0b1624; border: 1px solid var(--line); border-radius: 8px; }
.resource-tabs button { display: inline-flex; align-items: center; gap: 7px; padding: 6px 13px; border: 1px solid transparent; border-radius: 5px; background: none; color: var(--muted); }
.resource-tabs button[aria-selected=true] { background: var(--accent-soft); color: var(--accent); border-color: var(--accent-line); }
.resource-tabs .console-icon, .actions .console-icon { width: 15px; height: 15px; }
.view-notice { margin-bottom: 18px; }
.history-filter { display: flex; align-items: center; gap: 12px; margin-top: 24px; color: var(--muted); font-size: 12px; }
@media (max-width: 720px) {
  .source-hero { min-height: 166px; padding: 18px 16px; }
  .hero-copy { width: 62%; }
  .hero-copy > .muted { font-size: 12px; }
  .orbit-art { left: 64%; background-size: auto 68%; background-position: 50% 40%; }
  .orbit-art small { font-size: 8px; right: 8px; }
  .source-metrics { gap: 9px; }
  .source-metrics div { gap: 4px; }
  .source-metrics div + div { padding-left: 9px; }
  .source-toolbar { margin: 14px 0; }
}
</style>
