<script setup lang="ts">
import { computed, onMounted, onUnmounted, watchEffect } from 'vue'
import { RouterView } from 'vue-router'
import {
  NConfigProvider,
  NDialogProvider,
  NGlobalStyle,
  NLoadingBarProvider,
  NMessageProvider,
  NNotificationProvider,
  darkTheme,
  dateZhCN,
  zhCN,
  type GlobalThemeOverrides,
} from 'naive-ui'

import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

const theme = computed(() => (appStore.isDark ? darkTheme : null))

/**
 * 把主题状态同步到 <html class="dark">，
 * 这样 global.css 里的 CSS 变量才会跟着切换。
 */
watchEffect(() => {
  const root = document.documentElement
  root.classList.toggle('dark', appStore.isDark)
  root.style.colorScheme = appStore.isDark ? 'dark' : 'light'
})

/** 品牌主色 + 圆角，亮暗两套共用（暗色由 naive-ui 的暗色算法微调）。 */
const themeOverrides = computed<GlobalThemeOverrides>(() => {
  const isDark = appStore.isDark
  return {
    common: {
      primaryColor: isDark ? '#6d95ff' : '#3b6ff5',
      primaryColorHover: isDark ? '#85a8ff' : '#5583f7',
      primaryColorPressed: isDark ? '#5583f7' : '#2a5ae0',
      primaryColorSuppl: isDark ? '#85a8ff' : '#5583f7',
      successColor: isDark ? '#4cc07f' : '#2f9e5f',
      warningColor: isDark ? '#f0a03c' : '#dd7a15',
      errorColor: isDark ? '#f2687f' : '#d6455e',
      infoColor: isDark ? '#93a1bd' : '#5b6b8c',
      borderRadius: '8px',
      borderRadiusSmall: '6px',
      fontSize: '14px',
    },
    Card: {
      paddingMedium: '18px 20px',
      titleFontSizeMedium: '15px',
      titleFontWeight: '600',
    },
    DataTable: {
      thColor: isDark ? 'rgba(255,255,255,0.035)' : 'rgba(15,23,42,0.025)',
      thFontWeight: '600',
      thTextColor: 'var(--text-secondary)',
      tdTextColor: 'var(--text-primary)',
      borderColor: 'var(--border)',
    },
    Menu: {
      itemHeight: '42px',
      borderRadius: '8px',
    },
    Statistic: {
      labelFontSize: '13px',
      valueFontSize: '28px',
      valueFontWeight: '600',
    },
    Tag: {
      borderRadius: '999px',
    },
  }
})

// 跟随系统主题变化（仅在 auto 模式下生效）
let media: MediaQueryList | undefined
const onSystemThemeChange = (event: MediaQueryListEvent) => {
  appStore.applySystemTheme(event.matches)
}

onMounted(() => {
  appStore.initTheme()
  media = window.matchMedia('(prefers-color-scheme: dark)')
  media.addEventListener('change', onSystemThemeChange)
})

onUnmounted(() => {
  media?.removeEventListener('change', onSystemThemeChange)
})
</script>

<template>
  <n-config-provider
    :theme="theme"
    :theme-overrides="themeOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
    inline-theme-disabled
  >
    <n-global-style />
    <n-loading-bar-provider>
      <n-message-provider>
        <n-dialog-provider>
          <n-notification-provider>
            <router-view />
          </n-notification-provider>
        </n-dialog-provider>
      </n-message-provider>
    </n-loading-bar-provider>
  </n-config-provider>
</template>
