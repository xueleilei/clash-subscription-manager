import { onScopeDispose, reactive, shallowRef } from 'vue'
import { request } from '../../api/client'
import type { NodeSummary, Page, SourceCommand, SourceView } from '../../types/domain'
export interface NodeFilters { name: string; sourceId: string; protocol: string; region: string; page: number; size: number }
export const defaultNodeFilters = (): NodeFilters => ({ name: '', sourceId: '', protocol: '', region: '', page: 1, size: 50 })
export function useSources() {
  const sources = shallowRef<SourceView[]>([]), nodes = shallowRef<Page<NodeSummary>>({ items: [], total: 0, page: 1, size: 50 })
  const query = reactive(defaultNodeFilters()); const lifetime = new AbortController(); let read: AbortController | null = null; let sequence = 0
  onScopeDispose(() => { lifetime.abort(); read?.abort(); sequence++ })
  async function reloadSources() { sources.value = await request<SourceView[]>('/sources', { signal: lifetime.signal }) }
  async function reloadNodes() {
    const current = ++sequence; read?.abort(); read = new AbortController()
    const params = new URLSearchParams(Object.entries(query).filter(([, value]) => value !== '').map(([key, value]) => [key, String(value)]))
    if (query.sourceId === '__LOCAL__') { params.delete('sourceId'); params.set('localOnly', 'true') }
    const result = await request<Page<NodeSummary>>(`/nodes?${params}`, { signal: read.signal })
    if (current === sequence) nodes.value = result
  }
  async function reload(includeNodes = true) { await Promise.all([reloadSources(), ...(includeNodes ? [reloadNodes()] : [])]) }
  async function save(command: SourceCommand) {
    const result = await request<SourceView>(command.id ? `/sources/${command.id}` : '/sources', { method: command.id ? 'PATCH' : 'POST', body: command, signal: lifetime.signal })
    await reloadSources(); return result
  }
  return { sources, nodes, query, save, reload, reloadSources, reloadNodes }
}
