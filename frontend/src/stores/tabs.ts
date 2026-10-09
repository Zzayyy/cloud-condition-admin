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

  function closeAll() {
    tabs.value = []
    activePath.value = ''
    persist()
  }

  return { tabs, activePath, cachedNames, restore, open, close, closeOthers, closeAll }
})
