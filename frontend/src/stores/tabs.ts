import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export interface TabItem {
  /** 路由 name */
  name: string
  /** 完整路径 */
  path: string
  /** 页签标题 */
  title: string
}

const TABS_KEY = 'cca:tabs'

/**
 * 页签状态（多页签交互）。
 * 刻意不依赖 router：路由跳转由调用方（组件）负责，store 只维护数据。
 */
export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<TabItem[]>([])
  const activePath = ref('')

  const cachedNames = computed(() => tabs.value.map((t) => t.name))

  function persist() {
    sessionStorage.setItem(TABS_KEY, JSON.stringify(tabs.value))
  }

  function restore() {
    const raw = sessionStorage.getItem(TABS_KEY)
    if (!raw) return
    try {
      const parsed = JSON.parse(raw) as TabItem[]
      tabs.value = Array.isArray(parsed) ? parsed : []
    } catch {
      tabs.value = []
    }
  }

  /**
   * **建店即恢复**，不要等组件 `onMounted` 再调。
   *
   * 踩过的坑：`BasicLayout` 里有个 `immediate: true` 的 watcher 会在 setup 阶段
   * 就 `open()` 当前页签并**立即 persist**；而 `restore()` 原本写在 `onMounted` 里，
   * 那时 sessionStorage 早被那次 persist 覆盖成「只有当前这一个页签」了 ——
   * 于是 restore 读到的是自己的覆盖结果，页签永远恢复不出来（刷新即丢）。
   * 把恢复放在店铺创建时，就再也不可能被顺序问题搞错。
   */
  restore()

  /** 打开（或激活）一个页签。 */
  function open(tab: TabItem) {
    activePath.value = tab.path
    if (!tabs.value.some((t) => t.path === tab.path)) {
      tabs.value.push(tab)
      persist()
    }
  }

  /** 关闭页签，返回关闭后应当激活的路径（供调用方跳转）。 */
  function close(path: string): string | undefined {
    const index = tabs.value.findIndex((t) => t.path === path)
    if (index < 0) return undefined
    tabs.value.splice(index, 1)
    persist()
    if (activePath.value !== path) return undefined
    const next = tabs.value[index - 1] ?? tabs.value[index]
    return next?.path
  }

  function closeOthers(path: string) {
    tabs.value = tabs.value.filter((t) => t.path === path)
    activePath.value = path
    persist()
  }

  /** 关闭某个页签**右侧**的全部页签，返回当前应激活的路径 */
  function closeRight(path: string): string | undefined {
    const index = tabs.value.findIndex((t) => t.path === path)
    if (index < 0) return undefined
    tabs.value = tabs.value.slice(0, index + 1)
    persist()
    // 激活页若被关掉，就落到保留区最后一个（即 path 自己）
    if (!tabs.value.some((t) => t.path === activePath.value)) {
      activePath.value = path
      return path
    }
    return undefined
  }

  /** 关闭某个页签**左侧**的全部页签，返回当前应激活的路径 */
  function closeLeft(path: string): string | undefined {
    const index = tabs.value.findIndex((t) => t.path === path)
    if (index < 0) return undefined
    tabs.value = tabs.value.slice(index)
    persist()
    if (!tabs.value.some((t) => t.path === activePath.value)) {
      activePath.value = path
      return path
    }
    return undefined
  }

  function closeAll() {
    tabs.value = []
    activePath.value = ''
    persist()
  }

  /**
   * 剔除不满足条件的页签（权限变更后清理用）。
   *
   * 页签列表存在 sessionStorage 里、**跨登录保留**：换个权限更小的账号登录，
   * 上一任留下的页签仍会躺在页签栏里 —— 点得进去，然后被守卫弹回来，
   * 看起来像「点了没反应」。所以在权限集合变化时按当前可见菜单清一遍。
   *
   * @returns 被剔除的是否是当前激活页（调用方需要决定跳去哪）
   */
  function prune(keep: (tab: TabItem) => boolean): boolean {
    const next = tabs.value.filter(keep)
    if (next.length === tabs.value.length) return false

    const active = activePath.value
    tabs.value = next
    // 激活页被清掉时落到剩余页签的最后一个；一个都不剩就交给调用方跳转
    const activeRemoved = active !== '' && !next.some((t) => t.path === active)
    if (activeRemoved) activePath.value = next[next.length - 1]?.path ?? ''
    persist()
    return activeRemoved
  }

  return {
    tabs,
    activePath,
    cachedNames,
    restore,
    open,
    close,
    closeOthers,
    closeRight,
    closeLeft,
    closeAll,
    prune,
  }
})
