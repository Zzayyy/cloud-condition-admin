<script setup lang="ts">
import { NIcon } from 'naive-ui'
import type { Component } from 'vue'

/**
 * 指标卡。总览页与各业务页共用，保证视觉一致。
 * 颜色由调用方按模块/语义传入。
 */
withDefaults(
  defineProps<{
    label: string
    value: string | number
    suffix?: string
    icon?: Component
    color?: string
    hint?: string
    /** 次要指标：值小一号、无图标底色 */
    compact?: boolean
  }>(),
  { color: '#3b6ff5' },
)
</script>

<template>
  <div class="stat-card" :class="{ compact }" :style="{ '--c': color }">
    <div v-if="icon" class="stat-icon">
      <n-icon :component="icon" :size="compact ? 17 : 20" />
    </div>
    <div class="stat-body">
      <div class="stat-label">{{ label }}</div>
      <div class="stat-value">
        {{ value }}
        <span v-if="suffix" class="stat-suffix">{{ suffix }}</span>
      </div>
      <div v-if="hint" class="stat-hint">{{ hint }}</div>
    </div>
    <slot />
  </div>
</template>

<style scoped>
/*
 * 指标卡在栅格单元里一律撑满高度。
 *
 * 为什么放在组件内部而不是各页面各写一条：
 * 各卡片的 hint 文字长短不一（1~3 行），内容高度天然不同，
 * 同一排并排时下沿会参差不齐（实测过 149/149/132/132）。
 * 而 `<n-gi>` 渲染出来是**无类名的 div**，各页面只能靠
 * `.n-grid :deep(> div > .stat-card)` 这种脆弱选择器命中 ——
 * 此前 DashboardView 与 DatahubNode 两处写法就已不一致。
 * 写在组件自己的根元素上，任何容器都不用再管。
 *
 * `height:100%` 在**没有**定高的父容器里会退化成 auto（CSS 规范行为），
 * 所以不会把卡片意外拉高；只有栅格单元这种等高容器里才真正生效。
 */
.stat-card {
  height: 100%;
  box-sizing: border-box;
  position: relative;
  display: flex;
  gap: 14px;
  padding: 18px 20px;
  overflow: hidden;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    border-color 0.18s ease;
}

.stat-card::after {
  content: '';
  position: absolute;
  inset: auto -30px -40px auto;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: var(--c);
  opacity: var(--stat-glow);
  pointer-events: none;
}

.stat-card:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--c) 40%, var(--border));
  box-shadow: var(--shadow-md);
}

.stat-card.compact {
  padding: 14px 16px;
  gap: 11px;
}

.stat-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 11px;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 13%, transparent);
}

.compact .stat-icon {
  width: 36px;
  height: 36px;
  border-radius: 9px;
}

.stat-body {
  min-width: 0;
}

.stat-label {
  font-size: 12.5px;
  color: var(--text-muted);
}

.stat-value {
  margin-top: 3px;
  font-size: 27px;
  font-weight: 650;
  line-height: 1.15;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.compact .stat-value {
  font-size: 21px;
}

.stat-suffix {
  margin-left: 3px;
  font-size: 13px;
  font-weight: 400;
  color: var(--text-muted);
}

.stat-hint {
  margin-top: 5px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-muted);
}
</style>
