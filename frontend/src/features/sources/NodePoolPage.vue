<script setup lang="ts">
import { onMounted, onScopeDispose, shallowRef } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import NodePoolView from './NodePoolView.vue'
import NodeBindingDialog from '../import/NodeBindingDialog.vue'
import { useSources, type NodeFilters } from './useSources'
import { errorMessage, request } from '../../api/client'
import type { NodeSpec, NodeSummary } from '../../types/domain'
const { sources, nodes, query, reload, reloadNodes } = useSources()
const busy = shallowRef(false), error = shallowRef(''), connection = shallowRef<NodeSpec | null>(null), showConnection = shallowRef(false)
const binding = shallowRef<NodeSummary | null>(null), bindingNotice = shallowRef('')
const controller = new AbortController(); onScopeDispose(() => { controller.abort(); connection.value = null })
async function load() { busy.value = true; error.value = ''; try { await reload() } catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false } }
onMounted(() => void load())
async function search(value: NodeFilters) { Object.assign(query, value); busy.value = true; error.value = ''; try { await reloadNodes() } catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false } }
async function reveal(node: NodeSummary) { error.value = ''; try { connection.value = await request<NodeSpec>(`/nodes/${node.id}/connection`, { signal: controller.signal }); showConnection.value = true } catch (cause) { error.value = errorMessage(cause) } }
</script>
<template><header class="page-heading"><p class="eyebrow">SHARED RESOURCES</p><div class="toolbar"><div><h1>统一节点池</h1><p class="muted">所有订阅和本地节点，可在不同方案、不同代理组中复用。</p></div><ElButton :disabled="busy" @click="load">重新读取</ElButton></div></header><p v-if="error" class="error pool-error" role="alert">{{ error }}</p><p v-if="bindingNotice" class="notice pool-error">{{bindingNotice}}</p><NodePoolView :allow-binding="true" @bind="node=>binding=node" :nodes="nodes" :sources="sources" :query="query" :busy="busy" @query-change="search" @reveal="reveal" /><NodeBindingDialog v-if="binding" :key="binding.id" :local-node="binding" @close="binding=null" @applied="result=>{binding=null;bindingNotice=`已更新 ${result.affectedProfiles.length} 个方案草稿，请检查后分别发布。`;void load()}" /><ElDialog v-model="showConnection" title="节点连接参数" width="650px" destroy-on-close @closed="connection = null"><p class="warning">此处包含连接凭据，请仅在需要时查看。</p><template v-if="connection"><h3 class="connection-name">{{ connection.originalName }} · {{ connection.protocol }}</h3><pre>{{ JSON.stringify(connection.connection, null, 2) }}</pre><p v-if="connection.dialerRef" class="muted">此节点还包含 {{ connection.dialerRef.kind === 'DIRECT' || connection.dialerRef.kind === 'REJECT' ? connection.dialerRef.kind : '链式代理' }} 引用。</p></template></ElDialog></template>
<style scoped>.pool-error{margin-bottom:18px}.connection-name{margin-top:18px}</style>
