import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import ManualNodesPanel from './ManualNodesPanel.vue'
import NodePoolView from '../sources/NodePoolView.vue'
import { defaultNodeFilters } from '../sources/useSources'
import { button, mockApi, reply } from '../../test/http'
import { useSession } from '../auth/useSession'

const fixtures = ['甲', '乙', '丙'].map((name, index) => ({id: `local-${index}`, sourceId: null, originalName: name, protocol: 'ss', region: 'UNKNOWN', snapshotVersion: '3'}))
const writes: {path: string; method: string; body: Record<string, unknown>}[] = []
function serve(blocked = false, conflict = false) {
 let remaining = [...fixtures]
 mockApi((path, method, body) => {
  if (method !== 'GET') writes.push({path, method, body})
  if (path === '/profiles') return reply([{id: 'p1', name: '日常方案'}])
  if (path.startsWith('/nodes?')) {
   const page = Number(new URLSearchParams(path.split('?')[1]).get('page') ?? '1')
   return reply({items: remaining.slice((page - 1) * 2, page * 2), total: remaining.length, page, size: 2})
  }
  if (path === '/local-nodes/batch/removal') return reply({nodes: (body.ids as string[]).map(id => ({id, name: fixtures.find(node => node.id === id)!.originalName, version: '3'})), ownerRevision: '7', affectedProfiles: ['p1'], publishedProfiles: ['p1'], dependentNodes: blocked ? [{id: 'external', name: '未选中的链式节点'}] : []})
  if (path === '/local-nodes/batch' && method === 'DELETE') {
   if (conflict) return reply(null, 409)
   const ids = (body.nodes as {id: string}[]).map(node => node.id); remaining = remaining.filter(node => !ids.includes(node.id))
   return reply({ids, affectedProfiles: ['p1']})
  }
  throw new Error(`${method} ${path}`)
 })
}
async function check(label: string, value = true) {
 await expect.poll(() => document.querySelector(`[aria-label="${label}"]`)).not.toBeNull()
 await new DOMWrapper(document.querySelector(`[aria-label="${label}"]`)!).setValue(value)
}
beforeEach(async () => { writes.length = 0; mockApi(() => reply({id: crypto.randomUUID(), username: 'admin'})); await useSession().resume() })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })

test('selection spans pages and only confirmed captured nodes are deleted in one request', async () => {
 serve(); mount(ManualNodesPanel, {attachTo: document.body})
 await check('选择节点 甲'); button('下一页').click(); await check('选择节点 丙')
 expect(document.body.textContent).toContain('已选 2 个节点'); button('批量删除').click()
 await expect.poll(() => document.body.textContent).toContain('日常方案 的已发布订阅仍保留旧节点')
 expect(writes.map(write => write.method)).toEqual(['POST'])
 button('确认删除 2 个节点').click()
 await expect.poll(() => writes.filter(write => write.method === 'DELETE')).toEqual([{path: '/local-nodes/batch', method: 'DELETE', body: {nodes: [{id: 'local-0', expectedVersion: '3'}, {id: 'local-2', expectedVersion: '3'}], ownerRevision: '7'}}])
 await expect.poll(() => document.body.textContent).toContain('已删除 2 个手动节点')
 expect(document.body.textContent).toContain('已选 0 个节点'); expect(button('批量删除').disabled).toBe(true)
 expect(document.querySelector('[aria-label="选择节点 乙"]')).not.toBeNull()
})
test('select all is limited to the current page and changing filters clears hidden selection', async () => {
 serve(); mount(ManualNodesPanel, {attachTo: document.body})
 await check('选择节点 甲')
 expect(document.querySelector<HTMLInputElement>('[aria-label="全选本页手动节点"]')!.indeterminate).toBe(true)
 await check('全选本页手动节点'); button('下一页').click()
 await expect.poll(() => document.querySelector('[aria-label="选择节点 丙"]')).not.toBeNull()
 expect(document.querySelector<HTMLInputElement>('[aria-label="选择节点 丙"]')!.checked).toBe(false)
 expect(document.body.textContent).toContain('已选 2 个节点')
 await new DOMWrapper(document.querySelector('[aria-label="搜索节点名称"]')!).setValue('丙'); button('搜索').click()
 await expect.poll(() => document.body.textContent).toContain('已选 0 个节点')
 expect(writes).toEqual([])
})
test('unselected chain dependencies block deletion and cancel performs no write', async () => {
 serve(true); mount(ManualNodesPanel, {attachTo: document.body}); await check('选择节点 甲'); button('批量删除').click()
 await expect.poll(() => document.body.textContent).toContain('未选中的链式节点')
 expect(button('确认删除 1 个节点').disabled).toBe(true)
 button('取消').click(); expect(writes.filter(write => write.method === 'DELETE')).toEqual([])
 expect(document.body.textContent).toContain('已选 1 个节点')
})
test('a version conflict keeps selection and disables confirmation until rechecking', async () => {
 serve(false, true); mount(ManualNodesPanel, {attachTo: document.body}); await check('选择节点 甲'); button('批量删除').click()
 await expect.poll(() => button('确认删除 1 个节点').disabled).toBe(false); button('确认删除 1 个节点').click()
 await expect.poll(() => document.body.textContent).toContain('数据已变更')
 expect(button('确认删除 1 个节点').disabled).toBe(true); expect(button('重新检查影响')).toBeTruthy()
 expect(document.body.textContent).toContain('已选 1 个节点')
 button('重新检查影响').click()
 await expect.poll(() => button('确认删除 1 个节点').disabled).toBe(false)
 expect(writes.filter(write => write.path.endsWith('/removal'))).toHaveLength(2)
})

test('canceling an unfinished preview ignores its late response and preserves selection', async () => {
 let finish: (response: Response) => void = () => {}
 let previews = 0
 mockApi((path, method) => {
  if (path === '/profiles') return reply([])
  if (path.startsWith('/nodes?')) return reply({items: fixtures, total: 3, page: 1, size: 50})
  if (path === '/local-nodes/batch/removal') { previews++; return new Promise<Response>(resolve => { finish = resolve }) }
  throw new Error(`${method} ${path}`)
 })
 mount(ManualNodesPanel, {attachTo: document.body}); await check('选择节点 甲'); button('批量删除').click()
 await expect.poll(() => previews).toBe(1); button('取消').click()
 await expect.poll(() => document.querySelector('[role="dialog"][aria-label="批量删除手动节点"]')).toBeNull()
 finish(reply({nodes: [{id: 'local-0', name: 'Late fixture', version: '3'}], ownerRevision: '7', affectedProfiles: [], publishedProfiles: [], dependentNodes: []}))
 await flushPromises()
 await check('选择节点 乙')
 expect(document.body.textContent).toContain('已选 2 个节点')
 expect(document.body.textContent).not.toContain('Late fixture')
 expect(document.querySelector('[role="dialog"][aria-label="批量删除手动节点"]')).toBeNull()
})

test('selection limit disables additions while selected rows can still be unchecked', async () => {
 const selected = new Set([...Array.from({length: 499}, (_, i) => `other-${i}`), 'local-0'])
 const wrapper = mount(NodePoolView, {attachTo: document.body, props: {nodes: {items: fixtures, total: 3, page: 1, size: 50}, query: defaultNodeFilters(), sources: [], busy: false, localOnly: true, allowSelection: true, selectedIds: selected, selectionLimit: 500}})
 expect(document.querySelector<HTMLInputElement>('[aria-label="选择节点 甲"]')!.disabled).toBe(false)
 expect(document.querySelector<HTMLInputElement>('[aria-label="选择节点 乙"]')!.disabled).toBe(true)
 expect(document.querySelector<HTMLInputElement>('[aria-label="全选本页手动节点"]')!.disabled).toBe(true)
 await check('选择节点 甲', false)
 expect(wrapper.emitted('toggleSelection')?.[0]).toEqual([fixtures[0]])
 await wrapper.setProps({allowSelection: false})
 expect(document.querySelector('input[type="checkbox"]')).toBeNull()
})
