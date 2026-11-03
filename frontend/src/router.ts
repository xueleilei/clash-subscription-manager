import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import { useSession } from './features/auth/useSession'
import LoginPage from './features/auth/LoginPage.vue'
import ProfileHome from './layout/ProfileHome.vue'
export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({ history, routes: [
    { path: '/', redirect: '/profiles' }, { path: '/login', component: LoginPage },
    { path: '/profiles', component: ProfileHome },
    { path: '/profiles/:id', component: () => import('./features/workspace/WorkspaceView.vue') },
    { path: '/sources', component: () => import('./features/sources/SourcesView.vue') },
    { path: '/nodes', component: () => import('./features/sources/NodePoolPage.vue') },
    { path: '/import', component: () => import('./features/import/ImportWizard.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/profiles' },
  ] })
  router.beforeEach(async (to, from) => {
    const session = useSession()
    // Keep the mounted editing session behind the reauthentication dialog.
    if (session.status.value !== 'authenticated' && session.ownerId.value && from.matched.length && from.path !== '/login') return false
    if (to.path === '/login') return true
    if (session.status.value === 'unknown') { try { await session.resume() } catch { return '/login' } }
    return session.status.value === 'authenticated' ? true : '/login'
  })
  return router
}
