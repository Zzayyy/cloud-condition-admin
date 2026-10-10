import type { RouteRecordRaw } from 'vue-router'
import { createRouter, createWebHistory } from 'vue-router'

import { firstLeafPath, flattenMenu, menuTree, type MenuNode } from '@/config/menu'
import BasicLayout from '@/layouts/BasicLayout.vue'

/**
 * 路由表完全由 `src/config/menu.ts` 推导，不手写。
 * 用 `import.meta.glob` 懒加载页面；菜单里没写 view 的节点自动落到通用占位页。
 * 登录页是唯一手写的例外 —— 它不属于任何菜单，也不套 BasicLayout。
 */
const viewModules = import.meta.glob('../views/**/*.vue')

function resolveView(node: MenuNode) {
  if (node.view) {
    const key = `../views/${node.view}.vue`
    const loader = viewModules[key]
    if (loader) return loader
    console.warn(`[router] 菜单 "${node.name}" 指定的页面不存在: src/views/${node.view}.vue，已回退到占位页`)
  }
  return () => import('@/views/PlaceholderView.vue')
}

function routeMeta(node: MenuNode, parent: string | null) {
  return {
    title: node.meta.title,
    desc: node.meta.desc,
    refs: node.meta.refs,
    todos: node.meta.todos,
    status: node.meta.status,
    parent,
  }
}

/** 业务页外壳（侧边栏 + 顶栏 + 页签），所有菜单页面挂在它下面。 */
const layoutRoute: RouteRecordRaw & { children: RouteRecordRaw[] } = {
  path: '/',
  component: BasicLayout,
  redirect: firstLeafPath(),
  children: [],
}

for (const node of menuTree) {
  if (node.children?.length) {
    const leaves = flattenMenu([node])
    layoutRoute.children.push({
      path: node.path.replace(/^\//, ''),
      // 父级只作为分组，进入即跳到第一个子页面
      redirect: leaves[0]?.path ?? '/dashboard',
      children: leaves.map((leaf) => ({
        path: leaf.path,
        name: leaf.name,
        component: resolveView(leaf),
        meta: routeMeta(leaf, node.name),
      })),
    })
  } else {
    layoutRoute.children.push({
      path: node.path.replace(/^\//, ''),
      name: node.name,
      component: resolveView(node),
      meta: routeMeta(node, null),
    })
  }
}

// 404 也放在布局内，保持侧边栏可用，避免用户走进死胡同
layoutRoute.children.push({
  path: ':pathMatch(.*)*',
  name: 'not-found',
  component: () => import('@/views/NotFoundView.vue'),
  meta: { title: '页面不存在' },
})

const routes: RouteRecordRaw[] = [
  {
    // 登录页独立于 BasicLayout：还没登录时不该看到侧边栏与页签
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  layoutRoute,
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 登录守卫。
 *
 * `useAuthStore()` 必须**在守卫函数体内**调用，不能在模块顶层 ——
 * 顶层调用时 Pinia 还没被 `app.use()` 装上，会直接抛错。
 *
 * 三种情况：
 *  1. 公开页（登录页）：已登录就送进业务页，避免重复登录；
 *  2. 有 token 但内存里没身份（刷新页面）：先换一次资料再放行；
 *  3. 没有身份：拦到登录页，并把原目标写进 `redirect`，登录后跳回去。
 */
router.beforeEach(async (to) => {
  const { useAuthStore } = await import('@/stores/auth')
  const auth = useAuthStore()

  if (to.meta.public) {
    return auth.isLoggedIn ? firstLeafPath() : true
  }

  if (auth.isLoggedIn) return true

  // 刷新页面后内存里是空的，但 localStorage 里可能还有 token —— 先试着换回身份
  await auth.fetchProfile()
  if (auth.isLoggedIn) return true

  return {
    name: 'login',
    // 登录后跳回原来想去的页面；登录页自身不进 redirect
    query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : undefined,
  }
})

router.afterEach((to) => {
  const base = import.meta.env.VITE_APP_TITLE || '云条件单后台管理系统'
  const title = to.meta?.title as string | undefined
  document.title = title ? `${title} - ${base}` : base
})

export default router
