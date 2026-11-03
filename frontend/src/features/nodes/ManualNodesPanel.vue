<script setup lang="ts">
import { onMounted, shallowRef, useTemplateRef } from 'vue'
import { ElButton } from 'element-plus'
import LocalNodeActions from './LocalNodeActions.vue'
import NodePoolView from '../sources/NodePoolView.vue'
import { useSources, type NodeFilters } from '../sources/useSources'
import { errorMessage } from '../../api/client'
const { nodes, query, reloadNodes } = useSources(); query.sourceId = '__LOCAL__'
const busy = shallowRef(false), error = shallowRef(''), actions = useTemplateRef('actions')
async function reload() { busy.value = true; error.value = ''; try { await reloadNodes() } catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false } }
function search(filters: NodeFilters) { Object.assign(query, filters, { sourceId: '__LOCAL__' }); void reload() }
onMounted(() => void reload())
</script>
<template>
 <div class="toolbar manual-heading"><div><h2>手动节点</h2><p class="muted">自己维护的节点，以及从完整配置导入、保留为本地的节点。可在多套方案中复用。</p></div><div class="actions"><ElButton :disabled="busy" @click="reload">重新读取节点</ElButton><ElButton type="primary" @click="actions?.create()">添加节点</ElButton></div></div>
 <p v-if="error" class="error maintenance-error" role="alert">{{ error }}</p>
 <LocalNodeActions ref="actions" @changed="reload" />
 <NodePoolView :nodes="nodes" :query="query" :sources="[]" :busy="busy" :local-only="true" :allow-maintenance="true" @query-change="search" @reveal="node => actions?.edit(node)" @edit="node => actions?.edit(node)" @remove="node => actions?.remove(node)" />
</template>
<style scoped>.manual-heading{margin:22px 0 20px}.manual-heading p{margin-top:6px}.maintenance-error{margin-bottom:18px}</style>
