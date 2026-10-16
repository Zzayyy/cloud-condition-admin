<script setup lang="ts">
import { computed, h, onMounted, ref, watch, type Component } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import {
  NBreadcrumb,
  NBreadcrumbItem,
  NButton,
  NDropdown,
  NIcon,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NLayoutSider,
  NMenu,
  NTag,
  NTooltip,
  useMessage,
  type DropdownOption,
  type MenuOption,
} from 'naive-ui'
import {
  ArrowBackOutline,
  ArrowForwardOutline,
  ChevronBackOutline,
  ChevronForwardOutline,
  CloseCircleOutline,
  CloseOutline,
  DesktopOutline,
  MoonOutline,
  PersonCircleOutline,
  RefreshOutline,
  SunnyOutline,
  TrashOutline,
} from '@vicons/ionicons5'

import { findMenuChainByPath, firstLeafPath, menuTree, type MenuNode } from '@/config/menu'
import { accentOf, moduleKeyFromPath, tintOf } from '@/theme/palette'
import { useAppStore, type ThemeMode } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useTabsStore } from '@/stores/tabs'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const authStore = useAuthStore()
const tabsStore = useTabsStore()
const message = useMessage()

/** 当前模块（一级菜单）的 key，如 strategy / storage */
const moduleKey = computed(() => moduleKeyFromPath(route.path) ?? 'dashboard')
/** 当前模块主色 */
const accent = computed(() => accentOf(moduleKey.value))

/** 菜单树 → naive-ui 的 MenuOption（图标带模块色） */
function toMenuOptions(nodes: MenuNode[], topLevel = true): MenuOption[] {
  return nodes.map((node) => {
    const color = topLevel ? accentOf(node.name) : undefined
    return {
      label: node.meta.title,
      key: node.name,
      icon: node.meta.icon
        ? () =>
            h(
              'span',
              {
                class: 'menu-icon',
                style: color
                  ? { color, background: tintOf(node.name, 0.12) }
                  : undefined,
              },
              [h(NIcon, { size: 17 }, { default: () => h(node.meta.icon as Component) })],
            )
        : undefined,
      children: node.children?.length ? toMenuOptions(node.children, false) : undefined,
    }
  })
}

const menuOptions = computed(() => toMenuOptions(menuTree))

const activeMenuKey = computed(() => (route.name as string) ?? '')

const expandedKeys = ref<string[]>([])

watch(
  () => route.meta?.parent,
  (parent) => {
    const key = parent as string | null | undefined
    if (key && !expandedKeys.value.includes(key)) {
      expandedKeys.value = [...expandedKeys.value, key]
    }
  },
  { immediate: true },
)

function handleMenuSelect(key: string) {
  if (key !== route.name) router.push({ name: key })
}

const breadcrumb = computed(() => findMenuChainByPath(route.path))

const ctxShow = ref(false)

watch(
  () => route.path,
  () => {
    // 路由一变就收起右键菜单，否则它会浮在刚打开的页面上。
    // ⚠️ `ctxShow` 必须声明在本 watcher **之前**：watcher 带 `immediate: true`，
    // 在 setup 阶段就会跑一次，而 `const` 有暂时性死区（TDZ），
    // 写在下面会抛 ReferenceError 导致整个应用挂不上去 —— 白屏，
    // 且 typecheck / build 都不报错（踩过：函数声明会提升，`const` 不会）。
    ctxShow.value = false
    const title = route.meta?.title as string | undefined
    if (!title || route.name === 'not-found' || route.name === 'root') return
    tabsStore.open({ name: route.name as string, path: route.path, title })
  },
  { immediate: true },
)

onMounted(() => {
  // 页签的恢复已经在 `useTabsStore()` 建店时做掉了（见 stores/tabs.ts 的说明：
  // 放这里会被上面那个 immediate watcher 的 persist 覆盖，导致刷新丢页签）。
  // 这里只补记当前页签 —— 恢复出来的列表里可能还没有它。
  const title = route.meta?.title as string | undefined
  if (title && route.name !== 'not-found' && route.name !== 'root') {
    tabsStore.open({ name: route.name as string, path: route.path, title })
  }
})

function closeTab(path: string) {
  const next = tabsStore.close(path)
  if (next) router.push(next)
}

/* ------------------------------ 页签右键菜单 ------------------------------ */

/**
 * 页签太多时一个个点关闭很烦，所以给页签加右键菜单。
 *
 * 用 `n-dropdown` 的 `trigger="manual"` + `x`/`y` 实现：
 * 它只是「手动在指定坐标弹一个菜单」，不会自己劫持浏览器右键菜单，
 * 所以我们要在 `contextmenu` 事件上 `preventDefault()`。
 *
 * 三个容易踩的点：
 *  1. **菜单项要按上下文禁用**，不能一律可点 ——
 *     只有一个页签时「关闭其他」没有意义，最右一个页签没有「关闭右侧」；
 *  2. **关完要保证有落脚点**：全关了就回首页，否则会停在一个已关闭的页签上；
 *  3. **右键某个页签不应先切换过去**（那是左键的行为），
 *     但菜单操作要作用于**右键的那个**页签，所以目标单独记在 `ctxPath`。
 *
 * `ctxShow` 已提到上面的 watcher 之前声明（避免 TDZ 白屏），其余状态放这里。
 */
const ctxX = ref(0)
const ctxY = ref(0)
const ctxPath = ref('')

const ctxIndex = computed(() => tabsStore.tabs.findIndex((t) => t.path === ctxPath.value))
const ctxTab = computed(() => tabsStore.tabs.find((t) => t.path === ctxPath.value))

/** 右键目标在列表中的位置决定哪些项可用 */
const ctxOptions = computed<DropdownOption[]>(() => {
  const total = tabsStore.tabs.length
  const i = ctxIndex.value
  const onlyOne = total <= 1
  return [
    {
      label: '刷新当前页',
      key: 'reload',
      icon: () => h(NIcon, null, { default: () => h(RefreshOutline) }),
    },
    { type: 'divider', key: 'd1' },
    {
      label: '关闭',
      key: 'close',
      icon: () => h(NIcon, null, { default: () => h(CloseOutline) }),
    },
    {
      label: '关闭其他',
      key: 'close-others',
      disabled: onlyOne,
      icon: () => h(NIcon, null, { default: () => h(CloseCircleOutline) }),
    },
    {
      label: '关闭右侧',
      key: 'close-right',
      disabled: i < 0 || i >= total - 1,
      icon: () => h(NIcon, null, { default: () => h(ArrowForwardOutline) }),
    },
    {
      label: '关闭左侧',
      key: 'close-left',
      disabled: i <= 0,
      icon: () => h(NIcon, null, { default: () => h(ArrowBackOutline) }),
    },
    { type: 'divider', key: 'd2' },
    {
      label: '关闭全部',
      key: 'close-all',
      icon: () => h(NIcon, null, { default: () => h(TrashOutline) }),
    },
  ]
})

function openCtxMenu(tab: { path: string }, event: MouseEvent) {
  event.preventDefault()
  ctxPath.value = tab.path
  ctxX.value = event.clientX
  ctxY.value = event.clientY
  ctxShow.value = true
}

function closeCtxMenu() {
  ctxShow.value = false
}

/**
 * 关闭若干页签后确保有落脚点。
 *
 * `next` 是 store 给出的「应激活路径」；若为 undefined 说明当前激活页还在，
 * 只有它被关掉时才需要跳转。全部关完就回第一个业务页。
 */
function settle(next: string | undefined) {
  if (!next) return
  router.push(next)
}

function handleCtxSelect(key: string) {
  const path = ctxPath.value
  closeCtxMenu()

  switch (key) {
    case 'reload':
      // 刷新当前页：先确保停在目标页，再整页重载（最简单可靠，不引入 keep-alive 复杂度）
      if (route.path !== path) router.push(path)
      window.location.reload()
      return
    case 'close':
      settle(tabsStore.close(path))
      if (!tabsStore.tabs.length) router.push(firstLeafPath())
      return
    case 'close-others':
      tabsStore.closeOthers(path)
      if (route.path !== path) router.push(path)
      return
    case 'close-right':
      settle(tabsStore.closeRight(path))
      return
    case 'close-left':
      settle(tabsStore.closeLeft(path))
      return
    case 'close-all':
      tabsStore.closeAll()
      router.push(firstLeafPath())
  }
}

/** 页签也按模块着色 */
function tabAccent(path: string) {
  return accentOf(moduleKeyFromPath(path))
}

const themeOptions = [
  { label: '跟随系统', key: 'auto', icon: DesktopOutline },
  { label: '浅色', key: 'light', icon: SunnyOutline },
  { label: '深色', key: 'dark', icon: MoonOutline },
]

function handleThemeSelect(key: string) {
  appStore.setThemeMode(key as ThemeMode)
}

const currentThemeIcon = computed(
  () => themeOptions.find((o) => o.key === appStore.themeMode)?.icon ?? DesktopOutline,
)

const userOptions = [
  { label: '个人信息', key: 'profile' },
  { label: '退出登录', key: 'logout' },
]

async function handleUserSelect(key: string) {
  if (key === 'logout') {
    // 真的登出：清本地凭证并回登录页（守卫会把没身份的人拦在那儿）。
    // 后端 /system/auth/logout 未实现也不影响登出 —— 不能因为接口挂了就登不出去。
    await authStore.logout()
    message.success('已退出登录')
    router.replace({ name: 'login' })
  } else {
    message.info('个人信息页面待开发（后端 /system/auth/profile 尚未实现）')
  }
}

const pageTitle = computed(() => route.meta?.title as string | undefined)
const pageDesc = computed(() => route.meta?.desc as string | undefined)
const pageStatus = computed(() => route.meta?.status as string | undefined)
/*
 * 这里曾有 `parentTitle`（取面包屑链倒数第二段的标题，在页面标题上方渲染成
 * 「服务器管理 / 委托服务器」）。已删除，原因：
 * 顶栏的面包屑已经把同一条路径完整显示了一次，标题区再画一遍属于纯重复。
 * 现在标题区只保留「页面标题」与「页面说明」两件事。
 */
</script>

<template>
  <n-layout has-sider position="absolute">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed-width="64"
      :width="248"
      :collapsed="appStore.sidebarCollapsed"
      show-trigger
      class="app-sider"
      @collapse="appStore.sidebarCollapsed = true"
      @expand="appStore.sidebarCollapsed = false"
    >
      <div class="logo">
        <div class="logo-mark">云</div>
        <div v-show="!appStore.sidebarCollapsed" class="logo-text">
          <div class="logo-title">云条件单</div>
          <div class="logo-sub">后台管理系统</div>
        </div>
      </div>

      <n-menu
        :value="activeMenuKey"
        :options="menuOptions"
        :collapsed="appStore.sidebarCollapsed"
        :collapsed-width="64"
        :collapsed-icon-size="18"
        :indent="18"
        :expanded-keys="expandedKeys"
        @update:expanded-keys="(keys: string[]) => (expandedKeys = keys)"
        @update:value="handleMenuSelect"
      />
    </n-layout-sider>

    <n-layout class="main-col">
      <n-layout-header bordered class="header">
        <div class="header-left">
          <n-button quaternary circle size="small" @click="appStore.toggleSidebar()">
            <template #icon>
              <n-icon
                :component="appStore.sidebarCollapsed ? ChevronForwardOutline : ChevronBackOutline"
              />
            </template>
          </n-button>

          <n-breadcrumb>
            <n-breadcrumb-item v-for="node in breadcrumb" :key="node.name">
              {{ node.meta.title }}
            </n-breadcrumb-item>
          </n-breadcrumb>
        </div>

        <div class="header-right">
          <!-- 只在「未完成」时才显示状态标签。
               vueuc 要求 NTooltip 的 trigger 槽恰好有一个子节点，
               若标签被 v-if 全部挡掉就会抛错，所以整个 tooltip 一起判断。 -->
          <n-tooltip v-if="pageStatus && pageStatus !== 'ready'" trigger="hover">
            <template #trigger>
              <n-tag v-if="pageStatus === 'planned'" size="small" type="warning" round>
                后端待实现
              </n-tag>
              <n-tag v-else size="small" type="info" round> 页面待开发 </n-tag>
            </template>
            该页面的功能尚未开发完成
          </n-tooltip>

          <n-dropdown :options="themeOptions" @select="handleThemeSelect">
            <n-button quaternary circle size="small">
              <template #icon>
                <n-icon :component="currentThemeIcon" />
              </template>
            </n-button>
          </n-dropdown>

          <n-dropdown :options="userOptions" @select="handleUserSelect">
            <n-button quaternary size="small" class="user-btn">
              <template #icon>
                <n-icon :component="PersonCircleOutline" />
              </template>
              {{ authStore.displayName }}
              <!-- 显示角色而不是「演示身份」：现在是真的登录过了，
                   角色名能解释「为什么有些按钮是灰的」 -->
              <n-tag
                v-if="authStore.roleNames.length"
                size="tiny"
                round
                :bordered="false"
                type="info"
                style="margin-left: 6px"
              >
                {{ authStore.roleNames.join(' / ') }}
              </n-tag>
            </n-button>
          </n-dropdown>
        </div>
      </n-layout-header>

      <!-- 多页签栏 -->
      <div v-if="tabsStore.tabs.length" class="tabs-bar">
        <div
          v-for="tab in tabsStore.tabs"
          :key="tab.path"
          class="tab-item"
          :class="{ active: tab.path === route.path }"
          :style="{ '--tab-accent': tabAccent(tab.path) }"
          :title="`${tab.title}（右键查看更多操作）`"
          @click="router.push(tab.path)"
          @contextmenu="openCtxMenu(tab, $event)"
        >
          <span class="tab-dot" />
          <span class="tab-text">{{ tab.title }}</span>
          <n-icon class="tab-close" :component="CloseOutline" @click.stop="closeTab(tab.path)" />
        </div>
      </div>

      <!--
        页签右键菜单。trigger="manual" + x/y：自己不劫持浏览器右键菜单，
        由页签上的 @contextmenu 打开，所以不会和原生菜单打架。
        `:show` 受控 + `@clickoutside` 保证点别处能关掉。
      -->
      <n-dropdown
        trigger="manual"
        placement="bottom-start"
        size="small"
        :show="ctxShow"
        :x="ctxX"
        :y="ctxY"
        :options="ctxOptions"
        @select="handleCtxSelect"
        @clickoutside="closeCtxMenu"
      />

      <n-layout-content class="content" :native-scrollbar="false">
        <div class="page-container">
          <!-- 页面标题栏：模块色竖条 + 标题 + 说明 -->
          <div v-if="pageDesc || pageTitle" class="page-head" :style="{ '--accent': accent }">
            <div class="page-head-bar" />
            <div class="page-head-text">
              <h2 class="page-title">{{ pageTitle }}</h2>
              <p v-if="pageDesc" class="page-desc">{{ pageDesc }}</p>
            </div>
          </div>

          <router-view />
        </div>
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<style scoped>
/* --------------------------------- 侧边栏 -------------------------------- */

.app-sider :deep(.n-layout-sider-scroll-container) {
  background: var(--bg-sider);
}

.logo {
  display: flex;
  align-items: center;
  gap: 11px;
  height: var(--header-height);
  padding: 0 18px;
  overflow: hidden;
  border-bottom: 1px solid var(--border);
}

.logo-mark {
  flex: none;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #3b6ff5, #7c5cf0);
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 0.5px;
  box-shadow: 0 3px 10px rgba(59, 111, 245, 0.35);
}

.logo-text {
  min-width: 0;
  white-space: nowrap;
}

.logo-title {
  font-size: 15px;
  font-weight: 650;
  line-height: 1.25;
  color: var(--text-primary);
}

.logo-sub {
  font-size: 11px;
  line-height: 1.4;
  color: var(--text-muted);
  letter-spacing: 1.5px;
}

/* 一级菜单图标：圆角色块 + 模块色 */
.app-sider :deep(.menu-icon) {
  display: inline-grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 7px;
  margin-right: 2px;
}

/* 选中项：左侧模块色指示条 */
.app-sider :deep(.n-menu-item-content) {
  border-radius: 8px;
}

.app-sider :deep(.n-menu-item-content--selected::before) {
  border-radius: 8px;
}

.app-sider :deep(.n-menu-item-content--selected) {
  font-weight: 600;
}

/* --------------------------------- 顶栏 --------------------------------- */

.header {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 20px;
  background: var(--bg-header);
  backdrop-filter: blur(12px);
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.user-btn {
  font-weight: 500;
}

/* --------------------------------- 页签 --------------------------------- */

.tabs-bar {
  display: flex;
  flex: none;
  gap: 8px;
  padding: 8px 20px;
  overflow-x: auto;
  background: var(--bg-sider);
  border-bottom: 1px solid var(--border);
}

.tab-item {
  display: flex;
  flex: none;
  align-items: center;
  gap: 7px;
  max-width: 200px;
  padding: 5px 10px;
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  color: var(--text-secondary);
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  transition: all 0.16s ease;
  user-select: none;
}

/*
 * 页签多了会一路撑开，所以限宽 + 省略号。
 * 截断的是标题而不是整个页签：关闭按钮必须始终可见可用。
 */
.tab-text {
  overflow: hidden;
  text-overflow: ellipsis;
}

.tab-item:hover {
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.tab-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--tab-accent, var(--brand));
  opacity: 0.45;
  transition: opacity 0.16s ease;
}

.tab-item.active {
  color: var(--tab-accent, var(--brand));
  font-weight: 600;
  border-color: var(--tab-accent, var(--brand));
  background: color-mix(in srgb, var(--tab-accent, var(--brand)) 9%, var(--bg-surface));
}

.tab-item.active .tab-dot {
  opacity: 1;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--tab-accent, var(--brand)) 20%, transparent);
}

.tab-close {
  flex: none;
  font-size: 13px;
  border-radius: 4px;
  opacity: 0.5;
}

.tab-close:hover {
  opacity: 1;
  background: var(--bg-hover);
}

/* --------------------------------- 内容区 -------------------------------- */

/*
 * 用 flex 列布局分配高度：顶栏与页签栏固定，内容区吃掉剩余空间。
 * 不写死 calc(100vh - Npx)，页签栏出现/消失时高度自动适应。
 */
.main-col {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.content {
  flex: 1;
  min-height: 0;
  background: var(--bg-body);
}

/* 页面标题栏 */
.page-head {
  display: flex;
  gap: 13px;
  align-items: stretch;
  margin-bottom: 18px;
}

.page-head-bar {
  flex: none;
  width: 4px;
  border-radius: 3px;
  background: linear-gradient(
    180deg,
    var(--accent, var(--brand)),
    color-mix(in srgb, var(--accent, var(--brand)) 45%, transparent)
  );
}

.page-head-text {
  min-width: 0;
}

/* 标题上方原来还有一行 `父级 / 标题` 的浅灰路径，与顶栏面包屑重复，已移除。
   原 `.page-parent` / `.page-parent::after` / `.page-head-top` 三条样式随之删除。 */

.page-title {
  margin: 0;
  font-size: 19px;
  font-weight: 650;
  letter-spacing: 0.2px;
}

.page-desc {
  margin: 5px 0 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
}
</style>
