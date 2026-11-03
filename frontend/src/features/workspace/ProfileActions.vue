<script setup lang="ts">
import { shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElDialog } from 'element-plus'
import { request, errorMessage } from '../../api/client'
import { useProfiles } from '../../layout/useProfiles'
import { useSession } from '../auth/useSession'
import { forgetDraft } from './useProfileDraft'
import type { ProfileView } from '../../types/domain'
const props = defineProps<{ profile: ProfileView | null }>()
const router = useRouter(), { reload } = useProfiles(), session = useSession()
const renaming = shallowRef(false), name = shallowRef(''), busy = shallowRef(false), error = shallowRef('')
const impact = shallowRef<{ profileId: string; profileVersion: string; groupCount: number; ruleCount: number; hasPublished: boolean; message: string } | null>(null)
function openRename() { name.value = props.profile?.name ?? ''; error.value = ''; renaming.value = true }
async function rename() {
 if (!props.profile) return
 busy.value = true; error.value = ''
 try { await request(`/profiles/${props.profile.id}`, { method: 'PATCH', body: { expectedVersion: props.profile.version, name: name.value } }); await reload(); renaming.value = false }
 catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false }
}
async function previewDelete() { if (!props.profile) return; error.value = ''; try { impact.value = await request(`/profiles/${props.profile.id}/deletion-impact`) } catch (cause) { error.value = errorMessage(cause) } }
async function remove() {
 if (!impact.value) return
 busy.value = true; error.value = ''
 try { const id = impact.value.profileId; await request(`/profiles/${id}?expectedVersion=${encodeURIComponent(impact.value.profileVersion)}`, { method: 'DELETE' }); if (session.ownerId.value) forgetDraft(session.ownerId.value, id); impact.value = null; await reload(); await router.push('/profiles') }
 catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false }
}
</script>
<template><div class="profile-actions"><button class="text-button" :disabled="!profile" @click="openRename">重命名</button><button class="text-button danger" :disabled="!profile" @click="previewDelete">删除方案</button></div><p v-if="error && !renaming && !impact" class="error" role="alert">{{ error }}</p><ElDialog v-model="renaming" title="重命名方案" width="440px" :close-on-click-modal="false"><form @submit.prevent="rename"><label>方案名称<input v-model="name" required maxlength="128"></label><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton @click="renaming = false">取消</ElButton><ElButton type="primary" native-type="submit" :loading="busy">保存名称</ElButton></div></form></ElDialog><ElDialog v-if="impact" :model-value="true" title="删除配置方案" width="490px" :close-on-click-modal="false" @close="impact = null"><p>将删除 {{ profile?.name }}：{{ impact.groupCount }} 个组、{{ impact.ruleCount }} 条规则。</p><p class="warning impact-warning">订阅链接将立即失效，当前未保存编辑也将丢弃。上游订阅和共享节点继续保留。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton @click="impact = null">取消</ElButton><ElButton type="danger" :loading="busy" @click="remove">确认删除方案</ElButton></div></ElDialog></template>
<style scoped>.profile-actions{display:flex;gap:12px;font-size:10px}.impact-warning{margin:16px 0}</style>
