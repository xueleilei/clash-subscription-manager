import { shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue'
export function useNodeSelection(groupId: MaybeRefOrGetter<string | null>) {
 const selectedIds = shallowRef(new Set<string>())
 function clear() { selectedIds.value = new Set() }
 function toggle(id: string) { const next = new Set(selectedIds.value); if (next.has(id)) next.delete(id); else next.add(id); selectedIds.value = next }
 function select(ids: readonly string[]) { selectedIds.value = new Set([...selectedIds.value, ...ids]) }
 watch(() => toValue(groupId), clear, { flush: 'sync' })
 return { selectedIds, toggle, select, clear }
}
