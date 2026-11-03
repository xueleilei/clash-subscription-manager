<script setup lang="ts">
import { computed, onScopeDispose, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElDialog, ElButton } from 'element-plus'
import AppShell from './layout/AppShell.vue'
import LoginPage from './features/auth/LoginPage.vue'
import { useProfiles } from './layout/useProfiles'
import { useSession } from './features/auth/useSession'
import { errorMessage } from './api/client'
import { hasUnsavedDrafts } from './features/workspace/useProfileDraft'
const session = useSession(), router = useRouter(), route = useRoute(), { profiles, reload, create } = useProfiles()
const error = shallowRef(''), createOpen = shallowRef(false), name = shallowRef(''), busy = shallowRef(false)
const logoutOpen = shallowRef(false)
const hasWorkspace = computed(() => Boolean(session.ownerId.value && route.path !== '/login'))
const reauthenticate = computed(() => hasWorkspace.value && session.status.value === 'anonymous')
watch(session.ownerId, (owner, previous) => {
 if (owner === previous) return
 createOpen.value = false; logoutOpen.value = false; name.value = ''; error.value = ''
 if (owner && previous) void router.replace('/profiles')
})
function beforeUnload(event: BeforeUnloadEvent) { if (hasUnsavedDrafts.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onScopeDispose(() => window.removeEventListener('beforeunload', beforeUnload))
const controller = new AbortController(); onScopeDispose(() => controller.abort())
watch(session.status, async status => {
  if (status === 'authenticated') { try { await reload(controller.signal) } catch (cause) { error.value = errorMessage(cause) } }
  else if (status === 'anonymous' && !session.ownerId.value) { await router.replace('/login') }
}, { immediate: true })
async function createProfile() {
  busy.value = true; error.value = ''
  try { const draft = await create(name.value); createOpen.value = false; name.value = ''; await router.push(`/profiles/${draft.profileId}`) }
  catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false }
}
function logout() { if (hasUnsavedDrafts.value) logoutOpen.value = true; else void performLogout() }
async function performLogout() { try { await session.logout(); logoutOpen.value = false; await router.replace('/login') } catch (cause) { error.value = errorMessage(cause) } }
</script>
<template>
  <div v-if="hasWorkspace" :key="session.ownerId.value!" v-show="session.status.value === 'authenticated'" :inert="session.status.value !== 'authenticated'" data-workspace-shell><AppShell :profiles="profiles" :username="session.user.value?.username ?? ''" @create="createOpen = true" @logout="logout">
    <p v-if="error" class="error" role="alert">{{ error }}</p><RouterView />
    <ElDialog v-model="createOpen" title="新建配置方案" width="420px" :close-on-click-modal="false"><form @submit.prevent="createProfile"><label>方案名称<input v-model="name" maxlength="128" required placeholder="例如：工作专用"></label><div class="dialog-actions"><ElButton @click="createOpen = false">取消</ElButton><ElButton type="primary" native-type="submit" :loading="busy">创建方案</ElButton></div></form></ElDialog>
  </AppShell>
  <ElDialog v-model="logoutOpen" title="退出将丢失未保存内容" width="440px" :close-on-click-modal="false"><p>有方案包含尚未保存的编辑。确认退出后，内存中的编辑副本会清除。</p><div class="dialog-actions"><ElButton @click="logoutOpen = false">继续编辑</ElButton><ElButton type="danger" @click="performLogout">放弃修改并退出</ElButton></div></ElDialog></div>
  <RouterView v-else />
  <ElDialog :model-value="reauthenticate" fullscreen append-to-body destroy-on-close :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" title="会话已过期，请重新登录" class="reauthentication-dialog"><LoginPage reauthenticate /></ElDialog>
</template>
<style>.reauthentication-dialog.el-dialog{padding:0}.reauthentication-dialog .el-dialog__header{position:absolute;left:24px;top:18px;padding:0}.reauthentication-dialog .el-dialog__body{padding:0}</style>
