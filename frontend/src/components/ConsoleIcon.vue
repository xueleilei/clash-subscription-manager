<script setup lang="ts">
defineProps<{ name: 'sources' | 'nodes' | 'import' | 'logout' | 'plus' | 'refresh' | 'history' }>()
</script>

<template>
  <svg class="console-icon" viewBox="0 0 24 24" width="18" height="18" fill="none"
    stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <g v-if="name === 'sources'">
      <circle cx="12" cy="12" r="2"/><path d="M7.8 7.8a6 6 0 0 0 0 8.4m8.4-8.4a6 6 0 0 1 0 8.4M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14"/>
    </g>
    <g v-else-if="name === 'nodes'">
      <rect x="3" y="3" width="6" height="6" rx="1.3"/><rect x="15" y="15" width="6" height="6" rx="1.3"/><path d="M15 6h3v5M6 13v5h5"/>
    </g>
    <path v-else-if="name === 'import'" d="M12 15V3m-4 4 4-4 4 4M4 14v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5"/>
    <path v-else-if="name === 'logout'" d="M10 4H5v16h5m-1-8h12m-4-4 4 4-4 4"/>
    <path v-else-if="name === 'plus'" d="M12 5v14M5 12h14"/>
    <path v-else-if="name === 'refresh'" d="M20 5v5h-5M4 19v-5h5m10.5-4A7.8 7.8 0 0 0 6 6.5m-1.5 7.5A7.8 7.8 0 0 0 18 17.5"/>
    <g v-else><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3 2"/></g>
  </svg>
</template>
