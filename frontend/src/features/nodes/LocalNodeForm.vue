<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import { ElButton } from 'element-plus'
import { defaults, parseConnection, protocols } from './localNodeForm'
import type { JsonObject, JsonValue, LocalNodeCommand, NodeSummary, TargetRef } from '../../types/domain'
const props = defineProps<{ initial: LocalNodeCommand; nodes: readonly NodeSummary[]; nodeId: string | null; busy: boolean }>()
const emit = defineEmits<{ submit: [value: LocalNodeCommand]; cancel: [] }>()
const name = shallowRef(props.initial.name), protocol = shallowRef(props.initial.protocol)
const connection = ref<JsonObject>(JSON.parse(JSON.stringify(props.initial.connection)))
const dialer = shallowRef(props.initial.dialerRef?.id ?? props.initial.dialerRef?.kind ?? '')
const advanced = shallowRef(false), json = shallowRef(''), error = shallowRef(''), resetNotice = shallowRef(false)
const password = computed(() => ['ss', 'ssr', 'trojan', 'hysteria2', 'tuic', 'http', 'socks5'].includes(protocol.value))
const uuid = computed(() => ['vmess', 'vless', 'tuic'].includes(protocol.value))
const explicitTls = computed(() => ['vmess', 'vless', 'http', 'socks5'].includes(protocol.value))
const hasTls = computed(() => explicitTls.value || ['trojan', 'hysteria2', 'tuic'].includes(protocol.value))
const sniKey = computed(() => ['vmess', 'vless'].includes(protocol.value) ? 'servername' : 'sni')
const networks = computed(() => protocol.value === 'trojan' ? ['tcp', 'ws', 'grpc'] : ['tcp', 'ws', 'http', 'h2', 'grpc'])
function set(key: string, event: Event, number = false) {
 const value = (event.target as HTMLInputElement).value
 if (value === '') delete connection.value[key]; else connection.value[key] = number ? Number(value) : value
}
function check(key: string, event: Event) { connection.value[key] = (event.target as HTMLInputElement).checked }
function changeProtocol(event: Event) {
 protocol.value = (event.target as HTMLSelectElement).value
 connection.value = defaults(protocol.value, String(connection.value.server ?? ''), Number(connection.value.port ?? 443))
 advanced.value = false; json.value = ''; error.value = ''; resetNotice.value = true
}
function switchEditor(value: boolean) {
 error.value = ''
 if (value) json.value = JSON.stringify(connection.value, null, 2)
 else { try { connection.value = parseConnection(json.value) } catch { error.value = '高级参数必须是有效的 JSON 对象'; return } }
 advanced.value = value
}
function transportValue(key: string): JsonValue | undefined { return (connection.value[`${connection.value.network}-opts`] as JsonObject | undefined)?.[key] }
function setTransport(key: string, event: Event) {
 const option = `${connection.value.network}-opts`, previous = connection.value[option]
 const fields: JsonObject = previous && typeof previous === 'object' && !Array.isArray(previous) ? { ...previous } : {}
 const value = (event.target as HTMLInputElement).value
 if (value) fields[key] = value; else delete fields[key]
 connection.value[option] = fields
}
function submit() {
 if (props.busy) return
 error.value = ''; let parameters: JsonObject
 try { parameters = advanced.value ? parseConnection(json.value) : JSON.parse(JSON.stringify(connection.value)) }
 catch { error.value = '高级参数必须是有效的 JSON 对象'; return }
 let target: TargetRef | null = null
 if (dialer.value) target = ['DIRECT', 'REJECT'].includes(dialer.value) ? { kind: dialer.value as 'DIRECT' | 'REJECT', id: null, unresolvedName: null } : { kind: 'NODE', id: dialer.value, unresolvedName: null }
 emit('submit', { ...(props.initial.expectedVersion !== undefined ? { expectedVersion: props.initial.expectedVersion } : {}), name: name.value, protocol: protocol.value, connection: parameters, dialerRef: target })
}
</script>
<template>
 <form class="form-grid node-form" autocomplete="off" @submit.prevent="submit">
  <fieldset :disabled="busy">
   <div class="form-row"><label>节点名称<input v-model="name" name="node-name" required maxlength="255" placeholder="例如：我的香港节点"></label><label>协议<select :value="protocol" name="node-protocol" @change="changeProtocol"><option v-for="item in protocols" :key="item">{{ item }}</option></select></label></div>
   <p class="muted">切换协议会重置该协议的连接参数，保留名称、地址和端口。</p>
   <p v-if="resetNotice" class="notice">已切换协议，请补充认证与连接参数。</p>
   <div class="parameter-tabs"><button type="button" :aria-pressed="!advanced" @click="advanced && switchEditor(false)">常用参数</button><button type="button" :aria-pressed="advanced" @click="!advanced && switchEditor(true)">高级 JSON</button></div>
   <label v-if="advanced">完整连接参数<textarea v-model="json" name="node-json" rows="14" spellcheck="false"></textarea><small class="muted">使用 Mihomo 字段名；名称、协议及链式代理由各自选项维护。</small></label>
   <div v-else class="form-grid">
    <div class="form-row"><label>服务器地址<input :value="connection.server" name="node-server" required placeholder="example.com" @input="set('server', $event)"></label><label>端口<input :value="connection.port" name="node-port" :required="protocol !== 'hysteria2' || !connection.ports" type="number" min="1" max="65535" @input="set('port', $event, true)"><small v-if="protocol === 'hysteria2' && connection.ports" class="muted">已配置端口范围 {{ connection.ports }}，可在高级 JSON 中修改。</small></label></div>
    <label v-if="uuid">UUID<input :value="connection.uuid" name="node-uuid" :required="protocol !== 'tuic' || !connection.token" spellcheck="false" @input="set('uuid', $event)"></label>
    <label v-if="protocol === 'http' || protocol === 'socks5'">用户名<input :value="connection.username" name="node-username" @input="set('username', $event)"></label>
    <label v-if="password">密码<input :value="connection.password" name="node-password" type="password" autocomplete="new-password" :required="!['http','socks5','tuic'].includes(protocol) || (protocol === 'tuic' && !connection.token)" @input="set('password', $event)"></label>
    <label v-if="protocol === 'ss' || protocol === 'ssr' || protocol === 'vmess'">加密方式<input :value="connection.cipher" name="node-cipher" required @input="set('cipher', $event)"></label>
    <label v-if="protocol === 'vmess'">Alter ID<input :value="connection.alterId" type="number" min="0" @input="set('alterId', $event, true)"></label>
    <div v-if="protocol === 'ssr'" class="form-row"><label>SSR 协议<input :value="connection.protocol" @input="set('protocol', $event)"></label><label>混淆方式<input :value="connection.obfs" @input="set('obfs', $event)"></label></div>
    <label v-if="explicitTls"><input :checked="connection.tls === true" type="checkbox" @change="check('tls', $event)">启用 TLS</label>
    <template v-if="hasTls"><label v-if="protocol !== 'socks5'">SNI / 服务器名称<input :value="connection[sniKey]" name="node-sni" placeholder="留空使用服务器地址" @input="set(sniKey, $event)"></label><label><input :checked="connection['skip-cert-verify'] === true" type="checkbox" @change="check('skip-cert-verify', $event)">跳过证书验证</label></template>
    <template v-if="['vmess','vless','trojan'].includes(protocol)"><label>传输方式<select :value="connection.network ?? 'tcp'" name="node-network" @change="set('network', $event)"><option v-for="network in networks" :key="network">{{ network }}</option></select></label><label v-if="connection.network === 'ws'">WebSocket 路径<input :value="transportValue('path')" name="node-ws-path" @input="setTransport('path', $event)"></label><label v-if="connection.network === 'grpc'">gRPC 服务名<input :value="transportValue('grpc-service-name')" @input="setTransport('grpc-service-name', $event)"></label></template>
    <p class="muted">其他参数（Reality、ECH、smux 等）可在“高级 JSON”中维护，已有参数会保留。</p>
   </div>
   <label>链式代理<select v-model="dialer" name="node-dialer"><option value="">不指定</option><option value="DIRECT">DIRECT</option><option value="REJECT">REJECT</option><option v-for="node in nodes.filter(node => node.id !== nodeId)" :key="node.id" :value="node.id">{{ node.originalName }} · {{ node.protocol }}</option></select></label>
  </fieldset>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div class="dialog-actions"><ElButton :disabled="busy" @click="emit('cancel')">取消</ElButton><ElButton native-type="submit" type="primary" :loading="busy">保存节点</ElButton></div>
 </form>
</template>
<style scoped>fieldset{display:grid;gap:16px;border:0;margin:0;padding:0;min-width:0}.parameter-tabs{display:flex;gap:8px}.parameter-tabs button{background:var(--surface-raised);border:1px solid var(--line);border-radius:6px;padding:7px 14px}.parameter-tabs button[aria-pressed=true]{background:var(--accent-soft);color:var(--accent);border-color:var(--accent)}textarea{font-family:monospace;font-size:12px}.node-form .dialog-actions{margin-top:4px}</style>
