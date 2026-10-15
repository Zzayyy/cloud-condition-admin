<script setup lang="ts">
import { computed } from 'vue'
import { NModal } from 'naive-ui'

/**
 * 统一弹窗（居中模态框）。全站详情/表单从右侧抽屉改为居中弹窗后都走这里。
 *
 * ## 为什么要有这个组件
 *
 * 1. **宽度要跟着内容走，但不能溢出小屏。** 详情类 560 / 常规 720 / 带宽表 900，
 *    再宽就用 `calc(100vw - 48px)` 夹住 —— 1366×768 的运维机是常见场景。
 *
 * 2. **高度必须封顶，否则长内容会把按钮顶出屏幕。** `preset="card"` 的 modal 是
 *    `align-self:center; margin:auto`，高度跟着内容长。这里用 `max-height` 封顶，
 *    配合 naive-ui 的 `content-scrollable` 让**内容区成为唯一滚动容器**，
 *    标题与页脚保持固定（对齐抽屉原本的观感）。
 *
 *    > 已实测确认：`content-scrollable` 本身就能做到「头尾固定、只有内容滚」。
 *    > 它的根元素 `.n-scrollbar` 带 `overflow:hidden`，而 overflow 非 visible 会把
 *    > flex 项的自动最小尺寸变成 0，于是 `.n-card__content-scrollbar` 默认的
 *    > `flex: 0 1 auto` 就能正常收缩。**不需要**额外补 `flex` / `min-height`。
 *    > （这一点踩过坑：起初按常理补了 `flex:1;min-height:0`，既没必要，
 *    > 而且因为它写在 scoped 里根本作用不到 teleport 出去的卡片上 —— 见下条。）
 *
 * 3. **样式一律写在「自己模板渲染出来的元素」上，不依赖 `:deep()`。**
 *    弹窗是 **teleport 到 `body`** 的，卡片内部（`.n-card__footer` 等）由 naive-ui
 *    的 Card 渲染、且**祖先链上没有任何 `data-v-*` 属性**（实测 `scopeAttrsOnCard: []`），
 *    所以 scoped 里的 `:deep(.n-card__footer)` 会**静默失效**（不报错、也不生效）。
 *    因此页脚的对齐与分隔线都画在下面那个 `.app-dialog__footer` 上 ——
 *    它是本组件模板自己的元素，带作用域属性，普通 scoped 选择器就能命中。
 *
 * 4. **遮罩关闭要区分「看」和「填」。** 详情可以点遮罩就走；表单不行，
 *    填写中误点一下就全没了。默认 `false`（安全的一侧），详情显式传 `true`。
 *
 * ## 用法
 *
 * ```vue
 * <AppDialog v-model:show="showDetail" :title="`节点详情 · ${active?.ip}`"
 *            :width="560" mask-closable>
 *   <template v-if="active"> ... </template>
 *   <template #footer>
 *     <div class="spacer" />
 *     <n-button size="small" @click="showDetail = false">关闭</n-button>
 *   </template>
 * </AppDialog>
 * ```
 *
 * `#footer` 里的内容会被排成**一行、右对齐**（对齐抽屉原来的 footer 观感），
 * 想把一组按钮推到左边就在中间放一个 `<div class="spacer" />`
 * （`.spacer { flex: 1 }` 各页已有）。
 */
const props = withDefaults(
  defineProps<{
    show: boolean
    title?: string
    /** 期望宽度（px）；实际再被 `calc(100vw - 48px)` 夹一次，小屏不溢出。 */
    width?: number
    /** 点遮罩是否关闭。详情类传 true；表单类保持默认 false，避免误关丢输入。 */
    maskClosable?: boolean
    /** Esc 是否关闭。表单类建议传 false，理由同 maskClosable。 */
    closeOnEsc?: boolean
    closable?: boolean
  }>(),
  {
    title: '',
    width: 560,
    maskClosable: false,
    closeOnEsc: true,
    closable: true,
  },
)

const emit = defineEmits<{
  'update:show': [value: boolean]
}>()

function handleUpdateShow(value: boolean) {
  emit('update:show', value)
}

/**
 * 这里**不需要**再接 `on-close` 去发 `update:show`。
 *
 * 右上角的 X 最终走到 `NModal` 内部的 `handleCloseClick`，它自己就会
 * `doUpdateShow(false)`（即 emit 一次 `update:show`）。再补一个 on-close 只会
 * 重复 emit 同一个事件。（这点与 `n-drawer` 不同，drawer 的关闭按钮是自己 emit 的。）
 */

const cardStyle = computed(() => ({
  width: `min(${props.width}px, calc(100vw - 48px))`,
  maxHeight: 'calc(100vh - 96px)',
}))
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    class="app-dialog-card"
    :style="cardStyle"
    :title="title"
    :closable="closable"
    :mask-closable="maskClosable"
    :close-on-esc="closeOnEsc"
    content-scrollable
    @update:show="handleUpdateShow"
  >
    <slot />

    <template v-if="$slots.footer" #footer>
      <div class="app-dialog__footer">
        <slot name="footer" />
      </div>
    </template>

    <template v-if="$slots['header-extra']" #header-extra>
      <slot name="header-extra" />
    </template>
  </n-modal>
</template>

<style scoped>
/*
 * 页脚：一行、右对齐，并补一条上分隔线。
 *
 * 为什么三项都落在这个 div 上，而不是 naive-ui 的 `.n-card__footer`：
 * 见组件头注释第 3 条 —— 弹窗 teleport 到 body 之后，scoped 的 `:deep()`
 * 命中不了卡片内部（祖先链上没有 data-v 属性，规则会静默失效）。
 * 这个 div 是本模板自己的元素，所以普通 scoped 选择器一定生效。
 *
 * 各页的 `#footer` 里仍可放 `<div class="spacer" />`（`flex:1`）把按钮分组推开。
 */
.app-dialog__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

/* 页脚为空时不画分隔线（避免只有一条线没有内容）。 */
.app-dialog__footer:empty {
  display: none;
}
</style>

<!--
  非 scoped 块，只此一处，专门用来改**卡片自身**的阴影。

  为什么不能放进上面的 scoped 块：卡片根元素由 naive-ui 渲染并 teleport 到 body，
  `:deep()` 无法命中（同上）。而 global.css 里有一条
  `.n-card { box-shadow: var(--shadow-sm) !important }` 用来统一页面卡片质感 ——
  它带 `!important`，会盖掉 modal 自己的 `--n-box-shadow`，让弹窗只有卡片级的浅阴影，
  和背后的遮罩叠在一起分不出层次。

  用独占的 `.app-dialog-card` 类限定作用范围（class 透传到卡片根元素上），
  不改其它 `.n-card`，也不使用项目里明令禁止的 `:global()`。
-->
<style>
.n-card.app-dialog-card {
  box-shadow: var(--shadow-lg) !important;
}

/* 标题略收紧：Card 的 paddingMedium 四面 18px，用在弹窗标题上偏高。 */
.n-card.app-dialog-card > .n-card-header {
  padding-bottom: 12px;
}
</style>
