<script setup lang="ts">
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import { localTime, statusLabel } from '../../utils/display'
import type { SourceView, TaskView } from '../../types/domain'
import { computed } from 'vue'
const props = defineProps<{ tasks: readonly TaskView[]; sources: readonly SourceView[] }>()
const names = computed(() => new Map(props.sources.map(source => [source.id, source.name])))
</script>
<template><section v-if="tasks.length" class="panel refresh-log"><header class="panel-header"><h2>刷新记录</h2><span class="muted">各来源独立处理</span></header><article v-for="task in tasks" :key="task.id" class="task-row" :data-task-id="task.id"><div class="toolbar"><strong>{{ names.get(task.sourceId) ?? '已删除的来源' }}</strong><span class="tag" :class="{success: task.status === 'SUCCESS' || task.status === 'UNCHANGED'}">{{ statusLabel(task.status) }}</span></div><p class="muted task-time">{{ localTime(task.createdAt) }} · {{ task.trigger === 'MANUAL' ? '手动刷新' : '后台任务' }}</p><p v-if="task.outcome" class="changes">新增 {{ task.outcome.changes.added }} · 更新 {{ task.outcome.changes.updated }} · 删除 {{ task.outcome.changes.removed }}<span v-if="task.status === 'FAILED'"> · 旧数据与分组已保留</span></p><DiagnosticsList v-if="task.outcome" :items="[...task.outcome.report.errors, ...task.outcome.report.warnings]" /></article></section></template>
<style scoped>.refresh-log{margin-top:24px}.task-row{padding:16px 20px;border-bottom:1px solid var(--line)}.task-row:last-child{border:0}.task-time{font-size:11px;margin-top:4px}.changes{font-size:12px;margin-top:8px}</style>
