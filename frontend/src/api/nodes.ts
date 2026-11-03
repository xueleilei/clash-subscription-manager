import { request } from './client'
import type { NodeSummary, Page } from '../types/domain'
/** Metadata only; connection credentials require a separate explicit action. */
export async function readNodeSummaries(signal?: AbortSignal): Promise<NodeSummary[]> {
 const nodes = new Map<string, NodeSummary>()
 for (let page = 1; ; page++) {
  const result = await request<Page<NodeSummary>>(`/nodes?page=${page}&size=200`, { signal })
  result.items.forEach(node => nodes.set(node.id, node))
  if (page * result.size >= result.total || !result.items.length) return [...nodes.values()]
 }
}
