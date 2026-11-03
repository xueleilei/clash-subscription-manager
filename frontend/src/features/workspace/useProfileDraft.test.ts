import { afterEach, expect, test, vi } from 'vitest'
import { effectScope, nextTick, shallowRef, type EffectScope, type ShallowRef } from 'vue'
import { useProfileDraft } from './useProfileDraft'
import { useSession } from '../auth/useSession'
import { useProfiles } from '../../layout/useProfiles'
import { ApiError, request } from '../../api/client'
import { mockApi, reply } from '../../test/http'
import { deferred, documentFixture, draftFixture, target } from '../../test/fixtures'
import type { DraftView, ProfileDocument } from '../../types/domain'
const scopes: EffectScope[] = []
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.unstubAllGlobals() })
function start(profile: string | ShallowRef<string> = 'p1') { const scope = effectScope(); scopes.push(scope); return scope.run(() => useProfileDraft(profile))! }
function api(handler: Parameters<typeof mockApi>[0], owner = crypto.randomUUID()) { mockApi((path, method, body) => path === '/session' ? reply(method === 'GET' ? { id: owner, username: 'admin' } : null) : handler(path, method, body)); return useSession().resume() }
test('a slow profile A response never replaces the current profile B', async () => {
 const slow = deferred<Response>()
 await api(path => path === '/profiles/a/draft' ? slow.promise : reply(draftFixture('b', '9', { ...documentFixture(), baseYaml: 'owner: B' })))
 const id = shallowRef('a'), workspace = start(id); await nextTick(); id.value = 'b'; await nextTick()
 await expect.poll(() => workspace.version.value).toBe('9'); slow.resolve(reply(draftFixture('a'))); await nextTick()
 expect(workspace.currentProfileId.value).toBe('b'); expect(workspace.draft.value?.baseYaml).toBe('owner: B')
})
test('409 preserves exact local input and does not replay a save', async () => {
 let writes = 0
 await api((_path, method) => { if (method === 'PUT') { writes++; return reply(null, 409) }; return reply(draftFixture()) })
 const workspace = start(); await expect.poll(() => workspace.version.value).toBe('1')
 workspace.updateDocument({ ...workspace.draft.value!, baseYaml: 'dns: [unfinished\n' })
 await expect(workspace.save()).rejects.toBeInstanceOf(ApiError)
 expect(workspace.draft.value?.baseYaml).toBe('dns: [unfinished\n'); expect(workspace.dirty.value).toBe(true); expect(workspace.serverChanged.value).toBe(true); expect(writes).toBe(1)
})
test('background updates reload clean drafts but only flag dirty ones', async () => {
 let server = draftFixture()
 await api(() => reply(server)); const workspace = start(); await expect.poll(() => workspace.version.value).toBe('1')
 server = draftFixture('p1', '2', { ...documentFixture(), baseYaml: 'mixed-port: 7892\n' }); await workspace.reload()
 expect(workspace.version.value).toBe('2'); expect(workspace.draft.value?.baseYaml).toContain('7892')
 workspace.updateDocument({ ...workspace.draft.value!, baseYaml: 'mixed-port: 8888\n' })
 server = draftFixture('p1', '3', { ...documentFixture(), baseYaml: 'mixed-port: 7893\n' }); await workspace.reload()
 expect(workspace.version.value).toBe('2'); expect(workspace.draft.value?.baseYaml).toContain('8888'); expect(workspace.serverChanged.value).toBe(true)
})
test('explicitly keeping edits adopts latest version and prunes removed nodes before save', async () => {
 const original = documentFixture(); original.groups[0]!.members = [target('NODE', 'gone')]; original.rules[0]!.target = target('NODE', 'gone')
 let server = draftFixture('p1', '1', original), saved: ProfileDocument | undefined
 await api((path, method, body) => {
  if (path.startsWith('/nodes?')) return reply({ items: [], total: 0, page: 1, size: 200 })
  if (method === 'PUT') { expect(body.expectedVersion).toBe('2'); saved = body.document as ProfileDocument; return reply(draftFixture('p1', '3', saved)) }
  return reply(server)
 })
 const workspace = start(); await expect.poll(() => workspace.version.value).toBe('1')
 workspace.updateDocument({ ...workspace.draft.value!, baseYaml: 'mixed-port: 9999\n' }); server = draftFixture('p1', '2')
 await workspace.reload(); await workspace.keepLocalEdits(); expect(workspace.version.value).toBe('2'); expect(workspace.draft.value?.baseYaml).toContain('9999')
 expect(workspace.draft.value?.groups[0]?.members).toEqual([]); expect(workspace.draft.value?.rules[0]?.target.kind).toBe('REJECT')
 await workspace.save(); expect(saved?.groups[0]?.members).toEqual([]); expect(workspace.dirty.value).toBe(false)
})
test('typing while a save is in flight survives its response', async () => {
 const pending = deferred<Response>(); let sent: ProfileDocument | undefined
 await api((_path, method, body) => { if (method === 'PUT') { sent = body.document as ProfileDocument; return pending.promise }; return reply(draftFixture()) })
 const workspace = start(); await expect.poll(() => workspace.version.value).toBe('1')
 workspace.updateDocument({ ...workspace.draft.value!, baseYaml: 'mixed-port: 8881\n' }); const saving = workspace.save(); await expect.poll(() => sent?.baseYaml).toContain('8881')
 workspace.updateDocument({ ...workspace.draft.value!, baseYaml: 'mixed-port: 8882\n' }); pending.resolve(reply(draftFixture('p1', '2', sent))); await saving
 expect(workspace.version.value).toBe('2'); expect(workspace.draft.value?.baseYaml).toContain('8882'); expect(workspace.dirty.value).toBe(true)
})
test('same-owner reauthentication retains edits while changing owner clears the cache', async () => {
 let owner = 'owner-a', authorized = true, server: DraftView = draftFixture()
 mockApi((path, method) => {
  if (path === '/session') { if (method === 'POST') { authorized = true; return reply(null) }; return authorized ? reply({ id: owner, username: 'admin' }) : reply(null, 401) }
  return reply(server)
 })
 await useSession().resume(); const workspace = start(); await expect.poll(() => workspace.version.value).toBe('1')
 workspace.updateDocument({ ...workspace.draft.value!, baseYaml: 'local unsaved content' }); scopes.pop()!.stop()
 authorized = false; await request('/session').catch(() => {}); await useSession().login({ username: 'admin', password: 'fixture' })
 const resumed = start(); await expect.poll(() => resumed.draft.value?.baseYaml).toBe('local unsaved content')
 owner = 'owner-b'; server = draftFixture('p1', '1', { ...documentFixture(), baseYaml: 'owner b content' }); await useSession().resume()
 await expect.poll(() => resumed.draft.value?.baseYaml).toBe('owner b content'); expect(resumed.dirty.value).toBe(false)
})
test('late profile metadata from a previous owner cannot repopulate the sidebar', async () => {
 const pending = deferred<Response>(); let owner = 'metadata-owner-a'
 mockApi((path) => path === '/session' ? reply({ id: owner, username: 'admin' }) : pending.promise)
 await useSession().resume(); const profiles = useProfiles(), loading = profiles.reload()
 owner = 'metadata-owner-b'; await useSession().resume(); pending.resolve(reply([{ id: 'secret-profile-a', name: 'Owner A only' }]))
 await loading; expect(profiles.profiles.value).toEqual([])
})
test('publication acknowledgement advances the saved baseline while preserving edits made during publishing', async () => {
 let server = draftFixture(); await api(() => reply(server)); const workspace=start(); await expect.poll(()=>workspace.version.value).toBe('1')
 const submitted={...documentFixture(),baseYaml:'mixed-port: 8881\n'};workspace.updateDocument(submitted)
 server=draftFixture('p1','2',submitted); workspace.updateDocument({...submitted,baseYaml:'mixed-port: 8882\n'})
 await workspace.acknowledgePublished('p1',submitted)
 expect(workspace.version.value).toBe('2');expect(workspace.draft.value?.baseYaml).toContain('8882');expect(workspace.dirty.value).toBe(true);expect(workspace.serverChanged.value).toBe(false)
 workspace.updateDocument(submitted);expect(workspace.dirty.value).toBe(false)
})
