import { computed, onScopeDispose, shallowReactive, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { ApiError, errorMessage, request } from '../../api/client'
import { readNodeSummaries } from '../../api/nodes'
import { onSessionReset, useSession } from '../auth/useSession'
import type { DraftView, ProfileDocument, ValidationReport } from '../../types/domain'
interface DraftState { document: ProfileDocument | null; baseline: string; version: string; diagnostics: ValidationReport; serverChanged: boolean; latest: DraftView | null; notice: string }
const cache = shallowReactive(new Map<string, DraftState>()), writes = new Set<AbortController>()
let epoch = 0
const encode = (value: unknown) => JSON.stringify(value)
const copy = <T>(value: T): T => JSON.parse(encode(value)) as T
const isDirty = (entry: DraftState) => entry.document !== null && encode(entry.document) !== entry.baseline
onSessionReset(() => { epoch++; writes.forEach(controller => controller.abort()); writes.clear(); cache.clear() })
export const hasUnsavedDrafts = computed(() => [...cache.values()].some(isDirty))
export function forgetDraft(ownerId: string, profileId: string) { cache.delete(`${ownerId}:${profileId}`) }
export function useProfileDraft(profileId: MaybeRefOrGetter<string>) {
 const session = useSession(), entry = shallowRef<DraftState | null>(null), loading = shallowRef(false), error = shallowRef('')
 const currentProfileId = computed(() => toValue(profileId)), draft = computed(() => entry.value?.document ?? null)
 const version = computed(() => entry.value?.version ?? ''), dirty = computed(() => entry.value ? isDirty(entry.value) : false)
 const serverChanged = computed(() => entry.value?.serverChanged ?? false), latest = computed(() => entry.value?.latest ?? null)
 const diagnostics = computed(() => entry.value?.diagnostics ?? { errors: [], warnings: [] }), notice = computed(() => entry.value?.notice ?? '')
 let sequence = 0, read: AbortController | null = null, timer: ReturnType<typeof setTimeout> | undefined, disposed = false
 const currentKey = () => `${session.ownerId.value}:${currentProfileId.value}`
 function accept(target: DraftState, value: DraftView) {
  target.document = copy(value.document); target.baseline = encode(value.document); target.version = value.version
  target.diagnostics = value.diagnostics; target.latest = value; target.serverChanged = false; target.notice = ''
 }
 function updateDocument(document: ProfileDocument) { if (entry.value) entry.value.document = copy(document) }
 async function load(discard: boolean) {
  const target = entry.value; if (!target || !session.ownerId.value || !currentProfileId.value) return
  const key = currentKey(), currentEpoch = epoch, current = ++sequence; read?.abort(); read = new AbortController(); loading.value = true; error.value = ''
  try {
   const result = await request<DraftView>(`/profiles/${currentProfileId.value}/draft`, { signal: read.signal })
   if (current !== sequence || currentEpoch !== epoch || key !== currentKey() || disposed) return
   if (discard || !isDirty(target)) accept(target, result)
   else { target.latest = result; if (target.version !== result.version) target.serverChanged = true }
  } finally { if (current === sequence) loading.value = false }
 }
 async function reload() { await load(false) }
 async function discardChanges() { await load(true) }
 async function save() {
  const target = entry.value; if (!target?.document) throw new Error('Draft is not loaded')
  const key = currentKey(), currentEpoch = epoch, sent = copy(target.document), sentText = encode(sent), id = currentProfileId.value
  const controller = new AbortController(); writes.add(controller)
  try {
   const result = await request<DraftView>(`/profiles/${id}/draft`, { method: 'PUT', body: { expectedVersion: target.version, document: sent }, signal: controller.signal })
   if (currentEpoch === epoch && cache.get(key) === target) {
    if (encode(target.document) === sentText) target.document = copy(result.document)
    target.baseline = encode(result.document); target.version = result.version; target.diagnostics = result.diagnostics; target.latest = result; target.serverChanged = false; target.notice = ''
   }
   return result
  } catch (cause) { if (cause instanceof ApiError && (cause.status === 409 || cause.status === 404) && currentEpoch === epoch) target.serverChanged = true; throw cause }
  finally { writes.delete(controller) }
 }
 async function keepLocalEdits() {
  const target = entry.value; if (!target?.document) return
  const key = currentKey(), currentEpoch = epoch, current = ++sequence; read?.abort(); read = new AbortController(); loading.value = true
  try {
   const [server, nodes] = await Promise.all([request<DraftView>(`/profiles/${currentProfileId.value}/draft`, { signal: read.signal }), readNodeSummaries(read.signal)])
   if (current !== sequence || currentEpoch !== epoch || currentKey() !== key || disposed) return
   const active = new Set(nodes.map(node => node.id)), local = copy(target.document); let removed = 0
   for (const group of local.groups) group.members = group.members.filter(member => { if (member.kind === 'NODE' && !active.has(member.id!)) { removed++; return false }; return true })
   for (const rule of local.rules) if (rule.target.kind === 'NODE' && !active.has(rule.target.id!)) { removed++; rule.target = { kind: 'REJECT', id: null, unresolvedName: null }; rule.issues = [...rule.issues, {severity:'WARNING',code:'REMOVED_NODE_TARGET',path:'rules',line:null,column:null,message:'原目标节点已失效，自动改为 REJECT'}] }
   target.document = local; target.version = server.version; target.baseline = encode(server.document); target.latest = server; target.diagnostics = server.diagnostics; target.serverChanged = false
   target.notice = removed ? `已保留本地编辑，并清理 ${removed} 个失效节点引用；相关直接规则已回退为 REJECT。` : '已保留本地编辑，请核对后再次保存。'
  } finally { if (current === sequence) loading.value = false }
 }
 function schedule() { clearTimeout(timer); if (!disposed) timer = setTimeout(() => { void check().finally(schedule) }, 15_000) }
 async function check() { if (document.visibilityState === 'hidden' || session.status.value !== 'authenticated') return; try { await reload() } catch (cause) { error.value = errorMessage(cause) } }
 const visibility = () => { if (document.visibilityState === 'visible') void check() }
 watch([() => toValue(profileId), session.ownerId], ([id, owner]) => {
  sequence++; read?.abort(); entry.value = null; loading.value = false; error.value = ''
  if (!owner || !id) return
  const key = `${owner}:${id}`
  if (!cache.has(key)) cache.set(key, shallowReactive<DraftState>({ document: null, baseline: '', version: '', diagnostics: { errors: [], warnings: [] }, serverChanged: false, latest: null, notice: '' }))
  entry.value = cache.get(key)!
  void reload().catch(cause => { if (!disposed) error.value = errorMessage(cause) })
 }, { immediate: true, flush: 'sync' })
 document.addEventListener('visibilitychange', visibility); schedule()
 onScopeDispose(() => { disposed = true; sequence++; read?.abort(); clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) })
 return { currentProfileId, draft, version, dirty, serverChanged, latest, diagnostics, notice, error, loading, updateDocument, save, reload, discardChanges, keepLocalEdits }
}
