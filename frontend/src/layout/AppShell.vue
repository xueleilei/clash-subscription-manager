<script setup lang="ts">
defineProps<{ profiles: readonly { id: string; name: string }[]; username: string }>()
const emit = defineEmits<{ create: []; logout: [] }>()
</script>
<template>
  <div class="app-layout">
    <aside class="sidebar">
      <RouterLink to="/profiles" class="brand"><span class="brand-mark" aria-hidden="true">≋</span><span><strong>Clash 订阅管理</strong><small>YOUR CONNECTIONS</small></span></RouterLink>
      <p class="nav-caption">统一资源</p><nav aria-label="统一资源"><RouterLink to="/sources"><span aria-hidden="true">◎</span> 上游订阅</RouterLink><RouterLink to="/nodes"><span aria-hidden="true">▦</span> 统一节点池</RouterLink></nav>
      <div class="nav-caption caption-row"><span>配置方案</span><button class="icon-button" aria-label="新建方案" @click="emit('create')">＋</button></div>
      <nav aria-label="配置方案" class="profile-nav"><RouterLink v-for="profile in profiles" :key="profile.id" :to="`/profiles/${profile.id}`"><span class="dot" aria-hidden="true"></span><span class="truncate" :title="profile.name">{{ profile.name }}</span></RouterLink><RouterLink v-if="!profiles.length" to="/profiles">创建第一个方案</RouterLink></nav>
      <RouterLink class="import-entry" to="/import">↥ 导入完整配置</RouterLink>
      <p class="sidebar-hint">订阅和节点统一管理<br>分组、规则按方案独立维护</p>
      <div class="account"><span class="avatar">{{ username.slice(0, 1).toUpperCase() }}</span><div><strong>个人工作空间</strong><small>{{ username }} · 管理员</small></div><button class="icon-button" aria-label="退出登录" title="退出登录" @click="emit('logout')">↪</button></div>
    </aside>
    <main class="main-content"><slot /></main>
  </div>
</template>
