import { afterEach, expect, test, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ManualNodesPanel from './ManualNodesPanel.vue'
import NodePicker from '../workspace/NodePicker.vue'
import { mockApi, reply } from '../../test/http'

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
const nodeRegions = [{name: '自定义分组', regions: [{code: 'MO', name: '澳门'}, {code: 'XZ', name: '自定义地区'}]}]

test('manual node filters follow the server catalog, including entries absent from the current page', async () => {
 const queries: URLSearchParams[] = []
 mockApi(path => {
  if (path.startsWith('/nodes?')) {
   const query = new URLSearchParams(path.split('?')[1]); queries.push(query)
   const region = query.get('region') || 'MO'
   return reply({items: [{id: 'node', sourceId: null, originalName: region + ' 节点', region, protocol: 'ss', snapshotVersion: '1'}], total: 1, page: 1, size: 50})
  }
  throw new Error(path)
 }, {nodeRegions})
 const view = mount(ManualNodesPanel, {attachTo: document.body})
 const select = view.get<HTMLSelectElement>('[aria-label="筛选地区"]')
 await expect.poll(() => [...select.element.options].map(option => option.text)).toContain('自定义地区（XZ）')
 expect(select.find('optgroup[label="自定义分组"]').exists()).toBe(true)
 expect(select.find('option[value="HK"]').exists()).toBe(false)
 await select.setValue('XZ')
 await expect.poll(() => view.find('tbody').text()).toContain('XZ 节点')
 expect(queries.at(-1)?.get('region')).toBe('XZ')
 expect(queries.at(-1)?.get('localOnly')).toBe('true')
 expect(queries.at(-1)?.get('page')).toBe('1')
 await select.setValue('UNKNOWN')
 await expect.poll(() => queries.at(-1)?.get('region')).toBe('UNKNOWN')
})

test('the workspace picker uses configured labels for present regions and still filters unknown nodes', async () => {
 mockApi(path => { throw new Error(path) }, {nodeRegions})
 const base = {sourceId: null, protocol: 'ss', snapshotVersion: '1'}
 const nodes = [{...base, id: 'a', originalName: '自定义线路', region: 'XZ'}, {...base, id: 'b', originalName: '无地区线路', region: null}]
 const view = mount(NodePicker, {props: {nodes, sources: [], disabledIds: new Set<string>(), selectedIds: new Set<string>(), hasGroup: true}})
 const select = view.get<HTMLSelectElement>('[aria-label="候选节点地区"]')
 await expect.poll(() => [...select.element.options].map(item => item.text)).toContain('自定义地区（XZ）')
 expect(select.find('option[value="MO"]').exists()).toBe(false)
 await select.setValue('XZ'); expect(view.findAll('.picker-row strong').map(item => item.text())).toEqual(['自定义线路'])
 await select.setValue('UNKNOWN'); expect(view.findAll('.picker-row strong').map(item => item.text())).toEqual(['无地区线路'])
})
