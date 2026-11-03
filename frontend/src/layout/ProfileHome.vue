<script setup lang="ts">
import { useProfiles } from './useProfiles'
const { profiles } = useProfiles()
</script>
<template><header class="page-heading"><p class="eyebrow">CONFIGURATIONS</p><h1>配置方案</h1><p class="muted">每套方案拥有独立的代理组、分流规则和订阅链接。</p></header><div class="profile-grid"><RouterLink v-for="profile in profiles" :key="profile.id" class="panel profile-card" :to="`/profiles/${profile.id}`"><h2>{{ profile.name }}</h2><p class="muted">{{ profile.publishedVersion ? `已发布 v${profile.publishedVersion}` : '尚未发布' }}</p><span>打开方案 →</span></RouterLink><div v-if="!profiles.length" class="panel empty-state"><h2>从你的第一个方案开始</h2><p>点击侧栏「＋」新建方案，或导入已有完整配置。</p></div></div></template>
