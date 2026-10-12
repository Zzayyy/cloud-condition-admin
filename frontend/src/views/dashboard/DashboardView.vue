<script setup lang="ts">
import { computed, h, onMounted } from 'vue'
import {
  NCard,
  NDataTable,
  NGi,
  NGrid,
  NIcon,
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

/* ------------------------------ 节点健康度 ------------------------------ */

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

const nodeOffline = computed(() => nodeTotal.value - nodeOnline.value)

/** 三类节点的在线情况，用于卡片下方的明细行 */
const nodeBreakdown = computed(() => {
  const n = summary.value?.nodes
  if (!n) return []
  return [
    { label: '委托服务器', online: n.tradeOnline, total: n.tradeTotal, color: '#0d9c9c' },
    { label: '策略服务器', online: n.strategyOnline, total: n.strategyTotal, color: '#7c5cf0' },
    { label: '数据中台', online: n.datahubOnline, total: n.datahubTotal, color: '#dd7a15' },
  ]
})

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
 * （实测 410 万 / 1.7 万 / 770），画成饼图时后两项会被压成看不见的细线，
 * 等于什么都没表达。条形图能如实呈现悬殊差距，数值也直接标在旁边。
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

    <!-- 顶部：全局健康度 -->
    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="在线节点"
          :value="nodeOnline"
          :suffix="`/ ${nodeTotal} 个`"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          hint="三类服务器合计，心跳 30 秒内视为在线"
        >
          <div class="breakdown">
            <span v-for="b in nodeBreakdown" :key="b.label" class="breakdown-item">
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
          label="离线 / 停用节点"
          :value="nodeOffline"
          suffix="个"
          :icon="ServerOutline"
          :color="nodeOffline > 0 ? '#d6455e' : '#8a94a6'"
          hint="需要人工确认是否为正常下线"
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
        <n-card class="page-card" size="small" :bordered="false">
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
          <BaseChart :option="funnelOption" :height="168" />
          <div class="fill-rate">
            <span class="fill-label">成交率</span>
            <span class="fill-value">{{ fillRate }}%</span>
            <span class="fill-note">
              {{ thousands(summary?.order.filled ?? 0) }} / {{ thousands(summary?.order.total ?? 0) }}
              笔委托已产生成交（按 OrderNo 关联 T_Trade 实算）
            </span>
          </div>
        </n-card>
      </n-gi>

      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="TrendingUpOutline" color="#2f9e5f" :size="17" />
              近 14 天跨表趋势
            </span>
          </template>
          <BaseChart :option="trendOption" :height="190" />
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
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="GitNetworkOutline" color="#d6455e" :size="17" />
              消息总线积压构成
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">
              {{ thousands(summary?.stream.count ?? 0) }} 条流
            </span>
          </template>
          <BaseChart :option="backlogOption" :height="190" />
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
          <div class="account-block">
            <div class="account-row">
              <span class="account-label">账号总数</span>
              <span class="account-value">{{ thousands(summary?.account.total ?? 0) }}</span>
            </div>
            <div class="account-row">
              <span class="account-label">在线</span>
              <span class="account-value" style="color: #2f9e5f">
                {{ thousands(summary?.account.online ?? 0) }}
              </span>
            </div>
            <div class="account-row">
              <span class="account-label">离线</span>
              <span class="account-value" style="color: #8a94a6">
                {{ thousands((summary?.account.total ?? 0) - (summary?.account.online ?? 0)) }}
              </span>
            </div>
            <p class="account-note">
              口径取自「账户与用户」页（MySQL <code>T_User</code> 与 Redis <code>acc-*</code>
              合并后的账号视图）；在线状态来自 <code>acc-*</code> 的 <code>onlineflag</code>。
            </p>
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

    <!-- 生产环境硬约束 -->
    <n-card class="page-card risk-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="WarningOutline" color="#d6455e" :size="17" />
          接入前必读：生产环境硬约束
        </span>
      </template>
      <ul class="risk-list">
        <li>
          入口流积压 <b>{{ thousands(summary?.stream.ingressBacklog ?? 0) }}</b> 条
          （现场 137 db0 的 <code>DataHub_req_stream</code> 就有 407 万），
          列表接口必须强制分页 + 条数上限，禁止全量 <code>XRANGE</code>。
        </li>
        <li>
          有 <b>{{ summary?.stream.groupLess ?? 0 }}</b> 条流<b>没有消费组</b> —— 写入后无人投递。
          这类流不会被 lag/pending 指标暴露，需要单独告警。
        </li>
        <li>
          <b>T_UserLog</b> 19 万行、<b>T_StrategyLog</b> 1.6 万行，查询必须走分页与索引。
        </li>
        <li>Redis 键扫描一律用 <code>SCAN</code>，禁止 <code>KEYS *</code>。</li>
        <li>
          破坏测试写的是 <b>137 db2</b> 的 <code>DataHub_req_stream</code>（与生产流隔离），
          不要写到 db0/db1。高危操作需二次确认 + 写审计。
        </li>
      </ul>
    </n-card>
  </div>
</template>

<style scoped>
/**
 * 第一张卡片塞了节点明细行，会比其余三张高。
 * 让栅格单元内的卡片一律撑满，四张卡等高才整齐。
 */
:deep(.stat-card) {
  height: 100%;
  box-sizing: border-box;
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
  display: grid;
  gap: 10px;
  padding: 4px 0;
}

.account-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  background: var(--bg-code);
}

.account-label {
  font-size: 13px;
  color: var(--text-secondary);
}

.account-value {
  font-size: 19px;
  font-weight: 650;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.account-note {
  margin: 2px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-muted);
}

.account-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
  color: var(--text-primary);
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

/* ------------------------------ 硬约束 ------------------------------ */

.risk-card {
  border-left: 3px solid var(--danger) !important;
}

.risk-list {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 2;
  color: var(--text-secondary);
}

.risk-list b {
  color: var(--danger);
  font-weight: 600;
}

.risk-list code {
  padding: 1.5px 6px;
  margin: 0 1px;
  border-radius: 5px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  color: var(--text-primary);
}
</style>
