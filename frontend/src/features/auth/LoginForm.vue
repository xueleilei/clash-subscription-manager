<script setup lang="ts">
import { reactive } from 'vue'
import { ElButton } from 'element-plus'
import type { Credentials } from '../../types/domain'
defineProps<{ busy: boolean; error: string }>()
const emit = defineEmits<{ submit: [credentials: Credentials] }>()
const credentials = reactive({ username: 'admin', password: '' })
</script>
<template>
  <form class="login-form" @submit.prevent="emit('submit', { ...credentials })">
    <label>用户名<input v-model="credentials.username" name="username" autocomplete="username" required autofocus></label>
    <label>密码<input v-model="credentials.password" name="password" type="password" autocomplete="current-password" required></label>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <ElButton type="primary" native-type="submit" :loading="busy" :disabled="busy">登录工作空间 <span aria-hidden="true">↗</span></ElButton>
  </form>
</template>
<style scoped>.login-form { display: grid; gap: 20px; }.login-form .el-button { width:100%; height:44px; }</style>
