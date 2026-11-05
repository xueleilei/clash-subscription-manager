import { afterEach, expect, test, vi } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import WorkspaceView from './WorkspaceView.vue'
import App from '../../App.vue'
import { createAppRouter } from '../../router'
import { useSession } from '../auth/useSession'
import { mockApi, reply, button } from '../../test/http'
import { documentFixture, draftFixture, nodesFixture, profileFixture, target } from '../../test/fixtures'
import type { NodeSummary, ProfileDocument } from '../../types/domain'
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
async function prepare({ nodes = nodesFixture(), profileDocument = documentFixture(), withApp = false, unpublishedNodes = false }: {
 nodes?: NodeSummary[]; profileDocument?: ProfileDocument; withApp?: boolean; unpublishedNodes?: boolean
} = {}) {
 const writes: ProfileDocument[] = []
 mockApi((path, method, body) => {
  if (path === '/session') return reply(method === 'GET' ? { id: owner, username: 'admin' } : null)
  if (path === '/profiles') return reply([{ ...profileFixture(), ...(unpublishedNodes ? { currentReleaseId: 'release-1', publishedVersion: '1', publishedDefinitionHash: 'hash-a', hasUnpublishedNodeChanges: true } : {}) }])
  if (path === '/sources') return reply([{ id: 's1', name: '云帆订阅' }, { id: 's2', name: '远山订阅' }])
  if (path.startsWith('/nodes?')) {
   const page = Number(new URLSearchParams(path.split('?')[1]).get('page') ?? 1), size = 200
   return reply({ items: nodes.slice((page - 1) * size, page * size), total: nodes.length, page, size })
  }
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
function memberCheckbox(id: string) { return document.querySelector<HTMLInputElement>(`[data-member-key="NODE:${id}:0"] input[type="checkbox"]`)! }
test('local node changes show pending publication even when the profile definition is unchanged', async () => {
 await prepare({ unpublishedNodes: true })
 await expect.poll(() => documentBody()).toContain('已保存待发布')
 expect(documentBody()).not.toContain('与已发布版本一致')
})
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
test('filters local candidates by their source group and keeps that label after adding a member', async () => {
 const grouped = nodesFixture().map(n => ({ ...n, sourceId: null, localGroupId: 'tag', localGroupName: 'TAG' }))
 const sameName = grouped[0]!
 const { wrapper, writes } = await prepare({ nodes: [
  ...grouped, { ...sameName, id: 'sjc', localGroupId: 'sjc-group', localGroupName: '联通SJC' },
  { ...sameName, id: 'loose', localGroupId: null, localGroupName: null },
  { ...sameName, id: 'remote', sourceId: 's1', localGroupId: null, localGroupName: null },
 ] })
 const source = wrapper.get('[aria-label="候选节点来源"]')
 const groupSelector = '[aria-label="候选节点本地分组"]'
 const visibleIds = () => wrapper.findAll('[data-node-id]').map(row => row.attributes('data-node-id'))
 await source.setValue('__LOCAL__')
 button('下一页候选节点').click()
 await wrapper.get(groupSelector).setValue('sjc-group')
 expect(visibleIds()).toEqual(['sjc'])
 expect(nodeCheckbox('sjc').getAttribute('aria-label')).toContain('联通SJC')
 await wrapper.get('[aria-label="候选节点地区"]').setValue('JP')
 expect(visibleIds()).toEqual([])
 await wrapper.get('[aria-label="候选节点地区"]').setValue('')
 button('选择本页可用').click()
 await wrapper.get(groupSelector).setValue('ungrouped')
 expect(visibleIds()).toEqual(['loose'])
 expect(nodeCheckbox('loose').getAttribute('aria-label')).toContain('未分组')
 await source.setValue('s1')
 expect(wrapper.find(groupSelector).exists()).toBe(false)
 expect(visibleIds()).toEqual(['remote'])
 button('加入当前组').click()
 await expect.poll(() => wrapper.get('[data-member-row]').text()).toContain('联通SJC')
 button('保存草稿').click()
 await expect.poll(() => writes[0]?.groups[0]?.members.map(member => member.id)).toEqual(['sjc'])
 await source.setValue('__LOCAL__')
 expect((wrapper.get(groupSelector).element as HTMLSelectElement).value).toBe('')
})
test('adds an entire local source group across pages, skips existing members and preserves unrelated selections', async () => {
 const grouped = nodesFixture(254).map((node, index) => ({
  ...node, sourceId: null, localGroupId: 'tag', localGroupName: 'TAG', protocol: index % 3 ? 'ss' : 'vmess',
 }))
 const sameName = grouped[0]!
 const { wrapper, writes } = await prepare({ nodes: [
  ...grouped, { ...sameName, id: 'sjc', localGroupId: 'sjc-group', localGroupName: '联通SJC' },
  { ...sameName, id: 'loose', localGroupId: null, localGroupName: null },
  { ...sameName, id: 'remote', sourceId: 's1', localGroupId: null, localGroupName: null },
 ] })
 const source = wrapper.get('[aria-label="候选节点来源"]')
 const groupSelector = '[aria-label="候选节点本地分组"]'
 const bulkSelector = '[aria-label="加入整个本地分组"]'
 expect(wrapper.find(bulkSelector).exists()).toBe(false)
 nodeCheckbox('n1').click()
 await expect.poll(() => button('加入当前组').disabled).toBe(false)
 button('加入当前组').click()
 await expect.poll(() => wrapper.findAll('[data-member-row]').length).toBe(1)
 await source.setValue('s1')
 nodeCheckbox('remote').click()
 await source.setValue('__LOCAL__')
 expect(wrapper.find(bulkSelector).exists()).toBe(false)
 await wrapper.get(groupSelector).setValue('tag')
 nodeCheckbox('n2').click()
 await wrapper.get('[aria-label="筛选候选节点"]').setValue('香港')
 await wrapper.get('[aria-label="候选节点地区"]').setValue('HK')
 await wrapper.get('[aria-label="候选节点协议"]').setValue('vmess')
 expect(wrapper.get('.picker-group-action').text()).toContain('整组 254 个 · 可加入 253 个')
 expect(wrapper.get('.picker-group-action').text()).toContain('包含其他筛选条件隐藏的节点')
 button('加入整个本地分组').click()
 await expect.poll(() => wrapper.findAll('[data-member-row]').length).toBe(254)
 expect(button('加入整个本地分组').disabled).toBe(true)
 expect(wrapper.get('.picker-group-action').text()).toContain('可加入 0 个')
 expect(wrapper.get('.picker-column .panel-footer').text()).toContain('已选 1 个节点')
 expect(writes).toHaveLength(0)
 expect(documentBody()).toContain('未保存')
 button('保存草稿').click()
 await expect.poll(() => writes.length).toBe(1)
 const groupedIds = grouped.map(node => node.id)
 expect(writes[0]!.groups[0]!.members.map(member => member.id)).toEqual(groupedIds)
 expect(writes[0]!.groups[1]!.members).toEqual([])
 await source.setValue('s1')
 expect(nodeCheckbox('remote').checked).toBe(true)
 expect(nodeCheckbox('remote').disabled).toBe(false)
 await source.setValue('__LOCAL__')
 await wrapper.get(groupSelector).setValue('tag')
 button('选择代理组 香港优选').click()
 await expect.poll(() => button('加入整个本地分组').disabled).toBe(false)
 button('加入整个本地分组').click()
 await expect.poll(() => wrapper.findAll('[data-member-row]').length).toBe(254)
 await wrapper.get(groupSelector).setValue('ungrouped')
 button('加入整个本地分组').click()
 await expect.poll(() => wrapper.findAll('[data-member-row]').length).toBe(255)
 button('保存草稿').click()
 await expect.poll(() => writes.length).toBe(2)
 expect(writes[1]!.groups[0]!.members.map(member => member.id)).toEqual(groupedIds)
 expect(writes[1]!.groups[1]!.members.map(member => member.id)).toEqual([...groupedIds, 'loose'])
})
test('bulk removal follows selected member identities after reordering and affects only the current draft group', async () => {
 const nodes = nodesFixture(4), profileDocument = documentFixture()
 nodes[1]!.originalName = nodes[0]!.originalName
 profileDocument.groups[0]!.members = nodes.map(node => target('NODE', node.id))
 profileDocument.groups[1]!.members = [target('NODE', 'n1'), target('NODE', 'n2')]
 const { wrapper, writes } = await prepare({ nodes, profileDocument })
 const selectAll = () => wrapper.get<HTMLInputElement>('[aria-label="全选当前组筛选结果"]').element
 expect(button('批量移除当前组成员').disabled).toBe(true)
 memberCheckbox('n1').click()
 await expect.poll(() => selectAll().indeterminate).toBe(true)
 button('选择代理组 香港优选').click()
 await expect.poll(() => wrapper.get('.member-selection').text()).toContain('已选 0 / 2')
 expect(memberCheckbox('n1').checked).toBe(false)
 expect(button('批量移除当前组成员').disabled).toBe(true)
 button('选择代理组 AI 服务').click()
 await expect.poll(() => wrapper.get('.member-selection').text()).toContain('已选 0 / 4')
 memberCheckbox('n1').click()
 memberCheckbox('n3').click()
 await wrapper.get('[data-member-key="NODE:n1:0"] [aria-label^="下移"]').trigger('click')
 expect(memberCheckbox('n1').checked).toBe(true)
 expect(memberCheckbox('n2').checked).toBe(false)
 expect(memberCheckbox('n3').checked).toBe(true)
 expect(wrapper.get('.member-selection').text()).toContain('已选 2 / 4')
 button('批量移除当前组成员').click()
 await expect.poll(() => wrapper.findAll('[data-member-row]').map(row => row.attributes('data-member-key'))).toEqual(['NODE:n2:0', 'NODE:n4:0'])
 expect(wrapper.get('.member-selection').text()).toContain('已选 0 / 2')
 expect(button('批量移除当前组成员').disabled).toBe(true)
 expect(nodeCheckbox('n1').disabled).toBe(false)
 expect(nodeCheckbox('n3').disabled).toBe(false)
 expect(nodeCheckbox('n2').disabled).toBe(true)
 expect(writes).toHaveLength(0)
 expect(documentBody()).toContain('未保存')
 button('保存草稿').click()
 await expect.poll(() => writes.length).toBe(1)
 expect(writes[0]!.groups[0]!.members).toEqual([target('NODE', 'n2'), target('NODE', 'n4')])
 expect(writes[0]!.groups[1]).toEqual(profileDocument.groups[1])
 expect(writes[0]!.rules).toEqual(profileDocument.rules)
})
test('select all covers the whole member list and clearing a group keeps its nodes and other groups available', async () => {
 const nodes = nodesFixture(), profileDocument = documentFixture()
 profileDocument.groups[0]!.members = [...nodes.map(node => target('NODE', node.id)), target('GROUP', 'g2'), target('DIRECT'), target('REJECT')]
 profileDocument.groups[1]!.members = [target('NODE', 'n1')]
 const { wrapper, writes } = await prepare({ nodes, profileDocument })
 const selectAll = wrapper.get<HTMLInputElement>('[aria-label="全选当前组筛选结果"]')
 await selectAll.setValue(true)
 expect(wrapper.get('.member-selection').text()).toContain('已选 57 / 57')
 expect(memberCheckbox('n54').checked).toBe(true)
 expect(selectAll.element.indeterminate).toBe(false)
 memberCheckbox('n2').click()
 await expect.poll(() => selectAll.element.indeterminate).toBe(true)
 expect(wrapper.get('.member-selection').text()).toContain('已选 56 / 57')
 button('清空成员选择').click()
 await expect.poll(() => wrapper.get('.member-selection').text()).toContain('已选 0 / 57')
 expect(button('批量移除当前组成员').disabled).toBe(true)
 await selectAll.setValue(true)
 await selectAll.setValue(false)
 expect(wrapper.findAll('[data-member-row] input:checked')).toHaveLength(0)
 await selectAll.setValue(true)
 button('批量移除当前组成员').click()
 await expect.poll(() => wrapper.findAll('[data-member-row]').length).toBe(0)
 expect(wrapper.get('.member-column').text()).toContain('空组 → REJECT')
 expect(selectAll.element.disabled).toBe(true)
 expect(selectAll.element.checked).toBe(false)
 expect(nodeCheckbox('n1').disabled).toBe(false)
 expect(writes).toHaveLength(0)
 button('保存草稿').click()
 await expect.poll(() => writes.length).toBe(1)
 expect(writes[0]!.groups[0]!.members).toEqual([])
 expect(writes[0]!.groups[1]).toEqual(profileDocument.groups[1])
})
test('combines member name and source-group filters and removes only the selected matching references', async () => {
 const nodes = nodesFixture(7), profileDocument = documentFixture()
 for (const node of nodes) node.originalName = '香港 Alpha'
 nodes[1] = { ...nodes[1]!, sourceId: null, localGroupId: 'tag', localGroupName: 'TAG' }
 nodes[2] = { ...nodes[2]!, originalName: '日本 Beta', sourceId: null, localGroupId: 'tag', localGroupName: 'TAG' }
 nodes[3] = { ...nodes[3]!, originalName: '香港 ALPHA 02', sourceId: null, localGroupId: 'tag', localGroupName: 'TAG' }
 nodes[4] = { ...nodes[4]!, sourceId: null, localGroupId: 'sjc', localGroupName: '联通SJC' }
 nodes[5] = { ...nodes[5]!, sourceId: null, localGroupId: null, localGroupName: null }
 nodes[6] = { ...nodes[6]!, sourceId: 's2' }
 profileDocument.groups[0]!.members = [...nodes.map(node => target('NODE', node.id)), target('DIRECT'), target('GROUP', 'g2')]
 profileDocument.groups[1]!.members = [target('NODE', 'n2'), target('NODE', 'n4')]
 const { wrapper, writes } = await prepare({ nodes, profileDocument })
 const search = wrapper.get('[aria-label="搜索当前组成员名称"]')
 const source = wrapper.get('[aria-label="当前组成员来源分组"]')
 const selectAll = wrapper.get<HTMLInputElement>('[aria-label="全选当前组筛选结果"]')
 const visibleKeys = () => wrapper.findAll('[data-member-row]').map(row => row.attributes('data-member-key'))
 await new DOMWrapper(memberCheckbox('n1')).setValue(true)
 await search.setValue(' ALPHA ')
 expect(visibleKeys()).toEqual(['n1', 'n2', 'n4', 'n5', 'n6', 'n7'].map(id => `NODE:${id}:0`))
 expect(wrapper.get('.member-filter-count').text()).toContain('匹配 6 / 共 9 个成员')
 expect(memberCheckbox('n1').checked).toBe(false)
 await source.setValue('local:tag')
 expect(visibleKeys()).toEqual(['NODE:n2:0', 'NODE:n4:0'])
 expect(wrapper.get<HTMLButtonElement>('[data-member-key="NODE:n2:0"] [aria-label^="上移"]').element.disabled).toBe(true)
 await selectAll.setValue(true)
 expect(wrapper.get('.member-selection').text()).toContain('已选 2 / 2')
 await wrapper.get('[data-member-key="NODE:n4:0"] [aria-label^="上移"]').trigger('click')
 expect(visibleKeys()).toEqual(['NODE:n4:0', 'NODE:n2:0'])
 expect(memberCheckbox('n4').checked).toBe(true)
 expect(memberCheckbox('n2').checked).toBe(true)
 expect(wrapper.get('.member-selection').text()).toContain('已选 2 / 2')
 await source.setValue('subscription:s1')
 expect(visibleKeys()).toEqual(['NODE:n1:0'])
 expect(button('批量移除当前组成员').disabled).toBe(true)
 await selectAll.setValue(true)
 await source.setValue('local:tag')
 expect(wrapper.get('.member-selection').text()).toContain('已选 0 / 2')
 await selectAll.setValue(true)
 button('批量移除当前组成员').click()
 await expect.poll(visibleKeys).toEqual([])
 expect(wrapper.get('.member-column').text()).toContain('没有匹配的成员')
 expect(wrapper.get('.member-column').text()).not.toContain('空组 → REJECT')
 expect(wrapper.get('.member-filter-count').text()).toContain('匹配 0 / 共 7 个成员')
 expect(selectAll.element.disabled).toBe(true)
 expect(nodeCheckbox('n2').disabled).toBe(false)
 expect(nodeCheckbox('n4').disabled).toBe(false)
 expect(writes).toHaveLength(0)
 button('清除成员筛选').click()
 await expect.poll(() => visibleKeys().length).toBe(7)
 expect(visibleKeys()).toContain('NODE:n1:0')
 expect(visibleKeys()).toContain('NODE:n3:0')
 button('保存草稿').click()
 await expect.poll(() => writes.length).toBe(1)
 expect(writes[0]!.groups[0]!.members).toEqual(profileDocument.groups[0]!.members.filter(member => member.id !== 'n2' && member.id !== 'n4'))
 expect(writes[0]!.groups[1]).toEqual(profileDocument.groups[1])
 expect(writes[0]!.rules).toEqual(profileDocument.rules)
})
test('member source filters distinguish ungrouped nodes and non-node references, and reset when switching proxy groups', async () => {
 const nodes = nodesFixture(3), profileDocument = documentFixture()
 nodes[0] = { ...nodes[0]!, sourceId: null, localGroupId: null, localGroupName: null }
 nodes[1] = { ...nodes[1]!, sourceId: null, localGroupId: 's1', localGroupName: '云帆订阅' }
 profileDocument.groups[0]!.members = [...nodes.map(node => target('NODE', node.id)), target('GROUP', 'g2'), target('DIRECT'), target('NODE', 'missing'), { kind: 'UNRESOLVED', id: null, unresolvedName: 'Legacy' }]
 profileDocument.groups[1]!.members = [target('NODE', 'n1'), target('NODE', 'n2')]
 const { wrapper } = await prepare({ nodes, profileDocument })
 const search = wrapper.get<HTMLInputElement>('[aria-label="搜索当前组成员名称"]')
 const source = wrapper.get<HTMLSelectElement>('[aria-label="当前组成员来源分组"]')
 const visibleKeys = () => wrapper.findAll('[data-member-row]').map(row => row.attributes('data-member-key'))
 await source.setValue('local:ungrouped')
 await search.setValue('香港')
 expect(visibleKeys()).toEqual(['NODE:n1:0'])
 await wrapper.get('[aria-label="全选当前组筛选结果"]').setValue(true)
 button('选择代理组 香港优选').click()
 await expect.poll(() => search.element.value).toBe('')
 expect(source.element.value).toBe('')
 expect(visibleKeys()).toEqual(['NODE:n1:0', 'NODE:n2:0'])
 expect(button('批量移除当前组成员').disabled).toBe(true)
 await new DOMWrapper(button('选择代理组 AI 服务')).trigger('click')
 await source.setValue('local:all')
 expect(visibleKeys()).toEqual(['NODE:n1:0', 'NODE:n2:0'])
 await source.setValue('local:s1')
 expect(visibleKeys()).toEqual(['NODE:n2:0'])
 await source.setValue('subscription:s1')
 expect(visibleKeys()).toEqual(['NODE:n3:0'])
 await source.setValue('')
 await search.setValue('香港优选')
 expect(visibleKeys()).toEqual(['GROUP:g2:0'])
 await source.setValue('local:all')
 expect(visibleKeys()).toEqual([])
 await source.setValue('')
 await search.setValue('direct')
 expect(visibleKeys()).toEqual(['DIRECT::0'])
 await search.setValue('已失效')
 expect(visibleKeys()).toEqual(['NODE:missing:0'])
 await search.setValue('legacy')
 expect(visibleKeys()).toEqual(['UNRESOLVED:Legacy:0'])
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
 await prepare({ withApp: true })
 nodeCheckbox('n1').click(); await expect.poll(() => button('加入当前组').disabled).toBe(false); button('加入当前组').click(); await expect.poll(() => documentBody()).toContain('未保存')
 button('退出登录').click(); await expect.poll(() => documentBody()).toContain('退出将丢失未保存内容')
 expect(useSession().status.value).toBe('authenticated'); button('继续编辑').click()
 expect(useSession().status.value).toBe('authenticated'); button('退出登录').click(); await expect.poll(() => documentBody()).toContain('退出将丢失未保存内容'); button('放弃修改并退出').click()
 await expect.poll(() => useSession().status.value).toBe('anonymous')
})
