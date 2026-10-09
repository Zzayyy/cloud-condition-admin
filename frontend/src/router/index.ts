import type { RouteRecordRaw } from 'vue-router'
import { createRouter, createWebHistory } from 'vue-router'

import { firstLeafPath, flattenMenu, menuTree, type MenuNode } from '@/config/menu'
import BasicLayout from '@/layouts/BasicLayout.vue'

/**
 * 路由表完全由 `src/config/menu.ts` 推导，不手写。
 * 用 `import.meta.glob` 懒加载页面；菜单里没写 view 的节点自动落到通用占位页。
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

/** 每个一级菜单都挂在同一个 BasicLayout 下，保证外壳只渲染一次。 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: BasicLayout,
    redirect: firstLeafPath(),
    children: [],
  },
]

for (const node of menuTree) {
  const layoutRoute = routes[0] as RouteRecordRaw & { children: RouteRecordRaw[] }

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
const layoutRoot = routes[0] as RouteRecordRaw & { children: RouteRecordRaw[] }
layoutRoot.children.push({
  path: ':pathMatch(.*)*',
  name: 'not-found',
  component: () => import('@/views/NotFoundView.vue'),
  meta: { title: '页面不存在' },
})

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const base = import.meta.env.VITE_APP_TITLE || '云条件单后台管理系统'
  const title = to.meta?.title as string | undefined
  document.title = title ? `${title} - ${base}` : base
})

export default router
