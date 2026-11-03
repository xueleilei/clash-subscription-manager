import { readonly, shallowRef } from 'vue'
import { request } from '../api/client'
import { onSessionReset, useSession } from '../features/auth/useSession'
import type { DraftView, ProfileView } from '../types/domain'
const profiles = shallowRef<ProfileView[]>([])
let sequence = 0
onSessionReset(() => { sequence++; profiles.value = [] })
export function useProfiles() {
  async function reload(signal?: AbortSignal) {
    const current = ++sequence, owner = useSession().ownerId.value
    const result = await request<ProfileView[]>('/profiles', { signal })
    if (current === sequence && owner === useSession().ownerId.value) profiles.value = result
  }
  async function create(name: string) { const draft = await request<DraftView>('/profiles', { method: 'POST', body: { name } }); await reload(); return draft }
  return { profiles: readonly(profiles), reload, create }
}
