<script setup lang="ts">
import { ElDialog, ElButton } from 'element-plus'
import type { DraftView } from '../../types/domain'
defineProps<{ version: string; latest: DraftView | null; busy: boolean; error: string }>()
const emit = defineEmits<{ reload: []; keep: []; close: [] }>()
</script>
<template><ElDialog :model-value="true" title="草稿在服务器上已更新" width="560px" :close-on-click-modal="false" @close="emit('close')"><p>当前编辑基于 v{{ version }}<span v-if="latest">，服务器为 v{{ latest.version }}</span>。本地输入已保留。</p><p class="warning explanation">保留本地编辑会以服务器最新版本为基准继续保存，覆盖服务器的人工修改；已失效的节点引用会清理，直接节点规则回退为 REJECT。请先核对变化。</p><p v-if="latest" class="muted">服务器草稿：{{ latest.document.groups.length }} 个组 · {{ latest.document.rules.length }} 条规则</p><details v-if="latest"><summary>查看服务器草稿</summary><pre>{{ JSON.stringify(latest.document, null, 2) }}</pre></details><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton :disabled="busy" @click="emit('reload')">读取服务器草稿</ElButton><ElButton type="primary" :loading="busy" @click="emit('keep')">保留本地编辑</ElButton></div><p class="muted hint">读取服务器草稿将放弃当前本地修改。关闭此窗口可以继续查看，保存前仍需解决冲突。</p></ElDialog></template>
<style scoped>.explanation{margin:16px 0}details{margin-top:14px}pre{max-height:250px;overflow:auto;background:#f4f7f6;padding:12px}.hint{font-size:11px;margin-top:16px}</style>
