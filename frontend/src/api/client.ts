export interface RequestOptions { method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'; body?: unknown; signal?: AbortSignal }
interface Envelope<T> { code: string; message: string; data: T; traceId?: string }
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly data: unknown = null, readonly traceId?: string) { super(message); this.name = 'ApiError' }
}
const unauthorized = new Set<() => void>()
export function onUnauthorized(listener: () => void) { unauthorized.add(listener); return () => { unauthorized.delete(listener) } }
async function send<T>(path: string, init: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, { ...init, credentials: 'same-origin', cache: 'no-store', redirect: 'error' })
  let result: Envelope<T>
  try { result = await response.json() as Envelope<T> }
  catch { throw new ApiError(response.status, 'B0001', '服务返回了无法读取的响应') }
  if (!response.ok || result.code !== '00000') {
    if (response.status === 401) unauthorized.forEach(listener => listener())
    throw new ApiError(response.status, result.code, result.message, result.data, result.traceId)
  }
  return result.data
}
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('API path must be relative')
  const method = options.method ?? 'GET'
  const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(60_000)]) : AbortSignal.timeout(60_000)
  const headers = new Headers({ Accept: 'application/json' })
  if (method !== 'GET') {
    const csrf = await send<{ headerName: string; token: string }>('/csrf', { method: 'GET', signal })
    headers.set(csrf.headerName, csrf.token)
  }
  const form = options.body instanceof URLSearchParams
  if (options.body !== undefined) headers.set('Content-Type', form ? 'application/x-www-form-urlencoded;charset=UTF-8' : 'application/json')
  return send<T>(path, { method, headers, signal, body: options.body === undefined ? undefined : form ? options.body as URLSearchParams : JSON.stringify(options.body) })
}
export function errorMessage(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') return ''
  if (error instanceof ApiError) {
    const prefix: Record<number, string> = { 403: '请求校验失败，请刷新登录后重试', 409: '数据已变更，已保留本地输入，请重新读取或处理冲突', 422: '校验未通过，请检查标记的问题' }
    return prefix[error.status] ?? error.message ?? '请求失败，请稍后重试'
  }
  return '无法连接服务，请检查网络后重试'
}
