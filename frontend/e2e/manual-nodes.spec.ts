import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { api, digest, draft, link, login, publishUi, save, subscription } from './helpers'
import type { LocalNodeRemoval, NodeSummary, Page, ProfileView } from '../src/types/domain'

test('manual nodes keep published subscriptions stable until explicit publication and clean up after deletion', async ({ page }) => {
 const name = `手动节点验收-${randomUUID().slice(0, 8)}`, updatedName = `${name}-已修改`
 let nodeId: string | undefined, profileId: string | undefined
 const errors: string[] = []; page.on('pageerror', error => errors.push(error.name))
 await login(page)
 try {
  await page.goto('/sources'); await page.getByRole('tab', { name: '手动节点', exact: true }).click()
  await page.getByRole('button', { name: '添加节点', exact: true }).click()
  await page.locator('[name=node-name]').fill(name); await page.locator('[name=node-server]').fill('manual.example.invalid'); await page.locator('[name=node-password]').fill('manual-fixture-v1')
  await page.getByRole('button', { name: '保存节点', exact: true }).click(); await expect(page.getByRole('dialog')).toBeHidden()
  nodeId = (await api<Page<NodeSummary>>(page, `/nodes?localOnly=true&name=${encodeURIComponent(name)}`)).items.find(node => node.originalName === name)!.id
  const created = await api<{ profileId: string }>(page, '/profiles', 'POST', { name: `${name}-方案` }); profileId = created.profileId
  const document = (await draft(page, profileId)).document, groupId = randomUUID()
  document.groups = [{ id: groupId, name: '手动节点组', type: 'select', members: [{ kind: 'NODE', id: nodeId, unresolvedName: null }], options: {} }]
  document.rules = [{ id: randomUUID(), expression: { type: 'MATCH', value: '', providerId: null }, target: { kind: 'GROUP', id: groupId, unresolvedName: null }, modifiers: [], rawText: null, issues: [] }]
  await save(page, profileId, document); await page.goto(`/profiles/${profileId}`)
  const first = await publishUi(page, '1'), url = await link(page, profileId)
  expect(first).toContain('manual-fixture-v1'); expect(digest((await subscription(page, url)).text)).toBe(digest(first))
  await page.goto('/nodes'); await page.getByLabel('搜索节点名称', { exact: true }).fill(name); await page.getByRole('button', { name: '搜索', exact: true }).click()
  await page.getByRole('button', { name: `编辑 ${name}`, exact: true }).click()
  await page.locator('[name=node-name]').fill(updatedName); await page.locator('[name=node-password]').fill('manual-fixture-v2')
  await page.getByRole('button', { name: '保存节点', exact: true }).click(); await expect(page.getByRole('dialog')).toBeHidden()
  expect(digest((await subscription(page, url)).text)).toBe(digest(first))
  expect((await api<ProfileView[]>(page, '/profiles')).find(profile => profile.id === profileId)?.hasUnpublishedNodeChanges).toBe(true)
  await page.goto(`/profiles/${profileId}`); await expect(page.getByText('已保存待发布', { exact: true })).toBeVisible()
  const second = await publishUi(page, '2'); expect(second).toContain('manual-fixture-v2'); expect(second).toContain(updatedName)
  expect(digest((await subscription(page, url)).text)).toBe(digest(second))
  await page.goto('/sources'); await page.getByRole('tab', { name: '手动节点', exact: true }).click()
  await page.getByLabel('搜索节点名称', { exact: true }).fill(updatedName); await page.getByRole('button', { name: '搜索', exact: true }).click()
  await page.getByRole('button', { name: `删除节点 ${updatedName}`, exact: true }).click(); await expect(page.getByRole('dialog')).toContainText(`${name}-方案`)
  await page.getByRole('button', { name: '确认删除节点', exact: true }).click(); await expect(page.getByRole('dialog')).toBeHidden(); nodeId = undefined
  expect((await draft(page, profileId)).document.groups[0]!.members).toEqual([]); expect(digest((await subscription(page, url)).text)).toBe(digest(second))
  await page.goto(`/profiles/${profileId}`); const third = await publishUi(page, '3')
  expect(third).not.toContain('manual-fixture-v2'); expect(third).toContain('REJECT'); expect(digest((await subscription(page, url)).text)).toBe(digest(third))
  // Check the actual editor on a narrow viewport, using only a new empty form.
  await page.setViewportSize({ width: 390, height: 844 }); await page.goto('/sources'); await page.getByRole('tab', { name: '手动节点', exact: true }).click(); await page.getByRole('button', { name: '添加节点', exact: true }).click()
  await expect(page.locator('[name=node-name]')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('button', { name: '取消', exact: true }).click(); expect(errors).toEqual([])
 } finally {
  // Only delete IDs created by this test; never touch existing profiles or upstreams.
  if (profileId) { const profile = (await api<ProfileView[]>(page, '/profiles')).find(value => value.id === profileId); if (profile) await api(page, `/profiles/${profileId}?expectedVersion=${profile.version}`, 'DELETE') }
  if (nodeId) { const preview = await api<LocalNodeRemoval>(page, `/local-nodes/${nodeId}/removal`); await api(page, `/local-nodes/${nodeId}`, 'DELETE', { expectedVersion: preview.version, ownerRevision: preview.ownerRevision }) }
 }
})
