export function localTime(value: string | null | undefined) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
export const taskLabels: Record<string, string> = { QUEUED: '排队中', RUNNING: '正在刷新', SUCCESS: '刷新成功', UNCHANGED: '节点未变化', EMPTY_PENDING: '空结果待确认', EMPTY_KEPT: '已保留旧数据', FAILED: '刷新失败', CONFLICT: '数据已变更', CANCELLED: '已取消', OK: '同步正常', PENDING: '等待刷新' }
export function statusLabel(status: string) { return taskLabels[status] ?? status }

export function statusTone(status: string) {
 if (['OK', 'SUCCESS', 'UNCHANGED', 'EMPTY_KEPT'].includes(status)) return 'success'
 if (status === 'FAILED') return 'failed'
 if (status === 'EMPTY_PENDING' || status === 'CONFLICT') return 'attention'
 return status === 'RUNNING' ? 'running' : ''
}
