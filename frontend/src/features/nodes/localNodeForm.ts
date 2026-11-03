import type { JsonObject, LocalNodeCommand, LocalNodeView } from '../../types/domain'

export const protocols = ['ss', 'ssr', 'vmess', 'vless', 'trojan', 'hysteria2', 'tuic', 'http', 'socks5']
export function defaults(protocol = 'ss', server = '', port = 443): JsonObject {
 const base: JsonObject = { server, port }
 if (protocol === 'ss' || protocol === 'ssr') Object.assign(base, { cipher: 'aes-128-gcm', password: '' })
 if (protocol === 'ssr') Object.assign(base, { cipher: 'aes-128-cfb', protocol: 'origin', obfs: 'plain' })
 if (protocol === 'vmess' || protocol === 'vless' || protocol === 'tuic') base.uuid = ''
 if (protocol === 'vmess') Object.assign(base, { alterId: 0, cipher: 'auto' })
 if (['trojan', 'hysteria2', 'tuic'].includes(protocol)) base.password = ''
 return base
}
export function initialCommand(view: LocalNodeView | null): LocalNodeCommand {
 return view ? { expectedVersion: view.version, name: view.node.originalName, protocol: view.node.protocol, connection: structuredClone(view.node.connection), dialerRef: view.node.dialerRef ? { ...view.node.dialerRef } : null }
  : { name: '', protocol: 'ss', connection: defaults(), dialerRef: null }
}
export function parseConnection(text: string): JsonObject {
 const value: unknown = JSON.parse(text)
 if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('object required')
 return value as JsonObject
}
