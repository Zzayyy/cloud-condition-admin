<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NDrawer,
  NDrawerContent,
  NGi,
  NGrid,
  NIcon,
  NInput,
  NProgress,
  NSelect,
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  BarChartOutline,
  CloseOutline,
  DocumentTextOutline,
  InformationCircleOutline,
  LocationOutline,
  PeopleOutline,
  PersonOutline,
  RefreshOutline,
  SearchOutline,
  TimeOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { accountApi } from '@/api/endpoints'
import { mockAccountApi, mockDailyLogs } from '@/api/mock/account'
import { withDelay } from '@/api/mock/_shared'
import type { AccountLogStat, UserLog } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { orDash, shortDateTime, thousands } from '@/utils/format'

/**
 * 登录日志页（MySQL `T_UserLog`，现场 19.1 万行）。
 *
 * ⚠️ 现场实测的关键事实：`LogIP` / `LogMAC` / `LogOS` **三列全部为 NULL**
 * （19.1 万行无例外），`Info` 实测只有 `"user registed"` 一种值（原文拼写如此）。
 * 所以这一页**查不到来源设备**，页面上必须明说，不能让使用者以为是自己筛错了。
 *
 * 演示数据里给一部分行补了设备信息，以便展示「有/无设备信息」两种形态，
 * 但页面的提示与统计口径都按真实现状说明。
 */

const accent = '#d94f8a'

const tab = ref<'logs' | 'stats'>('logs')

/* ------------------------------ 日志列表 ------------------------------ */

const keyword = ref('')
const accountFilter = ref('')
const infoFilter = ref<string | null>(null)
const deviceFilter = ref<'all' | 'has' | 'none'>('all')
const page = ref(1)
const pageSize = ref(15)

const {
  data: logData,
  loading: logLoading,
  error: logError,
  isMock,
  load: loadLogs,
} = useResource(
  (params: Record<string, unknown>) => accountApi.listLogs(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockAccountApi.listLogs(params)) },
)

/** 聚合统计 */
const {
  data: statData,
  loading: statLoading,
  load: loadStats,
} = useResource(
  (params: Record<string, unknown>) => accountApi.listLogStats(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockAccountApi.listLogStats(params)) },
)

/**
 * 近 14 天日志量（趋势有自己的 useResource，含自己的 mock 分支）。
 *
 * ⚠️ **必须显式传时间范围**：后端 `selectDaily` 的 `from`/`to` 都是可选的，
 * 不传就返回**全部历史**（现场实测 42 天，且会随年月增长）。
 * 页面只画 14 天，不传的话每次都要白拉全量再丢掉大部分。
 */
const {
  data: dailyData,
  load: loadDaily,
} = useResource(
  (params: Record<string, unknown>) => accountApi.listLogDaily(params),
  { mock: () => withDelay(mockDailyLogs()) },
)

/** 近 14 天窗口（含今天），格式 yyyy-MM-dd HH:mm:ss —— 与后端时间口径一致 */
function dailyWindow() {
  const p = (n: number) => String(n).padStart(2, '0')
  const d = (dt: Date) =>
    `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`
  const today = new Date()
  const from = new Date(today.getTime() - 13 * 86400_000)
  return { from: `${d(from)} 00:00:00`, to: `${d(today)} 23:59:59` }
}

function logParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    account: accountFilter.value.trim() || undefined,
    info: infoFilter.value ?? undefined,
    withDevice: deviceFilter.value === 'all' ? undefined : deviceFilter.value === 'has',
  }
}

function reload() {
  loadLogs(logParams())
  loadStats({ page: 1, pageSize: 50 })
  loadDaily(dailyWindow())
}

onMounted(reload)

function handleSearch() {
  page.value = 1
  reload()
}

function resetFilters() {
  keyword.value = ''
  accountFilter.value = ''
  infoFilter.value = null
  deviceFilter.value = 'all'
  page.value = 1
  reload()
}

const infoOptions = ['user registed', 'user login', 'user logout', 'pwd changed', 'account updated'].map(
  (v) => ({ label: v, value: v }),
)

const logRows = computed<UserLog[]>(() => logData.value?.list ?? [])
const logTotal = computed(() => logData.value?.total ?? 0)
const statRows = computed<AccountLogStat[]>(() => statData.value?.list ?? [])

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = logRows.value
  const withDevice = list.filter((r) => r.LogIP || r.LogMAC || r.LogOS).length
  const accounts = new Set(list.map((r) => r.Account)).size
  return {
    count: list.length,
    withDevice,
    missingDevice: list.length - withDevice,
    accounts,
  }
})

/** 近 14 天日志量（趋势有自己的 useResource，含自己的 mock 分支 —— 直接用 dailyData） */
const dailyOption = computed(() => {
  /*
   * ⚠️ 不要用列表资源的 `isMock` 来判断走哪边：那会犯 README 第 5 条记的时序坑
   * （列表的 isMock 在 await 之前仍是 false，会把演示态误判成真实态）。
   * 趋势自己那次 useResource 已经处理了降级。
   *
   * 后端只返回有数据的日期，这里按 14 天窗口补零 ——
   * 不补的话折线会把两个相隔多日的点直接连起来，看起来像「一直有数据」。
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
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(217,79,138,0.28)' },
              { offset: 1, color: 'rgba(217,79,138,0.02)' },
            ],
          },
        },
      },
    ],
  }
})

/** Top 账号日志量 */
const topAccountOption = computed(() => {
  const top = [...statRows.value].sort((a, b) => b.count - a.count).slice(0, 8)
  return {
    grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', name: '日志条数', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: top.map((s) => s.Account).reverse(),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        data: top.map((s) => s.count).reverse(),
        barMaxWidth: 18,
        itemStyle: { color: accent, borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

/** 设备信息来源是否可用 */
function hasDevice(row: UserLog): boolean {
  return Boolean(row.LogIP || row.LogMAC || row.LogOS)
}

const logColumns: DataTableColumns<UserLog> = [
  { title: '日志 ID', key: 'id', width: 100, render: (row) => h('span', { class: 'mono' }, String(row.id)) },
  {
    title: '账号',
    key: 'Account',
    width: 185,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => filterByAccount(row.Account ?? '') },
        { default: () => h('span', { class: 'mono' }, orDash(row.Account)) },
      ),
  },
  {
    title: '时间',
    key: 'LogTime',
    width: 150,
    render: (row) =>
      h(NTooltip, { trigger: 'hover' }, {
        trigger: () => h('span', {}, shortDateTime(row.LogTime)),
        default: () => row.LogTime ?? '-',
      }),
  },
  {
    title: '信息',
    key: 'Info',
    width: 150,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          type: row.Info === 'user registed' ? 'info' : 'default',
        },
        { default: () => orDash(row.Info) },
      ),
  },
  {
    title: '来源 IP',
    key: 'LogIP',
    width: 140,
    render: (row) =>
      row.LogIP
        ? h('span', { class: 'mono' }, row.LogIP)
        : h('span', { class: 'mono text-muted' }, 'NULL'),
  },
  {
    title: '来源 MAC',
    key: 'LogMAC',
    width: 150,
    render: (row) =>
      row.LogMAC ? h('span', { class: 'mono' }, row.LogMAC) : h('span', { class: 'mono text-muted' }, 'NULL'),
  },
  {
    title: '登录系统',
    key: 'LogOS',
    width: 120,
    render: (row) => (row.LogOS ? row.LogOS : h('span', { class: 'text-muted' }, 'NULL')),
  },
]

const statColumns: DataTableColumns<AccountLogStat> = [
  {
    title: '账号',
    key: 'Account',
    width: 200,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => filterByAccount(row.Account) },
        { default: () => h('span', { class: 'mono' }, row.Account) },
      ),
  },
  {
    title: '日志条数',
    key: 'count',
    width: 180,
    sorter: (a, b) => a.count - b.count,
    render: (row) => {
      const max = Math.max(...statRows.value.map((s) => s.count), 1)
      const pct = Math.round((row.count / max) * 100)
      return h('div', { class: 'cnt-cell' }, [
        h('span', { class: 'num' }, thousands(row.count)),
        h(NProgress, {
          type: 'line',
          percentage: pct,
          height: 4,
          showIndicator: false,
          color: accent,
          railColor: 'var(--bg-hover)',
          borderRadius: 2,
        }),
      ])
    },
  },
  {
    title: '最早',
    key: 'firstTime',
    width: 170,
    render: (row) => orDash(row.firstTime),
  },
  {
    title: '最近',
    key: 'lastTime',
    width: 170,
    render: (row) => orDash(row.lastTime),
  },
]

function filterByAccount(account: string) {
  accountFilter.value = account
  tab.value = 'logs'
  page.value = 1
  reload()
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<UserLog | null>(null)
const activeStat = ref<AccountLogStat | null>(null)

function openDetail(row: UserLog) {
  active.value = row
  activeStat.value = statRows.value.find((s) => s.Account === row.Account) ?? null
  showDetail.value = true
}
</script>

<template>
  <div>
    <QueryErrorAlert v-if="logError" :message="logError" :loading="logLoading" @retry="reload" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="日志总条数"
          :value="thousands(logTotal)"
          suffix="条"
          :icon="DocumentTextOutline"
          :color="accent"
          hint="MySQL T_UserLog（现场 19.1 万）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="本页有设备信息"
          :value="summary.withDevice"
          suffix="条"
          :icon="LocationOutline"
          color="#2f9e5f"
          hint="IP/MAC/OS 至少一项非空"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="本页缺设备信息"
          :value="summary.missingDevice"
          suffix="条"
          :icon="AlertCircleOutline"
          color="#dd7a15"
          hint="现场真实情况是三列全 NULL"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="涉及账号"
          :value="summary.accounts"
          suffix="个"
          :icon="PeopleOutline"
          color="#3b6ff5"
          hint="本页去重"
        />
      </n-gi>
    </n-grid>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="TimeOutline" :color="accent" :size="17" />
              近 14 天日志量
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">按 LogTime 聚合（T_UserLog）</span>
          </template>
          <BaseChart :option="dailyOption" :height="230" empty-text="近 14 天内没有登录日志" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="BarChartOutline" :color="accent" :size="17" />
              日志量 Top 8 账号
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">按聚合结果</span>
          </template>
          <BaseChart :option="topAccountOption" :height="230" empty-text="暂无数据" />
        </n-card>
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="DocumentTextOutline" :color="accent" :size="17" />
          登录日志
        </span>
      </template>

      <n-tabs v-model:value="tab" type="segment" size="small" style="margin-bottom: 14px">
        <n-tab-pane name="logs">
          <template #tab>明细（T_UserLog）</template>
        </n-tab-pane>
        <n-tab-pane name="stats">
          <template #tab>按账号聚合</template>
        </n-tab-pane>
      </n-tabs>

      <template v-if="tab === 'logs'">
        <div class="toolbar">
          <n-input
            v-model:value="accountFilter"
            placeholder="账号"
            clearable
            style="width: 190px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          >
            <template #prefix>
              <n-icon :component="PersonOutline" />
            </template>
          </n-input>

          <n-input
            v-model:value="keyword"
            placeholder="搜索账号 / 信息"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          >
            <template #prefix>
              <n-icon :component="SearchOutline" />
            </template>
          </n-input>

          <n-select
            v-model:value="infoFilter"
            :options="infoOptions"
            placeholder="全部信息"
            clearable
            style="width: 170px"
            @update:value="handleSearch"
          />

          <n-button-group>
            <n-button
              v-for="opt in [
                { key: 'all', label: '全部' },
                { key: 'has', label: '有设备信息' },
                { key: 'none', label: '缺设备信息' },
              ]"
              :key="opt.key"
              size="small"
              :type="deviceFilter === opt.key ? 'primary' : 'default'"
              :quaternary="deviceFilter !== opt.key"
              @click="deviceFilter = opt.key as 'all' | 'has' | 'none'; handleSearch()"
            >
              {{ opt.label }}
            </n-button>
          </n-button-group>

          <n-button size="small" @click="resetFilters">
            <template #icon>
              <n-icon :component="CloseOutline" />
            </template>
            重置
          </n-button>

          <n-button size="small" :loading="logLoading" @click="reload">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>
        </div>

        <n-data-table
          remote
          :columns="logColumns"
          :data="logRows"
          :loading="logLoading"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: UserLog) => String(row.id)"
          :scroll-x="1215"
          :row-props="(row: UserLog) => ({ style: 'cursor:pointer', onClick: () => openDetail(row) })"
          :pagination="{
            page: page,
            pageSize: pageSize,
            itemCount: logTotal,
            showSizePicker: true,
            pageSizes: [15, 30, 60],
            onUpdatePage: (p: number) => { page = p; reload() },
            onUpdatePageSize: (s: number) => { pageSize = s; page = 1; reload() },
            prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
          }"
        />
      </template>

      <template v-else>
        <div class="toolbar">
          <span class="text-muted" style="font-size: 12.5px">
            按账号聚合日志量（对应 <code>GROUP BY Account</code>）。点击账号可跳回明细。
          </span>
          <n-button size="small" :loading="statLoading" @click="reload">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>
        </div>
        <n-data-table
          :columns="statColumns"
          :data="statRows"
          :loading="statLoading"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: AccountLogStat) => row.Account"
          :scroll-x="720"
          :pagination="{ pageSize: 20 }"
        />
      </template>
    </n-card>

    <!-- 日志详情 -->
    <n-drawer v-model:show="showDetail" :width="560" placement="right">
      <n-drawer-content :title="`日志详情 #${active?.id ?? ''}`" closable>
        <template v-if="active">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="日志 ID">
              <span class="mono">{{ active.id }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="账号">
              <span class="mono">{{ orDash(active.Account) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="时间">{{ orDash(active.LogTime) }}</n-descriptions-item>
            <n-descriptions-item label="信息">{{ orDash(active.Info) }}</n-descriptions-item>
            <n-descriptions-item label="来源 IP">
              <span v-if="active.LogIP" class="mono">{{ active.LogIP }}</span>
              <span v-else class="text-muted mono">NULL（现场均为空）</span>
            </n-descriptions-item>
            <n-descriptions-item label="来源 MAC">
              <span v-if="active.LogMAC" class="mono">{{ active.LogMAC }}</span>
              <span v-else class="text-muted mono">NULL（现场均为空）</span>
            </n-descriptions-item>
            <n-descriptions-item label="登录系统">
              <span v-if="active.LogOS">{{ active.LogOS }}</span>
              <span v-else class="text-muted">NULL（现场均为空）</span>
            </n-descriptions-item>
          </n-descriptions>

          <div v-if="activeStat" class="detail-block">
            <div class="detail-title">
              <n-icon :component="BarChartOutline" :color="accent" />
              该账号的日志汇总
            </div>
            <n-descriptions :column="1" label-placement="left" size="small" bordered>
              <n-descriptions-item label="日志条数">
                <span class="num">{{ thousands(activeStat.count) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="最早">{{ orDash(activeStat.firstTime) }}</n-descriptions-item>
              <n-descriptions-item label="最近">{{ orDash(activeStat.lastTime) }}</n-descriptions-item>
            </n-descriptions>
          </div>

          <div class="detail-note">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <div>
              数据来自 MySQL 表 <code>T_UserLog</code>。
              该表 <code>LogIP</code> / <code>LogMAC</code> / <code>LogOS</code>
              在现场<b>全为 NULL</b>；如需来源设备，需要中台侧补写这三列，
              或改用 <code>T_StrategyLog</code> 的 <code>FromIP</code> / <code>FromMac</code>
              （那边有值）。
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

.detail-note code,
.toolbar code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.num {
  font-variant-numeric: tabular-nums;
}

.cnt-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
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

.detail-note b {
  color: var(--danger);
}
</style>
