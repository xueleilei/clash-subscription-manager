import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { DOMWrapper, mount } from '@vue/test-utils'
import LocalNodeEditor from './LocalNodeEditor.vue'
import { button, mockApi, reply } from '../../test/http'

const text = '{name: One, type: ss}\n{name: Two, type: ss}'
const preview = { ownerRevision: '7', nodes: ['One', 'Two'].map(name => ({name, protocol: 'ss', connection: {server: 'fixture.invalid', port: 443}, dialerRef: null})) }
const calls: string[] = []
beforeEach(() => { calls.length = 0 })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
async function enter(value = text) {
 button('分享链接 / YAML').click()
 await expect.poll(() => document.querySelector('[name="node-input"]')).not.toBeNull()
 await new DOMWrapper(document.querySelector('[name="node-input"]')!).setValue(value)
 button('解析并预览').click()
}
test('multiple nodes require preview and an explicit atomic import action', async () => {
 mockApi((path) => { calls.push(path); return reply(preview) })
 const wrapper = mount(LocalNodeEditor, {attachTo: document.body, props: {initial: null, nodes: [], busy: false}})
 await enter()
 await expect.poll(() => document.body.textContent).toContain('已识别 2 个节点')
 expect(document.body.textContent).toContain('One'); expect(document.body.textContent).toContain('Two')
 expect(calls).toEqual(['/local-nodes/batch/preview']); expect(wrapper.emitted('import')).toBeUndefined()
 button('确认导入 2 个节点').click()
 expect(wrapper.emitted('import')).toEqual([[{text, ownerRevision: '7'}]])
})
test('changing input invalidates a preview and failed parsing retains the complete input', async () => {
 mockApi(() => reply(preview))
 const wrapper = mount(LocalNodeEditor, {attachTo: document.body, props: {initial: null, nodes: [], busy: false}})
 await enter(); await expect.poll(() => button('确认导入 2 个节点')).toBeTruthy()
 await new DOMWrapper(document.querySelector('[name="node-input"]')!).setValue(text + '\nbroken')
 await expect.poll(() => document.body.textContent).not.toContain('确认导入 2 个节点')
 mockApi(() => reply({errors: [{severity: 'ERROR', code: 'INVALID_NODE', path: '$.proxies[1]', message: '节点参数不完整'}], warnings: []}, 422))
 button('解析并预览').click()
 await expect.poll(() => document.body.textContent).toContain('节点参数不完整')
 expect(document.querySelector<HTMLTextAreaElement>('[name="node-input"]')!.value).toBe(text + '\nbroken')
 expect(wrapper.emitted('import')).toBeUndefined()
})
