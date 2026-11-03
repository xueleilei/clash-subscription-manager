import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { effectScope, nextTick } from 'vue'
import SourcesView from './SourcesView.vue'
import NodePoolPage from './NodePoolPage.vue'
import SourceForm from './SourceForm.vue'
import SourceList from './SourceList.vue'
import { useRefreshTasks } from './useRefreshTasks'
import { mockApi, reply, button } from '../../test/http'
import { deferred } from '../../test/fixtures'
import { useSession } from '../auth/useSession'
import { request } from '../../api/client'
const source = (id: string, name = `来源 ${id}`) => ({ id, name, configVersion: '1', snapshotVersion: '1', enabled: true, refreshIntervalSeconds: 21600, inputFormat: 'AUTO' as const, hostHint: 'fixture.invalid', syncStatus: 'OK', nodeCount: 1, lastAttemptTime: null, lastSuccessTime: null, nextRefreshTime: null, lastErrorCode: null })
const node = { id: 'n1', sourceId: 'a', originalName: '香港 01', protocol: 'ss', region: 'HK', snapshotVersion: '1' }
const empty = { id: 'e1', sourceId: 'a', configVersion: '1', snapshotVersion: '1', state: 'PENDING', createdAt: '2026-11-03T00:00:00Z' }
const task = (id: string, sourceId: string, status = 'RUNNING') => ({ id, sourceId, status, trigger: 'MANUAL', createdAt: '', updatedAt: '', outcome: null })
function serve(options: { sources?: ReturnType<typeof source>[]; decisions?: typeof empty[]; taskStatus?: string; expired?: boolean; proxyOptions?: string[] } = {}) {
 const sources = options.sources ?? [source('a'), source('b')]; let decisions = options.decisions ?? []; const writes: { path: string; method: string; body: Record<string, unknown> }[] = []
 mockApi((path, method, body) => {
   if (method !== 'GET') writes.push({ path, method, body })
   if (path === '/sources/proxy-options') return reply(options.proxyOptions ?? [])
   if (path === '/sources' && method === 'GET') return reply(sources)
   if (path === '/sources' && method === 'POST') { const value = { ...source(`s${sources.length}`), ...body }; sources.push(value as ReturnType<typeof source>); return reply(value) }
   if (path === '/sources/refresh-all') return reply(sources.map(s => task(`t${s.id}`, s.id)), 202)
   if (path.endsWith('/refresh')) return reply(task('ta', path.split('/')[2]!), 202)
   if (path.startsWith('/tasks/')) return reply({ ...task(path.split('/')[2]!, 'a', options.taskStatus ?? 'SUCCESS'), outcome: { status: options.taskStatus ?? 'SUCCESS', changes: { added: 1, updated: 0, removed: 0, profiles: {} }, report: { errors: options.taskStatus === 'FAILED' ? [{ severity: 'ERROR', code: 'NODE_INVALID', path: 'nodes[2].port', message: '节点端口无效' }] : [], warnings: [] } } })
   if (path.endsWith('/history')) return reply([])
   if (path.endsWith('/removal/preview')) return reply({ id: 'r1', ownerRevision: '1', expiresAt: '', sourceId: 'a', nodeCount: 1, affectedProfiles: ['p1'] })
   if (path === '/source-removals/r1/apply') { sources.splice(0, 1); return reply({}) }
   if (path === '/empty-decisions') return reply(decisions)
   if (path.includes('/decision')) { if (options.expired) { decisions = []; return reply(null, 409) }; decisions = []; return reply({}) }
   if (path.startsWith('/nodes?')) return reply({ items: [node], total: 1, page: 1, size: 50 })
   if (method === 'PATCH') { const index = sources.findIndex(s => path.endsWith(s.id)); sources[index] = { ...sources[index]!, ...body } as ReturnType<typeof source>; return reply(sources[index]) }
   throw new Error(`Unexpected API ${method} ${path}`)
 })
 return { sources, writes }
}
beforeEach(async () => { mockApi(() => reply({ id: crypto.randomUUID(), username: 'admin' })); await useSession().resume() })
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
test('adding two sources renders independent cards and does not render HTML from names', async () => {
 const server = serve({ sources: [] }); mount(SourcesView, { attachTo: document.body }); await expect.poll(() => document.body.textContent).toContain('添加订阅')
 for (const name of ['来源甲', '<script data-untrusted>来源乙</script>']) {
  await expect.poll(() => button('添加订阅').disabled).toBe(false); button('添加订阅').click(); await nextTick()
  await new DOMWrapper(document.querySelector('input[name=source-name]')!).setValue(name)
  await new DOMWrapper(document.querySelector('input[name=source-url]')!).setValue('https://fixture.invalid/sub')
  await expect.poll(() => button('保存订阅').disabled).toBe(false); button('保存订阅').click(); await expect.poll(() => server.sources.length).toBe(name === '来源甲' ? 1 : 2)
  await expect.poll(() => document.querySelector('input[name=source-name]')).toBeNull()
 }
 expect(document.body.textContent).toContain('来源甲'); expect(document.body.textContent).toContain('来源乙'); expect(document.querySelector('script[data-untrusted]')).toBeNull()
})
test('new sources visibly select the configured trusted proxy and submit it by default', async () => {
 const server = serve({ sources: [], proxyOptions: ['default'] }); mount(SourcesView, { attachTo: document.body })
 await expect.poll(() => button('添加订阅').disabled).toBe(false); button('添加订阅').click(); await nextTick()
 const proxy = document.querySelector<HTMLSelectElement>('select[name=source-proxy]')
 expect(proxy).not.toBeNull(); expect(proxy?.closest('details')).toBeNull(); expect(proxy?.value).toBe('default')
 await new DOMWrapper(document.querySelector('input[name=source-name]')!).setValue('自动代理')
 await new DOMWrapper(document.querySelector('input[name=source-url]')!).setValue('https://fixture.invalid/sub')
 button('保存订阅').click(); await expect.poll(() => server.writes.find(write => write.path === '/sources')?.body.outboundProxy).toBe('default')
})
test('direct connections stay selectable and absent proxy configuration defaults to direct', async () => {
 const wrapper = mount(SourceForm, { props: { source: null, busy: false, proxyOptions: ['default'] }, attachTo: document.body })
 await wrapper.get('select[name=source-proxy]').setValue('')
 await wrapper.setProps({ proxyOptions: ['default'] })
 await wrapper.get('form').trigger('submit')
 expect((wrapper.emitted('submit')![0]![0] as Record<string, unknown>).outboundProxy).toBe('')
 wrapper.unmount()
 const direct = mount(SourceForm, { props: { source: null, busy: false, proxyOptions: [] }, attachTo: document.body })
 await direct.get('form').trigger('submit')
 expect((direct.emitted('submit')![0]![0] as Record<string, unknown>).outboundProxy).toBe('')
})
test('single and all refreshes report independent durable task results', async () => {
 const server = serve(); mount(SourcesView, { attachTo: document.body }); await expect.poll(() => document.querySelectorAll('[data-source-id]').length).toBe(2)
 button('刷新 来源 a').click(); await expect.poll(() => document.body.textContent).toContain('刷新成功')
 button('刷新全部').click(); await expect.poll(() => server.writes.filter(c => c.path.endsWith('/refresh-all')).length).toBe(1)
 await expect.poll(() => document.querySelectorAll('[data-task-id]').length).toBe(2)
})
test('empty result close never clears and repeated reads do not reopen a dismissed decision', async () => {
 const server = serve({ decisions: [empty] }); mount(SourcesView, { attachTo: document.body })
 await expect.poll(() => document.querySelector('[data-empty-decision]')).not.toBeNull()
 expect(button('保留旧数据')).toBeTruthy(); expect(button('清空节点')).toBeTruthy()
 button('关闭空结果提示').click(); await expect.poll(() => document.querySelector('[data-empty-decision]')).toBeNull()
 button('重新读取').click(); await nextTick(); await expect.poll(() => button('重新读取').disabled).toBe(false)
 expect(document.querySelector('[data-empty-decision]')).toBeNull(); expect(server.writes.filter(c => c.body.choice === 'CLEAR')).toHaveLength(0)
 button('处理空结果').click(); await expect.poll(() => document.querySelector('[data-empty-decision]')).not.toBeNull()
 button('保留旧数据').click(); await expect.poll(() => server.writes.some(c => c.body.choice === 'KEEP')).toBe(true)
})
test('stale empty confirmation keeps inputs and tells the user to reload', async () => {
 serve({ decisions: [empty], expired: true }); mount(SourcesView, { attachTo: document.body })
 await expect.poll(() => document.querySelector('[data-empty-decision]')).not.toBeNull(); button('清空节点').click()
 await expect.poll(() => document.body.textContent).toContain('数据已变更')
})
test('failed parsing keeps old node data visible and pausing only changes the schedule', async () => {
 const server = serve({ taskStatus: 'FAILED' }); const wrapper = mount(SourcesView, { attachTo: document.body })
 await expect.poll(() => document.querySelectorAll('[data-source-id]').length).toBe(2); button('刷新 来源 a').click()
 await expect.poll(() => document.body.textContent).toContain('nodes[2].port'); expect(document.body.textContent).toContain('节点端口无效')
 button('暂停 来源 a').click(); await expect.poll(() => server.writes.some(c => c.body.enabled === false)).toBe(true)
 expect(server.writes.some(c => c.path.includes('removal') || c.body.choice === 'CLEAR')).toBe(false)
 wrapper.unmount(); mount(NodePoolPage, { attachTo: document.body }); await expect.poll(() => document.body.textContent).toContain('香港 01')
})
test('editing a masked source preserves omitted credentials and proxy even when a trusted proxy is configured', async () => {
 const wrapper = mount(SourceForm, { props: { source: source('a'), busy: false, proxyOptions: ['default'] }, attachTo: document.body })
 await wrapper.get('input[name=source-name]').setValue('改名'); await wrapper.get('form').trigger('submit')
 const submitted = wrapper.emitted('submit')![0]![0] as Record<string, unknown>
 expect(submitted.name).toBe('改名'); expect(submitted).not.toHaveProperty('url'); expect(submitted).not.toHaveProperty('headers'); expect(submitted).not.toHaveProperty('outboundProxy'); expect(submitted.expectedVersion).toBe('1')
})
test.each(['default', ''])('editing displays and preserves the saved connection mode %j without revealing credentials', async outboundProxy => {
 const wrapper = mount(SourceForm, { props: { source: { ...source('a'), outboundProxy }, busy: false, proxyOptions: ['default'] }, attachTo: document.body })
 expect(wrapper.get<HTMLSelectElement>('select[name=source-proxy]').element.value).toBe(outboundProxy)
 await wrapper.get('input[name=source-name]').setValue('只改名称'); await wrapper.get('form').trigger('submit')
 const submitted = wrapper.emitted('submit')![0]![0] as Record<string, unknown>
 expect(submitted.outboundProxy).toBe(outboundProxy); expect(submitted).not.toHaveProperty('url'); expect(submitted).not.toHaveProperty('headers')
})
test('source cards identify the actual trusted proxy or direct connection', () => {
 const wrapper = mount(SourceList, { props: { sources: [{ ...source('a'), outboundProxy: 'default' }, { ...source('b'), outboundProxy: '' }], busy: false } })
 expect(wrapper.get('[data-source-id=a]').text()).toContain('可信代理 · default')
 expect(wrapper.get('[data-source-id=b]').text()).toContain('直接连接')
})
test('source removal shows its impact and sends only the explicitly chosen keep-local mode', async () => {
 const server = serve(); mount(SourcesView, { attachTo: document.body }); await expect.poll(() => button('删除 来源 a')).toBeTruthy()
 await expect.poll(() => button('重新读取').disabled).toBe(false); button('删除 来源 a').click()
 await expect.poll(() => document.body.textContent).toContain('p1')
 await expect.poll(() => button('删除并转为本地节点').disabled).toBe(false); button('删除并转为本地节点').click()
 await expect.poll(() => document.querySelectorAll('[data-source-id]').length).toBe(1)
 expect(server.writes.filter(c => c.path.endsWith('/apply')).map(c => c.body.mode)).toEqual(['KEEP_LOCAL'])
})
test('active task polling pauses while hidden and stops when its scope is disposed', async () => {
 let taskReads = 0
 mockApi((path) => {
  if (path.endsWith('/history')) return reply([task('t1', 'a')])
  if (path === '/empty-decisions') return reply([])
  if (path === '/tasks/t1') { taskReads++; return reply(task('t1', 'a')) }
  throw new Error(path)
 })
 vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
 const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
 const scope = effectScope(), refresh = scope.run(() => useRefreshTasks())!
 await refresh.load(['a']); await vi.advanceTimersByTimeAsync(1500); expect(taskReads).toBe(1)
 visibility.mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange'))
 await vi.advanceTimersByTimeAsync(6000); expect(taskReads).toBe(1)
 scope.stop(); visibility.mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange'))
 await vi.advanceTimersByTimeAsync(6000); expect(taskReads).toBe(1)
})
test('rapid deletion selections cannot mismatch a source name and its removal preview', async () => {
 const pending = deferred<Response>(); const previews: string[] = [], applied: string[] = []
 mockApi((path, method) => {
  if (path === '/sources') return reply([source('a'), source('b')])
  if (path === '/sources/proxy-options' || path === '/empty-decisions' || path.endsWith('/history')) return reply([])
  if (path.endsWith('/removal/preview')) { previews.push(path); return pending.promise }
  if (path.endsWith('/apply')) { applied.push(path); return reply(null) }
  throw new Error(`${method} ${path}`)
 })
 mount(SourcesView, { attachTo: document.body }); await expect.poll(() => button('重新读取').disabled).toBe(false)
 button('删除 来源 a').click(); button('删除 来源 b').click()
 await expect.poll(() => previews).toEqual(['/sources/a/removal/preview'])
 pending.resolve(reply({ id: 'preview-a', ownerRevision: '1', expiresAt: '', sourceId: 'a', nodeCount: 1, affectedProfiles: [] }))
 await expect.poll(() => document.querySelector('.el-dialog strong')?.textContent).toBe('来源 a')
 button('删除并清空节点').click(); await expect.poll(() => applied).toEqual(['/source-removals/preview-a/apply'])
})
test('retained source views stop background polling while the session is expired and resume after login', async () => {
 let reads = 0, authorized = true; const owner = crypto.randomUUID()
 mockApi(path => {
  if (path === '/session') return authorized ? reply({ id: owner, username: 'admin' }) : reply(null, 401)
  if (path.endsWith('/history')) return reply([task('t1', 'a')])
  if (path === '/empty-decisions') return reply([])
  if (path === '/tasks/t1') { reads++; return reply(task('t1', 'a')) }
  throw new Error(path)
 })
 await useSession().resume(); vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
 const scope = effectScope(), refresh = scope.run(() => useRefreshTasks())!
 try {
  await refresh.load(['a']); await vi.advanceTimersByTimeAsync(1500); expect(reads).toBe(1)
  authorized = false; await request('/session').catch(() => {}); const before = reads
  await vi.advanceTimersByTimeAsync(6000); expect(reads).toBe(before)
  authorized = true; await useSession().resume(); await vi.waitFor(() => expect(reads).toBeGreaterThan(before), { timeout: 3000, interval: 200 })
 } finally { scope.stop() }
})
