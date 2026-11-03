import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import { useSession } from './features/auth/useSession'
import LoginPage from './features/auth/LoginPage.vue'
import ProfileHome from './layout/ProfileHome.vue'
import FeaturePage from './layout/FeaturePage.vue'
export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({ history, routes: [
    { path: '/', redirect: '/profiles' }, { path: '/login', component: LoginPage },
    { path: '/profiles', component: ProfileHome },
    { path: '/profiles/:id', component: FeaturePage, props: { title: '代理组' } },
    { path: '/sources', component: () => import('./features/sources/SourcesView.vue') },
    { path: '/nodes', component: () => import('./features/sources/NodePoolPage.vue') },
    { path: '/import', component: FeaturePage, props: { title: '导入完整配置' } },
    { path: '/:pathMatch(.*)*', redirect: '/profiles' },
  ] })
  router.beforeEach(async to => {
    if (to.path === '/login') return true
    const session = useSession()
    if (session.status.value === 'unknown') { try { await session.resume() } catch { return '/login' } }
    return session.status.value === 'authenticated' ? true : '/login'
  })
  return router
}
