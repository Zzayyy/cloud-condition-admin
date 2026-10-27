import type { RouteRecordRaw } from 'vue-router'
import { createRouter, createWebHashHistory } from 'vue-router'

import { firstLeafPath, flattenMenu, menuTree, type MenuNode } from '@/config/menu'
import { USE_MOCK } from '@/composables/useResource'
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

/*
 * 个人信息页：从右上角头像进入，**不进菜单**。
 *
 * 它是「关于自己」的页面，不是业务导航的一站 —— 放进 menu.ts 会白占一个侧边栏位置，
 * 还会多出一个 menu:profile 权限键（而它对所有已登录用户都该开放）。
 *
 * ⚠️ 它同时是「强制改密」的落点：mustChangePassword 为 true 时，
 *    守卫会把所有页面重定向到这里（见下方 mustChangeRedirect）。
 */
layoutRoute.children.push({
  path: '/system/profile',
  name: 'system-profile',
  component: () => import('@/views/system/ProfileView.vue'),
  meta: { title: '个人信息' },
})

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

/*
 * hash 路由：后端用 Java 托管 dist 时，页面挂在 /api/ 下，history 模式刷新
 * 会向后端要 /api/xxx（404 或命中同名接口）。hash 后面的部分浏览器不发给服务器，
 * 刷新与深链都只请求 /api/index.html，不需要服务端兜底。
 * 开发态只是地址多一个 #，HMR 与代理都不受影响。
 */
const router = createRouter({
  history: createWebHashHistory(),
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
/**
 * 「下次登录必须改密」的拦截。
 *
 * <h2>为什么现在才加</h2>
 * 后端从建表起就有 `mustChangePassword`（新账号默认 1、重置密码可置 1、
 * 本人改完后端置 0），但前端**一直没有改密入口**，也从不读这个字段 ——
 * 于是标记永远清不掉，强制改密从未真正生效。
 *
 * <h2>为什么先做页面再拦</h2>
 * 顺序反了会把人锁死：拦了却没有地方改，用户进不去系统。
 * 现在落点是个人信息页（`system-profile`），那里有改密表单。
 *
 * <h2>演示态为什么不拦</h2>
 * mock 没有改密实现（也没有可写的密码库）。真拦进来只会卡死，
 * 且这与项目一贯口径一致：演示态宁可少一个能力，也不假装有。
 *
 * @returns 需要改密时返回重定向目标；否则 null（放行）
 */
function mustChangeRedirect(name: unknown, mustChange: boolean) {
  if (USE_MOCK || !mustChange) return null
  // 已经在改密页就放行，否则无限重定向
  if (name === 'system-profile') return null
  return { name: 'system-profile', query: { force: '1' } }
}

router.beforeEach(async (to) => {
  const { useAuthStore } = await import('@/stores/auth')
  const auth = useAuthStore()

  if (to.meta.public) {
    return auth.isLoggedIn ? firstLeafPath() : true
  }

  if (auth.isLoggedIn) {
    return mustChangeRedirect(to.name, auth.mustChangePassword) ?? true
  }

  // 刷新页面后内存里是空的，但 localStorage 里可能还有 token —— 先试着换回身份
  await auth.fetchProfile()
  if (auth.isLoggedIn) {
    return mustChangeRedirect(to.name, auth.mustChangePassword) ?? true
  }

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
