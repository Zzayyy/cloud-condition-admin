<script setup lang="ts">
import type { Component } from 'vue'
import { NButton, NIcon, NInput, NTooltip } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'

/**
 * 带「可点搜索图标」的搜索框。
 *
 * <h2>为什么要有这个组件</h2>
 * 全站有 20 多个搜索框，此前**一个搜索按钮都没有** —— 只能靠回车触发。
 * 问题是界面上没有任何提示说「这里要按回车」，鼠标用户填完就不知道下一步该干嘛。
 *
 * <h2>为什么把图标放在框内（suffix），而不是在旁边加一个独立按钮</h2>
 * 工具栏本来就挤（有的页面已经有 6 个控件：搜索框 + 多个下拉 + 按钮组 + 重置 + 刷新）。
 * 再加一个独立「搜索」按钮会：
 * <ol>
 *   <li>多占约 70px 横向空间，窄屏下换行更严重；</li>
 *   <li>和已有的「刷新」按钮语义重叠 —— 用户会犹豫「改了条件该点搜索还是刷新」。</li>
 * </ol>
 * 放进框内既不多占空间，又把「这里可点」表达得很直接。**回车照常可用**。
 *
 * <h2>⚠️ 放大镜只出现一次，而且就是那个按钮</h2>
 * 原先各页面在框内左侧放了一个**装饰性**的放大镜（`#prefix`，不可点）。
 * 若右侧再加一个可点的，同一个框里就有两个放大镜 ——
 * 用户不知道该点哪个，而且其中一个点了没反应，更像坏了。
 *
 * <p>所以本组件的默认行为是：**左边不再放放大镜，右边那个可点的就是唯一的放大镜**。
 * 左图标只在它表达的是「这个字段是什么」（而不是「这是搜索」）时才传 ——
 * 例如「来源 IP」用定位图标、账号用人物图标，那是字段语义，与动作图标不重复。
 *
 * <h2>与「输入即时过滤」的区别</h2>
 * 本组件用于**后端分页查询**的搜索框：必须由用户显式确认再查，
 * 否则每敲一个字发一次请求、翻页状态还会乱。
 * 纯前端本地过滤的页面（角色与权限、数据库管理）**不用**这个组件 ——
 * 那里输入即时生效才是对的。
 *
 * <h2>用法</h2>
 * <pre>
 *   &lt;SearchInput v-model:value="keyword" placeholder="搜索账号 / 户名"
 *                 :loading="loading" style="width: 220px" @search="handleSearch" /&gt;
 *   &lt;!-- 字段语义明确的用 prefix-icon 保留左图标 --&gt;
 *   &lt;SearchInput v-model:value="fromIp" placeholder="来源 IP" :prefix-icon="LocationOutline" … /&gt;
 * </pre>
 *
 * 除 value / loading / title / prefixIcon 外的属性（placeholder、size、style…）
 * 都透传给内层 `n-input`；`@clear` 之类的事件也一样。
 */

const props = withDefaults(
  defineProps<{
    /** 输入值（配 v-model:value 用） */
    value?: string
    /** 是否正在查询 —— 按钮显示 loading，避免连点 */
    loading?: boolean
    /** 按钮提示文案 */
    title?: string
    /**
     * 左侧图标（可选）。**只在它表达「字段是什么」时才传** ——
     * 若只是装饰性的放大镜，不要传（否则和右侧的按钮重复）。
     */
    prefixIcon?: Component
  }>(),
  { value: '', loading: false, title: '搜索（也可直接按回车）', prefixIcon: undefined },
)

const emit = defineEmits<{
  (e: 'search'): void
  (e: 'update:value', v: string): void
}>()

/** 触发搜索（点图标，或按回车） */
function doSearch() {
  emit('search')
}
</script>

<template>
  <!-- 除本组件声明的 props 外，其余属性（placeholder、size、style…）透传给 n-input -->
  <n-input
    v-bind="$attrs"
    :value="props.value"
    clearable
    @keyup.enter="doSearch"
    @update:value="(v: string) => emit('update:value', v)"
  >
    <!-- 左图标只在「表达字段语义」时出现，避免与右侧可点的放大镜重复 -->
    <template v-if="props.prefixIcon" #prefix>
      <n-icon :component="props.prefixIcon" />
    </template>

    <!--
      唯一的那颗放大镜，而且可点。
      ⚠️ text 形态：嵌在输入框右侧，默认的边框/背景会和输入框边框打架。
      ⚠️ :focusable="false"：点完不抢焦点，避免输入框失焦后光标位置丢失。
    -->
    <template #suffix>
      <n-tooltip trigger="hover" :delay="300">
        <template #trigger>
          <n-button
            text
            :loading="props.loading"
            :focusable="false"
            style="padding: 0 2px"
            @click="doSearch"
          >
            <template #icon>
              <n-icon :component="SearchOutline" />
            </template>
          </n-button>
        </template>
        {{ props.title }}
      </n-tooltip>
    </template>
  </n-input>
</template>
