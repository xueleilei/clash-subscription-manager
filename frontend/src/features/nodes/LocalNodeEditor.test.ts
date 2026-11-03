import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { DOMWrapper, mount } from '@vue/test-utils'
import SourcesView from '../sources/SourcesView.vue'
import NodePoolPage from '../sources/NodePoolPage.vue'
import { deferred } from '../../test/fixtures'
import { useSession } from '../auth/useSession'
import { button, mockApi, reply } from '../../test/http'

const local = { id: 'local-a', sourceId: null, originalName: '本地香港', protocol: 'vless', region: 'HK', snapshotVersion: '3' }
const connection = { server: 'fixture.invalid', port: 443, uuid: '11111111-1111-4111-8111-111111111111', tls: true, network: 'ws', 'ws-opts': { path: '/original', headers: { Host: 'fixture.invalid' } }, 'ech-opts': { enable: true, config: 'fixture' }, smux: { enabled: true } }
const writes: { path: string; method: string; body: Record<string, unknown> }[] = []
function serve(options: { conflict?: boolean; invalid?: boolean; dependent?: boolean } = {}) {
 mockApi((path, method, body) => {
  if (method !== 'GET') writes.push({ path, method, body })
  if (path === '/sources' || path === '/sources/proxy-options' || path === '/empty-decisions' || path.endsWith('/history')) return reply([])
  if (path === '/profiles') return reply([{ id: 'p1', name: '日常方案' }])
  if (path.startsWith('/nodes?')) return reply({ items: [local], total: 1, page: 1, size: 50 })
  if (path === '/local-nodes/parse') return reply({ name: '链接节点', protocol: 'vless', connection, dialerRef: null })
  if (path === '/local-nodes/local-a' && method === 'GET') return reply({ node: { ...local, connection, dialerRef: null }, version: '3', affectedProfiles: ['p1'], publishedProfiles: ['p1'] })
  if (path === '/local-nodes/local-a/removal') return reply({ id: local.id, version: '3', ownerRevision: '8', affectedProfiles: ['p1'], publishedProfiles: ['p1'], dependentNodes: options.dependent ? ['another-node'] : [] })
  if (path.startsWith('/local-nodes') && method !== 'GET') return options.conflict ? reply(null, 409) : options.invalid ? reply({ errors: [{ severity: 'ERROR', code: 'UNKNOWN_PROXY_FIELD', path: 'node.unsupported', message: '不支持此连接字段' }], warnings: [] }, 422) : reply({ id: local.id, version: '4', affectedProfiles: ['p1'] })
  throw new Error(`Unexpected API ${method} ${path}`)
 })
}
async function field(name: string, value: string) { await expect.poll(() => document.querySelector(`[name="${name}"]`)).not.toBeNull(); await new DOMWrapper(document.querySelector(`[name="${name}"]`)!).setValue(value) }
async function openManual() {
 mount(SourcesView, { attachTo: document.body })
 await expect.poll(() => button('手动节点')).toBeTruthy(); button('手动节点').click()
 await expect.poll(() => document.body.textContent).toContain('本地香港')
}
beforeEach(async () => { writes.length = 0; mockApi(() => reply({ id: crypto.randomUUID(), username: 'admin' })); await useSession().resume() })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
test('sources offers a local-only node list and creates a manual node without refreshing subscriptions', async () => {
 serve(); await openManual(); button('添加节点').click()
 await expect.poll(() => document.querySelector('[name="node-name"]')).not.toBeNull()
 await field('node-name', '新节点'); await field('node-server', 'fixture.invalid'); await field('node-password', 'fixture-password')
 button('保存节点').click(); await expect.poll(() => writes.some(w => w.path === '/local-nodes')).toBe(true)
 expect(writes.find(w => w.path === '/local-nodes')?.body).toMatchObject({ name: '新节点', protocol: 'ss', connection: { server: 'fixture.invalid', password: 'fixture-password' } })
 await expect.poll(() => document.querySelector('[name="node-name"]')).toBeNull()
 expect(document.body.textContent).toContain('检查后手动发布'); expect(writes.some(w => w.path.includes('refresh'))).toBe(false)
})
test('editing preserves advanced fields and stable identity while changing common parameters', async () => {
 serve(); await openManual(); button('编辑 本地香港').click()
 await expect.poll(() => document.querySelector<HTMLInputElement>('[name="node-name"]')?.value).toBe('本地香港')
 await field('node-name', '新名称'); await field('node-server', 'changed.invalid'); button('保存节点').click()
 await expect.poll(() => writes.some(w => w.method === 'PUT')).toBe(true)
 expect(writes.find(w => w.method === 'PUT')).toMatchObject({ path: '/local-nodes/local-a', body: { expectedVersion: '3', name: '新名称', connection: { ...connection, server: 'changed.invalid' } } })
})
test('a single link is parsed into an editable form before the user saves', async () => {
 serve(); await openManual(); button('添加节点').click()
 await expect.poll(() => button('分享链接 / YAML')).toBeTruthy(); button('分享链接 / YAML').click()
 await field('node-input', 'vless://fixture@fixture.invalid:443#链接节点'); button('解析为表单').click()
 await expect.poll(() => document.querySelector<HTMLInputElement>('[name="node-name"]')?.value).toBe('链接节点')
 expect(writes.map(w => w.path)).toEqual(['/local-nodes/parse'])
 button('保存节点').click(); await expect.poll(() => writes.some(w => w.path === '/local-nodes')).toBe(true)
})
test('conflicts retain the edited name and credentials without overwriting the stored version', async () => {
 serve({ conflict: true }); await openManual(); button('编辑 本地香港').click()
 await field('node-name', '保留我的输入'); await field('node-server', 'keep.invalid'); button('保存节点').click()
 await expect.poll(() => document.body.textContent).toContain('数据已变更')
 expect(document.querySelector<HTMLInputElement>('[name="node-name"]')?.value).toBe('保留我的输入')
 expect(document.querySelector<HTMLInputElement>('[name="node-server"]')?.value).toBe('keep.invalid')
 expect(writes.filter(w => w.method === 'PUT')).toHaveLength(1)
})
test('advanced JSON can be corrected after local syntax and server field errors without losing input', async () => {
 serve({ invalid: true }); await openManual(); button('编辑 本地香港').click()
 await expect.poll(() => button('高级 JSON')).toBeTruthy(); button('高级 JSON').click(); await field('node-json', '{broken')
 button('保存节点').click(); await expect.poll(() => document.body.textContent).toContain('高级参数必须是有效的 JSON 对象'); expect(writes).toHaveLength(0)
 const parameters = { ...connection, unsupported: 'fixture' }; await field('node-json', JSON.stringify(parameters)); button('保存节点').click()
 await expect.poll(() => document.body.textContent).toContain('node.unsupported')
 expect(JSON.parse(document.querySelector<HTMLTextAreaElement>('[name="node-json"]')!.value)).toEqual(parameters)
})
test('cancel writes nothing and reopening a new node clears the previous credentials', async () => {
 serve(); await openManual(); button('添加节点').click(); await field('node-name', '不保存'); await field('node-password', 'discard-fixture')
 button('取消').click(); await expect.poll(() => document.querySelector('[name="node-name"]')).toBeNull()
 button('添加节点').click(); await expect.poll(() => document.querySelector<HTMLInputElement>('[name="node-password"]')?.value).toBe('')
 expect(writes).toHaveLength(0)
})
test('delete shows draft and published impact, and submits the captured node and versions', async () => {
 serve(); await openManual(); button('删除节点 本地香港').click()
 await expect.poll(() => document.body.textContent).toContain('日常方案 的已发布订阅仍保留旧节点')
 expect(document.body.textContent).toContain('REJECT'); button('确认删除节点').click()
 await expect.poll(() => writes.find(w => w.method === 'DELETE')).toEqual({ path: '/local-nodes/local-a', method: 'DELETE', body: { expectedVersion: '3', ownerRevision: '8' } })
})
test('chain dependencies prevent confirming deletion', async () => {
 serve({ dependent: true }); await openManual(); button('删除节点 本地香港').click()
 await expect.poll(() => document.body.textContent).toContain('请先解除依赖'); expect(button('确认删除节点').disabled).toBe(true); expect(writes).toHaveLength(0)
})
test('late detail responses cannot replace the selected node or reopen a closed editor', async () => {
 const pending = deferred<Response>(), late = deferred<Response>(); let reads = 0
 const second = { ...local, id: 'local-b', originalName: '本地日本' }
 mockApi((path) => {
  if (path === '/sources' || path === '/profiles') return reply([])
  if (path.startsWith('/nodes?')) return reply({ items: [local, second], total: 2, page: 1, size: 50 })
  if (path === '/local-nodes/local-a') { reads++; return reads === 1 ? pending.promise : late.promise }
  if (path === '/local-nodes/local-b') return reply({ node: { ...second, connection, dialerRef: null }, version: '4', affectedProfiles: [], publishedProfiles: [] })
  throw new Error(path)
 })
 mount(NodePoolPage, { attachTo: document.body }); await expect.poll(() => button('编辑 本地香港')).toBeTruthy()
 button('编辑 本地香港').click(); await expect.poll(() => reads).toBe(1); button('编辑 本地日本').click()
 await expect.poll(() => document.querySelector<HTMLInputElement>('[name="node-name"]')?.value).toBe('本地日本')
 pending.resolve(reply({ node: { ...local, connection, dialerRef: null }, version: '3', affectedProfiles: [], publishedProfiles: [] }))
 await field('node-name', '日本自定义'); expect(document.querySelector<HTMLInputElement>('[name="node-name"]')?.value).toBe('日本自定义')
 button('取消').click(); await expect.poll(() => document.querySelector('[name="node-name"]')).toBeNull()
 button('编辑 本地香港').click(); await expect.poll(() => reads).toBe(2)
 document.querySelector<HTMLButtonElement>('.el-dialog__headerbtn')!.click()
 late.resolve(reply({ node: { ...local, connection, dialerRef: null }, version: '3', affectedProfiles: [], publishedProfiles: [] }))
 await expect.poll(() => document.querySelector('[name="node-name"]')).toBeNull()
})
test('same-owner reauthentication preserves unsaved credentials, while switching owners clears them', async () => {
 const owner = useSession().ownerId.value; serve(); await openManual(); button('添加节点').click()
 await field('node-name', '重登继续编辑'); await field('node-password', 'retained-fixture')
 mockApi(() => reply(null, 401)); await useSession().resume(); expect(useSession().status.value).toBe('anonymous')
 mockApi(() => reply({ id: owner, username: 'admin' })); await useSession().resume()
 expect(document.querySelector<HTMLInputElement>('[name="node-password"]')?.value).toBe('retained-fixture')
 mockApi(() => reply({ id: crypto.randomUUID(), username: 'another' })); await useSession().resume()
 await expect.poll(() => document.querySelector('[name="node-password"]')).toBeNull()
})
