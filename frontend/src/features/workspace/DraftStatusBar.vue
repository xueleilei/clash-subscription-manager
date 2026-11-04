<script setup lang="ts">
import { ElButton } from 'element-plus'
defineProps<{ version: string; publishedVersion: string | null; dirty: boolean; matchesPublished: boolean; busy: boolean; publishReady?: boolean }>()
const emit = defineEmits<{ save: []; publish: [] }>()
</script>
<template><div class="draft-bar"><div class="draft-label"><span class="tag" :class="{success: matchesPublished && !dirty}">{{ dirty ? '未保存' : matchesPublished ? '与已发布版本一致' : '已保存待发布' }}</span><small>草稿 v{{ version || '—' }}<span v-if="publishedVersion"> · 已发布 v{{ publishedVersion }}</span></small></div><ElButton :disabled="busy || !version" :loading="busy" @click="emit('save')">保存草稿</ElButton><ElButton type="primary" :disabled="!publishReady || busy || !version" @click="emit('publish')">校验并发布 ↗</ElButton></div></template>
<style scoped>
.draft-bar { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
.draft-label { display: inline-flex; align-items: center; gap: 8px; margin-right: 4px; white-space: nowrap; }
.draft-label small { font-size: 9px; color: var(--muted); }
</style>
