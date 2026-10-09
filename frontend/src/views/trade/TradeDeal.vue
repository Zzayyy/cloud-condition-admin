<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NCode,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NGi,
  NGrid,
  NIcon,
  NInput,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import {
  CashOutline,
  CloseOutline,
  CodeSlashOutline,
  InformationCircleOutline,
  LinkOutline,
  RefreshOutline,
  SearchOutline,
  SwapHorizontalOutline,
  TimeOutline,
  TrendingUpOutline,
  WalletOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { tradeApi } from '@/api/endpoints'
import { mockTradeApi } from '@/api/mock/trade'
import { withDelay } from '@/api/mock/_shared'
import type { OrderRecord, TradeRecord } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { formatJson, orDash, thousands } from '@/utils/format'

/**
 * 成交回报页：对应 MySQL T_Trade / T_Trade_his。
 *
 * 与「委托单」的关系：成交按 OrderNo 挂在委托上，可相互下钻。
 */

const accent = '#0d9c9c'

const tab = ref<'active' | 'history'>('active')
const keyword = ref('')
const codeFilter = ref('')
const page = ref(1)
const pageSize = ref(10)

const { data, loading, error, isMock, mockReason, load } = useResource(
  (params: Record<string, unknown>) => tradeApi.listTrades(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockTradeApi.listTrades(params)) },
)

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    code: codeFilter.value.trim() || undefined,
    table: tab.value === 'active' ? 'T_Trade' : 'T_Trade_his',
  }
}

function reload() {
  load(currentParams())
}

onMounted(reload)

function handleSearch() {
  page.value = 1
  reload()
}

function resetFilters() {
  keyword.value = ''
  codeFilter.value = ''
  page.value = 1
  reload()
}

const rows = computed(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = rows.value
  const amount = list.reduce((s, r) => s + (r.Price ?? 0) * Number(r.Volumn ?? 0), 0)
  const volumn = list.reduce((s, r) => s + Number(r.Volumn ?? 0), 0)
  const orders = new Set(list.map((r) => r.OrderNo)).size
  return { amount, volumn, orders, count: list.length }
})

/** 成交均价（按当前页口径） */
const avgPrice = computed(() => {
  const v = summary.value.volumn
  return v > 0 ? summary.value.amount / v : 0
})

/** 各合约成交量分布 */
const codeChartOption = computed(() => {
  const map = new Map<string, number>()
  for (const r of rows.value) {
    const key = r.Code ?? '未知'
    map.set(key, (map.get(key) ?? 0) + Number(r.Volumn ?? 0))
  }
  const entries = [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8)
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>成交量 {c}（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11 } },
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '42%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data: entries.map(([name, value]) => ({ name, value })),
        color: ['#0d9c9c', '#3b6ff5', '#7c5cf0', '#dd7a15', '#d6455e', '#2f9e5f', '#d94f8a', '#5b6b8c'],
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<TradeRecord> = [
  {
    title: '成交编号',
    key: 'TradeNO',
    width: 145,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono' }, orDash(row.TradeNO)) },
      ),
  },
  {
    title: '委托编号',
    key: 'OrderNo',
    width: 120,
    render: (row) => h('span', { class: 'mono' }, orDash(row.OrderNo)),
  },
  {
    title: '策略编号',
    key: 'StrategyID',
    width: 150,
    render: (row) => h('span', { class: 'mono' }, orDash(row.StrategyID)),
  },
  {
    title: '账号',
    key: 'Account',
    width: 175,
    render: (row) => h('span', { class: 'mono' }, orDash(row.Account)),
  },
  {
    title: '合约',
    key: 'Code',
    width: 110,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 500 } }, orDash(row.Code)),
  },
  { title: '市场', key: 'Market', width: 90, render: (row) => orDash(row.Market) },
  {
    title: '成交量',
    key: 'Volumn',
    width: 100,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, orDash(row.Volumn)),
  },
  {
    title: '成交价',
    key: 'Price',
    width: 110,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, row.Price != null ? row.Price.toFixed(4) : '-'),
  },
  {
    title: '成交金额',
    key: 'amount',
    width: 125,
    align: 'right',
    render: (row) => {
      const v = (row.Price ?? 0) * Number(row.Volumn ?? 0)
      return h('span', { class: 'num' }, v ? v.toFixed(2) : '-')
    },
  },
  {
    title: '成交时间',
    key: 'TradeTime',
    width: 120,
    render: (row) => orDash(row.TradeTime),
  },
  {
    title: '操作',
    key: 'actions',
    width: 78,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, onClick: () => openDetail(row) },
        { default: () => '详情' },
      ),
  },
]

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<TradeRecord | null>(null)

/** 同委托号下的其他成交（演示态读 mock；真实接口下复用同一委托的成交列表） */
const siblingTrades = ref<TradeRecord[]>([])

/** 用成交反查委托（成交 → 委托 下钻） */
const relatedOrder = ref<OrderRecord | null>(null)

const siblingColumns: DataTableColumns<TradeRecord> = [
  { title: '成交编号', key: 'TradeNO', render: (row) => h('span', { class: 'mono' }, orDash(row.TradeNO)) },
  { title: '成交量', key: 'Volumn', width: 90, align: 'right', render: (row) => orDash(row.Volumn) },
  {
    title: '成交价',
    key: 'Price',
    width: 100,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, row.Price != null ? row.Price.toFixed(4) : '-'),
  },
  { title: '成交时间', key: 'TradeTime', width: 120, render: (row) => orDash(row.TradeTime) },
]

/**
 * 打开详情并做双向下钻。
 * 演示态：直接读 mock；
 * 真实态：走后端接口（listTradesByOrder / listOrders），查不到就留空，
 * 不会拿假数据顶上。
 */
async function loadRelated(row: TradeRecord) {
  siblingTrades.value = []
  relatedOrder.value = null
  if (!row.OrderNo) return

  if (isMock.value) {
    siblingTrades.value = mockTradeApi
      .tradesByOrderNo(row.OrderNo)
      .filter((t) => t.TradeNO !== row.TradeNO)
    relatedOrder.value =
      (mockTradeApi.listOrders({ pageSize: 999 }).list as OrderRecord[]).find(
        (o) => o.OrderNo === row.OrderNo,
      ) ?? null
    return
  }

  try {
    const [trades, orders] = await Promise.all([
      tradeApi.listTradesByOrder(row.OrderNo),
      tradeApi.listOrders({ keyword: row.OrderNo, pageSize: 1 }),
    ])
    siblingTrades.value = trades.filter((t) => t.TradeNO !== row.TradeNO)
    relatedOrder.value = orders.list[0] ?? null
  } catch {
    siblingTrades.value = []
    relatedOrder.value = null
  }
}

function openDetail(row: TradeRecord) {
  active.value = row
  showDetail.value = true
  void loadRelated(row)
}

const prettyOrderParam = computed(() => formatJson(relatedOrder.value?.OrderParam))
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="成交笔数" :value="summary.count" suffix="笔" :icon="CashOutline" color="#2f9e5f" hint="本页口径" />
      </n-gi>
      <n-gi>
        <StatCard label="成交总量" :value="thousands(summary.volumn)" suffix="股" :icon="WalletOutline" :color="accent" hint="本页口径" />
      </n-gi>
      <n-gi>
        <StatCard
          label="成交金额"
          :value="thousands(Math.round(summary.amount))"
          suffix="元"
          :icon="TrendingUpOutline"
          color="#dd7a15"
          hint="本页口径（价×量）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="成交均价"
          :value="avgPrice ? avgPrice.toFixed(4) : '-'"
          :icon="SwapHorizontalOutline"
          color="#7c5cf0"
          hint="加权均价（价×量 / 总量）"
        />
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="CashOutline" :color="accent" :size="17" />
          各合约成交量分布
        </span>
      </template>
      <template #header-extra>
        <span class="text-muted" style="font-size: 12px">按当前页 {{ rows.length }} 笔统计，最多显示 8 个合约</span>
      </template>
      <BaseChart :option="codeChartOption" :height="280" empty-text="暂无成交数据" />
    </n-card>

    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="CashOutline" :color="accent" :size="17" />
          成交回报列表
        </span>
      </template>

      <!-- 主表 / 历史表切换（必须用 #tab 插槽） -->
      <n-tabs v-model:value="tab" type="segment" size="small" style="margin-bottom: 14px" @update:value="handleSearch">
        <n-tab-pane name="active">
          <template #tab>当前成交（T_Trade）</template>
        </n-tab-pane>
        <n-tab-pane name="history">
          <template #tab>历史成交（T_Trade_his）</template>
        </n-tab-pane>
      </n-tabs>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索成交号 / 委托号 / 账号"
          clearable
          style="width: 260px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-input
          v-model:value="codeFilter"
          placeholder="合约代码"
          clearable
          style="width: 130px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />

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
        :row-key="(row: TradeRecord) => String(row.id)"
        :scroll-x="1420"
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

    <!-- 成交详情 -->
    <n-drawer v-model:show="showDetail" :width="620" placement="right">
      <n-drawer-content :title="`成交详情 · ${active?.TradeNO ?? ''}`" closable>
        <template v-if="active">
          <n-descriptions :column="2" label-placement="top" size="small" bordered>
            <n-descriptions-item label="成交编号">
              <span class="mono">{{ orDash(active.TradeNO) }}</span>
            </n-descriptions-item>
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
            </n-descriptions-item>
            <n-descriptions-item label="市场">{{ orDash(active.Market) }}</n-descriptions-item>
            <n-descriptions-item label="成交量">{{ orDash(active.Volumn) }}</n-descriptions-item>
            <n-descriptions-item label="成交价">
              <span class="num">{{ active.Price != null ? active.Price.toFixed(4) : '-' }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="成交金额">
              <span class="num">
                {{ active.Price != null ? ((active.Price * Number(active.Volumn ?? 0))).toFixed(2) : '-' }}
              </span>
            </n-descriptions-item>
            <n-descriptions-item label="成交时间" :span="2">{{ orDash(active.TradeTime) }}</n-descriptions-item>
          </n-descriptions>

          <!-- 成交 → 委托 下钻 -->
          <div class="detail-block">
            <div class="detail-title">
              <n-icon :component="LinkOutline" :color="accent" />
              关联委托
            </div>
            <div v-if="relatedOrder" class="rel-card">
              <div class="rel-row">
                <span class="rel-k">委托编号</span>
                <span class="mono">{{ orDash(relatedOrder.OrderNo) }}</span>
              </div>
              <div class="rel-row">
                <span class="rel-k">合约</span>
                <span class="mono">{{ orDash(relatedOrder.Code) }} {{ orDash(relatedOrder.CodeName) }}</span>
              </div>
              <div class="rel-row">
                <span class="rel-k">委托价 / 量</span>
                <span class="num">
                  {{ relatedOrder.OrderPrice != null ? relatedOrder.OrderPrice.toFixed(4) : '-' }}
                  /
                  {{ relatedOrder.OrderVolumn ?? '-' }}
                </span>
              </div>
              <div class="rel-row">
                <span class="rel-k">委托状态</span>
                <n-tag size="small" round :bordered="false" type="info">
                  {{ orDash(relatedOrder.StatueDis) }}
                </n-tag>
              </div>
              <div class="rel-row">
                <span class="rel-k">委托时间</span>
                <span>{{ orDash(relatedOrder.OrderTime) }}</span>
              </div>
            </div>
            <div v-else class="text-muted" style="font-size: 13px">
              未在当前演示数据中找到对应委托（真实实现应由后端按委托号反查 T_Order / T_Order_his）。
            </div>
          </div>

          <!-- 同委托的其他成交 -->
          <div v-if="siblingTrades.length" class="detail-block">
            <div class="detail-title">
              <n-icon :component="CashOutline" :color="accent" />
              同一委托的其他成交（{{ siblingTrades.length }}）
            </div>
            <n-data-table
              :columns="siblingColumns"
              :data="siblingTrades"
              :bordered="false"
              size="small"
              :pagination="false"
              :max-height="220"
            />
          </div>

          <div v-if="relatedOrder" class="detail-block">
            <div class="detail-title">
              <n-icon :component="CodeSlashOutline" :color="accent" />
              委托的下单参数
            </div>
            <n-code :code="prettyOrderParam" language="json" show-line-numbers word-wrap />
          </div>

          <div class="detail-note">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <div>
              数据来自 MySQL 表 <code>T_Trade</code> / <code>T_Trade_his</code>；
              通过 <code>OrderNo</code> 与委托表 <code>T_Order</code> 关联。
            </div>
          </div>
        </template>

        <template #footer>
          <n-button size="small" @click="showDetail = false">关闭</n-button>
        </template>
      </n-drawer-content>
    </n-drawer>
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

.detail-block {
  margin-top: 20px;
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
}

.rel-card {
  padding: 12px 14px;
  background: var(--bg-body);
  border-radius: var(--radius-sm);
}

.rel-row {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 4px 0;
  font-size: 13px;
}

.rel-k {
  flex: none;
  width: 104px;
  color: var(--text-muted);
}

.detail-note {
  display: flex;
  gap: 10px;
  margin-top: 18px;
  padding: 12px 14px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid v-bind(accent);
}

.detail-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
</style>
