import { afterEach, expect, test, vi } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import ProfileActions from './ProfileActions.vue'
import { createAppRouter } from '../../router'
import { useSession } from '../auth/useSession'
import { mockApi, reply, button } from '../../test/http'
import { deferred, profileFixture } from '../../test/fixtures'
const profiles = [{ ...profileFixture('a'), name: '方案 A' }, { ...profileFixture('b'), name: '方案 B' }]
const impact = (id: string) => ({ profileId: id, profileVersion: '1', groupCount: 1, ruleCount: 1, hasPublished: true, message: '' })
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
async function prepare(handler: Parameters<typeof mockApi>[0]) {
 const owner = crypto.randomUUID()
 mockApi((path, method, body) => path === '/session' ? reply({ id: owner, username: 'admin' }) : path === '/profiles' ? reply(profiles) : handler(path, method, body))
 await useSession().resume(); const router = createAppRouter(createMemoryHistory()); await router.push('/profiles/a'); await router.isReady()
 return mount(ProfileActions, { props: { profile: profiles[0]! }, attachTo: document.body, global: { plugins: [router] } })
}
test('switching profiles discards a late deletion preview and confirmation always targets the displayed profile', async () => {
 const pending = deferred<Response>(); const deletes: string[] = []; let reads = 0
 const wrapper = await prepare((path, method) => {
  if (method === 'DELETE') { deletes.push(path); return reply(null) }
  if (path === '/profiles/a/deletion-impact') { reads++; return pending.promise }
  if (path === '/profiles/b/deletion-impact') return reply(impact('b'))
  throw new Error(path)
 })
 button('删除方案').click(); await expect.poll(() => reads).toBe(1)
 await wrapper.setProps({ profile: profiles[1]! }); pending.resolve(reply(impact('a')))
 await new Promise(resolve => setTimeout(resolve, 30))
 expect(document.body.textContent).not.toContain('确认删除方案')
 button('删除方案').click(); await expect.poll(() => document.body.textContent).toContain('将删除 方案 B')
 button('确认删除方案').click(); await expect.poll(() => deletes).toEqual(['/profiles/b?expectedVersion=1'])
})
test('a rename form belongs to its opening profile and is reset when the selection changes', async () => {
 const writes: string[] = []; const wrapper = await prepare((path, method) => { if (method === 'PATCH') { writes.push(path); return reply(profiles[1]) }; throw new Error(path) })
 button('重命名').click(); await expect.poll(() => document.querySelector('.profile-rename-input, input[maxlength="128"]')).not.toBeNull()
 await new DOMWrapper(document.querySelector('input[maxlength="128"]')!).setValue('A 的未提交名称')
 await wrapper.setProps({ profile: profiles[1]! })
 await expect.poll(() => (document.querySelector('input[maxlength="128"]') as HTMLInputElement | null)?.checkVisibility() ?? false).toBe(false)
 button('重命名').click(); await expect.poll(() => (document.querySelector('input[maxlength="128"]') as HTMLInputElement)?.value).toBe('方案 B')
 expect(writes).toEqual([])
})
