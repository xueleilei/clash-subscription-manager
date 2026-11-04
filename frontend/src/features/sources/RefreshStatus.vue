<script setup lang="ts">
import DiagnosticsList from '../../components/DiagnosticsList.vue'
import ConsoleIcon from '../../components/ConsoleIcon.vue'
import { localTime, statusLabel, statusTone } from '../../utils/display'
import type { SourceView, TaskView } from '../../types/domain'
import { computed } from 'vue'
const props = defineProps<{ tasks: readonly TaskView[]; sources: readonly SourceView[] }>()
const names = computed(() => new Map(props.sources.map(source => [source.id, source.name])))
</script>
<template>
 <section v-if="tasks.length" class="panel refresh-log">
   <header class="panel-header"><h2><ConsoleIcon name="history"/>刷新记录</h2><small aria-hidden="true">ACTIVITY LOG</small></header>
   <article v-for="task in tasks" :key="task.id" class="task-row" :data-task-id="task.id">
     <div class="task-summary">
       <div><strong>{{ names.get(task.sourceId) ?? '已删除的来源' }}</strong><p class="task-time">{{ localTime(task.createdAt) }} · {{ task.trigger === 'MANUAL' ? '手动刷新' : '后台任务' }}</p></div>
       <p v-if="task.outcome" class="changes">新增 {{ task.outcome.changes.added }} · 更新 {{ task.outcome.changes.updated }} · 删除 {{ task.outcome.changes.removed }}<span v-if="task.status === 'FAILED'"> · 旧数据与分组已保留</span></p>
       <span class="tag status-tag" :class="statusTone(task.status)">{{ statusLabel(task.status) }}</span>
     </div>
     <DiagnosticsList v-if="task.outcome" :items="[...task.outcome.report.errors, ...task.outcome.report.warnings]" />
   </article>
 </section>
</template>
<style scoped>
.refresh-log { margin-top: 22px; }
.panel-header h2 { display: flex; align-items: center; gap: 8px; }
.panel-header .console-icon { color: var(--accent); }
.panel-header small { color: var(--subtle); font: 10px var(--mono); letter-spacing: .12em; }
.task-row { padding: 13px 18px; border-bottom: 1px solid var(--line-soft); }
.task-row:last-child { border: 0; }
.task-summary { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 14px; }
.task-summary strong { overflow-wrap: anywhere; }
.task-time { margin-top: 2px; color: var(--muted); font: 11px/1.6 var(--mono); }
.changes { color: var(--muted); font-size: 12px; }
.status-tag { grid-column: 3; justify-self: end; }
@media (max-width: 720px) {
  .task-summary { grid-template-columns: minmax(0, 1fr) auto; gap: 4px 8px; }
  .changes { grid-column: 1 / -1; }
  .status-tag { grid-area: 1 / 2; }
}
</style>
