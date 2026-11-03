import { afterEach, expect, test, vi } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ImportWizard from './ImportWizard.vue'
import NodeBindingDialog from './NodeBindingDialog.vue'
import { documentFixture, draftFixture, nodesFixture, profileFixture } from '../../test/fixtures'
import { button, mockApi, reply } from '../../test/http'
afterEach(()=>{vi.unstubAllGlobals();document.body.innerHTML=''})
const imported={id:'preview',ownerRevision:'1',expiresAt:'2099-01-01T00:00:00Z',document:documentFixture(),nodes:[{id:'local1',originalName:'同名节点',protocol:'ss',suggestedRemoteId:null,candidateRemoteIds:['n1','n2']}],report:{errors:[],warnings:[]}}
test('real YAML file preview keeps ambiguous nodes local by default and confirms into a new profile',async()=>{
 let input:unknown, choices:unknown
 mockApi((path,_method,body)=>{if(path==='/imports/preview'){input=body.yaml;return reply(imported)}if(path==='/imports/preview/confirm'){choices=body.choices;return reply(draftFixture('new-profile'))}if(path==='/profiles')return reply([profileFixture('existing'),profileFixture('new-profile')]);if(path==='/sources')return reply([{id:'s1',name:'订阅一'},{id:'s2',name:'订阅二'}]);if(path.startsWith('/nodes?'))return reply({items:nodesFixture(2),total:2,page:1,size:200});throw new Error(path)})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/import',component:ImportWizard},{path:'/profiles/:id',component:{template:'<p>新方案</p>'}}]});await router.push('/import');await router.isReady()
 mount(ImportWizard,{attachTo:document.body,global:{plugins:[router]}})
 const inputElement=document.querySelector<HTMLInputElement>('input[type="file"]')!,transfer=new DataTransfer();transfer.items.add(new File(['proxies: []\nrules: ["MATCH,REJECT"]\n'],'config.yaml',{type:'text/yaml'}));inputElement.files=transfer.files;inputElement.dispatchEvent(new Event('change',{bubbles:true}))
 await expect.poll(()=>(document.querySelector('[aria-label="完整配置 YAML"]')as HTMLTextAreaElement).value).toContain('MATCH,REJECT');button('解析并预览').click();await expect.poll(()=>document.body.textContent).toContain('多个相同参数候选')
 expect(input).toContain('proxies: []');expect((document.querySelector('[aria-label="同名节点 的导入方式"]')as HTMLSelectElement).value).toBe('LOCAL')
 button('创建新方案草稿').click();await expect.poll(()=>router.currentRoute.value.params.id).toBe('new-profile');expect(choices).toEqual({local1:{mode:'LOCAL',remoteNodeId:null}})
})
test('editing YAML invalidates a pending preview and never applies an old response',async()=>{
 let resolve!:(value:Response)=>void;mockApi(path=>{if(path==='/imports/preview')return new Promise<Response>(done=>{resolve=done});if(path==='/sources')return reply([]);if(path.startsWith('/nodes?'))return reply({items:[],total:0,page:1,size:200});throw new Error(path)})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:ImportWizard}]});await router.push('/');mount(ImportWizard,{attachTo:document.body,global:{plugins:[router]}})
 const textarea=new DOMWrapper(document.querySelector('[aria-label="完整配置 YAML"]')!);await textarea.setValue('rules: [MATCH,REJECT]');button('解析并预览').click();await expect.poll(()=>resolve).toBeTruthy();await textarea.setValue('rules: []');resolve(reply(imported));await new Promise(resolve=>setTimeout(resolve,30));expect(document.body.textContent).not.toContain('同名节点');expect(document.querySelector('[data-import-preview]')).toBeNull()
})
test('local binding previews affected drafts and requires explicit apply without claiming publication',async()=>{
 let applied=0
 mockApi((path,_method,body)=>{if(path.startsWith('/nodes?'))return reply({items:nodesFixture(2),total:2,page:1,size:200});if(path==='/sources')return reply([]);if(path==='/profiles')return reply([profileFixture()]);if(path==='/nodes/local1/binding/preview'){expect(body.remoteNodeId).toBe('n1');return reply({id:'binding',localNodeId:'local1',remoteNodeId:'n1',ownerRevision:'2',expiresAt:'2099-01-01',affectedProfiles:['p1'],changedFields:['server','password']})}if(path==='/bindings/binding/apply'){applied++;return reply({localNodeId:'local1',remoteNodeId:'n1',affectedProfiles:['p1'],copiedNodes:1})}throw new Error(path)})
 const wrapper=mount(NodeBindingDialog,{attachTo:document.body,props:{localNode:{...nodesFixture(1)[0]!,id:'local1',sourceId:null}}})
 await expect.poll(()=>document.querySelector('[data-remote-node="n1"]')).not.toBeNull();(document.querySelector('[data-remote-node="n1"]')as HTMLInputElement).click();await expect.poll(()=>button('预览关联影响').disabled).toBe(false);button('预览关联影响').click()
 await expect.poll(()=>document.body.textContent).toContain('工作专用');expect(document.body.textContent).toContain('仅更新草稿');expect(applied).toBe(0);button('确认关联到草稿').click();await expect.poll(()=>applied).toBe(1);await expect.poll(()=>wrapper.emitted('applied')?.length).toBe(1)
})
