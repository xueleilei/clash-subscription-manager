import { afterEach, expect, test } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { userEvent } from 'vitest/browser'
import RuleTextCodeEditor from './RuleTextCodeEditor.vue'

let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
const token = (text: string) => [...document.querySelectorAll<HTMLElement>('.cm-content span')].find(element => element.textContent === text)
const visibleText = () => [...document.querySelectorAll('.cm-line')].map(line => line.textContent).join('\n')

test('rule categories have distinct readable colors while editing preserves exact text and comments', async () => {
 const text='# === 局域网直连 ===\nIP-CIDR,192.168.0.0/16,全球直连,no-resolve\nMATCH,REJECT # 兜底拒绝'
 const updates:string[]=[]
 wrapper=mount(RuleTextCodeEditor,{attachTo:document.body,props:{modelValue:text,'onUpdate:modelValue':value=>updates.push(value)}})
 await expect.poll(()=>token('IP-CIDR')).toBeDefined()
 const samples=['IP-CIDR','192.168.0.0/16','全球直连','no-resolve','# === 局域网直连 ===']
 expect(new Set(samples.map(value=>getComputedStyle(token(value)!).color)).size).toBe(samples.length)
 expect(getComputedStyle(token('# === 局域网直连 ===')!).fontStyle).toBe('italic')
 expect(visibleText()).toBe(text); expect(updates).toEqual([])
 const editor=document.querySelector<HTMLElement>('[aria-label="完整规则文本"]')!
 await userEvent.click(editor); await userEvent.keyboard('{ControlOrMeta>}a{/ControlOrMeta}MATCH,DIRECT # 保留尾注')
 await expect.poll(()=>updates.at(-1)).toBe('MATCH,DIRECT # 保留尾注')
 expect(token('DIRECT')).toBeDefined(); expect(token('# 保留尾注')).toBeDefined()
 await userEvent.keyboard('{ControlOrMeta>}z{/ControlOrMeta}')
 await expect.poll(()=>updates.at(-1)).toBe(text)
})

test('nested expressions, regex commas and quoted YAML targets keep their own highlight boundaries', async () => {
 const text="- 'DOMAIN-SUFFIX,local,''我的 # 代理,备用''' # 分段说明\nAND,((DOMAIN,a.invalid),(NETWORK,UDP)),全球直连\nDOMAIN-REGEX,\"^a,b$\",DIRECT\nMATCH,REJECT"
 wrapper=mount(RuleTextCodeEditor,{attachTo:document.body,props:{modelValue:text}})
 await expect.poll(()=>token('DOMAIN-SUFFIX')).toBeDefined()
 expect(token("''我的 # 代理,备用''")).toBeDefined()
 expect(token('((DOMAIN,a.invalid),(NETWORK,UDP))')).toBeDefined()
 expect(token('"^a,b$"')).toBeDefined(); expect(token('# 分段说明')).toBeDefined()
 expect(getComputedStyle(token("''我的 # 代理,备用''")!).color).toBe(getComputedStyle(token('全球直连')!).color)
 expect(visibleText()).toBe(text)
 await wrapper.setProps({modelValue:"DOMAIN-SUFFIX,unfinished,'未闭合\n# 下一行仍是注释\nMATCH,DIRECT"})
 await expect.poll(()=>token('# 下一行仍是注释')).toBeDefined()
 expect(token('MATCH')).toBeDefined(); expect(token('DIRECT')).toBeDefined()
})
