<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { NCard, NEmpty, NIcon, NTag } from 'naive-ui'
import {
  CheckmarkCircleOutline,
  ConstructOutline,
  CubeOutline,
  PencilOutline,
} from '@vicons/ionicons5'

import type { MenuRefItem } from '@/config/menu'
import { accentOf, moduleKeyFromPath, tintOf } from '@/theme/palette'

/**
 * 页面占位组件。
 *
 * 「先上菜单、后补页面」的过渡手段：把该页面**真实的数据来源**和
 * 待办清单直接渲染出来，接手的人不需要再去翻文档或猜。
 *
 * 默认从当前路由的 meta 读取（meta 由 `src/config/menu.ts` 注入），
 * 也可以通过 props 覆盖。
 */
const props = defineProps<{
  refs?: MenuRefItem[]
  todos?: string[]
  hint?: string
}>()

const route = useRoute()

const refs = computed(() => props.refs ?? (route.meta?.refs as MenuRefItem[] | undefined))
const todos = computed(() => props.todos ?? (route.meta?.todos as string[] | undefined))
const hasInfo = computed(() => Boolean(refs.value?.length || todos.value?.length))

const moduleKey = computed(() => moduleKeyFromPath(route.path) ?? 'dashboard')
const accent = computed(() => accentOf(moduleKey.value))

/**
 * 给每条数据来源一个类型标签。
 *
 * 只按 **label** 判断，不看 value —— 否则像「DB 分布」这种
 * 值里带 “破坏测试流” 的条目会被误判成 Stream。
 * 判断不出来就返回 undefined（不显示标签），而不是硬贴一个「其他」。
 */
const KIND_RULES: { match: RegExp; label: string }[] = [
  { match: /Pub\/Sub|频道/, label: 'Pub/Sub' },
  { match: /Stream|流|消费组/, label: 'Stream' },
  { match: /MySQL|表清单|表结构/, label: 'MySQL' },
  { match: /JSON/, label: 'JSON' },
  { match: /实例|DB/, label: 'Redis' },
  { match: /协议|文档/, label: '文档' },
  { match: /工具|统计|口径|指标/, label: '工具' },
  { match: /字段/, label: '字段' },
]

function kindOf(label: string): string | undefined {
  return KIND_RULES.find((k) => k.match.test(label))?.label
}

const KIND_COLOR: Record<string, string> = {
  MySQL: '#0d9c9c',
  'Pub/Sub': '#dd7a15',
  Stream: '#7c5cf0',
  Redis: '#d6455e',
  JSON: '#d94f8a',
  文档: '#5b6b8c',
  工具: '#2f9e5f',
  字段: '#3b6ff5',
}

/** 无法归类时用模块主色，而不是灰色 */
function itemColor(label: string) {
  const kind = kindOf(label)
  return kind ? KIND_COLOR[kind] : accent.value
}

const statusMeta = computed(() => {
  const status = route.meta?.status as string | undefined
  if (status === 'planned') {
    return { text: '后端接口待实现', type: 'warning' as const, icon: ConstructOutline }
  }
  return { text: '页面待开发', type: 'info' as const, icon: PencilOutline }
})
</script>

<template>
  <div>
    <!-- 数据来源 -->
    <n-card v-if="refs?.length" class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="CubeOutline" :color="accent" :size="17" />
          数据来源
        </span>
      </template>
      <template #header-extra>
        <n-tag size="small" type="success" round :bordered="false">
          <template #icon>
            <n-icon :component="CheckmarkCircleOutline" />
          </template>
          现场实测确认
        </n-tag>
      </template>

      <div class="ref-grid">
        <div
          v-for="item in refs"
          :key="item.label + item.value"
          class="ref-item"
          :style="{ '--item-color': itemColor(item.label) }"
        >
          <div class="ref-head">
            <span v-if="kindOf(item.label)" class="ref-kind">{{ kindOf(item.label) }}</span>
            <span class="ref-label">{{ item.label }}</span>
          </div>
          <div class="ref-value mono">{{ item.value }}</div>
        </div>
      </div>
    </n-card>

    <!-- 待开发功能 -->
    <n-card v-if="todos?.length" class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="ConstructOutline" :color="accent" :size="17" />
          待开发功能
          <n-tag size="tiny" round :bordered="false" :style="{ background: tintOf(moduleKey, 0.12), color: accent }">
            {{ todos.length }} 项
          </n-tag>
        </span>
      </template>

      <ol class="todo-list" :style="{ '--accent': accent }">
        <li v-for="(todo, index) in todos" :key="todo">
          <span class="todo-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <span>{{ todo }}</span>
        </li>
      </ol>
    </n-card>

    <!-- 空状态 -->
    <n-card class="page-card" size="small" :bordered="false">
      <n-empty :description="statusMeta.text">
        <template #icon>
          <n-icon :component="statusMeta.icon" :color="accent" />
        </template>
        <template #extra>
          <div class="empty-hint">
            <p v-if="hint">{{ hint }}</p>
            <p v-else>
              本页组件已经建好，直接编辑对应文件即可开始开发（文件路径见
              <code>src/config/menu.ts</code> 中该菜单项的 <code>view</code> 字段）。
            </p>
            <p v-if="!hasInfo" class="text-muted">
              该页面的数据来源尚未确认，建议先用真实环境核对后再设计。
            </p>
          </div>
        </template>
      </n-empty>
    </n-card>
  </div>
</template>

<style scoped>
/* 数据来源：两列自适应网格卡片 */
.ref-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}

.ref-item {
  position: relative;
  padding: 12px 14px 12px 16px;
  overflow: hidden;
  background: var(--bg-body);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  transition:
    border-color 0.16s ease,
    transform 0.16s ease;
}

/* 左侧色条，颜色按数据类型区分 */
.ref-item::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--item-color);
}

.ref-item:hover {
  border-color: color-mix(in srgb, var(--item-color) 45%, var(--border));
  transform: translateY(-1px);
}

.ref-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
}

.ref-kind {
  padding: 1px 7px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: var(--item-color);
  background: color-mix(in srgb, var(--item-color) 12%, transparent);
  border-radius: 999px;
}

.ref-label {
  font-size: 12.5px;
  color: var(--text-muted);
}

.ref-value {
  color: var(--text-primary);
  line-height: 1.65;
  word-break: break-word;
}

/* 待办清单：编号徽章 + 悬停高亮 */
.todo-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}

.todo-list li {
  display: flex;
  gap: 11px;
  align-items: flex-start;
  padding: 9px 12px;
  font-size: 13.5px;
  line-height: 1.6;
  border-radius: var(--radius-sm);
  transition: background 0.16s ease;
}

.todo-list li:hover {
  background: var(--bg-hover);
}

.todo-index {
  flex: none;
  margin-top: 1px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  border-radius: 5px;
  padding: 2px 6px;
}

.empty-hint {
  max-width: 560px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.empty-hint code {
  padding: 1.5px 6px;
  border-radius: 5px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
}
</style>
