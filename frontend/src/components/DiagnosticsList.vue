<script setup lang="ts">
import type { Diagnostic } from '../types/domain'
defineProps<{ items: readonly Diagnostic[] }>()
</script>
<template><ul v-if="items.length" class="diagnostics"><li v-for="(item, index) in items" :key="`${item.code}-${item.path}-${index}`" :class="item.severity === 'ERROR' ? 'error' : 'warning'"><strong>{{ item.message }}</strong><small>{{ item.path }}<span v-if="item.line"> · 第 {{ item.line }} 行<span v-if="item.column">，第 {{ item.column }} 列</span></span> · {{ item.code }}</small></li></ul></template>
<style scoped>.diagnostics{list-style:none;padding:0;margin:12px 0;display:grid;gap:8px}.diagnostics strong{font-weight:500}.diagnostics small{display:block;margin-top:3px;opacity:.8;overflow-wrap:anywhere}</style>
