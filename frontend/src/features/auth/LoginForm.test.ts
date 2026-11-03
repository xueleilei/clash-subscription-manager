import { afterEach, expect, test, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../../router'
import { useSession } from './useSession'
import LoginPage from './LoginPage.vue'
import AppShell from '../../layout/AppShell.vue'
const ok = (data: unknown, status = 200, message = '') => new Response(JSON.stringify({ code: status === 200 ? '00000' : 'A0002', data, message }), { status })
afterEach(() => vi.unstubAllGlobals())
test('login displays failure and successful retry opens profiles with in-memory session', async () => {
  let success = false
  vi.stubGlobal('fetch', async (url: string, options: RequestInit) => {
    if (url.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (options.method === 'POST') { expect(String(options.body)).toContain('username=admin'); return success ? ok(null) : ok(null, 401, '用户名或密码错误') }
    return ok({ id: 'owner-a', username: 'admin' })
  })
  const router = createAppRouter(createMemoryHistory()); await router.push('/login'); await router.isReady()
  const wrapper = mount(LoginPage, { global: { plugins: [router] } })
  await wrapper.get('input[name=username]').setValue('admin'); await wrapper.get('input[name=password]').setValue('fixture-password')
  await wrapper.get('form').trigger('submit'); await expect.poll(() => wrapper.text()).toContain('用户名或密码错误')
  success = true; await wrapper.get('form').trigger('submit'); await flushPromises()
  await expect.poll(() => router.currentRoute.value.path).toBe('/profiles'); expect(useSession().status.value).toBe('authenticated')
  expect(localStorage.getItem('token')).toBeNull()
})
test('untrusted profile labels are rendered as text', async () => {
  const router = createAppRouter(createMemoryHistory())
  await router.push('/login'); await router.isReady()
  const label = '<script data-untrusted>alert(1)</script>'
  const wrapper = mount(AppShell, { props: { profiles: [{ id: 'p', name: label }], username: 'admin' }, global: { plugins: [router] } })
  expect(wrapper.text()).toContain(label); expect(wrapper.find('script[data-untrusted]').exists()).toBe(false)
})
