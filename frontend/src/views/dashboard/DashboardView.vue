<script setup lang="ts">
import { computed, h, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NGi,
  NGrid,
  NIcon,
  NProgress,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import {
  AnalyticsOutline,
  CheckmarkCircleOutline,
  CloudOutline,
  GitNetworkOutline,
  InformationCircleOutline,
  LayersOutline,
  ServerOutline,
  SwapHorizontalOutline,
  TrendingUpOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { dashboardApi } from '@/api/endpoints'
import { mockDashboardApi } from '@/api/mock/dashboard'
import { withDelay } from '@/api/mock/_shared'
import type { DashboardSourceRow } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { thousands } from '@/utils/format'

/**
 * 总览页。
 *
 * 与其余各页一致，数据走 `useResource`（真实接口优先、演示数据兜底），
 * **不再把数字写死在组件里** —— 写死会导致菜单/数据一变页面就与真实情况脱节。
 *
 * 本页刻意回答「单页看不到的问题」：三类节点的健康度、跨表漏斗
 * （条件单 → 委托 → 成交）、以及消息总线里最危险的「无消费组的流」。
 * 这些都是分散在四个业务页里、需要来回翻才能拼出来的信息。
 */

const accent = '#3b6ff5'
const router = useRouter()

const { data, loading, error, load } = useResource(
  () => dashboardApi.getOverview(),
  { mock: () => withDelay(mockDashboardApi.getOverview()) },
)

function reload() {
  load(undefined as never)
}

onMounted(reload)

const summary = computed(() => data.value?.summary)
const sources = computed<DashboardSourceRow[]>(() => data.value?.sources ?? [])
const daily = computed(() => data.value?.daily ?? [])

/* ---------------------------- 云单账号在线情况 ---------------------------- */

/**
 * 账号四个口径。
 *
 * <p>⚠️ **「离线」不能直接用 `total - online`**（原实现就是这么写的）：
 * `total` 是 MySQL 与 Redis 合并后的账号数，其中 {@code mysqlOnly} 那部分
 * 在 Redis 里**没有实时对象**（连 `acc-*` 键都没有），谈不上一线还是离线。
 *
 * <p>把它们算成「离线」有两个后果：离线数虚高；且与总览页别处
 * 「未知 ≠ 离线」的原则自相矛盾（数据中台、两类服务器都是三态口径）。
 *
 * <p>所以这里：`离线 = total − online − mysqlOnly`，只算「有实时对象但没在线」的。
 */
const accountTotal = computed(() => summary.value?.account.total ?? 0)
const accountOnline = computed(() => summary.value?.account.online ?? 0)
const accountOffline = computed(() => {
  const s = summary.value?.account
  if (!s) return 0
  return Math.max(0, s.total - s.online - s.mysqlOnly)
})

/** 在线率；总数为 0 时返回 null（不显示 0%，那会被读成「全离线」） */
const accountOnlineRate = computed<number | null>(() => {
  const t = accountTotal.value
  return t > 0 ? Math.round((accountOnline.value / t) * 100) : null
})

/**
 * 两边数据源不一致的提示；都为 0 时返回 null（不占位置）。
 *
 * <p>这是后端 `AccountSummary` 里**最该被看见**的两个字段 ——
 * 它们解释了「总览的数字与账号页为什么可能对不上」。
 * 后端一直在算，前端却从没显示过。
 */
const accountSourceGap = computed(() => {
  const s = summary.value?.account
  if (!s) return null
  const parts: string[] = []
  if (s.mysqlOnly > 0) {
    parts.push(`${s.mysqlOnly} 个账号只在 MySQL、Redis 里没有实时对象（无法判断在线状态）`)
  }
  if (s.redisOnly > 0) {
    parts.push(`${s.redisOnly} 个账号只在 Redis、MySQL 里没有记录`)
  }
  return parts.length ? parts.join('；') : null
})

/* ------------------------------ 节点健康度 ------------------------------ */

/**
 * ⚠️ 三类节点**不能简单相加**成一个「在线/离线」。
 *
 * 三类「离线」的含义完全不同：
 *  - 委托服务器：真有活的（现场 1/13）；
 *  - 策略服务器：**服务未开放**，离线是必然（现场 0/2）；
 *  - 数据中台：**观测不到**（未知），不等于离线（现场 1 在线 / 1 未知）。
 *
 * 合并成「在线 1/15」会让人以为大面积宕机，而实际原因完全不同。
 * 所以这里只给「可判定的在线数」，并把三类分开列在卡片下方。
 */
const nodeTotal = computed(() => {
  const n = summary.value?.nodes
  if (!n) return 0
  return n.tradeTotal + n.strategyTotal + n.datahubTotal
})

const nodeOnline = computed(() => {
  const n = summary.value?.nodes
  if (!n) return 0
  return n.tradeOnline + n.strategyOnline + n.datahubOnline
})

/** 状态**未知**的节点数（目前只有数据中台会出现） */
const nodeUnknown = computed(() => summary.value?.nodes.datahubUnknown ?? 0)

/** 三类节点的在线情况，用于卡片下方的明细行（含各自的「离线含义」提示） */
const nodeBreakdown = computed(() => {
  const n = summary.value?.nodes
  if (!n) return []
  return [
    {
      label: '委托服务器',
      online: n.tradeOnline,
      total: n.tradeTotal,
      color: '#0d9c9c',
      note: '心跳 30 秒内视为在线',
    },
    {
      label: '策略服务器',
      online: n.strategyOnline,
      total: n.strategyTotal,
      color: '#7c5cf0',
      note: n.strategyRegistryStale ? '⚠️ 该服务未开放，离线是必然' : '心跳 30 秒内视为在线',
    },
    {
      label: '数据中台',
      online: n.datahubOnline,
      total: n.datahubTotal,
      color: '#dd7a15',
      note: nodeUnknown.value > 0 ? `另有 ${nodeUnknown.value} 个状态未知（观测不到，非离线）` : '按 Redis 实例统计',
    },
  ]
})

/** 把秒数说成人话（与后端 idleReason 里的写法一致） */
function humanAge(sec: number | null | undefined): string {
  if (sec == null) return '-'
  if (sec >= 86400) return `${(sec / 86400).toFixed(1)} 天`
  if (sec >= 3600) return `${(sec / 3600).toFixed(1)} 小时`
  return `${sec} 秒`
}

/* ------------------------------ 业务量级 ------------------------------ */

/**
 * 三类业务数据的量级对比 + 成交转化率。
 *
 * ⚠️ **这不是漏斗，刻意不画成逐级收窄的样子。**
 *
 * 原因：演示数据里「委托 → 成交」是**真实关联**的（`T_Trade` 的 `OrderNo`
 * 确实取自 `T_Order`），但「条件单 → 委托」**不是子集关系** ——
 * 一张条件单触发后可能拆成多笔委托（部分成交要继续委托），
 * 所以委托数完全可能多于条件单数（当前 58 > 46）。
 * 画成漏斗会暗示「逐级筛掉」，是错的。
 *
 * 真正有意义的派生指标是**成交率**：有多少委托最终产生了成交
 * —— 这个由 `OrderNo` 关联算出来是真实可信的。
 */
const funnelOption = computed(() => {
  const s = summary.value
  const rows = [
    { name: '条件单', value: s?.conditional.total ?? 0, color: '#7c5cf0' },
    { name: '委托单', value: s?.order.total ?? 0, color: '#3b6ff5' },
    { name: '成交回报', value: s?.trade.total ?? 0, color: '#2f9e5f' },
  ]

  return {
    grid: { left: 8, right: 56, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: Array<{ name: string; value: number }>) =>
        `${params[0].name}<br/><b>${thousands(params[0].value)}</b> 条`,
    },
    xAxis: { type: 'value', splitLine: { show: false } },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((r) => r.name),
      axisTick: { show: false },
    },
    series: [
      {
        type: 'bar',
        barWidth: 22,
        label: {
          show: true,
          position: 'right',
          formatter: (p: { value: number }) => thousands(p.value),
        },
        itemStyle: { borderRadius: [0, 5, 5, 0] },
        data: rows.map((r) => ({ value: r.value, itemStyle: { color: r.color } })),
      },
    ],
  }
})

/** 成交率：已产生成交的委托 / 委托总数（由 OrderNo 真实关联算出） */
const fillRate = computed(() => {
  const s = summary.value
  if (!s || s.order.total === 0) return 0
  return Math.round((s.order.filled / s.order.total) * 1000) / 10
})

/* ------------------------------ 跨表趋势 ------------------------------ */

const trendOption = computed(() => {
  const rows = daily.value
  return {
    grid: { left: 8, right: 16, top: 34, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: {
      top: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { fontSize: 12 },
      data: ['条件单新增', '委托单', '成交回报'],
    },
    xAxis: {
      type: 'category',
      data: rows.map((d) => d.date.slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '条件单新增',
        type: 'line',
        smooth: true,
        symbolSize: 5,
        data: rows.map((d) => d.conditional),
        itemStyle: { color: '#7c5cf0' },
        lineStyle: { width: 2, color: '#7c5cf0' },
      },
      {
        name: '委托单',
        type: 'line',
        smooth: true,
        symbolSize: 5,
        data: rows.map((d) => d.order),
        itemStyle: { color: '#3b6ff5' },
        lineStyle: { width: 2, color: '#3b6ff5' },
      },
      {
        name: '成交回报',
        type: 'line',
        smooth: true,
        symbolSize: 5,
        data: rows.map((d) => d.trade),
        itemStyle: { color: '#2f9e5f' },
        lineStyle: { width: 2, color: '#2f9e5f' },
      },
    ],
  }
})

/* ---------------------------- 消息总线消化 ---------------------------- */

/**
 * 流积压构成：入口积压 / lag / pending。
 *
 * 三者含义不同（写入未消费 / 已写入未投递 / 已投递未确认），
 * 混成一个数字会看不出卡在哪一环，所以分开画。
 *
 * ⚠️ **刻意用条形图而不是饼图**：这三个量级差了两个数量级
 * （现场 1416 万 / 12.4 万 / 796），画成饼图时后两项会被压成看不见的细线，
 * 等于什么都没表达。条形图能如实呈现悬殊差距，数值也直接标在旁边。
 *
 * ⚠️ 数量级大**不等于**流量大：现场 67/149 条流已停止进数据，
 * 入口流最后一条在 24 小时前 —— 这些是历史堆积。
 * 所以卡片标题旁会一并显示「已停止的流」，免得被读成「流量很大」。
 */
const backlogOption = computed(() => {
  const s = summary.value?.stream
  const rows = [
    { name: '入口积压', value: s?.ingressBacklog ?? 0, color: '#d6455e' },
    { name: '消费组 lag', value: s?.consumerLag ?? 0, color: '#dd7a15' },
    { name: '未确认 pending', value: s?.pending ?? 0, color: '#7c5cf0' },
  ]
  return {
    grid: { left: 8, right: 76, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: Array<{ name: string; value: number }>) =>
        `${params[0].name}<br/><b>${thousands(params[0].value)}</b> 条`,
    },
    xAxis: { type: 'value', splitLine: { show: false }, axisLabel: { show: false } },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((r) => r.name),
      axisTick: { show: false },
    },
    series: [
      {
        type: 'bar',
        barWidth: 24,
        label: {
          show: true,
          position: 'right',
          formatter: (p: { value: number }) => `${thousands(p.value)} 条`,
          fontSize: 12,
        },
        itemStyle: { borderRadius: [0, 5, 5, 0] },
        data: rows.map((r) => ({ value: r.value, itemStyle: { color: r.color } })),
      },
    ],
  }
})

/* ---------------------------- 数据来源表格 ---------------------------- */

const KIND_COLOR: Record<string, string> = {
  redis: '#d6455e',
  mysql: '#0d9c9c',
}

const sourceColumns: DataTableColumns<DashboardSourceRow> = [
  {
    title: '数据来源',
    key: 'name',
    width: 220,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 500 } }, row.name),
  },
  {
    title: '类型',
    key: 'kindLabel',
    width: 130,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          style: {
            color: KIND_COLOR[row.kind],
            background: `${KIND_COLOR[row.kind]}1f`,
            fontWeight: 600,
          },
        },
        { default: () => row.kindLabel },
      ),
  },
  { title: '规模', key: 'scale', width: 170 },
  {
    title: '口径说明',
    key: 'hint',
    ellipsis: { tooltip: true },
  },
]
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />

    <!--
      现场当前没有在发数据 —— 这句话必须放在最上面。
      不说的话，下面每个数字都会被单独误读：
        「在线 0 个」→ 像全线宕机（其实策略服务本来就没开放）
        「成交率 0%」→ 像策略失效（其实成交没落库）
        「积压 1416 万」→ 像流量很大（其实是历史堆积）
      后端把这些信号拼成 idleReason，页面直接展示。
    -->
    <n-alert
      v-if="summary?.idle && summary?.idleReason"
      type="warning"
      :bordered="false"
      style="margin-bottom: 16px"
    >
      <template #header>现场当前状态说明</template>
      {{ summary.idleReason }}
    </n-alert>

    <!-- 顶部：全局健康度 -->
    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="在线节点"
          :value="nodeOnline"
          :suffix="`/ ${nodeTotal} 个`"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          :hint="nodeUnknown > 0 ? `另有 ${nodeUnknown} 个状态未知（观测不到，非离线）` : '三类服务器合计；心跳 30 秒内视为在线'"
        >
          <div class="breakdown">
            <span v-for="b in nodeBreakdown" :key="b.label" class="breakdown-item" :title="b.note">
              <i :style="{ background: b.color }" />
              {{ b.label }} {{ b.online }}/{{ b.total }}
            </span>
          </div>
        </StatCard>
      </n-gi>
      <n-gi>
        <StatCard
          label="运行中条件单"
          :value="thousands(summary?.conditional.running ?? 0)"
          suffix="条"
          :icon="LayersOutline"
          color="#7c5cf0"
          :hint="`全部 ${thousands(summary?.conditional.total ?? 0)} 条，已触发 ${thousands(summary?.conditional.triggered ?? 0)} 条`"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="委托 / 成交"
          :value="thousands(summary?.order.total ?? 0)"
          suffix="单"
          :icon="SwapHorizontalOutline"
          color="#3b6ff5"
          :hint="`成交 ${thousands(summary?.trade.total ?? 0)} 笔，成交量合计 ${thousands(summary?.trade.volume ?? 0)}`"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="已停止进数据的流"
          :value="summary?.stream.staleStreams ?? 0"
          :suffix="`/ ${summary?.stream.count ?? 0} 条`"
          :icon="ServerOutline"
          :color="(summary?.stream.staleStreams ?? 0) > 0 ? '#d6455e' : '#8a94a6'"
          :hint="
            summary?.stream.ingressBacklogAgeSec != null
              ? `入口流最后一条在 ${humanAge(summary.stream.ingressBacklogAgeSec)} 前；积压是历史堆积而非当前流量`
              : '超过 1 小时没有新条目即视为已停止'
          "
        />
      </n-gi>
    </n-grid>

    <!-- 第二排：跨表漏斗 + 跨表趋势 -->
    <n-grid
      cols="1 m:2"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      item-responsive
      style="margin-top: 16px"
    >
      <n-gi>
        <n-card class="page-card chart-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="AnalyticsOutline" :color="accent" :size="17" />
              业务量级与成交率
            </span>
          </template>
          <template #header-extra>
            <n-tooltip trigger="hover">
              <template #trigger>
                <n-icon :component="InformationCircleOutline" :size="15" class="text-muted" />
              </template>
              分属 T_Conditional / T_Order / T_Trade 三张表，只有总览页能放在一起看。
              注意这不是漏斗：一张条件单可拆成多笔委托，所以委托数可能多于条件单数。
            </n-tooltip>
          </template>
          <BaseChart :option="funnelOption" height="100%" style="min-height: 168px" />
          <div class="fill-rate">
            <span class="fill-label">成交率</span>
            <span class="fill-value" :style="fillRate === 0 ? 'color:#8a94a6' : ''">{{ fillRate }}%</span>
            <span class="fill-note">
              {{ thousands(summary?.order.filled ?? 0) }} / {{ thousands(summary?.order.total ?? 0) }}
              笔委托已产生成交（按 OrderNo 关联成交表实算）
              <!--
                ⚠️ 0% 有两种完全不同的原因，必须区分：
                  1) 真的没有委托，或委托确实都没成交；
                  2) 委托已成（OrderStatue=8）但成交**没落库** —— 现场就是这种。
                只显示一个 0% 会被读成「策略全废了」。
              -->
              <template v-if="(summary?.trade.total ?? 0) === 0 && (summary?.order.total ?? 0) > 0">
                <br />
                <b style="color: var(--warning)">
                  注：成交表当前为空。若委托已成但这里为 0%，说明成交尚未落库，
                  不代表策略失效。
                </b>
              </template>
            </span>
          </div>
        </n-card>
      </n-gi>

      <n-gi>
        <n-card class="page-card chart-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="TrendingUpOutline" color="#2f9e5f" :size="17" />
              近 14 天跨表趋势
            </span>
          </template>
          <BaseChart :option="trendOption" height="100%" style="min-height: 190px" />
        </n-card>
      </n-gi>
    </n-grid>
    <!-- 第三排：消息总线消化 + 账号 -->
    <n-grid
      cols="1 m:2"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      item-responsive
      style="margin-top: 16px"
    >
      <n-gi>
        <n-card class="page-card chart-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="GitNetworkOutline" color="#d6455e" :size="17" />
              消息总线积压构成
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">
              {{ thousands(summary?.stream.count ?? 0) }} 条流
              <template v-if="(summary?.stream.staleStreams ?? 0) > 0">
                · <b style="color: var(--danger)">{{ summary?.stream.staleStreams }} 条已停止进数据</b>
              </template>
            </span>
          </template>
          <BaseChart :option="backlogOption" height="100%" style="min-height: 190px" />
        </n-card>
      </n-gi>

      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="CloudOutline" color="#d94f8a" :size="17" />
              云单账号在线情况
            </span>
          </template>
          <!-- 口径说明收进 tooltip：运维在总览页不需要读表名与字段名 -->
          <template #header-extra>
            <n-tooltip trigger="hover">
              <template #trigger>
                <n-icon :component="InformationCircleOutline" :size="15" class="text-muted" />
              </template>
              账号视图 = MySQL <code>T_User</code> 与 Redis <code>acc-*</code> 合并；
              在线判定取 <code>acc-*</code> 的 <code>onlineflag</code>。
              「离线」只统计**有实时对象但未在线**的账号，不含只在 MySQL 里的。
            </n-tooltip>
          </template>

          <div class="account-block">
            <!--
              左环右表：环形直接表达「在线占比」，右侧三行给精确数字。
              原来是 4 个灰底方块平铺，视觉上像 4 个同等重要的碎片，
              而这里真正的主语只有一个 —— 在线率。
            -->
            <div class="account-main">
              <n-progress
                type="circle"
                :percentage="accountOnlineRate ?? 0"
                :stroke-width="9"
                :color="accountOnlineRate === 100 ? '#2f9e5f' : '#3b6ff5'"
                :rail-color="'rgba(15,23,42,0.08)'"
              >
                <!-- 圆环中心用自定义内容（naive-ui 的 circle 类型会优先渲染默认插槽） -->
                <div class="account-ring-inner">
                  <span class="account-ring-value">{{ thousands(accountOnline) }}</span>
                  <span class="account-ring-label">在线</span>
                </div>
              </n-progress>

              <div class="account-facts">
                <div class="account-fact">
                  <span class="account-fact-label">账号总数</span>
                  <span class="account-fact-value">{{ thousands(accountTotal) }}</span>
                </div>
                <div class="account-fact">
                  <span class="account-fact-label">离线</span>
                  <span class="account-fact-value" style="color: #8a94a6">
                    {{ thousands(accountOffline) }}
                  </span>
                </div>
                <div class="account-fact">
                  <span class="account-fact-label">在线率</span>
                  <span class="account-fact-value">
                    {{ accountOnlineRate === null ? '—' : `${accountOnlineRate}%` }}
                  </span>
                </div>
              </div>
            </div>

            <!--
              两边数据源不一致时才出现（后端一直算、前端从没显示过）。
              这句话解释了「总览的数字和账号页为什么可能对不上」，
              并给出下钻入口 —— 只说"有 N 个不一致"而不给路径等于没说。
            -->
            <div v-if="accountSourceGap" class="account-gap">
              <n-icon :component="WarningOutline" :size="14" style="flex: 0 0 auto" />
              <span>
                {{ accountSourceGap }}
                <n-button text type="primary" size="tiny" @click="router.push('/account/user')">
                  去账号页查看
                </n-button>
              </span>
            </div>
          </div>
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 数据来源 -->
    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="ServerOutline" color="#3b6ff5" :size="17" />
          数据来源与规模
        </span>
      </template>
      <template #header-extra>
        <span class="text-muted" style="font-size: 12px">
          快照于 {{ data?.generatedAt ?? '-' }}
        </span>
      </template>
      <n-data-table
        :columns="sourceColumns"
        :data="sources"
        :bordered="false"
        :single-line="false"
        size="small"
        :pagination="{ pageSize: 6 }"
      />
    </n-card>

    <!--
      风险提示：只保留**当下要处理的事**。
      原先这里是一整张「接入前必读：生产环境硬约束」卡片，已删除 ——
      它是项目**开发期**给开发者看的备忘（「列表接口必须强制分页」「用 SCAN 不用 KEYS」），
      而后端早已接入、这些约束都落在代码里（SCAN_HARD_LIMIT / maxRows / 强制分页）；
      登进系统的是运维，改不了代码，看到「必须强制分页」没有可操作性。
      里面那条行数是「现场快照」，会过期（T_StrategyLog 从 1.743 万涨到 1.775 万）。
      开发规范已挪进 backend/README.md，不再占页面位置。
    -->
    <n-card
      v-if="(summary?.stream.groupLess ?? 0) > 0"
      class="page-card risk-card"
      size="small"
      :bordered="false"
      style="margin-top: 16px"
    >
      <template #header>
        <span class="section-title">
          <n-icon :component="WarningOutline" color="#d6455e" :size="17" />
          需要注意：没有消费组的流
        </span>
      </template>
      <!--
        总览页只给结论和下钻入口：
        · 原来这里展开两段解释（这类流为什么 lag/pending 看不到），
          那是「为什么」而不是「怎么办」，放在概览页占了半屏；
        · 里面还写死了「现场实测 137 db0 的 DataHub_req_stream 有 1400 万条、
          没有消费组」——那条现在已经有 user_group 了，写死的"现场实测"会过期。
        详细排查留给消息总线页。
      -->
      <template #header-extra>
        <n-button size="small" type="primary" ghost @click="router.push('/server/stream')">
          <template #icon>
            <n-icon :component="GitNetworkOutline" />
          </template>
          去消息总线页查看
        </n-button>
      </template>
      <div class="risk-body">
        <p>
          有 <b>{{ summary?.stream.groupLess }}</b> 条流<b>有数据但没有消费组</b> ——
          有人往里面写，却没有任何消费者会处理，消息只进不出。
          <span class="text-muted">
            这类流不会出现在 <code>lag</code> / <code>pending</code> 里（那两个只在有消费组时才存在），
            所以积压告警发现不了。
          </span>
        </p>
      </div>
    </n-card>
  </div>
</template>

<style scoped>
/*
 * 栅格单元本身是等高的，但**卡片不撑满就会下沿对不齐**。
 * 第一排的指标卡早有上面那条 `.stat-card { height:100% }`，第二、三排的卡片之前漏了，
 * 于是同一排两张卡各按自己的内容高度：
 *   第二排  左「业务量级与成交率」288.6 / 右「近 14 天跨表趋势」253.6（差 35px）
 *   第三排  左「消息总线积压构成」253.6 / 右「云单账号在线情况」285.5（差 31.9px）
 *
 * ⚠️ 这里**不能写 `.n-gi > .n-card`**：naive-ui 的 `<n-gi>` 渲染出来是一个
 * **没有任何类名的 div**（实测父级链是 `.n-card` → `div[class=""]` → `.n-grid`），
 * 所以 `.n-gi` 选择器永远匹配不到，规则会静默失效。
 * 用「栅格单元的直接子卡片」这个结构关系来选：`.n-grid > div > .n-card`。
 *
 * 同时注意不能写成 `:deep(.n-grid > div > .n-card)` —— `:deep(x)` 编译成
 * `[data-v-xxx] x`，要求 x 是带该属性元素的**后代**，而 `.n-grid` 是 n-grid
 * 渲染的、不带本组件属性，卡片却带着属性。写成 `.n-grid :deep(> div > .n-card)`
 * 让作用域落在 `.n-grid` 上才对（编译为 `.n-grid[data-v-xxx] > div > .n-card`）。
 */
.n-grid :deep(> div > .n-card) {
  height: 100%;
  box-sizing: border-box;
}

/*
 * 只有图表的卡片，撑高后底部会留一块空白（趋势卡矮 35px、总线卡矮 32px）。
 * 让图表去吃掉这部分剩余高度。
 *
 * 两个都踩过的坑，写在前面免得再犯：
 *
 * ① 内容区的类名是 **`.n-card-content`**（连字符），不是 `.n-card__content`
 *    （BEM 的 `__` 形式）。写成 `__` 版会静默匹配不到。
 *
 * ② **不能把这些规则写成 `:deep(.n-card.chart-card > .n-card-content)`。**
 *    `:deep(x)` 的编译结果是 `[data-v-xxx] x` —— 作用域属性被放到**最前面**，
 *    要求 `x` 必须是带该属性元素的**后代**。而卡片自己就带着 `data-v-xxx`
 *    （它是本组件的模板元素），所以 `[data-v-xxx] .n-card.chart-card …`
 *    永远匹配不到，整套样式静默失效。
 *    正确做法：`:` 只限定到「我自己的元素」，内部结构一律交给 `:deep(…)` 收尾，
 *    例如 `.n-card.chart-card :deep(.n-card-content)`，
 *    编译成 `.n-card.chart-card[data-v-xxx] .n-card-content` —— 这才对。
 *
 * 高度不能靠 `height:auto` 改：BaseChart 把 height 写成 `.chart-wrap` 的内联 style，
 * 内联优先级高于样式表，所以模板里直接把 height 传 `"100%"`，
 * 并用内联 `min-height` 兜住下限（等于原来的固定高度）。
 */
.n-card.chart-card :deep(.n-card-content) {
  display: flex;
  flex-direction: column;
}

.n-card.chart-card :deep(.n-card-content > .chart-wrap) {
  flex: 1 1 auto;
}

/* ------------------------------ 节点明细 ------------------------------ */

.breakdown {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--border);
}

.breakdown-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-muted);
}

.breakdown-item i {
  width: 6px;
  height: 6px;
  border-radius: 2px;
}

/* ------------------------------ 账号区块 ------------------------------ */

.account-block {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 0;
}

/* 左环右表 */
.account-main {
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 2px 0;
}

/* 环心的数字：用绝对居中，不依赖 n-progress 默认的 indicator 布局 */
.account-ring-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  line-height: 1.15;
}

.account-ring-value {
  font-size: 23px;
  font-weight: 650;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.account-ring-label {
  margin-top: 1px;
  font-size: 11px;
  color: var(--text-secondary);
}

/* 右侧三个事实：标签左、数值右，中间虚线分隔 */
.account-facts {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.account-fact {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--border);
}

.account-fact:first-child {
  padding-top: 0;
}

.account-fact:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.account-fact-label {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.account-fact-value {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

/* 两边数据源不一致的提示（只在非 0 时出现） */
.account-gap {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: rgba(217, 122, 21, 0.12);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.account-gap code,
.account-gap .n-button {
  font-size: 12px;
}

/* ------------------------------ 成交率 ------------------------------ */

.fill-rate {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}

.fill-label {
  font-size: 12.5px;
  color: var(--text-muted);
}

.fill-value {
  font-size: 20px;
  font-weight: 650;
  color: #2f9e5f;
  font-variant-numeric: tabular-nums;
}

.fill-note {
  flex: 1 1 160px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-muted);
}

/* ------------------------------ 风险提示 ------------------------------ */

.risk-card {
  border-left: 3px solid var(--danger) !important;
}

.risk-body p {
  margin: 0;
  font-size: 13px;
  line-height: 1.9;
  color: var(--text-secondary);
}

.risk-body b {
  color: var(--danger);
  font-weight: 600;
}

.risk-body code {
  padding: 1.5px 6px;
  margin: 0 1px;
  border-radius: 5px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  color: var(--text-primary);
}
</style>
