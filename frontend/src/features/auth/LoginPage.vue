<script setup lang="ts">
import { shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import LoginForm from './LoginForm.vue'
import { useSession } from './useSession'
import { errorMessage } from '../../api/client'
import type { Credentials } from '../../types/domain'
const props = defineProps<{ reauthenticate?: boolean }>()
const session = useSession(), router = useRouter(), busy = shallowRef(false), error = shallowRef('')
async function login(credentials: Credentials) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await session.login(credentials); if (!props.reauthenticate) await router.replace('/profiles') }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
</script>
<template>
  <main class="login-page"><section class="login-card">
    <img class="brand-mark" src="../../assets/clash-cat-logo.png" width="38" height="38" alt="">
    <p class="eyebrow">NETWORK CONSOLE</p>
    <h1>Clash 订阅管理</h1><p class="muted intro">{{ reauthenticate ? '会话已过期，重新登录后继续编辑。' : '你的节点与配置，都在这里。' }}</p>
    <LoginForm :busy="busy" :error="error" @submit="login" />
    <p class="muted note">{{ reauthenticate ? '同一账号登录后，将恢复当前页面和未完成的输入。' : '使用服务器配置的管理员账号登录' }}</p>
  </section></main>
</template>
<style scoped>
.login-page { min-height: 100dvh; display: grid; place-items: center; padding: 24px; background-color: var(--canvas); background-image: radial-gradient(ellipse at 50% 30%, #16354899, transparent 68%), linear-gradient(#7ac5d00c 1px, transparent 1px), linear-gradient(90deg, #7ac5d00c 1px, transparent 1px); background-size: auto, 38px 38px, 38px 38px; }
.login-card { width: 100%; max-width: 410px; padding: clamp(24px, 6vw, 40px); background: var(--surface); border: 1px solid #36546a; border-top: 2px solid var(--accent); border-radius: 12px; box-shadow: 0 20px 64px #02071070; }
.brand-mark { margin-bottom: 24px; }
.eyebrow { margin-bottom: 8px; }
.intro { margin: 12px 0 32px; }
.note { font-size: 12px; margin-top: 25px; }
</style>
