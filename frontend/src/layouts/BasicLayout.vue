<script setup lang="ts">
import { computed, h, nextTick, onMounted, ref, watch, type Component } from 'vue'
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

  /*
   * 首次进入也要滚一次。
   *
   * watch(route.path) 默认不 immediate，而刷新页面时路由**本来就没变**——
   * 页签是从 localStorage 恢复的，当前页签很可能排在靠后的位置，
   * 不滚的话打开就看不见自己在哪一页。
   *
   * 必须 nextTick：这里刚 open() 完，DOM 里的页签还没渲染出来。
   */
  void nextTick(() => scrollActiveTabIntoView())
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
  } else if (key === 'profile') {
    /*
     * 个人信息页已实现（后端 /system/auth/profile 与 /password 一直都在）。
     * 原来这里弹「待开发」—— 那条提示是过期的，且正好掩盖了
     * 「mustChangePassword 没有出口」这个问题。
     */
    router.push({ name: 'system-profile' })
  }
}

/**
 * 页签栏用滚轮横向滚动。
 *
 * <h2>为什么需要</h2>
 * 页签多了会横向溢出（`.tabs-bar` 有 `overflow-x: auto`），
 * 但浏览器里**普通滚轮只滚纵向**，横向得去拖那条细滚动条、或按 Shift+滚轮 ——
 * 都不顺手。把纵向增量转成横向滚动，符合 Chrome / VS Code 标签栏的习惯。
 *
 * <h2>三个边界条件，缺一个就会帮倒忙</h2>
 * <ol>
 *   <li><b>没溢出就别拦</b>：否则鼠标停在页签栏上时页面滚不动，像卡住了；</li>
 *   <li><b>触控板横向滑动要让开</b>：那种情况 `deltaX` 本来就大于 `deltaY`，
 *       浏览器原生就能横向滚，我们再处理会打架（速度翻倍、手感变怪）；</li>
 *   <li><b>必须能 preventDefault</b>：所以模板里用 `@wheel` 而不是 `.passive`，
 *       否则拦不住纵向滚动，页面会跟着一起滚。</li>
 * </ol>
 */
function onTabsWheel(e: WheelEvent) {
  const el = e.currentTarget as HTMLElement | null
  if (!el || el.scrollWidth <= el.clientWidth) {
    return
  }
  if (Math.abs(e.deltaX) >= Math.abs(e.deltaY)) {
    return
  }
  el.scrollLeft += e.deltaY
  e.preventDefault()
}

/**
 * 切换页签后，把当前页签滚进可视区。
 *
 * <h2>解决什么</h2>
 * 页签一多就会横向溢出，而新页签是**追加到最右**的 ——
 * 用户点了菜单，页签加出去了，可滚动位置还停在左边，等于"开了一个看不见的页签"，
 * 还得自己滚过去找。这个和新开的页签是在最左边还是最右边无关，统一都滚。
 *
 * <h2>两个细节</h2>
 * <ul>
 *   <li><b>已经可见就不动</b>：否则每次切页签都平滑滚一下，整条栏轻微抖动，
 *       比不滚更烦；</li>
 *   <li><b>用 scrollTo 手算位置而不是 scrollIntoView</b>：
 *       `scrollIntoView` 会向上遍历所有可滚祖先，可能把整个页面也带着滚一下。</li>
 * </ul>
 */
function scrollActiveTabIntoView() {
  const bar = document.querySelector('.tabs-bar') as HTMLElement | null
  const active = bar?.querySelector('.tab-item.active') as HTMLElement | null
  if (!bar || !active || bar.scrollWidth <= bar.clientWidth) {
    return
  }

  const barRect = bar.getBoundingClientRect()
  const activeRect = active.getBoundingClientRect()
  // 完整落在可视范围内 → 不需要动
  if (activeRect.left >= barRect.left && activeRect.right <= barRect.right) {
    return
  }

  // 居中放置：贴边滚动会让下一个页签也看不见，居中最稳
  const offset = activeRect.left - barRect.left
  bar.scrollTo({
    left: bar.scrollLeft + offset - (barRect.width - activeRect.width) / 2,
    behavior: 'smooth',
  })
}

watch(() => route.path, async () => {
  await nextTick()
  scrollActiveTabIntoView()
})

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

      <!-- 多页签栏（页签多了横向溢出，滚轮可横向滚动，见 onTabsWheel） -->
      <div v-if="tabsStore.tabs.length" class="tabs-bar" @wheel="onTabsWheel">
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

/*
 * 细滚动条：默认约 8~12px，会把页签栏撑高，看着像多了一条分隔条。
 *
 * ⚠️ 这里**不能**在 .tabs-bar 上写 `scrollbar-width: thin`：
 *    Chrome 121+ 起，元素一旦设置了标准属性 `scrollbar-width`/`scrollbar-color`，
 *    就会**整块忽略** `::-webkit-scrollbar-*` 伪元素 ——
 *    于是下面写 height:3px 等于白写，实际还是 thin 的 8px。
 *    （曾经就是这样：写了 4px 却一直显示 8px。）
 *
 * Firefox 不支持 webkit 伪元素，用 @supports 单独降级，两边互不干扰。
 */
.tabs-bar::-webkit-scrollbar {
  height: 3px;
}

.tabs-bar::-webkit-scrollbar-thumb {
  border-radius: 2px;
  /* 不支持 color-mix 的浏览器用这条 */
  background: var(--border);
  /* 半透明：比实色更轻，不抢页签的视觉重量 */
  background: color-mix(in srgb, var(--text-muted) 45%, transparent);
}

.tabs-bar::-webkit-scrollbar-thumb:hover {
  background: var(--text-muted);
}

.tabs-bar::-webkit-scrollbar-track {
  background: transparent;
}

/* 只在「不支持 ::-webkit-scrollbar」的浏览器（Firefox）生效 */
@supports not selector(::-webkit-scrollbar) {
  .tabs-bar {
    scrollbar-width: thin;
  }
}

.tab-item {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  max-width: 200px;
  /* 右内边距比左侧小 2px：关闭按钮自带 20px 盒，
     盒子边缘到页签边框的视觉留白已经足够，不必再叠一层 10px。 */
  padding: 5px 8px 5px 10px;
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

/*
 * 关闭按钮原来只有 13×13 的可点区域（图标多大、可点区就多大，没有 padding），
 * 必须精确点在那个小叉上，而且 opacity 0.5 让它很淡、更像装饰而不是按钮。
 * 现在给它 20×20 的固定盒 + 居中，图标略放大到 14px；
 * 页签右侧内边距同步从 10px 收到 8px、gap 7→6，保证整颗页签宽度基本不变。
 * （WCAG 2.2 的最低目标尺寸是 24×24，20px 已是这块紧凑布局里能给到的上限，
 *   再大页签会明显变胖。）
 */
.tab-close {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  font-size: 14px;
  border-radius: 5px;
  opacity: 0.55;
  transition:
    opacity 0.16s ease,
    background-color 0.16s ease;
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

/*
 * ⚠️ 这一条是「顶栏 + 页签栏固定」真正生效的关键，删了就退回滚动整页。
 *
 * `<n-layout class="main-col">` 会在内部**自己再套一层** `.n-layout-scroll-container`
 * （naive-ui Layout 的 render 固定结构），并把 header / tabs-bar / content 全部装进那一层；
 * 而 naive-ui 给这层写了 `overflow-x:hidden; height:100%`，浏览器据此把 `overflow-y`
 * 算成 `auto` —— 于是**滚动发生在这层容器上**，`.main-col` 上的 `flex-direction:column`
 * 完全够不到三个子元素，顶栏和页签栏就跟着一起被卷走了。
 * （实测：滚动 816px 后 `.header` 的 top 从 0 变成 -816。）
 *
 * 修法：让这层容器不滚（同时必须补 `flex-direction:column`，否则它自身的
 * `display:flex` 会把三个子元素横向排开），滚动交给下面的 `.content`。
 * 这样 flex 的「头部固定、内容吃剩余高度」才真正成立。
 *
 * ⚠️ 必须用 `:deep()`，不能直接写 `.main-col > .n-layout-scroll-container`。
 * 这层容器是 naive-ui 渲染的，**不带本组件的 `data-v-*` 作用域属性**
 * （实测该元素只有 `class`，没有任何 `data-v-*`），而 scoped 会把作用域属性
 * 追加到最后一个选择器片段上，直接写会编译成
 * `.main-col > .n-layout-scroll-container[data-v-xxx]` 而**静默失效**。
 * 这正是下面 .content 那类朴素选择器能生效、而外层必须下钻的原因。
 */
.main-col :deep(.n-layout-scroll-container) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/*
 * 内容区是唯一的滚动容器。
 * `min-height: 0` 必需：flex 子项默认 `min-height:auto` 不肯收缩到内容以下，
 * 只写 `flex:1` 会被内容顶高，滚动条就不出现在这里。
 */
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
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
