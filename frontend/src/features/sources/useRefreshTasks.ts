import { computed, onScopeDispose, shallowRef } from 'vue'
import { errorMessage, request } from '../../api/client'
import type { EmptyDecision, TaskView } from '../../types/domain'
const active = (task: TaskView) => task.status === 'QUEUED' || task.status === 'RUNNING'
export function useRefreshTasks(changed: () => Promise<void> = async () => {}) {
  const tasks = shallowRef<TaskView[]>([]), decisions = shallowRef<EmptyDecision[]>([]), error = shallowRef('')
  const activeCount = computed(() => tasks.value.filter(active).length)
  let timer: ReturnType<typeof setTimeout> | undefined, disposed = false, polling = false, reads = new AbortController(), sourceIds: string[] = []
  const lifetime = new AbortController()
  function merge(values: TaskView[]) {
    const map = new Map(tasks.value.map(value => [value.id, value]))
    for (const value of values) { const old = map.get(value.id); if (!old || value.updatedAt >= old.updatedAt) map.set(value.id, value) }
    tasks.value = [...map.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 200)
  }
  async function loadDecisions() { decisions.value = await request<EmptyDecision[]>('/empty-decisions', { signal: reads.signal }) }
  function schedule() { clearTimeout(timer); if (!disposed && document.visibilityState !== 'hidden' && activeCount.value) timer = setTimeout(() => void poll(), 1500) }
  async function poll() {
    if (disposed || polling || document.visibilityState === 'hidden') return
    polling = true
    try {
      const values = await Promise.all(tasks.value.filter(active).map(task => request<TaskView>(`/tasks/${task.id}`, { signal: reads.signal })))
      merge(values); await loadDecisions()
      if (values.some(task => !active(task))) await changed()
    } catch (cause) { if (!disposed) error.value = errorMessage(cause) }
    finally { polling = false; schedule() }
  }
  async function load(ids: string[]) {
    sourceIds = ids; if (reads.signal.aborted) reads = new AbortController()
    const histories = await Promise.all(ids.map(id => request<TaskView[]>(`/sources/${id}/history`, { signal: reads.signal })))
    merge(histories.flat()); await loadDecisions(); schedule()
  }
  async function refreshOne(id: string) { error.value = ''; merge([await request<TaskView>(`/sources/${id}/refresh`, { method: 'POST', signal: lifetime.signal })]); await poll() }
  async function refreshAll() { error.value = ''; merge(await request<TaskView[]>('/sources/refresh-all', { method: 'POST', signal: lifetime.signal })); await poll() }
  async function decideEmpty(id: string, choice: 'KEEP' | 'CLEAR') {
    await request(`/empty-decisions/${id}/decision`, { method: 'POST', body: { choice }, signal: lifetime.signal }); await loadDecisions(); await changed()
  }
  function stopPolling() { clearTimeout(timer); reads.abort() }
  function visibility() {
    if (document.visibilityState === 'hidden') stopPolling()
    else { reads = new AbortController(); void load(sourceIds).then(changed).catch(cause => { error.value = errorMessage(cause) }) }
  }
  document.addEventListener('visibilitychange', visibility)
  onScopeDispose(() => { disposed = true; stopPolling(); lifetime.abort(); document.removeEventListener('visibilitychange', visibility) })
  return { tasks, decisions, error, activeCount, load, refreshOne, refreshAll, decideEmpty, stopPolling }
}
