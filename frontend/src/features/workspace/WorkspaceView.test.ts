import { afterEach, expect, test, vi } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import WorkspaceView from './WorkspaceView.vue'
import App from '../../App.vue'
import { createAppRouter } from '../../router'
import { useSession } from '../auth/useSession'
import { mockApi, reply, button } from '../../test/http'
import { documentFixture, draftFixture, nodesFixture, profileFixture } from '../../test/fixtures'
import type { ProfileDocument } from '../../types/domain'
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
async function prepare(profileDocument = documentFixture(), withApp = false) {
 const nodes = nodesFixture(), writes: ProfileDocument[] = []
 mockApi((path, method, body) => {
  if (path === '/session') return reply(method === 'GET' ? { id: owner, username: 'admin' } : null)
  if (path === '/profiles') return reply([profileFixture()])
  if (path === '/sources') return reply([{ id: 's1', name: '云帆订阅' }, { id: 's2', name: '远山订阅' }])
  if (path.startsWith('/nodes?')) return reply({ items: nodes, total: nodes.length, page: 1, size: 200 })
  if (path === '/profiles/p1/draft' && method === 'GET') return reply(draftFixture('p1', '1', profileDocument))
  if (path === '/profiles/p1/draft' && method === 'PUT') { writes.push(body.document as ProfileDocument); return reply(draftFixture('p1', '2', body.document as ProfileDocument)) }
  throw new Error(`${method} ${path}`)
 })
 const owner = crypto.randomUUID(); await useSession().resume()
 const router = createAppRouter(createMemoryHistory()); await router.push('/profiles/p1'); await router.isReady()
 const wrapper = mount(withApp ? App : WorkspaceView, { attachTo: document.body, global: { plugins: [router] } })
 await expect.poll(() => documentBody().includes('AI 服务')).toBe(true)
 await expect.poll(() => nodeCheckbox('n1')).toBeTruthy()
 return { wrapper, writes }
}
function documentBody() { return document.body.textContent ?? '' }
function nodeCheckbox(id: string) { return document.querySelector<HTMLInputElement>(`input[data-node-id="${id}"]`)! }
test('cross-source selection persists across filters/pages and same node can join different groups only once each', async () => {
 const { writes } = await prepare()
 nodeCheckbox('n1').click(); nodeCheckbox('n2').click()
 await new DOMWrapper(document.querySelector('input[aria-label="筛选候选节点"]')!).setValue('香港')
 await expect.poll(() => documentBody()).toContain('已选 2 个节点')
 button('加入当前组').click(); await expect.poll(() => document.querySelectorAll('[data-member-row]').length).toBe(2)
 expect(nodeCheckbox('n1').disabled).toBe(true)
 await new DOMWrapper(document.querySelector('input[aria-label="筛选候选节点"]')!).setValue('')
 nodeCheckbox('n3').click(); button('选择代理组 香港优选').click(); await expect.poll(() => documentBody()).toContain('已选 0 个节点')
 nodeCheckbox('n1').click(); button('下一页候选节点').click(); await expect.poll(() => nodeCheckbox('n51')).toBeTruthy(); nodeCheckbox('n51').click()
 button('加入当前组').click(); button('保存草稿').click(); await expect.poll(() => writes.length).toBe(1)
 expect(writes[0]!.groups[0]!.members.map(ref => ref.id)).toEqual(['n1', 'n2']); expect(writes[0]!.groups[1]!.members.map(ref => ref.id)).toEqual(['n1', 'n51'])
})
test('members reorder/remove, nested groups reject cycles, and empty groups visibly fall back to REJECT', async () => {
 const { writes } = await prepare(); expect(documentBody()).toContain('空组 → REJECT')
 nodeCheckbox('n1').click(); nodeCheckbox('n2').click(); await expect.poll(() => button('加入当前组').disabled).toBe(false); button('加入当前组').click()
 await expect.poll(() => document.querySelectorAll('[data-member-row]').length).toBe(2); button('下移 香港 01').click()
 await expect.poll(() => document.querySelector('[data-member-row]')?.textContent).toContain('日本 02'); button('移除 日本 02').click()
 button('引用其他组').click(); await expect.poll(() => document.querySelector('[aria-label="引用的代理组"]')).not.toBeNull()
 await new DOMWrapper(document.querySelector('[aria-label="引用的代理组"]')!).setValue('g2'); button('添加组引用').click()
 button('选择代理组 香港优选').click(); await expect.poll(() => document.querySelectorAll('[data-member-row]').length).toBe(0); button('引用其他组').click(); await expect.poll(() => document.querySelector('select[aria-label="引用的代理组"] option[value="g1"]')).not.toBeNull()
 const option = document.querySelector<HTMLOptionElement>('select[aria-label="引用的代理组"] option[value="g1"]')!; expect(option.disabled).toBe(true)
 button('关闭引用选择').click(); button('保存草稿').click(); await expect.poll(() => writes.length).toBe(1)
 expect(writes[0]!.groups[0]!.members.map(ref => `${ref.kind}:${ref.id}`)).toEqual(['NODE:n1', 'GROUP:g2'])
})
test('explicit logout warns about unsaved edits before ending the session', async () => {
 await prepare(documentFixture(), true)
 nodeCheckbox('n1').click(); await expect.poll(() => button('加入当前组').disabled).toBe(false); button('加入当前组').click(); await expect.poll(() => documentBody()).toContain('未保存')
 button('退出登录').click(); await expect.poll(() => documentBody()).toContain('退出将丢失未保存内容')
 expect(useSession().status.value).toBe('authenticated'); button('继续编辑').click()
 expect(useSession().status.value).toBe('authenticated'); button('退出登录').click(); await expect.poll(() => documentBody()).toContain('退出将丢失未保存内容'); button('放弃修改并退出').click()
 await expect.poll(() => useSession().status.value).toBe('anonymous')
})
