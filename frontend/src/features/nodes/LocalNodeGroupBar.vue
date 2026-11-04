<script setup lang="ts">
import { computed } from 'vue'
import type { LocalNodeGroupDirectory } from '../../types/domain'

const props = defineProps<{ directory: LocalNodeGroupDirectory | null; filter: string; busy: boolean }>()
const emit = defineEmits<{ filter: [id: string]; manage: [] }>()
const choices = computed(() => {
 const d = props.directory
 if (!d) return []
 return [
  { id: '', name: '全部分组', nodeCount: d.groups.reduce((sum, group) => sum + group.nodeCount, d.ungroupedCount) },
  { id: 'ungrouped', name: '未分组', nodeCount: d.ungroupedCount },
  ...d.groups,
 ]
})
</script>
<template>
 <section class="panel group-bar" aria-label="来源分组">
  <div class="toolbar"><h3>来源分组</h3><button type="button" class="text-button" :disabled="busy || !directory" @click="emit('manage')">管理分组</button></div>
  <div v-if="directory" class="group-choices" role="group" aria-label="按来源分组筛选">
   <button v-for="group in choices" :key="group.id" type="button" class="group-choice" :aria-pressed="filter === group.id" :aria-label="'筛选分组 ' + group.name" :disabled="busy" @click="emit('filter', group.id)"><span>{{ group.name }}</span><small>{{ group.nodeCount }}</small></button>
  </div>
  <p v-else class="muted">{{ busy ? '正在读取来源分组…' : '未能读取来源分组，请重新读取节点。' }}</p>
 </section>
</template>
<style scoped>
.group-bar{display:grid;gap:10px;padding:12px 16px;margin-bottom:16px}.group-choices{display:flex;gap:8px;flex-wrap:wrap;max-height:155px;overflow:auto;padding:3px}.group-choice{display:inline-flex;align-items:center;gap:7px;max-width:100%;border:1px solid var(--line);border-radius:7px;padding:4px 10px;color:#41534f;background:#fff}.group-choice span{overflow-wrap:anywhere}.group-choice small{font-variant-numeric:tabular-nums;color:#596e69}.group-choice[aria-pressed=true]{border-color:#88b8b0;background:#eaf4f0;color:#155b51;font-weight:600}.group-choice[aria-pressed=true] small{color:inherit}
</style>
