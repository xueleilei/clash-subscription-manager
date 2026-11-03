import { toValue, type MaybeRefOrGetter } from 'vue'
import type { RuleDefinition } from '../../types/domain'
export function useRuleOrder(rules: MaybeRefOrGetter<readonly RuleDefinition[]>) {
 function moveById(id: string, absolutePosition: number) {
  const next = [...toValue(rules)], index = next.findIndex(rule => rule.id === id)
  if (index < 0 || !Number.isFinite(absolutePosition) || next[index]!.expression.type === 'MATCH') return next
  const [item] = next.splice(index, 1), match = next.findIndex(rule => rule.expression.type === 'MATCH')
  const position = Math.max(0, Math.min(Math.trunc(absolutePosition) - 1, match < 0 ? next.length : match))
  next.splice(position, 0, item!); return next
 }
 function insertBeforeMatch(rule: RuleDefinition) { const next = [...toValue(rules)], index = next.findIndex(item => item.expression.type === 'MATCH'); next.splice(index < 0 ? next.length : index, 0, rule); return next }
 return { moveById, insertBeforeMatch }
}
