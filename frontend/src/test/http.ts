import { vi } from 'vitest'
import type { NodeRegionGroup } from '../types/domain'
export function reply(data: unknown, status = 200, message = '') { return new Response(JSON.stringify({ code: status < 300 ? '00000' : 'A0003', data, message }), { status, headers: { 'content-type': 'application/json' } }) }
export function mockApi(handler: (path: string, method: string, body: Record<string, unknown>) => Response | Promise<Response>, options: {nodeRegions?: NodeRegionGroup[]; handleLocalGroups?: boolean} = {}) {
  vi.stubGlobal('fetch', async (input: string, init: RequestInit = {}) => {
    const path = input.replace('/api/v1', '')
    if (path === '/csrf') return reply({ headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' })
    if (path === '/local-node-groups' && (!init.method || init.method === 'GET') && !options.handleLocalGroups) return reply({groups:[],ungroupedCount:0})
    if (path === '/nodes/regions') return reply(options.nodeRegions ?? [{name: '测试地区', regions: [{code: 'HK', name: '香港'}, {code: 'JP', name: '日本'}]}])
    return handler(path, init.method ?? 'GET', typeof init.body === 'string' ? JSON.parse(init.body) : {})
  })
}
export function button(text: string) {
  const value = [...document.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.trim() === text || b.getAttribute('aria-label') === text)
  if (!value) throw new Error(`Button not found: ${text}`)
  return value
}
