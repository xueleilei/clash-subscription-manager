import { afterEach, expect, test, vi } from 'vitest'
import { mount, DOMWrapper, flushPromises } from '@vue/test-utils'
import { defineComponent, h, shallowRef } from 'vue'
import { userEvent } from 'vitest/browser'
import RulesView from './RulesView.vue'
import { documentFixture, target } from '../../test/fixtures'
import { button, mockApi, reply } from '../../test/http'
import type { Diagnostic, ProfileDocument, RuleDefinition } from '../../types/domain'
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
const rule = (id: string, value = id): RuleDefinition => ({ id, expression: { type: 'DOMAIN-SUFFIX', value, providerId: null }, target: target('DIRECT'), modifiers: [], rawText: null, issues: [] })
function setup(doc = documentFixture(), mode: 'rules' | 'providers' = 'rules') {
 const current = shallowRef(doc)
 mount(defineComponent(() => () => h(RulesView, { profileId: 'p1', version: '1', document: current.value, nodes: [], diagnostics: {errors:[],warnings:[]}, mode, onUpdateRules: (rules: RuleDefinition[]) => { current.value = {...current.value, rules} }, onUpdateProviders: (providers: ProfileDocument['providers']) => { current.value = {...current.value, providers} } })), { attachTo: document.body })
 return current
}
async function set(label: string, value: string) { await new DOMWrapper(document.querySelector(`[aria-label="${label}"]`)!).setValue(value) }
test('visual rules edit stable targets, RULE-SET selects a provider and retained removed-node reasons are visible', async () => {
 const doc = documentFixture(); doc.providers.push({id:'pv1',name:'广告列表',type:'http',url:'https://rules.example.invalid/a.yaml',behavior:'domain',format:'yaml',interval:3600,options:{}})
 const current = setup(doc)
 button('添加规则').click(); await expect.poll(() => document.querySelector('[aria-label="规则类型"]')).not.toBeNull()
 await set('规则类型','DOMAIN-SUFFIX'); await set('匹配内容','example.com'); await set('规则目标','GROUP:g1'); button('应用规则编辑').click()
 await expect.poll(() => current.value.rules.length).toBe(2); expect(current.value.rules[0]!.target).toEqual(target('GROUP','g1')); expect(current.value.rules[1]!.expression.type).toBe('MATCH')
 button('添加规则').click(); await expect.poll(() => document.querySelector('[aria-label="规则类型"]')).not.toBeNull(); await set('规则类型','RULE-SET'); await set('引用规则集','pv1'); button('应用规则编辑').click()
 await expect.poll(() => current.value.rules.length).toBe(3); expect(current.value.rules[1]!.expression.providerId).toBe('pv1')
 const warning: Diagnostic = {severity:'WARNING',code:'REMOVED_NODE_TARGET',path:'rules',line:null,column:null,message:'原目标节点已失效，自动改为 REJECT'}
 current.value = {...current.value,rules:[{...rule('removed'),target:target('REJECT'),issues:[warning]},...current.value.rules]}
 await expect.poll(() => document.body.textContent).toContain(warning.message)
})
test('2124 rules paginate and moves use absolute positions while hidden rules keep their relative order', async () => {
 const doc = documentFixture(); doc.rules = [...Array.from({length:2123},(_,i)=>rule(`r${i}`,i===0||i===2?'show.example':`hidden-${i}.example`)), {...doc.rules[0]!,id:'match'}]
 const current = setup(doc); expect(document.querySelectorAll('[data-rule-id]').length).toBe(50)
 await set('筛选规则','show'); expect(document.querySelectorAll('[data-rule-id]').length).toBe(2)
 button('上移规则 r2').click(); await expect.poll(() => current.value.rules[1]!.id).toBe('r2')
 expect(current.value.rules.slice(0,4).map(r=>r.id)).toEqual(['r0','r2','r1','r3'])
 await set('规则 r2 的绝对位置','1'); button('移动规则 r2').click(); await expect.poll(() => current.value.rules[0]!.id).toBe('r2')
 expect(current.value.rules.slice(0,4).map(r=>r.id)).toEqual(['r2','r0','r1','r3']); expect(current.value.rules.at(-1)!.id).toBe('match')
 button('批量导入').click(); await expect.poll(() => document.querySelector('[aria-label="规则文本"]')).not.toBeNull(); await set('规则文本','DOMAIN,unused.example,DIRECT'); button('取消导入').click()
 expect(current.value.rules.length).toBe(2124); expect(current.value.rules[0]!.id).toBe('r2')
})
test('batch preview errors retain all original rules; explicit MATCH replacement is required and edits invalidate the preview', async () => {
 const issue: Diagnostic={severity:'ERROR',code:'RULE_SYNTAX',path:'rules[1]',line:2,column:1,message:'第 2 行规则有误'}
 const calls: Record<string,unknown>[]=[]
 mockApi((_path,_method,body)=>{ calls.push(body); const valid=body.matchPolicy==='REPLACE'; return reply({applicable:valid,expectedVersion:'1',rules:[{...documentFixture().rules[0],target:target('DIRECT')}],importedRules:[],report:{errors:valid?[]:[issue],warnings:[]}}) })
 const current = setup(); const original=JSON.stringify(current.value.rules)
 button('批量导入').click(); await expect.poll(() => document.querySelector('[aria-label="规则文本"]')).not.toBeNull(); await set('规则文本','DOMAIN,ok.example,DIRECT\nMATCH,DIRECT')
 button('预览导入').click(); await expect.poll(() => document.body.textContent).toContain('第 2 行'); expect(button('应用导入').disabled).toBe(true); expect(JSON.stringify(current.value.rules)).toBe(original)
 const checkbox=document.querySelector<HTMLInputElement>('[aria-label="替换现有 MATCH"]')!; checkbox.click(); await expect.poll(() => button('预览导入').disabled).toBe(false); button('预览导入').click()
 await expect.poll(() => button('应用导入').disabled).toBe(false); expect(calls.at(-1)!.matchPolicy).toBe('REPLACE')
 await set('规则文本','MATCH,REJECT'); expect(button('应用导入').disabled).toBe(true)
 button('预览导入').click(); await expect.poll(() => button('应用导入').disabled).toBe(false); button('应用导入').click()
 await expect.poll(() => current.value.rules[0]!.target.kind).toBe('DIRECT'); expect(current.value.rules.length).toBe(1)
})
test('file import reads real File content and cancel keeps draft unchanged', async () => {
 let sent:unknown; mockApi((_path,_method,body)=>{sent=body.text;return reply({applicable:true,expectedVersion:'1',rules:documentFixture().rules,importedRules:[],report:{errors:[],warnings:[]}})})
 const current=setup(); button('批量导入').click(); await expect.poll(()=>document.querySelector('input[type="file"]')).not.toBeNull()
 const input=document.querySelector<HTMLInputElement>('input[type="file"]')!, data=new DataTransfer(); data.items.add(new File(['DOMAIN,file.example,DIRECT'],'rules.txt',{type:'text/plain'})); input.files=data.files; input.dispatchEvent(new Event('change',{bubbles:true}))
 await expect.poll(()=> (document.querySelector('[aria-label="规则文本"]') as HTMLTextAreaElement).value).toBe('DOMAIN,file.example,DIRECT'); button('预览导入').click(); await expect.poll(()=>sent).toBe('DOMAIN,file.example,DIRECT'); button('取消导入').click(); expect(current.value.rules.length).toBe(1)
})
test('provider invalid mrs/classical combination is blocked and a used provider cannot be deleted', async () => {
 const doc=documentFixture(), current=setup(doc,'providers'); button('添加规则集').click(); await expect.poll(()=>document.querySelector('[aria-label="规则集名称"]')).not.toBeNull()
 await set('规则集名称','远程列表'); await set('规则集地址','https://rules.example.invalid/a'); await set('规则集行为','classical'); await set('规则集格式','mrs'); button('应用规则集').click()
 await expect.poll(()=>document.body.textContent).toContain('mrs 仅支持 domain / ipcidr'); expect(current.value.providers).toHaveLength(0)
 await set('规则集格式','yaml'); button('应用规则集').click(); await expect.poll(()=>current.value.providers.length).toBe(1)
 const provider=current.value.providers[0]!; current.value={...current.value,rules:[{...rule('used'),expression:{type:'RULE-SET',value:provider.name,providerId:provider.id}},...current.value.rules]}
 await expect.poll(()=>button(`删除规则集 ${provider.name}`).disabled).toBe(true)
})

test('full text editor uses the working copy and replaces rules only after validation and apply', async () => {
 const doc=documentFixture(); doc.rules=[rule('old','unsaved.invalid'),{...doc.rules[0]!,target:target('REJECT')}]
 const updated=[rule('new','edited.invalid'),doc.rules[1]!], calls:{path:string;body:Record<string,unknown>}[]=[]
 mockApi((path,_method,body)=>{
  calls.push({path,body})
  if(path.endsWith('/rule-text'))return reply({expectedVersion:'1',text:'DOMAIN-SUFFIX,unsaved.invalid,DIRECT\nMATCH,REJECT\n',ruleCount:2,ownerRevision:'10'})
  if(path.endsWith('/rule-text/preview'))return reply({applicable:true,expectedVersion:'1',rules:updated,importedRules:updated,report:{errors:[],warnings:[]}})
  throw new Error('Unexpected configuration write')
 })
 const current=setup(doc), original=JSON.stringify(current.value)
 expect([...document.querySelectorAll('button')].some(value=>value.textContent?.trim()==='文本编辑')).toBe(true)
 button('文本编辑').click()
 await expect.poll(()=>document.querySelector('[aria-label="完整规则文本"]')).not.toBeNull()
 const editor=document.querySelector<HTMLElement>('[aria-label="完整规则文本"]')!
 expect(editor.textContent).toContain('unsaved.invalid'); expect(calls[0]!.body.document).toEqual(doc)
 await userEvent.click(editor); await userEvent.keyboard('{ControlOrMeta>}f{/ControlOrMeta}')
 await expect.poll(()=>document.querySelector('.cm-search')).not.toBeNull()
 await userEvent.keyboard('{Escape}'); await flushPromises(); expect(editor.isConnected).toBe(true)
 await userEvent.click(editor); await userEvent.keyboard('{ControlOrMeta>}a{/ControlOrMeta}DOMAIN-SUFFIX,edited.invalid,DIRECT{Enter}MATCH,REJECT')
 expect(button('应用文本修改').disabled).toBe(true); expect(JSON.stringify(current.value)).toBe(original)
 button('校验规则文本').click(); await expect.poll(()=>button('应用文本修改').disabled).toBe(false)
 expect(calls.at(-1)!.body.text).toBe('DOMAIN-SUFFIX,edited.invalid,DIRECT\nMATCH,REJECT')
 expect(calls.at(-1)!.body.ownerRevision).toBe('10')
 expect(JSON.stringify(current.value)).toBe(original)
 button('应用文本修改').click(); await expect.poll(()=>current.value.rules).toEqual(updated)
 expect(current.value.groups).toEqual(doc.groups); expect(current.value.baseYaml).toEqual(doc.baseYaml)
 expect(calls.map(call=>call.path)).toEqual(['/profiles/p1/rule-text','/profiles/p1/rule-text/preview'])
})

test('invalid full rule text stays editable and cancelling preserves all existing rules', async () => {
 const issue:Diagnostic={severity:'ERROR',code:'RULE_SYNTAX',path:'rules[1]',line:2,column:1,message:'规则语法不正确'}
 mockApi((path)=>path.endsWith('/rule-text')
  ?reply({expectedVersion:'1',text:'MATCH,REJECT\n',ruleCount:1,ownerRevision:'10'})
  :reply({applicable:false,expectedVersion:'1',rules:documentFixture().rules,importedRules:[],report:{errors:[issue],warnings:[]}}))
 const current=setup(), original=JSON.stringify(current.value)
 expect([...document.querySelectorAll('button')].some(value=>value.textContent?.trim()==='文本编辑')).toBe(true)
 button('文本编辑').click(); await expect.poll(()=>document.querySelector('[aria-label="完整规则文本"]')).not.toBeNull()
 const editor=document.querySelector<HTMLElement>('[aria-label="完整规则文本"]')!
 await userEvent.click(editor); await userEvent.keyboard('{ControlOrMeta>}a{/ControlOrMeta}DOMAIN,ok.invalid,DIRECT{Enter}BROKEN{Enter}MATCH,REJECT')
 button('校验规则文本').click(); await expect.poll(()=>document.body.textContent).toContain('第 2 行')
 expect(button('应用文本修改').disabled).toBe(true); expect(editor.textContent).toContain('BROKEN')
 button('取消文本编辑').click(); expect(JSON.stringify(current.value)).toBe(original)
})

test('late text validation cannot apply after further typing or a changed working copy', async () => {
 const pending:((value:Response)=>void)[]=[]
 const rules=[{...documentFixture().rules[0]!,target:target('DIRECT')}]
 mockApi(path=>path.endsWith('/rule-text')
  ?reply({expectedVersion:'1',text:'MATCH,REJECT\n',ruleCount:1,ownerRevision:'10'})
  :new Promise<Response>(resolve=>{pending.push(resolve)}))
 const current=setup()
 button('文本编辑').click(); await expect.poll(()=>document.querySelector('[aria-label="完整规则文本"]')).not.toBeNull()
 button('校验规则文本').click(); await expect.poll(()=>pending.length).toBe(1)
 const editor=document.querySelector<HTMLElement>('[aria-label="完整规则文本"]')!
 await userEvent.click(editor); await userEvent.keyboard('{ControlOrMeta>}a{/ControlOrMeta}MATCH,DIRECT')
 pending[0]!(reply({applicable:true,expectedVersion:'1',rules,importedRules:rules,report:{errors:[],warnings:[]}}))
 await flushPromises(); expect(button('应用文本修改').disabled).toBe(true)
 button('校验规则文本').click(); await expect.poll(()=>pending.length).toBe(2)
 pending[1]!(reply({applicable:true,expectedVersion:'1',rules,importedRules:rules,report:{errors:[],warnings:[]}}))
 await expect.poll(()=>button('应用文本修改').disabled).toBe(false)
 current.value={...current.value,baseYaml:'mode: rule\nport: 7893\n'}
 await expect.poll(()=>button('应用文本修改').disabled).toBe(true)
 expect(editor.textContent).toContain('MATCH,DIRECT'); expect(document.body.textContent).toContain('方案或节点来源已变化')
})
