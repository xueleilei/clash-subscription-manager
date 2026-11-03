<script setup lang="ts">
import { onScopeDispose, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElDialog } from 'element-plus'
import { request, errorMessage } from '../../api/client'
import { useProfiles } from '../../layout/useProfiles'
import { useSession } from '../auth/useSession'
import { forgetDraft } from './useProfileDraft'
import type { ProfileView } from '../../types/domain'
interface DeletionImpact { profileId: string; profileVersion: string; groupCount: number; ruleCount: number; hasPublished: boolean; message: string }
const props = defineProps<{ profile: ProfileView | null }>()
const router = useRouter(), { reload } = useProfiles(), session = useSession()
const renaming = shallowRef(false), name = shallowRef(''), busy = shallowRef(false), error = shallowRef('')
const renameTarget = shallowRef<ProfileView | null>(null)
const impact = shallowRef<(DeletionImpact & { profileName: string }) | null>(null)
let sequence = 0, active: AbortController | null = null
function reset() {
 sequence++; active?.abort(); active = null; busy.value = false; error.value = ''
 renaming.value = false; renameTarget.value = null; name.value = ''; impact.value = null
}
watch([() => props.profile?.id, session.ownerId], reset, { flush: 'sync' })
onScopeDispose(reset)
function begin() { active?.abort(); active = new AbortController(); busy.value = true; error.value = ''; return { current: ++sequence, signal: active.signal } }
function currentSelection(current: number, id: string) { return current === sequence && props.profile?.id === id }
function openRename() {
 if (!props.profile || busy.value) return
 renameTarget.value = { ...props.profile }; name.value = props.profile.name; error.value = ''; renaming.value = true
}
async function rename() {
 const selected = renameTarget.value
 if (!selected || busy.value || selected.id !== props.profile?.id) return
 const { current, signal } = begin()
 try {
  await request(`/profiles/${selected.id}`, { method: 'PATCH', body: { expectedVersion: selected.version, name: name.value }, signal })
  if (!currentSelection(current, selected.id)) return
  await reload()
  if (currentSelection(current, selected.id)) { renaming.value = false; renameTarget.value = null }
 } catch (cause) { if (currentSelection(current, selected.id)) error.value = errorMessage(cause) }
 finally { if (current === sequence) busy.value = false }
}
async function previewDelete() {
 if (!props.profile || busy.value) return
 const selected = { ...props.profile }, { current, signal } = begin()
 try {
  const result = await request<DeletionImpact>(`/profiles/${selected.id}/deletion-impact`, { signal })
  if (currentSelection(current, selected.id) && result.profileId === selected.id) impact.value = { ...result, profileName: selected.name }
 } catch (cause) { if (currentSelection(current, selected.id)) error.value = errorMessage(cause) }
 finally { if (current === sequence) busy.value = false }
}
async function remove() {
 const selected = impact.value, owner = session.ownerId.value
 if (!selected || busy.value || selected.profileId !== props.profile?.id) return
 const { current, signal } = begin()
 try {
  await request(`/profiles/${selected.profileId}?expectedVersion=${encodeURIComponent(selected.profileVersion)}`, { method: 'DELETE', signal })
  if (!currentSelection(current, selected.profileId)) return
  if (owner) forgetDraft(owner, selected.profileId)
  impact.value = null; await reload()
  if (session.ownerId.value === owner && router.currentRoute.value.params.id === selected.profileId) await router.push('/profiles')
 } catch (cause) { if (currentSelection(current, selected.profileId)) error.value = errorMessage(cause) }
 finally { if (current === sequence) busy.value = false }
}
</script>
<template>
 <div class="profile-actions"><button class="text-button" :disabled="!profile || busy" @click="openRename">重命名</button><button class="text-button danger" :disabled="!profile || busy" @click="previewDelete">删除方案</button></div>
 <p v-if="error && !renaming && !impact" class="error" role="alert">{{ error }}</p>
 <ElDialog v-model="renaming" title="重命名方案" width="440px" :close-on-click-modal="false"><form @submit.prevent="rename"><label>方案名称<input v-model="name" required maxlength="128"></label><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton @click="renaming = false">取消</ElButton><ElButton type="primary" native-type="submit" :loading="busy">保存名称</ElButton></div></form></ElDialog>
 <ElDialog v-if="impact" :model-value="true" title="删除配置方案" width="490px" :close-on-click-modal="false" @close="impact = null"><p>将删除 {{ impact.profileName }}：{{ impact.groupCount }} 个组、{{ impact.ruleCount }} 条规则。</p><p class="warning impact-warning">订阅链接将立即失效，当前未保存编辑也将丢弃。上游订阅和共享节点继续保留。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><ElButton @click="impact = null">取消</ElButton><ElButton type="danger" :loading="busy" @click="remove">确认删除方案</ElButton></div></ElDialog>
</template>
<style scoped>.profile-actions{display:flex;gap:12px;font-size:10px}.impact-warning{margin:16px 0}</style>
