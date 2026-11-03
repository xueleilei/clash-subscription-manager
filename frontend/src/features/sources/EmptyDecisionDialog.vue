<script setup lang="ts">
import { ElDialog, ElButton } from 'element-plus'
import type { EmptyDecision } from '../../types/domain'
defineProps<{ decision: EmptyDecision; sourceName: string; busy: boolean; error: string }>()
const emit = defineEmits<{ choose: [choice: 'KEEP' | 'CLEAR']; close: [] }>()
</script>
<template><ElDialog :model-value="true" width="490px" :show-close="false" :close-on-click-modal="false" @close="emit('close')"><template #header><div class="toolbar"><h2>订阅返回了空节点列表</h2><button class="icon-button" aria-label="关闭空结果提示" @click="emit('close')">×</button></div></template><div :data-empty-decision="decision.id"><p><strong>{{ sourceName }}</strong> 的订阅有效，但这次没有返回节点。</p><p class="muted explanation">未选择前会保留旧数据。清空会移除相关组成员，失效的直接节点规则改为 REJECT，空组也将拒绝连接。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton :disabled="busy" @click="emit('choose', 'CLEAR')">清空节点</ElButton><ElButton type="primary" :loading="busy" @click="emit('choose', 'KEEP')">保留旧数据</ElButton></div></div></ElDialog></template>
<style scoped>.explanation{margin:14px 0 20px}</style>
