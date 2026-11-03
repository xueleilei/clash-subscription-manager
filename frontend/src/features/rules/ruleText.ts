import type { NodeSummary, ProfileDocument, RuleDefinition, TargetRef } from '../../types/domain'
export const referenceKey = (target: TargetRef) => `${target.kind}${target.id ? `:${target.id}` : ''}`
export function targetLabel(target: TargetRef, document: ProfileDocument, nodes: readonly NodeSummary[]) {
 if (target.kind === 'GROUP') return document.groups.find(group => group.id === target.id)?.name ?? '未找到的组'
 if (target.kind === 'NODE') return nodes.find(node => node.id === target.id)?.originalName ?? '已失效节点'
 return target.kind === 'UNRESOLVED' ? target.unresolvedName ?? '待处理目标' : target.kind
}
function name(value: string) { return /[,'"()\\]|^\s|\s$/.test(value) ? `'${value.replaceAll("'", "''")}'` : value }
export function ruleText(rule: RuleDefinition, document: ProfileDocument, nodes: readonly NodeSummary[]) {
 if (rule.expression.type === 'UNSUPPORTED' || rule.issues.some(issue => issue.severity === 'ERROR')) return rule.rawText ?? `${rule.expression.type},${rule.expression.value ?? ''},${targetLabel(rule.target, document, nodes)}`
 const value = rule.expression.providerId ? name(document.providers.find(provider => provider.id === rule.expression.providerId)?.name ?? rule.expression.value ?? '') : rule.expression.value ?? ''
 return [rule.expression.type, ...(rule.expression.type === 'MATCH' ? [] : [value]), name(targetLabel(rule.target, document, nodes)), ...rule.modifiers].join(',')
}
export function downloadText(filename: string, text: string, type = 'text/plain') { const url = URL.createObjectURL(new Blob([text], {type})), link = window.document.createElement('a'); link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
