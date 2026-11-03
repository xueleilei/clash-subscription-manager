import type { GroupDefinition, ProfileDocument, TargetRef } from '../../types/domain'
export const targetKey = (target: TargetRef) => `${target.kind}:${target.id ?? target.unresolvedName ?? ''}`
export function wouldCycle(groups: readonly GroupDefinition[], parent: string, candidate: string) {
 const lookup = new Map(groups.map(group => [group.id, group])), pending = [candidate], seen = new Set<string>()
 while (pending.length) { const id = pending.pop()!; if (id === parent) return true; if (seen.has(id)) continue; seen.add(id); for (const target of lookup.get(id)?.members ?? []) if (target.kind === 'GROUP' && target.id) pending.push(target.id) }
 return false
}
export function groupReferences(document: ProfileDocument, id: string) {
 return [...document.groups.filter(group => group.id !== id && group.members.some(member => member.kind === 'GROUP' && member.id === id)).map(group => `代理组「${group.name}」`), ...document.rules.flatMap((rule, index) => rule.target.kind === 'GROUP' && rule.target.id === id ? [`第 ${index + 1} 条规则`] : [])]
}
