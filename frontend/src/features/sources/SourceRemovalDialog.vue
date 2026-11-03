<script setup lang="ts">
import { ElDialog, ElButton } from 'element-plus'
import type { RemovalPreview } from '../../types/domain'
defineProps<{ preview: RemovalPreview; sourceName: string; profileNames: ReadonlyMap<string, string>; busy: boolean; error: string }>()
const emit = defineEmits<{ choose: [mode: 'KEEP_LOCAL' | 'CLEAR']; close: [] }>()
</script>
<template><ElDialog :model-value="true" title="删除订阅来源" width="540px" :close-on-click-modal="false" @close="emit('close')"><p>删除 <strong>{{ sourceName }}</strong>，涉及 {{ preview.nodeCount }} 个节点和 {{ preview.affectedProfiles.length }} 套方案。</p><ul><li v-for="id in preview.affectedProfiles" :key="id">{{ profileNames.get(id) ?? id }}</li></ul><p class="notice">转为本地节点：保留节点 ID、连接参数和分组，停止参与该订阅的更新。</p><p class="warning removal-warning">一并清空：清理组成员，直接节点规则和空组回退为 REJECT。影响预览过期后需重新读取。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton @click="emit('close')">取消</ElButton><ElButton :disabled="busy" @click="emit('choose', 'CLEAR')">删除并清空节点</ElButton><ElButton type="primary" :loading="busy" @click="emit('choose', 'KEEP_LOCAL')">删除并转为本地节点</ElButton></div></ElDialog></template>
<style scoped>.removal-warning{margin:12px 0}.dialog-actions{flex-wrap:wrap}</style>
