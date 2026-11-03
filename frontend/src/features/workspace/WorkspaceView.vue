<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElDialog } from 'element-plus'
import GroupList from './GroupList.vue'
import GroupMemberList from './GroupMemberList.vue'
import NodePicker from './NodePicker.vue'
import GroupEditorDialog from './GroupEditorDialog.vue'
import DraftStatusBar from './DraftStatusBar.vue'
import ConflictDialog from './ConflictDialog.vue'
import ProfileActions from './ProfileActions.vue'
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import { useProfileDraft } from './useProfileDraft'
import { useNodeSelection } from './useNodeSelection'
import { groupReferences, targetKey, wouldCycle } from './groupActions'
import { readNodeSummaries } from '../../api/nodes'
import { ApiError, errorMessage, request } from '../../api/client'
import { useProfiles } from '../../layout/useProfiles'
import { useSession } from '../auth/useSession'
import type { GroupDefinition, NodeSummary, SourceView, TargetRef } from '../../types/domain'
const route = useRoute(), session = useSession(), profileId = computed(() => String(route.params.id ?? ''))
const state = useProfileDraft(profileId), catalog = useProfiles()
const profile = computed(() => catalog.profiles.value.find(profile => profile.id === profileId.value) ?? null)
const nodes = shallowRef<NodeSummary[]>([]), sources = shallowRef<SourceView[]>([]), selectedId = shallowRef<string | null>(null)
const selection = useNodeSelection(selectedId), busy = shallowRef(false), error = shallowRef(''), conflictOpen = shallowRef(false), conflictError = shallowRef('')
const groupOpen = shallowRef(false), editingId = shallowRef<string | null>(null), referenceOpen = shallowRef(false), referenceId = shallowRef('')
const groups = computed(() => state.draft.value?.groups ?? []), selectedGroup = computed(() => groups.value.find(group => group.id === selectedId.value) ?? null)
const editingGroup = computed(() => groups.value.find(group => group.id === editingId.value) ?? null)
const references = computed(() => state.draft.value && editingId.value ? groupReferences(state.draft.value, editingId.value) : [])
const disabledIds = computed(() => new Set(selectedGroup.value?.members.filter(member => member.kind === 'NODE').map(member => member.id!) ?? []))
const usedNodes = computed(() => new Set(groups.value.flatMap(group => group.members.filter(member => member.kind === 'NODE').map(member => member.id))).size)
const matchesPublished = computed(() => Boolean(profile.value?.publishedDefinitionHash && profile.value.draftVersion === state.version.value && profile.value.draftDefinitionHash === profile.value.publishedDefinitionHash))
const refOptions = computed(() => groups.value.filter(group => group.id !== selectedId.value).map(group => ({ id: group.id, name: group.name, disabled: !selectedId.value || !selectedGroup.value || wouldCycle(groups.value, selectedId.value, group.id) || selectedGroup.value!.members.some(member => member.kind === 'GROUP' && member.id === group.id) })))
let resourceRead: AbortController | null = null, resourceSequence = 0, disposed = false
async function reloadResources() {
 const current = ++resourceSequence, owner = session.ownerId.value; resourceRead?.abort(); resourceRead = new AbortController()
 try {
  const [values, subscriptionSources] = await Promise.all([readNodeSummaries(resourceRead.signal), request<SourceView[]>('/sources', { signal: resourceRead.signal }), catalog.reload(resourceRead.signal)])
  if (current === resourceSequence && owner === session.ownerId.value && !disposed) { nodes.value = values; sources.value = subscriptionSources }
 } catch (cause) { if (current === resourceSequence && !disposed) error.value = errorMessage(cause) }
}
watch(session.ownerId, () => { nodes.value = []; sources.value = []; void reloadResources() }, { immediate: true })
watch(state.latest, (_latest, previous) => { if (previous) void reloadResources() })
watch(profileId, () => { selectedId.value = null; selection.clear(); groupOpen.value = false; referenceOpen.value = false; conflictOpen.value = false; error.value = '' })
watch(() => groups.value.map(group => group.id).join(','), () => { if (!groups.value.some(group => group.id === selectedId.value)) selectedId.value = groups.value[0]?.id ?? null }, { immediate: true })
onScopeDispose(() => { disposed = true; resourceSequence++; resourceRead?.abort() })
function changeGroups(next: GroupDefinition[]) { if (state.draft.value) state.updateDocument({ ...state.draft.value, groups: next }) }
function changeMembers(members: TargetRef[]) { if (selectedGroup.value) changeGroups(groups.value.map(group => group.id === selectedId.value ? { ...group, members } : group)) }
function addTargets(targets: TargetRef[]) {
 if (!selectedGroup.value) return
 const seen = new Set(selectedGroup.value.members.map(targetKey)), next = [...selectedGroup.value.members]
 for (const target of targets) if (!seen.has(targetKey(target))) { next.push(target); seen.add(targetKey(target)) }
 changeMembers(next)
}
function addNodes() { const active = new Set(nodes.value.map(node => node.id)); addTargets([...selection.selectedIds.value].filter(id => active.has(id)).map(id => ({ kind: 'NODE', id, unresolvedName: null }))); selection.clear() }
function reorder(from: number, to: number) { const members = [...(selectedGroup.value?.members ?? [])]; if (from < 0 || to < 0 || from >= members.length || to >= members.length) return; members.splice(to, 0, members.splice(from, 1)[0]!); changeMembers(members) }
function editGroup(id: string | null) { editingId.value = id; groupOpen.value = true }
function saveGroup(group: GroupDefinition) { changeGroups(groups.value.some(value => value.id === group.id) ? groups.value.map(value => value.id === group.id ? group : value) : [...groups.value, group]); selectedId.value = group.id; groupOpen.value = false }
function removeGroup() { if (references.value.length || !editingId.value) return; changeGroups(groups.value.filter(group => group.id !== editingId.value)); groupOpen.value = false }
function openReference() { referenceId.value = refOptions.value.find(value => !value.disabled)?.id ?? ''; referenceOpen.value = true }
function addReference() { if (!refOptions.value.some(value => value.id === referenceId.value && !value.disabled)) return; addTargets([{ kind: 'GROUP', id: referenceId.value, unresolvedName: null }]); referenceOpen.value = false }
async function save() {
 if (busy.value) return
 busy.value = true; error.value = ''
 try { await state.save(); await catalog.reload() }
 catch (cause) { error.value = errorMessage(cause); if (cause instanceof ApiError && cause.status === 409) conflictOpen.value = true }
 finally { busy.value = false }
}
async function resolveConflict(keep: boolean) {
 busy.value = true; conflictError.value = ''
 try { if (keep) await state.keepLocalEdits(); else await state.discardChanges(); await reloadResources(); error.value = ''; conflictOpen.value = false }
 catch (cause) { conflictError.value = errorMessage(cause) } finally { busy.value = false }
}
</script>
<template>
 <div class="workspace-top"><div><p class="breadcrumbs">配置方案 / <strong>{{ profile?.name ?? '读取方案…' }}</strong></p><ProfileActions :profile="profile" /></div><DraftStatusBar :version="state.version.value" :published-version="profile?.publishedVersion ?? null" :dirty="state.dirty.value" :matches-published="matchesPublished" :busy="busy" @save="save" /></div>
 <header class="page-heading workspace-heading"><div><h1>代理组</h1><p class="muted">从统一节点池选取节点，安排到当前方案的代理组中。</p></div><div class="workspace-stats"><span><strong>{{ groups.length }}</strong> 个组</span><span><strong>{{ usedNodes }}</strong> 个直接成员节点</span><span><strong>{{ state.draft.value?.rules.length ?? 0 }}</strong> 条规则</span></div></header>
 <nav class="workspace-tabs" aria-label="方案设置"><button class="active" aria-current="page">代理组</button><button disabled>分流规则</button><button disabled>远程规则集</button><button disabled>基础配置</button><button disabled>发布与订阅</button></nav>
 <p v-if="error || state.error.value" class="error workspace-notice" role="alert">{{ error || state.error.value }}</p>
 <div v-if="state.serverChanged.value" class="warning toolbar workspace-notice"><span>服务器草稿已更新，本地编辑已保留。</span><button class="text-button" @click="conflictOpen = true">处理版本冲突</button></div>
 <p v-if="state.notice.value" class="notice workspace-notice">{{ state.notice.value }}</p>
 <template v-if="state.draft.value"><fieldset :disabled="busy" class="workspace-grid"><GroupList :groups="groups" :selected-id="selectedId" @select="id => selectedId = id" @create="editGroup(null)" @edit="group => editGroup(group.id)" /><GroupMemberList :group="selectedGroup" :groups="groups" :nodes="nodes" :sources="sources" @edit="editGroup(selectedId)" @remove="index => changeMembers(selectedGroup!.members.filter((_, position) => position !== index))" @reorder="reorder" @reference="openReference" @builtin="kind => addTargets([{ kind, id: null, unresolvedName: null }])" /><NodePicker :nodes="nodes" :sources="sources" :disabled-ids="disabledIds" :selected-ids="selection.selectedIds.value" :has-group="Boolean(selectedGroup)" @toggle="selection.toggle" @select-page="selection.select" @add="addNodes" /></fieldset><p class="workspace-footnote">✓ 节点跨订阅选择　 ✓ 分组仅属于当前方案　 ✓ 上游更新自动清理失效引用</p><details v-if="state.diagnostics.value.errors.length || state.draft.value.importIssues.length" class="draft-issues"><summary>查看草稿待处理问题</summary><DiagnosticsList :items="[...state.diagnostics.value.errors, ...state.draft.value.importIssues]" /></details></template>
 <div v-else class="panel empty-state"><h2>{{ state.loading.value ? '正在读取方案…' : '无法读取此方案' }}</h2><p>通过左侧切换方案，或重新读取。</p><ElButton @click="state.reload().catch(cause => error = errorMessage(cause))">重新读取草稿</ElButton></div>
 <GroupEditorDialog v-if="groupOpen" :group="editingGroup" :groups="groups" :references="references" @save="saveGroup" @remove="removeGroup" @close="groupOpen = false" />
 <ElDialog v-if="referenceOpen" :model-value="true" width="420px" :show-close="false" @close="referenceOpen = false"><template #header><div class="toolbar"><h2>引用其他组</h2><button class="icon-button" aria-label="关闭引用选择" @click="referenceOpen = false">×</button></div></template><label>选择同方案的代理组<select v-model="referenceId" aria-label="引用的代理组"><option value="" disabled>请选择</option><option v-for="option in refOptions" :key="option.id" :value="option.id" :disabled="option.disabled">{{ option.name }}{{ option.disabled ? '（重复或循环引用）' : '' }}</option></select></label><p class="muted reference-note">不能引用自己，也不能形成循环。</p><div class="dialog-actions"><ElButton type="primary" :disabled="!referenceId" @click="addReference">添加组引用</ElButton></div></ElDialog>
 <ConflictDialog v-if="conflictOpen" :version="state.version.value" :latest="state.latest.value" :busy="busy" :error="conflictError" @reload="resolveConflict(false)" @keep="resolveConflict(true)" @close="conflictOpen = false" />
</template>
<style scoped>.workspace-top{display:flex;align-items:center;justify-content:space-between;gap:15px;min-height:46px}.breadcrumbs{font-size:11px;color:var(--muted);margin-bottom:5px}.breadcrumbs strong{color:#374942;font-weight:500}.workspace-heading{display:flex;justify-content:space-between;align-items:center;margin:30px 0 18px;gap:15px}.workspace-stats{display:flex;gap:15px;font-size:10px;color:var(--muted)}.workspace-stats strong{font-size:17px;font-weight:600;color:#273d33;margin-right:4px}.workspace-tabs{display:flex;gap:23px;border-bottom:1px solid var(--line);margin-bottom:22px;overflow:auto}.workspace-tabs button{white-space:nowrap;padding:0 2px 13px;border:0;border-bottom:2px solid transparent;background:none;color:#6d7d76;font-size:12px}.workspace-tabs button.active{border-bottom-color:var(--accent);color:var(--accent);font-weight:600}.workspace-grid{display:grid;grid-template-columns:minmax(170px,.78fr) minmax(300px,1.7fr) minmax(350px,2fr);gap:14px;border:0;padding:0;margin:0;min-width:0}.workspace-notice{margin-bottom:16px}.workspace-footnote{font-size:10px;color:var(--muted);margin-top:18px}.draft-issues{margin-top:18px}.reference-note{font-size:11px;margin-top:12px}@media(max-width:1200px){.workspace-grid{grid-template-columns:minmax(155px,.75fr) minmax(250px,1.3fr) minmax(290px,1.5fr);gap:10px}.workspace-stats{display:none}}@media(max-width:1050px){.workspace-grid{grid-template-columns:minmax(150px,.7fr) minmax(0,1.5fr)}.workspace-grid>:last-child{grid-column:1 / -1}.workspace-top{align-items:flex-start;flex-direction:column}}@media(max-width:720px){.workspace-grid{display:flex;flex-direction:column}.workspace-grid>*{width:100%}.workspace-heading{margin-top:24px}.workspace-tabs{gap:18px}.workspace-footnote{line-height:2}}</style>
