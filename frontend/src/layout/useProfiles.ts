import { readonly, shallowRef } from 'vue'
import { request } from '../api/client'
import { onSessionReset } from '../features/auth/useSession'
import type { DraftView, ProfileView } from '../types/domain'
const profiles = shallowRef<ProfileView[]>([])
onSessionReset(() => { profiles.value = [] })
export function useProfiles() {
  async function reload(signal?: AbortSignal) { const result = await request<ProfileView[]>('/profiles', { signal }); profiles.value = result }
  async function create(name: string) { const draft = await request<DraftView>('/profiles', { method: 'POST', body: { name } }); await reload(); return draft }
  return { profiles: readonly(profiles), reload, create }
}
