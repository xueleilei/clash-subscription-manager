import { afterEach, expect, test, vi } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import App from '../../App.vue'
import { createAppRouter } from '../../router'
import { useSession } from './useSession'
import { request } from '../../api/client'
import { mockApi, reply, button } from '../../test/http'
import { draftFixture, nodesFixture, profileFixture } from '../../test/fixtures'
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
async function prepare() {
 let authorized = true, owner = crypto.randomUUID()
 mockApi((path, method) => {
  if (path === '/session' && method === 'POST') { authorized = true; return reply(null) }
  if (!authorized) return reply(null, 401)
  if (path === '/session') return reply({ id: owner, username: 'admin' })
  if (path === '/profiles') return reply([profileFixture()])
  if (path === '/sources') return reply([])
  if (path.startsWith('/nodes?')) return reply({ items: nodesFixture(), total: 54, page: 1, size: 200 })
  if (path === '/profiles/p1/draft') return reply(draftFixture())
  throw new Error(`${method} ${path}`)
 })
 await useSession().resume(); const router = createAppRouter(createMemoryHistory()); await router.push('/profiles/p1'); await router.isReady()
 mount(App, { attachTo: document.body, global: { plugins: [router] } })
 await expect.poll(() => document.querySelector('.workspace-grid')).not.toBeNull()
 return { router, expire: async () => { authorized = false; await request('/profiles/p1/draft').catch(() => {}) }, changeOwner: () => { owner = crypto.randomUUID() } }
}
async function authenticate() {
 await expect.poll(() => document.querySelector('input[name="password"]')?.checkVisibility()).toBe(true)
 const password = document.querySelector<HTMLInputElement>('input[name="password"]')!
 password.focus(); expect(document.activeElement).toBe(password)
 await new DOMWrapper(password).setValue('fixture-password')
 document.querySelector<HTMLButtonElement>('.login-form button[type="submit"]')!.click(); await expect.poll(() => useSession().status.value).toBe('authenticated')
}
const editors = [
 { name: 'rule', tab: '分流规则', open: '添加规则', selector: 'textarea[aria-label="匹配内容"]', text: 'unfinished.example.invalid' },
 { name: 'provider', tab: '远程规则集', open: '添加规则集', selector: 'input[aria-label="规则集地址"]', text: 'https://fixture.invalid/private/unfinished' },
 { name: 'group', tab: '', open: '＋ 新建代理组', selector: 'input[placeholder="例如：AI 服务"]', text: '尚未提交的新代理组' },
]
for (const editor of editors) test(`same-owner reauthentication preserves unapplied ${editor.name} editor input and its route`, async () => {
 const state = await prepare(); if (editor.tab) button(editor.tab).click()
 await expect.poll(() => button(editor.open)).toBeTruthy(); button(editor.open).click()
 await expect.poll(() => document.querySelector(editor.selector)).not.toBeNull()
 await new DOMWrapper(document.querySelector(editor.selector)!).setValue(editor.text)
 await state.expire(); await authenticate()
 await expect.poll(() => (document.querySelector(editor.selector) as HTMLInputElement | null)?.value).toBe(editor.text)
 expect(state.router.currentRoute.value.path).toBe('/profiles/p1')
 expect(document.querySelector(editor.selector)?.checkVisibility()).toBe(true)
})
test('reauthenticating as another owner clears unapplied editor state', async () => {
 const state = await prepare(); button('＋ 新建代理组').click()
 await expect.poll(() => document.querySelector('input[placeholder="例如：AI 服务"]')).not.toBeNull()
 await new DOMWrapper(document.querySelector('input[placeholder="例如：AI 服务"]')!).setValue('previous-owner-private-input')
 await state.expire(); state.changeOwner(); await authenticate()
 await expect.poll(() => state.router.currentRoute.value.path).toBe('/profiles')
 expect(document.querySelector('input[placeholder="例如：AI 服务"]')).toBeNull()
 expect(document.body.textContent).not.toContain('previous-owner-private-input')
})
