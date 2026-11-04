import { afterEach, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { mount } from '@vue/test-utils'
import '../../assets/main.css'
import { nodesFixture } from '../../test/fixtures'
import { mockApi, reply } from '../../test/http'
import ManualNodesPanel from './ManualNodesPanel.vue'

const originalViewport = { width: window.innerWidth, height: window.innerHeight }
afterEach(async () => {
  vi.unstubAllGlobals()
  document.body.replaceChildren()
  await page.viewport(originalViewport.width, originalViewport.height)
})

test.each([1411, 390])('manual node headers follow the page and their columns at %i px', async width => {
  await page.viewport(width, 844)
  const nodes = nodesFixture(50).map(node => ({ ...node, sourceId: null }))
  mockApi(path => {
    if (path.startsWith('/nodes?')) return reply({ items: nodes, page: 1, size: 50, total: nodes.length })
    throw new Error(path)
  })
  mount(ManualNodesPanel, { attachTo: document.body })

  const cell = page.getByRole('cell', { name: nodes[3]!.originalName, exact: true })
  await cell.hover()
  const initialY = window.scrollY
  await cell.wheel({ delta: { y: 120 } })
  await expect.poll(() => window.scrollY).toBeGreaterThan(initialY)
  const lowerY = window.scrollY
  await cell.wheel({ delta: { y: -120 } })
  await expect.poll(() => window.scrollY).toBeLessThan(lowerY)

  const table = document.querySelector('table')!
  const header = table.tHead!
  const row = table.tBodies[0]!.rows[13]!
  window.scrollBy(0, row.getBoundingClientRect().top - 96)
  await expect.poll(() => Math.abs(header.getBoundingClientRect().top)).toBeLessThan(1)

  const selectPage = page.getByRole('checkbox', { name: '全选本页手动节点' })
  await selectPage.click()
  await expect.element(page.getByText('已选 50 个节点')).toBeVisible()
  expect(table.getBoundingClientRect().top).toBeLessThan(-200)

  if (width === 390) {
    const tableContainer = table.parentElement!
    const initialX = tableContainer.scrollLeft
    await page.getByRole('cell', { name: nodes[13]!.originalName, exact: true }).wheel({ delta: { x: 160 } })
    await expect.poll(() => tableContainer.scrollLeft).toBeGreaterThan(initialX)
    expect(window.scrollX).toBe(0)
  }
  const protocol = page.getByRole('columnheader', { name: '协议' }).element().getBoundingClientRect()
  expect(Math.abs(protocol.top)).toBeLessThan(1)
  expect(Math.abs(protocol.left - row.cells[3]!.getBoundingClientRect().left)).toBeLessThan(1)

  // Leave enough space below the table to scroll its last row past the viewport top.
  const tail = document.createElement('div')
  tail.style.height = '100vh'
  document.body.append(tail)
  window.scrollBy(0, table.getBoundingClientRect().bottom - header.offsetHeight / 2)
  await expect.poll(() => Math.abs(header.getBoundingClientRect().bottom - table.getBoundingClientRect().bottom)).toBeLessThan(1)
  window.scrollTo(0, 0)
  await expect.poll(() => Math.abs(header.getBoundingClientRect().top - table.getBoundingClientRect().top)).toBeLessThan(1)
})
