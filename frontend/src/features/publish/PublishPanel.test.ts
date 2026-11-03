import { afterEach, expect, test, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PublishPanel from './PublishPanel.vue'
import SubscriptionLink from './SubscriptionLink.vue'
import { documentFixture } from '../../test/fixtures'
import { button, mockApi, reply } from '../../test/http'
import type { PublishPreview, ReleaseView } from '../../types/domain'
afterEach(()=>{vi.unstubAllGlobals();document.body.innerHTML=''})
const previous:ReleaseView={id:'rel1',profileId:'p1',version:'4',definitionHash:'old',yamlDigest:'old-yaml',nodeCount:2,reason:'MANUAL',createdAt:'2026-11-03T00:00:00Z'}
function preview():PublishPreview{return {profileId:'p1',expectedVersion:'3',definitionHash:'new',yamlDigest:'new-yaml',yaml:'proxies: []\nrules: [MATCH,REJECT]\n',nodeCount:0,groupCount:2,ruleCount:1,emptyGroupIds:['g1'],sourceVersions:{},report:{errors:[],warnings:[{severity:'WARNING',code:'EMPTY_GROUP',path:'groups[0]',line:null,column:null,message:'空组使用 REJECT'}]}}}
test('blocking diagnostics prohibit publication while retaining the previous successful release',async()=>{
 let writes=0;mockApi(path=>{if(path.endsWith('/releases'))return reply([previous]);if(path.endsWith('/validate')){const result=preview();result.report.errors.push({severity:'ERROR',code:'CORE_REJECTED',path:'$',line:null,column:null,message:'内核拒绝配置'});return reply(result)}if(path.endsWith('/publish'))writes++;throw new Error(path)})
 mount(PublishPanel,{attachTo:document.body,props:{profileId:'p1',draft:documentFixture(),version:'3',dirty:true,publishedVersion:'4'}})
 button('校验并预览').click();await expect.poll(()=>document.body.textContent).toContain('内核拒绝配置');expect(button('发布当前工作副本').disabled).toBe(true);expect(document.body.textContent).toContain('当前发布 v4');expect(writes).toBe(0)
})
test('warnings require explicit acknowledgement; one publication sends current document and version; failure preserves v4',async()=>{
 const bodies:unknown[]=[];mockApi((path,_method,body)=>{if(path.endsWith('/releases'))return reply([previous]);if(path.endsWith('/validate'))return reply(preview());if(path.endsWith('/publish')){bodies.push(body);return reply({errors:[{severity:'ERROR',code:'CORE_REJECTED',path:'$',line:null,column:null,message:'当前校验失败'}],warnings:[]},422,'发布校验失败')}throw new Error(path)})
 const draft=documentFixture();mount(PublishPanel,{attachTo:document.body,props:{profileId:'p1',draft,version:'3',dirty:true,publishedVersion:'4'}})
 button('校验并预览').click();await expect.poll(()=>document.body.textContent).toContain('空组使用 REJECT');expect(button('发布当前工作副本').disabled).toBe(true)
 const checkbox=document.querySelector<HTMLInputElement>('[aria-label="确认发布警告"]')!;checkbox.click();await expect.poll(()=>button('发布当前工作副本').disabled).toBe(false);button('发布当前工作副本').click()
 await expect.poll(()=>document.body.textContent).toContain('当前校验失败');expect(bodies).toEqual([{expectedVersion:'3',document:draft}]);expect(document.body.textContent).toContain('当前发布 v4')
})
test('editing after a successful preview invalidates it and successful publish emits the submitted snapshot',async()=>{
 mockApi((path,_method,body)=>{if(path.endsWith('/releases'))return reply([previous]);if(path.endsWith('/validate')){const result=preview();result.report.warnings=[];result.emptyGroupIds=[];return reply(result)}if(path.endsWith('/publish'))return reply({...previous,id:'rel2',version:'5'});throw new Error(`${path} ${Boolean(body)}`)})
 const wrapper=mount(PublishPanel,{attachTo:document.body,props:{profileId:'p1',draft:documentFixture(),version:'3',dirty:true,publishedVersion:'4'}})
 button('校验并预览').click();await expect.poll(()=>button('发布当前工作副本').disabled).toBe(false);const changed={...documentFixture(),baseYaml:'port: 7892'};await wrapper.setProps({draft:changed});expect(button('发布当前工作副本').disabled).toBe(true)
 button('校验并预览').click();await expect.poll(()=>button('发布当前工作副本').disabled).toBe(false);button('发布当前工作副本').click();await expect.poll(()=>wrapper.emitted('published')?.length).toBe(1);expect(wrapper.emitted('published')![0]![1]).toEqual(changed)
})
test('subscription secret is fetched only on explicit reveal and reset warns that old links stop working',async()=>{
 const calls:string[]=[];mockApi((path,method)=>{calls.push(`${method} ${path}`);return reply({profileId:'p1',url:`https://app.example.invalid/sub/${method==='POST'?'new':'old'}`,token:'test-token'})})
 const wrapper=mount(SubscriptionLink,{attachTo:document.body,props:{profileId:'p1',published:true}});expect(calls).toHaveLength(0);button('显示订阅链接').click();await expect.poll(()=>document.querySelector('input[aria-label="订阅链接"]')).not.toBeNull();expect(calls).toEqual(['GET /profiles/p1/subscription-link'])
 button('重置订阅链接').click();await expect.poll(()=>document.body.textContent).toContain('旧订阅链接将立即失效');expect(calls).toHaveLength(1);button('确认重置链接').click();await expect.poll(()=>(document.querySelector('input[aria-label="订阅链接"]')as HTMLInputElement).value).toContain('/new');expect(calls).toHaveLength(2)
 await wrapper.setProps({profileId:'p2'});expect(document.querySelector('input[aria-label="订阅链接"]')).toBeNull()
})
