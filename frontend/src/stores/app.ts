import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'auto'

const THEME_KEY = 'cca:theme'
const COLLAPSED_KEY = 'cca:sidebar-collapsed'
const SYSTEM_DARK = () => window.matchMedia('(prefers-color-scheme: dark)').matches

/**
 * 全局界面状态：主题、侧边栏折叠、多页签。
 * 只放真正需要跨组件共享的东西，页面级数据一律留在页面自己那里。
 */
export const useAppStore = defineStore('app', () => {
  const themeMode = ref<ThemeMode>((localStorage.getItem(THEME_KEY) as ThemeMode) || 'auto')
  const systemDark = ref(false)
  const sidebarCollapsed = ref(localStorage.getItem(COLLAPSED_KEY) === '1')

  const isDark = computed(() =>
    themeMode.value === 'auto' ? systemDark.value : themeMode.value === 'dark',
  )

  function initTheme() {
    systemDark.value = SYSTEM_DARK()
  }

  /** 系统主题变化时的回调（仅在 auto 模式下影响显示） */
  function applySystemTheme(dark: boolean) {
    systemDark.value = dark
  }

  function setThemeMode(mode: ThemeMode) {
    themeMode.value = mode
    localStorage.setItem(THEME_KEY, mode)
  }

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem(COLLAPSED_KEY, sidebarCollapsed.value ? '1' : '0')
  }

  return {
    themeMode,
    isDark,
    sidebarCollapsed,
    initTheme,
    applySystemTheme,
    setThemeMode,
    toggleSidebar,
  }
})
