<script setup lang="ts">
import { computed, onMounted, onScopeDispose, shallowRef } from 'vue'
import { request } from '../../api/client'
import type { NodeRegionGroup } from '../../types/domain'

const props = defineProps<{ modelValue: string; label: string; available?: ReadonlySet<string>; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const catalog = shallowRef<NodeRegionGroup[]>([]), failed = shallowRef(false), loading = shallowRef(false)
const lifetime = new AbortController()
onScopeDispose(() => lifetime.abort())
const groups = computed(() => props.available
 ? catalog.value.map(group => ({name: group.name, regions: group.regions.filter(item => props.available!.has(item.code))})).filter(group => group.regions.length)
 : catalog.value)
async function load() {
 if (loading.value) return
 loading.value = true; failed.value = false
 try {
  const data = await request<NodeRegionGroup[]>('/nodes/regions', {signal: lifetime.signal})
  if (!lifetime.signal.aborted) catalog.value = data
 } catch { if (!lifetime.signal.aborted) failed.value = true }
 finally { if (!lifetime.signal.aborted) loading.value = false }
}
onMounted(() => void load())
</script>
<template>
 <div class="node-region-filter">
  <select :value="modelValue" :aria-label="label" :disabled="disabled || loading" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
   <option value="">全部地区</option>
   <optgroup v-for="group in groups" :key="group.name" :label="group.name">
    <option v-for="item in group.regions" :key="item.code" :value="item.code">{{ item.name }}（{{ item.code }}）</option>
   </optgroup>
   <option v-if="!available || available.has('UNKNOWN')" value="UNKNOWN">未知地区</option>
  </select>
  <div v-if="failed" class="region-error" role="status">地区列表读取失败。<button class="text-button" @click="load">重试</button></div>
 </div>
</template>
<style scoped>.node-region-filter{min-width:0}.region-error{font-size:11px;color:var(--muted)}</style>
