import { readonly, shallowRef } from 'vue'
import { ApiError, onUnauthorized, request } from '../../api/client'
import type { Credentials, User } from '../../types/domain'
const user = shallowRef<User | null>(null)
const status = shallowRef<'unknown' | 'checking' | 'authenticated' | 'anonymous'>('unknown')
const ownerId = shallowRef<string | null>(null)
const resets = new Set<() => void>()
onUnauthorized(() => { user.value = null; status.value = 'anonymous' })
export function onSessionReset(listener: () => void) { resets.add(listener); return () => { resets.delete(listener) } }
function accept(value: User) {
  if (ownerId.value && ownerId.value !== value.id) resets.forEach(reset => reset())
  ownerId.value = value.id; user.value = value; status.value = 'authenticated'
}
async function resume() {
  status.value = 'checking'
  try { accept(await request<User>('/session')) }
  catch (error) { status.value = 'anonymous'; if (!(error instanceof ApiError && error.status === 401)) throw error }
}
async function login(credentials: Credentials) {
  await request('/session', { method: 'POST', body: new URLSearchParams({ username: credentials.username, password: credentials.password }) })
  accept(await request<User>('/session'))
}
async function logout() {
  await request('/session', { method: 'DELETE' })
  user.value = null; ownerId.value = null; status.value = 'anonymous'; resets.forEach(reset => reset())
}
export function useSession() { return { user: readonly(user), status: readonly(status), ownerId: readonly(ownerId), login, logout, resume } }
