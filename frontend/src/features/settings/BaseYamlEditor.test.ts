import { afterEach, expect, test } from 'vitest'
import { mount } from '@vue/test-utils'
import { userEvent } from 'vitest/browser'
import BaseYamlEditor from './BaseYamlEditor.vue'
afterEach(()=>{document.body.innerHTML=''})
test('invalid YAML remains editable and server diagnostics show line/column plus reserved-field destination', async () => {
 const wrapper=mount(BaseYamlEditor,{attachTo:document.body,props:{modelValue:'mixed-port: 7890\n',diagnostics:[{severity:'ERROR',code:'RESERVED_BASE_FIELD',path:'baseYaml.proxies',line:2,column:3,message:'此字段由节点池和代理组页面管理'}]}})
 const editor=document.querySelector<HTMLElement>('[role="textbox"]')!; await userEvent.click(editor); await userEvent.keyboard('{ControlOrMeta>}a{/ControlOrMeta}dns: [[broken{Delete}')
 await expect.poll(()=>wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('dns: [broken')
 expect(wrapper.text()).toContain('第 2 行，第 3 列'); expect(wrapper.text()).toContain('代理组'); expect(editor.textContent).toContain('[broken')
 await wrapper.setProps({modelValue:'port: 7891\n'}); expect(editor.textContent).toContain('port: 7891'); expect(editor.textContent).not.toContain('[broken')
 wrapper.unmount(); expect(document.querySelector('.cm-editor')).toBeNull()
})
