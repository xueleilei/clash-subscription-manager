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

test.each([1411, 390])('wheel over a manual node scrolls the page at %i px', async width => {
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

  if (width === 390) {
    const tableContainer = document.querySelector('table')!.parentElement!
    const initialX = tableContainer.scrollLeft
    await cell.wheel({ delta: { x: 160 } })
    await expect.poll(() => tableContainer.scrollLeft).toBeGreaterThan(initialX)
    expect(window.scrollX).toBe(0)
  }
})
