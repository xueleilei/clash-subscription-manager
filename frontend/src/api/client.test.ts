import { afterEach, expect, test, vi } from 'vitest'
import { request, onUnauthorized, ApiError, errorMessage } from './client'
const response = (status: number, data: unknown, message = '请求失败') => new Response(JSON.stringify({ code: status === 200 ? '00000' : 'A0002', message, data, traceId: 'fixture-trace' }), { status, headers: { 'content-type': 'application/json' } })
afterEach(() => vi.unstubAllGlobals())
test('mutation obtains latest CSRF and expired session never replays the write', async () => {
  const calls: RequestInit[] = []; let expired = 0
  const off = onUnauthorized(() => expired++)
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) return response(200, { headerName: 'X-CSRF-TOKEN', token: 'fresh-token' })
    calls.push(init); return response(401, null)
  })
  await expect(request('/profiles/p/draft', { method: 'PUT', body: { expectedVersion: '9007199254740993' } })).rejects.toMatchObject({ status: 401 })
  expect(calls).toHaveLength(1); expect(new Headers(calls[0]!.headers).get('X-CSRF-TOKEN')).toBe('fresh-token')
  expect(calls[0]!.credentials).toBe('same-origin'); expect(calls[0]!.body).toContain('9007199254740993'); expect(expired).toBe(1); off()
})
test.each([403, 409, 422])('HTTP %s keeps its distinct actionable state and diagnostics', async status => {
  const data = { errors: [{ message: '字段无效', path: 'rules[2]' }], warnings: [] }
  vi.stubGlobal('fetch', async () => response(status, data))
  const error = await request('/profiles').catch(e => e)
  if (!(error instanceof ApiError)) throw error
  expect(error).toBeInstanceOf(ApiError); expect(error.status).toBe(status); expect(error.data).toEqual(data)
  expect(errorMessage(error)).toContain({ 403: '请求校验', 409: '数据已变更', 422: '校验未通过' }[status]!)
})
test('cancelled read is silent and is not converted into a server failure', async () => {
  vi.stubGlobal('fetch', async () => { throw new DOMException('aborted', 'AbortError') })
  const error = await request('/nodes').catch(e => e)
  if (!(error instanceof DOMException)) throw error
  expect(error.name).toBe('AbortError'); expect(errorMessage(error)).toBe('')
})
