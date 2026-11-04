import { afterEach, expect, test, vi } from 'vitest'
import { DOMWrapper, mount } from '@vue/test-utils'
import ManualNodesPanel from './ManualNodesPanel.vue'
import { button, mockApi, reply } from '../../test/http'

const path = '/local-node-groups'
function fixture(options: { failFilter?: boolean; removeFolderOnImport?: boolean } = {}) {
 const groups = [{id:'g1', name:'自建', version:'0'}]
 const nodes = ['甲','乙','丙'].map((originalName, i) => ({id:'n'+i, sourceId:null, originalName, protocol:'ss', region:'HK', snapshotVersion:'0', localGroupId:i === 1 ? 'g1' : ''}))
 const writes: {url:string; method:string; body:Record<string,unknown>}[] = []
 const directory = () => ({groups:groups.map(g => ({...g, nodeCount:nodes.filter(n => n.localGroupId === g.id).length})), ungroupedCount:nodes.filter(n => !n.localGroupId).length})
 mockApi((url, method, body) => {
  if (method !== 'GET') writes.push({url, method, body})
  if (url === path && method === 'GET') return reply(directory())
  if (url === path && method === 'POST') { groups.push({id:'g2', name:String(body.name), version:'0'}); return reply(groups.at(-1)) }
  if (url === path+'/g2' && method === 'PATCH') { groups[1]!.name = String(body.name); groups[1]!.version = '1'; return reply(groups[1]) }
  if (url === path+'/g2?expectedVersion=1' && method === 'DELETE') { groups.pop(); nodes.forEach(n => { if (n.localGroupId === 'g2') n.localGroupId = '' }); return reply(null) }
  if (url === path+'/g1/members') { nodes.forEach(n => { if ((body.ids as string[]).includes(n.id)) n.localGroupId = 'g1' }); return reply(null) }
  if (url.startsWith('/nodes?')) {
   const params = new URLSearchParams(url.split('?')[1]), page = Number(params.get('page') ?? 1)
   if (options.failFilter && params.has('localGroupId')) return reply(null,503,'分组列表暂时不可用')
   const filtered = nodes.filter(n => params.has('localGroupId') ? n.localGroupId === params.get('localGroupId') : !params.has('ungrouped') || !n.localGroupId)
   const size = params.get('size') === '200' ? 200 : 1
   return reply({items:filtered.slice((page-1)*size,page*size).map(n => ({...n, localGroupName:groups.find(g => g.id === n.localGroupId)?.name})), page, size, total:filtered.length})
  }
  if (url === '/profiles') return reply([])
  if (url === '/local-nodes/batch/preview') return reply({ownerRevision:'4',nodes:['一','二'].map(name => ({name,protocol:'ss',connection:{server:'fixture.invalid',port:443}}))})
  if (url === '/local-nodes/batch') {
   if (options.removeFolderOnImport && body.localGroupId) { groups.splice(0); return reply(null,404,'来源分组不存在，请重新选择') }
   return reply({ids:['new1','new2'],affectedProfiles:[]})
  }
  throw new Error(method+' '+url)
 }, { handleLocalGroups:true })
 mount(ManualNodesPanel, {attachTo:document.body})
 return writes
}
async function click(name: string) { await expect.poll(() => button(name).disabled).toBe(false); button(name).click() }
async function field(label:string,value:string|boolean) {
 const selector = '[aria-label="'+label+'"]'
 await expect.poll(() => document.querySelector<HTMLInputElement>(selector)?.disabled).toBe(false)
 await new DOMWrapper(document.querySelector(selector)!).setValue(value)
}
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML='' })

test('moves a cross-page selection, refreshes counts and clears selection when switching source folders', async () => {
 const writes = fixture(); await click('筛选分组 未分组')
 await field('选择节点 甲', true); await click('下一页'); await field('选择节点 丙', true)
 await click('移动到分组'); await field('目标来源分组','g1'); await click('取消移动')
 expect(writes).toEqual([]); expect(document.body.textContent).toContain('已选 2 个节点')
 await click('移动到分组'); await field('目标来源分组','g1'); await click('确认移动')
 await expect.poll(() => writes).toEqual([{url:path+'/g1/members',method:'PUT',body:{ids:['n0','n2']}}])
 await expect.poll(() => button('筛选分组 自建').textContent).toMatch(/自建\s*3/)
 expect(document.body.textContent).toContain('已选 0 个节点')
 await click('筛选分组 自建'); await field('选择节点 甲',true)
 await click('筛选分组 全部分组'); await expect.poll(() => document.body.textContent).toContain('已选 0 个节点')
})

test('creates and renames a folder and explicitly removes only the folder', async () => {
 const writes = fixture(); await click('管理分组')
 await field('新分组名称','朋友'); await click('新建分组')
 await click('重命名分组 朋友'); await field('修改分组名称','同事'); await click('保存名称')
 await click('删除分组 同事')
 await expect.poll(() => document.body.textContent).toContain('节点会回到「未分组」')
 expect(writes.map(w => w.method)).toEqual(['POST','PATCH'])
 await click('确认删除分组')
 await expect.poll(() => document.querySelector('[aria-label="筛选分组 同事"]')).toBeNull()
 expect(writes.at(-1)?.url).toBe(path+'/g2?expectedVersion=1')
 expect(writes.at(-1)?.method).toBe('DELETE')
})

test('batch import uses the currently selected folder for every node in a single write', async () => {
 const writes = fixture(); await click('筛选分组 自建')
 await expect.poll(() => document.querySelector('[aria-label="选择节点 乙"]')).not.toBeNull()
 await click('添加节点')
 await expect.poll(() => document.querySelector<HTMLSelectElement>('[aria-label="保存到来源分组"]')?.value).toBe('g1')
 await click('分享链接 / YAML')
 await expect.poll(() => document.querySelector('[name="node-input"]')).not.toBeNull()
 await new DOMWrapper(document.querySelector('[name="node-input"]')!).setValue('fixture node list')
 await click('解析并预览'); await click('确认导入 2 个节点')
 await expect.poll(() => writes.find(w => w.url === '/local-nodes/batch')?.body).toEqual({text:'fixture node list',ownerRevision:'4',localGroupId:'g1'})
 expect(writes.filter(w => w.url.endsWith('/members'))).toHaveLength(0)
})

test('a failed folder filter never offers rows from the preceding folder for selection', async () => {
 fixture({failFilter:true})
 await field('选择节点 甲',true); await click('筛选分组 自建')
 await expect.poll(() => document.body.textContent).toContain('分组列表暂时不可用')
 expect(document.querySelector('[aria-label="选择节点 甲"]')).toBeNull()
 expect(button('批量删除').disabled).toBe(true)
})

test('a removed import destination preserves pasted input and requires an explicit new choice', async () => {
 const writes = fixture({removeFolderOnImport:true})
 await click('筛选分组 自建'); await click('添加节点'); await click('分享链接 / YAML')
 const input = document.querySelector<HTMLTextAreaElement>('[name="node-input"]')!
 await new DOMWrapper(input).setValue('kept import text')
 await click('解析并预览'); await click('确认导入 2 个节点')
 await expect.poll(() => document.body.textContent).toContain('原分组已不存在')
 expect(input.value).toBe('kept import text')
 await field('保存到来源分组',''); await click('解析并预览'); await click('确认导入 2 个节点')
 await expect.poll(() => document.body.textContent).toContain('已导入 2 个手动节点到「未分组」')
 expect(writes.filter(w => w.url === '/local-nodes/batch').map(w => w.body.localGroupId)).toEqual(['g1',undefined])
})
