<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { ElButton } from 'element-plus'
import { localTime } from '../../utils/display'
import type { ReleaseView } from '../../types/domain'
const props=defineProps<{releases:readonly ReleaseView[];busy:boolean}>(),emit=defineEmits<{reload:[]}>()
const expanded=shallowRef(false),visible=computed(()=>expanded.value?props.releases:props.releases.slice(0,10))
const reasons:Record<string,string>={MANUAL:'主动发布',SOURCE_REFRESH:'上游订阅更新',EMPTY_CLEAR:'确认清空来源',SOURCE_REMOVAL:'移除订阅来源',SOURCE_DELETE:'移除订阅来源',KEEP_LOCAL:'来源转为本地'}
</script>
<template><section class="panel"><header class="panel-header"><div><h2>发布历史</h2><p class="muted">保留当前版本和最近 100 个历史版本。</p></div><ElButton :loading="busy" @click="emit('reload')">刷新历史</ElButton></header><div class="history-list scroll-area"><article v-for="release in visible" :key="release.id" class="history-row"><span class="tag success">v{{ release.version }}</span><div><strong>{{ reasons[release.reason]??release.reason }}</strong><small>{{ localTime(release.createdAt) }}</small></div><span class="muted">{{ release.nodeCount }} 个输出节点</span></article><p v-if="!releases.length" class="empty-state">还没有成功发布的版本</p></div><div v-if="releases.length>10" class="panel-footer"><button class="text-button" @click="expanded=!expanded">{{expanded?'收起':'查看全部历史'}}</button></div></section></template>
<style scoped>.history-list{max-height:420px}.history-row{display:flex;align-items:center;gap:16px;margin:0 18px;padding:16px 0;border-bottom:1px solid var(--line);font-size:11px}.history-row div{flex:1}.history-row strong{font-weight:500}.history-row small{display:block;color:var(--muted);margin-top:4px}</style>
