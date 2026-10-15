<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NCode,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NGi,
  NGrid,
  NIcon,
  NInput,
  NSelect,
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  CashOutline,
  ClipboardOutline,
  CloseOutline,
  CodeSlashOutline,
  EyeOutline,
  InformationCircleOutline,
  RefreshOutline,
  SwapHorizontalOutline,
  TimeOutline,
  TrendingUpOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { tradeApi } from '@/api/endpoints'
import {
  BS_TYPES,
  MARKET_ORDER_TYPES,
  OC_TYPES,
  ORDER_STATUS_NOTE,
  mockBsDistribution,
  mockDailyOrders,
  mockTradeApi,
} from '@/api/mock/trade'
import { withDelay } from '@/api/mock/_shared'
import type { OrderRecord, TradeRecord } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { formatJson, orDash, shortDateTime, thousands } from '@/utils/format'

/**
 * 委托单页：对应 MySQL T_Order / T_Order_his。
 *
 * 支持按策略编号、账号、合约、买卖/开平、时间筛选，并能下钻到成交明细。
 */

const accent = '#0d9c9c'

const tab = ref<'active' | 'history'>('active')
const keyword = ref('')
const bsFilter = ref<number | null>(null)
const ocFilter = ref<number | null>(null)
const codeFilter = ref('')
const page = ref(1)
const pageSize = ref(10)

const { data, loading, error, isMock, load } = useResource(
  (params: Record<string, unknown>) => tradeApi.listOrders(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockTradeApi.listOrders(params)) },
)

/**
 * 近 14 天委托量（趋势图）。
 *
 * ⚠️ 这里**曾经是个漏做的占位**：原先写的是
 * `const daily = isMock.value ? mockDailyOrders() : []` —— 真实态下恒为空数组，
 * 图表永远是空的。当时那样写没错（后端还没这个接口），
 * 但接口做出来之后没回头接上。
 *
 * 现在走真实接口，并按**当前 tab**（主表/历史表）取数：
 * 切到「历史表」时看的就是历史表的按天数据。
 */
const {
  data: dailyData,
  load: loadDaily,
} = useResource(
  (params: Record<string, unknown>) => tradeApi.listOrderDaily(params),
  { mock: () => withDelay(mockDailyOrders()) },
)

/** 近 14 天窗口（含今天）—— 后端 from/to 可选，不传会返回全部历史 */
function dailyWindow() {
  const p = (n: number) => String(n).padStart(2, '0')
  const d = (dt: Date) => `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`
  const today = new Date()
  const from = new Date(today.getTime() - 13 * 86400_000)
  return { from: `${d(from)} 00:00:00`, to: `${d(today)} 23:59:59` }
}

function loadDailyTrend() {
  loadDaily({ table: tab.value === 'active' ? 'T_Order' : 'T_Order_his', ...dailyWindow() })
}

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    bsType: bsFilter.value ?? undefined,
    ocType: ocFilter.value ?? undefined,
    code: codeFilter.value.trim() || undefined,
    // 主表/历史表区分：mock 用 ID 奇偶无关，真实实现按表名分
    table: tab.value === 'active' ? 'T_Order' : 'T_Order_his',
  }
}

function reload() {
  load(currentParams())
  // 趋势与列表同源，一起刷新 —— 否则切 tab 后图表会滞后一次
  loadDailyTrend()
}

onMounted(reload)

function handleSearch() {
  page.value = 1
  reload()
}

function resetFilters() {
  keyword.value = ''
  bsFilter.value = null
  ocFilter.value = null
  codeFilter.value = ''
  page.value = 1
  reload()
}

const bsOptions = Object.entries(BS_TYPES).map(([v, l]) => ({ label: l, value: Number(v) }))
const ocOptions = Object.entries(OC_TYPES).map(([v, l]) => ({ label: l, value: Number(v) }))

const rows = computed(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = rows.value
  const buy = list.filter((r) => r.BSType === 1)
  const sell = list.filter((r) => r.BSType === 2)
  const amount = list.reduce((s, r) => s + (r.OrderPrice ?? 0) * (r.OrderVolumn ?? 0), 0)
  return {
    buy: buy.length,
    sell: sell.length,
    amount,
    abnormal: list.filter((r) => r.Reason).length,
  }
})

const bsChartOption = computed(() => {
  // 买卖分布可由当前页数据算出，真实接口下同样成立（不走 mock）
  const buy = rows.value.filter((r) => r.BSType === 1).length
  const sell = rows.value.filter((r) => r.BSType === 2).length
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 笔（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data: [
          { name: '买', value: buy },
          { name: '卖', value: sell },
        ],
        color: ['#d6455e', '#2f9e5f'],
      },
    ],
  }
})

const dailyChartOption = computed(() => {
  /*
   * 趋势有**自己的** useResource（含自己的 mock 分支），所以直接用 `dailyData` ——
   * **不要**拿列表那个资源的 `isMock` 判断走哪边：那既重复处理，
   * 又会犯 README 第 5 条记的时序坑（列表的 isMock 在 await 前仍是 false）。
   *
   * 后端只返回**有数据的日期**，这里按 14 天窗口补零 ——
   * 不补的话，只有一条数据时折线会画成一个点（或把相隔多日的两点连成线），
   * 看起来像「一直有数据」。
   */
  const daily = dailyData.value ?? []
  const byDay = new Map(daily.map((d) => [d.date, d.count]))
  const filled: Array<{ date: string; count: number }> = []
  const today = new Date()
  for (let i = 13; i >= 0; i -= 1) {
    const dt = new Date(today.getTime() - i * 86400_000)
    const p = (n: number) => String(n).padStart(2, '0')
    const day = `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`
    filled.push({ date: day, count: byDay.get(day) ?? 0 })
  }

  return {
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: filled.map((d) => d.date.slice(5)), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: filled.map((d) => d.count),
        itemStyle: { color: accent },
        lineStyle: { width: 2.5, color: accent },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(13,156,156,0.28)' },
              { offset: 1, color: 'rgba(13,156,156,0.02)' },
            ],
          },
        },
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<OrderRecord> = [
  {
    title: '委托编号',
    key: 'OrderNo',
    width: 120,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono' }, orDash(row.OrderNo)) },
      ),
  },
  {
    title: '策略编号',
    key: 'StrategyID',
    width: 130,
    render: (row) => h('span', { class: 'mono' }, orDash(row.StrategyID)),
  },
  {
    title: '账号',
    key: 'Account',
    width: 150,
    render: (row) => h('span', { class: 'mono' }, orDash(row.Account)),
  },
  {
    title: '合约',
    key: 'Code',
    width: 150,
    render: (row) =>
      h('div', { class: 'code-cell' }, [
        h('span', { class: 'mono', style: { fontWeight: 500 } }, orDash(row.Code)),
        h('span', { class: 'code-name' }, orDash(row.CodeName)),
      ]),
  },
  {
    title: '方向',
    key: 'BSType',
    width: 96,
    render: (row) =>
      h('div', { class: 'dir-cell' }, [
        h(
          NTag,
          {
            size: 'small',
            round: true,
            bordered: false,
            type: row.BSType === 1 ? 'error' : 'success',
          },
          { default: () => (row.BSType != null ? BS_TYPES[row.BSType] : '-') },
        ),
        h('span', { class: 'dir-sub' }, row.OCType != null ? OC_TYPES[row.OCType] : '-'),
      ]),
  },
  {
    title: '委托价',
    key: 'OrderPrice',
    width: 96,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, row.OrderPrice != null ? row.OrderPrice.toFixed(4) : '-'),
  },
  {
    title: '数量',
    key: 'OrderVolumn',
    width: 84,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, row.OrderVolumn != null ? thousands(row.OrderVolumn) : '-'),
  },
  {
    title: '委托金额',
    key: 'amount',
    width: 104,
    align: 'right',
    render: (row) => {
      const v = (row.OrderPrice ?? 0) * (row.OrderVolumn ?? 0)
      return h('span', { class: 'num' }, v ? v.toFixed(2) : '-')
    },
  },
  {
    title: '委托状态',
    key: 'StatueDis',
    width: 108,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              NTag,
              {
                size: 'small',
                round: true,
                bordered: false,
                type: row.Reason ? 'error' : 'info',
              },
              { default: () => orDash(row.StatueDis) },
            ),
          // 状态枚举未在协议文档中定义，这里如实说明原值来源
          default: () => `OrderStatue 原值：${row.OrderStatue ?? '-'}（枚举未在协议文档中定义）`,
        },
      ),
  },
  {
    title: '委托时间',
    key: 'OrderTime',
    width: 100,
    render: (row) => orDash(row.OrderTime),
  },
  {
    title: '操作',
    key: 'actions',
    width: 72,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, onClick: () => openDetail(row) },
        { icon: () => h(NIcon, null, { default: () => h(EyeOutline) }) },
      ),
  },
]

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<OrderRecord | null>(null)
const detailTab = ref('basic')

/**
 * 该委托对应的成交（委托 → 成交 下钻）。
 * 真实接口：GET /trade/orders/{orderNo}/trades；
 * 演示态下直接读 mock。两条路径都不会「假装成功」。
 */
const relatedTrades = ref<TradeRecord[]>([])

async function loadRelatedTrades(orderNo: string) {
  if (isMock.value) {
    relatedTrades.value = mockTradeApi.tradesByOrderNo(orderNo)
    return
  }
  try {
    relatedTrades.value = await tradeApi.listTradesByOrder(orderNo)
  } catch {
    relatedTrades.value = []
  }
}

const tradeColumns: DataTableColumns<TradeRecord> = [
  { title: '成交编号', key: 'TradeNO', render: (row) => h('span', { class: 'mono' }, orDash(row.TradeNO)) },
  { title: '合约', key: 'Code', width: 100, render: (row) => h('span', { class: 'mono' }, orDash(row.Code)) },
  {
    title: '成交量',
    key: 'Volumn',
    width: 90,
    align: 'right',
    render: (row) => orDash(row.Volumn),
  },
  {
    title: '成交价',
    key: 'Price',
    width: 100,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, row.Price != null ? row.Price.toFixed(4) : '-'),
  },
  { title: '成交时间', key: 'TradeTime', width: 120, render: (row) => orDash(row.TradeTime) },
]

function openDetail(row: OrderRecord) {
  active.value = row
  detailTab.value = 'basic'
  showDetail.value = true
  relatedTrades.value = []
  if (row.OrderNo) void loadRelatedTrades(row.OrderNo)
}

const prettyParam = computed(() => formatJson(active.value?.OrderParam))

/** 从 OrderParam 里取委托方式（用于明细展示） */
const marketOrderType = computed(() => {
  const raw = active.value?.OrderParam
  if (!raw) return undefined
  try {
    const obj = JSON.parse(raw) as { createOrder?: { MarketOrderType?: number } }
    return obj.createOrder?.MarketOrderType
  } catch {
    return undefined
  }
})
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="买入委托" :value="summary.buy" suffix="笔" :icon="TrendingUpOutline" color="#d6455e" hint="本页口径（BSType=1）" />
      </n-gi>
      <n-gi>
        <StatCard label="卖出委托" :value="summary.sell" suffix="笔" :icon="TrendingUpOutline" color="#2f9e5f" hint="本页口径（BSType=2）" />
      </n-gi>
      <n-gi>
        <StatCard
          label="委托金额合计"
          :value="thousands(Math.round(summary.amount))"
          suffix="元"
          :icon="CashOutline"
          :color="accent"
          hint="本页口径（价×量）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="含失败原因"
          :value="summary.abnormal"
          suffix="笔"
          :icon="AlertCircleOutline"
          color="#dd7a15"
          hint="Reason 字段非空"
        />
      </n-gi>
    </n-grid>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="SwapHorizontalOutline" :color="accent" :size="17" />
              买卖方向分布
            </span>
          </template>
          <BaseChart :option="bsChartOption" :height="230" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="TimeOutline" :color="accent" :size="17" />
              近 14 天委托量
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">
              按 OrderTime 聚合（{{ tab === 'active' ? 'T_Order' : 'T_Order_his' }}）
            </span>
          </template>
          <BaseChart
            :option="dailyChartOption"
            :height="230"
            empty-text="近 14 天内没有委托单"
          />
        </n-card>
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="ClipboardOutline" :color="accent" :size="17" />
          委托单列表
        </span>
      </template>

      <!-- 主表 / 历史表切换
           注意：必须用 #tab 插槽，naive-ui 2.45.x 不认 tab 属性 -->
      <n-tabs v-model:value="tab" type="segment" size="small" style="margin-bottom: 14px" @update:value="handleSearch">
        <n-tab-pane name="active">
          <template #tab>当前委托（T_Order）</template>
        </n-tab-pane>
        <n-tab-pane name="history">
          <template #tab>历史委托（T_Order_his）</template>
        </n-tab-pane>
      </n-tabs>

      <div class="toolbar">
        <SearchInput
          v-model:value="keyword"
          placeholder="搜索委托号 / 策略号 / 账号 / 合约"
          style="width: 270px"
          :loading="loading"
          @search="handleSearch"
          @clear="handleSearch"
        />

        <SearchInput
          v-model:value="codeFilter"
          placeholder="合约代码"
          style="width: 130px"
          :loading="loading"
          @search="handleSearch"
          @clear="handleSearch"
        />

        <n-select v-model:value="bsFilter" :options="bsOptions" placeholder="买卖方向" clearable style="width: 120px" @update:value="handleSearch" />
        <n-select v-model:value="ocFilter" :options="ocOptions" placeholder="开平方向" clearable style="width: 120px" @update:value="handleSearch" />

        <n-button size="small" @click="resetFilters">
          <template #icon>
            <n-icon :component="CloseOutline" />
          </template>
          重置
        </n-button>

        <n-button size="small" :loading="loading" @click="reload">
          <template #icon>
            <n-icon :component="RefreshOutline" />
          </template>
          刷新
        </n-button>
      </div>

      <n-data-table
        remote
        :columns="columns"
        :data="rows"
        :loading="loading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: OrderRecord) => String(row.ID)"
        :scroll-x="1210"
        :pagination="{
          page: page,
          pageSize: pageSize,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50],
          onUpdatePage: (p: number) => { page = p; reload() },
          onUpdatePageSize: (s: number) => { pageSize = s; page = 1; reload() },
          prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 笔`,
        }"
      />
    </n-card>

    <!-- 委托详情 -->
    <AppDialog v-model:show="showDetail" :title="`委托详情 · ${active?.OrderNo ?? ''}`" :width="680" mask-closable>
      <template v-if="active">
        <n-tabs v-model:value="detailTab" type="line" size="small">
          <n-tab-pane name="basic">
            <template #tab>基本信息</template>

            <n-descriptions :column="2" label-placement="top" size="small" bordered>
              <n-descriptions-item label="委托编号">
                <span class="mono">{{ orDash(active.OrderNo) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="策略编号">
                <span class="mono">{{ orDash(active.StrategyID) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="账号">
                <span class="mono">{{ orDash(active.Account) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="下单账号">
                <span class="mono">{{ orDash(active.UserAccount) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="合约">
                <span class="mono">{{ orDash(active.Code) }}</span>
                <span class="text-muted" style="margin-left: 6px">{{ orDash(active.CodeName) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="市场">{{ orDash(active.Market) }}</n-descriptions-item>
              <n-descriptions-item label="买卖方向">
                {{ active.BSType != null ? BS_TYPES[active.BSType] : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="开平方向">
                {{ active.OCType != null ? OC_TYPES[active.OCType] : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="委托价">
                <span class="num">{{ active.OrderPrice != null ? active.OrderPrice.toFixed(4) : '-' }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="委托量">
                <span class="num">{{ active.OrderVolumn != null ? thousands(active.OrderVolumn) : '-' }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="委托金额">
                <span class="num">
                  {{ active.OrderPrice != null && active.OrderVolumn != null
                    ? ((active.OrderPrice * active.OrderVolumn)).toFixed(2) : '-' }}
                </span>
              </n-descriptions-item>
              <n-descriptions-item label="委托时间">{{ orDash(active.OrderTime) }}</n-descriptions-item>
              <n-descriptions-item label="委托状态">
                {{ orDash(active.StatueDis) }}
                <span class="text-muted" style="margin-left: 6px; font-size: 12px">
                  OrderStatue={{ active.OrderStatue ?? '-' }}
                </span>
              </n-descriptions-item>
              <n-descriptions-item label="委托方式">
                {{ marketOrderType != null ? (MARKET_ORDER_TYPES[marketOrderType] ?? marketOrderType) : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="失败原因" :span="2">{{ orDash(active.Reason) }}</n-descriptions-item>
            </n-descriptions>

            <div class="detail-note">
              <n-icon :component="InformationCircleOutline" :color="accent" />
              <div>{{ ORDER_STATUS_NOTE }}</div>
            </div>
          </n-tab-pane>

          <n-tab-pane name="trades">
            <template #tab>成交明细（{{ relatedTrades.length }}）</template>
            <div class="json-head">
              <n-icon :component="CashOutline" :color="accent" />
              <span>
                按委托号 <code>{{ active.OrderNo }}</code> 关联 MySQL 表
                <code>T_Trade</code> / <code>T_Trade_his</code>。
              </span>
            </div>
            <n-data-table
              :columns="tradeColumns"
              :data="relatedTrades"
              :bordered="false"
              size="small"
              :pagination="false"
              :max-height="320"
            />
          </n-tab-pane>

          <n-tab-pane name="param">
            <template #tab>下单参数</template>
            <div class="json-head">
              <n-icon :component="CodeSlashOutline" :color="accent" />
              <span>
                对应 <code>T_Order.OrderParam</code> 字段，即客户端
                <code>createOrder</code> 协议原文（其他非统一的下单参数以 JSON 存放）。
              </span>
            </div>
            <n-code :code="prettyParam" language="json" show-line-numbers word-wrap />
          </n-tab-pane>
        </n-tabs>
      </template>

      <template #footer>
        <n-button size="small" @click="showDetail = false">关闭</n-button>
      </template>
    </AppDialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}

.num {
  font-variant-numeric: tabular-nums;
}

.code-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.code-name {
  font-size: 11.5px;
  color: var(--text-muted);
}

.dir-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dir-sub {
  font-size: 12px;
  color: var(--text-muted);
}

.json-head {
  display: flex;
  gap: 9px;
  margin-bottom: 12px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid v-bind(accent);
}

.json-head code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.detail-note {
  display: flex;
  gap: 10px;
  margin-top: 16px;
  padding: 12px 14px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid v-bind(accent);
}
</style>
