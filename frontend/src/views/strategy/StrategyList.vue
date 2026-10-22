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
  NPopconfirm,
  NSelect,
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  CheckmarkCircleOutline,
  CloseOutline,
  CodeSlashOutline,
  CreateOutline,
  EyeOutline,
  LayersOutline,
  PauseOutline,
  PlayOutline,
  RefreshOutline,
  TimeOutline,
  TrendingUpOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { strategyApi } from '@/api/endpoints'
import {
  mockConditionalStats,
  mockDailyCreated,
  mockStrategyApi,
  mockTypeDistribution,
} from '@/api/mock/strategy'
import { withDelay } from '@/api/mock/_shared'
import { CONDITIONAL_FLAGS, CONDITIONAL_MODES, CONDITIONAL_TYPES, type Conditional } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { formatJson, orDash, shortDateTime, thousands } from '@/utils/format'

/** 条件单策略页：查询 / 明细 / 启停 / 删除 */

const message = useMessage()
const accent = '#7c5cf0'

const keyword = ref('')
const typeFilter = ref<number | null>(null)
const modeFilter = ref<number | null>(null)
/** 数据源：运行中主表 / 历史表 / 并集视图（对应 V_Conditional） */
const source = ref<'running' | 'history' | 'all'>('running')
const page = ref(1)
const pageSize = ref(10)

const { data, loading, error, isMock, load } = useResource(
  (params: Record<string, unknown>) => strategyApi.listConditionals(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockStrategyApi.listConditionals(params)) },
)

/**
 * 近 14 天新增条件单（趋势图）。
 *
 * ⚠️ 这里**曾经是个漏做的占位**：原先写的是
 * `const daily = isMock.value ? mockDailyCreated() : []` ——
 * 真实态下恒为空数组，于是图表永远是空的。
 * 那个写法在当时是对的（后端确实还没有这个接口），
 * 但接口做出来之后一直没回头接上，也没人发现。
 *
 * 现在改为走真实接口，并按**当前数据源**（运行中/历史/全部）取数 ——
 * 不跟着 source 走的话，图表与列表说的就不是同一批数据了。
 *
 * ⚠️ 同时**显式传 14 天时间范围**：后端的 `from`/`to` 都是可选的，
 * 不传就返回全部历史（按天聚合，可能很多天），页面只画 14 天，
 * 白拉再丢掉没有必要。
 */
const {
  data: dailyData,
  load: loadDaily,
} = useResource(
  (params: Record<string, unknown>) => strategyApi.listConditionalDaily(params),
  { mock: () => withDelay(mockDailyCreated()) },
)

/** 近 14 天窗口（含今天），与后端时间口径一致 */
function dailyWindow() {
  const p = (n: number) => String(n).padStart(2, '0')
  const d = (dt: Date) => `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`
  const today = new Date()
  const from = new Date(today.getTime() - 13 * 86400_000)
  return { from: `${d(from)} 00:00:00`, to: `${d(today)} 23:59:59` }
}

function loadDailyTrend() {
  loadDaily({ source: source.value, ...dailyWindow() })
}

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    type: typeFilter.value ?? undefined,
    mode: modeFilter.value ?? undefined,
    source: source.value,
  }
}

/**
 * 卡片汇总 —— **永远全量口径，不跟随任何筛选**。
 *
 * ⚠️ 2026-10-22 改：原来这里会传 `source`/`keyword`/`type`，于是切到
 * 「已停止」tab 时「运行中」那张卡片会变成 0。而卡片标签是「运行中」「已触发」
 * 这种**绝对指标**，没有"当前筛选"字样 —— 跟着 tab 变只会让人误解。
 *
 * 现在：卡片 = 整个条件单池子的现状；列表 = 按 tab 筛出来的那一批。
 * 两者口径不同是刻意的，卡片区标了「全量口径」以免混淆。
 */
const {
  data: statsData,
  load: loadStats,
} = useResource(
  (params: Record<string, unknown>) => strategyApi.conditionalStats(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockConditionalStats(params)) },
)

function reload() {
  load(currentParams())
  // 趋势跟着 tab 走（它画的就是当前筛选下的新增），但汇总不跟
  loadDailyTrend()
  loadStats()
}

/**
 * 写操作当前是否可用（后端 `app.conditional.enabled`）。
 *
 * ⚠️ 默认 `true`：演示态（mock）没有这个接口，不该让按钮全灰。
 * 真实态拉一次后按后端为准 —— 未启用时把按钮置灰并写明原因，
 * 而不是让用户点了才看到"未启用"（那种提示很容易被当成"按钮坏了"）。
 */
const writable = ref(true)
const writeDisabledReason = ref('')

async function loadWriteStatus() {
  try {
    const st = await strategyApi.getConditionalWriteStatus()
    writable.value = st?.enabled !== false
    writeDisabledReason.value = writable.value ? '' : st?.reason ?? '写操作未启用'
  } catch {
    // 拉不到就保持默认可写：真点了会由后端给出明确错误，不会静默失败
  }
}

onMounted(() => {
  reload()
  loadWriteStatus()
})

function handleSearch() {
  page.value = 1
  reload()
}

function resetFilters() {
  keyword.value = ''
  typeFilter.value = null
  modeFilter.value = null
  source.value = 'running'
  page.value = 1
  reload()
}

const typeOptions = Object.entries(CONDITIONAL_TYPES).map(([value, label]) => ({
  label,
  value: Number(value),
}))
const modeOptions = Object.entries(CONDITIONAL_MODES).map(([value, label]) => ({
  label,
  value: Number(value),
}))

const rows = computed(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)

/* ------------------------------ 汇总 ------------------------------ */

/**
 * 卡片汇总 —— **全量口径**，来自后端 `/stats/summary`。
 *
 * 这里曾经是对 `rows`（当前页）做 filter / reduce，导致「运行中」「已触发」
 * 这类标签下的数字**翻页就变**。现在与列表同筛选条件、由数据库统计全表，
 * 与列表的 `total` 同源（`totalCount` 应当相等）。
 */
const summary = computed(() => {
  const s = statsData.value
  return {
    running: s?.runningCount ?? 0,
    triggered: s?.triggeredCount ?? 0,
    runCount: s?.totalRunCount ?? 0,
    paused: s?.pausedCount ?? 0,
  }
})

const typeChartOption = computed(() => {
  // 类型分布可由当前页数据算出，真实接口下同样成立（不走 mock）
  const counts = new Map<number, number>()
  for (const r of rows.value) {
    if (r.Type == null) continue
    counts.set(r.Type, (counts.get(r.Type) ?? 0) + 1)
  }
  const dist = [...counts.entries()].map(([type, count]) => ({
    name: CONDITIONAL_TYPES[type as keyof typeof CONDITIONAL_TYPES] ?? `类型 ${type}`,
    count,
  }))
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 条（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        label: { show: false },
        data: dist.map((d) => ({ name: d.name, value: d.count })),
        color: ['#7c5cf0', '#3b6ff5', '#0d9c9c', '#dd7a15'],
      },
    ],
  }
})

const dailyChartOption = computed(() => {
  /*
   * 趋势有**自己的** useResource（含自己的 mock 分支），所以这里直接用
   * `dailyData` 即可 —— **不要**再拿列表那个资源的 `isMock` 来判断走哪边：
   * 那样既重复处理，又会犯 README 第 5 条记的时序坑
   * （列表的 isMock 在 await 之前仍是 false，会误判成真实态）。
   *
   * 后端只返回**有数据的日期**，所以这里按 14 天窗口补零：
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
    xAxis: {
      type: 'category',
      data: filled.map((d) => d.date.slice(5)),
      axisLabel: { fontSize: 11 },
    },
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
              { offset: 0, color: 'rgba(124,92,240,0.28)' },
              { offset: 1, color: 'rgba(124,92,240,0.02)' },
            ],
          },
        },
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

/** Mode → 标签样式 */
function modeTagType(mode: number | null): 'success' | 'warning' | 'error' | 'default' | 'info' {
  switch (mode) {
    case 1:
      return 'success'
    case 0:
      return 'default'
    case 2:
      return 'warning'
    case 3:
      return 'info'
    default:
      return 'default'
  }
}

const columns: DataTableColumns<Conditional> = [
  {
    title: '条件单号',
    key: 'id',
    width: 150,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono' }, String(row.id)) },
      ),
  },
  {
    title: '账号',
    key: 'Account',
    width: 165,
    render: (row) => h('span', { class: 'mono' }, orDash(row.Account)),
  },
  {
    title: '名称',
    key: 'Name',
    width: 128,
    ellipsis: { tooltip: true },
    render: (row) => orDash(row.Name),
  },
  {
    title: '类型',
    key: 'Type',
    width: 118,
    render: (row) => {
      const label = row.Type != null ? CONDITIONAL_TYPES[row.Type as keyof typeof CONDITIONAL_TYPES] : null
      return label ? h(NTag, { size: 'small', round: true, bordered: false, type: 'info' }, { default: () => label }) : '-'
    },
  },
  {
    title: '云单状态',
    key: 'Mode',
    width: 100,
    render: (row) => {
      const label = row.Mode != null ? CONDITIONAL_MODES[row.Mode as keyof typeof CONDITIONAL_MODES] : '未知'
      return h(
        NTag,
        { size: 'small', round: true, bordered: false, type: modeTagType(row.Mode) },
        { default: () => label },
      )
    },
  },
  {
    title: '触发状态',
    key: 'Status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          type: row.Status === 1 ? 'warning' : 'default',
        },
        { default: () => (row.Status === 1 ? '已触发' : '未触发') },
      ),
  },
  {
    title: '有效标志',
    key: 'Flag',
    width: 96,
    render: (row) => {
      const label = row.Flag != null ? CONDITIONAL_FLAGS[row.Flag as keyof typeof CONDITIONAL_FLAGS] : '-'
      const type = row.Flag === 0 ? 'success' : row.Flag === 1 ? 'warning' : 'error'
      return h(NTag, { size: 'small', round: true, bordered: false, type }, { default: () => label })
    },
  },
  {
    title: '运行次数',
    key: 'RunCount',
    // 从 96 收窄到 76，让给「操作」列 —— 这一列只放数字，
    // 表头 4 个字 + 单元格左右内边距刚好占满，再窄表头就会挤成两行。
    width: 76,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, thousands(row.RunCount ?? 0)),
  },
  {
    title: '加入时间',
    key: 'AddTime',
    width: 132,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          // vueuc 要求槽内恰好一个子节点；多行文案要拼成一个字符串
          trigger: () => h('span', {}, shortDateTime(row.AddTime)),
          default: () => `加入：${row.AddTime ?? '-'}\n修改：${row.ModifyTime ?? '-'}`,
        },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    // 明细 / 停止 / 运行 / 终止 四个按钮要在一行放下。
    // 每个 tiny 按钮（图标 + 2 个汉字）约 58px，四个 ≈ 232px，
    // 加 3 个 2px 间隙和单元格内边距 → 至少要 250px。
    // 原来是 168，直接换行成两行（实测截图确认）。
    width: 252,
    fixed: 'right',
    render: (row) =>
      // 用单个 div 包裹，避免多子节点结构问题。
      // ⚠️ 布局必须写成**行内样式**，不能只靠 <style scoped> 里的 .row-actions：
      //    这些节点是 n-data-table 在【它自己的渲染上下文】里调 render() 生成的，
      //    拿不到本组件的 scope id，scoped 规则命中不了 →
      //    退化成普通 div（display:block），里面 inline-block 的按钮就自然换行了。
      //    行内样式与 scoping 无关，稳定生效。
      h('div', {
        class: 'row-actions',
        style: 'display:flex;flex-wrap:nowrap;align-items:center;gap:2px',
      }, [
        h(
          NButton,
          { size: 'tiny', quaternary: true, onClick: () => openDetail(row) },
          { icon: () => h(NIcon, null, { default: () => h(EyeOutline) }), default: () => '明细' },
        ),
        h(
          NPopconfirm,
          { onPositiveClick: () => setMode(row, 0), positiveText: '确认', negativeText: '取消' },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'warning', disabled: isTerminal(row.Mode) || row.Mode === 0 || !writable },
                {
                  icon: () => h(NIcon, null, { default: () => h(PauseOutline) }),
                  default: () => '停止',
                },
              ),
            default: () => `确认停止条件单 ${row.id}？会下发 set 协议让策略平台停止它。`,
          },
        ),
        h(
          NPopconfirm,
          { onPositiveClick: () => setMode(row, 1), positiveText: '确认', negativeText: '取消' },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'primary', disabled: isTerminal(row.Mode) || row.Mode === 1 || !writable },
                {
                  icon: () => h(NIcon, null, { default: () => h(PlayOutline) }),
                  default: () => '运行',
                },
              ),
            default: () => `确认让条件单 ${row.id} 重新运行？会下发 set 协议。`,
          },
        ),
        h(
          NPopconfirm,
          { onPositiveClick: () => setMode(row, 2), positiveText: '确认', negativeText: '取消' },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'error', disabled: isTerminal(row.Mode) || !writable },
                {
                  icon: () => h(NIcon, null, { default: () => h(AlertCircleOutline) }),
                  default: () => '终止',
                },
              ),
            default: () => `确认终止条件单 ${row.id}（置为 INVALID）？终止后不可恢复。`,
          },
        ),
      ]),
  },
]

/**
 * 终态：一旦到了这里就**不能再改**。
 *
 * - `2` 终止（失效）—— 需求方明确「终止真的不可恢复」
 * - `3` 结束 —— **策略平台**在生命周期走完后自己写的（触发执行完 / 过了 EndTime）。
 *   它是「结果」不是「指令」，人工改它会让"正常走完"和"被强行结束"在审计上分不开，
 *   而且现场 `setByStrategy` 样本里从没出现过 `Mode:3`。
 *
 * ⚠️ 这里只是**禁用按钮**，真正的拦截在后端（`ConditionalDispatchService`
 * 的状态机）—— 直接 POST 接口照样能绕过 UI。
 */
function isTerminal(mode?: number | null) {
  return mode === 2 || mode === 3
}

/**
 * 状态变更（停止 / 运行 / 终止）。
 *
 * ⚠️ **不做删除**：需求方明确「不允许删除，只允许停止、终止等状态操作」——
 * 删除是不可逆的（remove 协议一旦被策略平台执行就删掉了），
 * 而状态变更是可逆的，风险低得多。
 *
 * ⚠️ 这里**不做本地假状态变更**：只有拿到策略平台的回执才算生效。
 * 没回执就是「已下发、结果未知」，页面会如实这么显示，不会先把行状态改了。
 */
async function setMode(row: Conditional, mode: number) {
  const label = mode === 1 ? '恢复运行' : mode === 0 ? '停止' : '终止'
  try {
    const res = await strategyApi.setConditional(row.id, { mode })
    const outcome = res?.outcome
    const localSync = res?.localSync ? `（本地同步：${res.localSync}）` : ''
    if (outcome === 'success') {
      message.success(`条件单 ${row.id} 已${label}${localSync}`)
      reload()
    } else if (outcome === 'business_failed') {
      message.error(`策略平台回执失败：${res?.message ?? '原因未给出'}`)
    } else if (outcome === 'local_only') {
      // ⭐ 正常路径：本地（Redis + MySQL）已经改了，只是没有平台可通知
      // （账号未连策略服务器 / 编号不在白名单 / 真平台闸未放行）。
      // 所以用 success 而不是 warning —— 说成"警告"会让人以为操作没生效。
      message.success(
        `条件单 ${row.id} 已${label}（${res?.localSync ?? '本地已改'}）` +
          (res?.note ? `；${res.note}` : ''),
        { duration: 6000 },
      )
      reload()
    } else {
      message.warning(`已下发，但未收到策略平台回执（结果未知）：条件单 ${row.id}`, { duration: 8000 })
      reload()
    }
  } catch (e) {
    message.error(`下发失败：${(e as Error)?.message ?? '未知错误'}`)
  }
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<Conditional | null>(null)
const detailTab = ref('basic')

function openDetail(row: Conditional) {
  active.value = row
  detailTab.value = 'basic'
  showDetail.value = true
}

const prettyStrategy = computed(() => formatJson(active.value?.Strategy))
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />

    <!--
      ⚠️ 这四张卡片是**全量口径**，不随下面的「运行中/已停止/全部」tab 变化。
      标签写的是「运行中」「已触发」这种绝对指标，跟着 tab 变只会让人误解
      （切到"已停止"时"运行中"变 0）。所以明确标一句，避免"卡片和列表对不上"的困惑。
    -->
    <div class="stats-scope-hint">全量口径（不随下方筛选/tab 变化）</div>

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="运行中"
          :value="summary.running"
          suffix="条"
          :icon="PlayOutline"
          color="#2f9e5f"
          hint="Mode=1"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="已触发"
          :value="summary.triggered"
          suffix="条"
          :icon="TrendingUpOutline"
          color="#dd7a15"
          hint="Status=1"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="暂停 / 失效"
          :value="summary.paused"
          suffix="条"
          :icon="PauseOutline"
          color="#d6455e"
          hint="Flag=1"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="累计运行次数"
          :value="thousands(summary.runCount)"
          suffix="次"
          :icon="RefreshOutline"
          :color="accent"
          hint="RunCount 合计"
        />
      </n-gi>
    </n-grid>

    <!-- 图表 -->
    <n-grid
      cols="1 m:2"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      item-responsive
      style="margin-top: 16px; margin-bottom: 16px"
    >
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="LayersOutline" :color="accent" :size="17" />
              条件单类型分布
            </span>
          </template>
          <BaseChart :option="typeChartOption" :height="230" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="TimeOutline" :color="accent" :size="17" />
              近 14 天新增条件单
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">
              按 AddTime 聚合（当前数据源：
              {{ source === 'running' ? '运行中' : source === 'history' ? '历史' : '全部' }}）
            </span>
          </template>
          <BaseChart
            :option="dailyChartOption"
            :height="230"
            empty-text="近 14 天内没有新增条件单"
          />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 列表 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="CodeSlashOutline" :color="accent" :size="17" />
          条件单列表
        </span>
      </template>

      <!-- 写操作未启用时提前说明，别让用户"点了才发现按钮不能用" -->
      <div v-if="!writable" class="write-off-hint">
        <n-icon :component="AlertCircleOutline" />
        <span>
          当前**不可修改状态**（后端 <code>app.conditional.enabled=false</code>）。
          启用后：先改 Redis + MySQL，若该账号连了策略服务器还会顺带通知平台。
        </span>
      </div>

      <!--
        数据源切换：按【状态】筛，不是按表筛。
        ⚠️ 2026-10-21 起主数据源是 Redis 的 strategy-* 键（实时态），
           MySQL 那三张表是 0 行 —— 原来写的「运行中（T_Conditional）」是表名，
           现在不再是查表，tab 文案必须跟着改，否则会误导成"在查某张表"。
           注意：naive-ui 2.45.x 的 n-tab-pane 只认 #tab 插槽，不认 tab 属性。
      -->
      <n-tabs v-model:value="source" type="segment" size="small" style="margin-bottom: 14px" @update:value="handleSearch">
        <n-tab-pane name="running">
          <template #tab>运行中</template>
        </n-tab-pane>
        <n-tab-pane name="history">
          <template #tab>已停止 / 已失效</template>
        </n-tab-pane>
        <n-tab-pane name="all">
          <template #tab>全部</template>
        </n-tab-pane>
      </n-tabs>

      <div class="toolbar">
        <SearchInput
          v-model:value="keyword"
          placeholder="搜索条件单号 / 账号 / 名称"
          style="width: 250px"
          :loading="loading"
          @search="handleSearch"
          @clear="handleSearch"
        />

        <n-select
          v-model:value="typeFilter"
          :options="typeOptions"
          placeholder="全部类型"
          clearable
          style="width: 172px"
          @update:value="handleSearch"
        />

        <n-select
          v-model:value="modeFilter"
          :options="modeOptions"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @update:value="handleSearch"
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
        :row-key="(row: Conditional) => String(row.id)"
        :scroll-x="1330"
        :pagination="{
          page: page,
          pageSize: pageSize,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50],
          onUpdatePage: (p: number) => { page = p; reload() },
          onUpdatePageSize: (s: number) => { pageSize = s; page = 1; reload() },
          prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
        }"
      />
    </n-card>

    <!-- 明细弹窗 -->
    <AppDialog v-model:show="showDetail" :title="`条件单明细 · ${active?.id ?? ''}`" :width="640" mask-closable>
      <template v-if="active">
        <n-tabs v-model:value="detailTab" type="line" size="small">
          <n-tab-pane name="basic">
            <template #tab>基本信息</template>
            <n-descriptions :column="2" label-placement="top" size="small" bordered>
              <n-descriptions-item label="条件单号">
                <span class="mono">{{ active.id }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="账号">
                <span class="mono">{{ orDash(active.Account) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="用户账号">
                <span class="mono">{{ orDash(active.UserAccount) }}</span>
              </n-descriptions-item>
              <n-descriptions-item label="名称">{{ orDash(active.Name) }}</n-descriptions-item>
              <n-descriptions-item label="类型">
                {{ active.Type != null ? CONDITIONAL_TYPES[active.Type as keyof typeof CONDITIONAL_TYPES] : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="云单状态">
                {{ active.Mode != null ? CONDITIONAL_MODES[active.Mode as keyof typeof CONDITIONAL_MODES] : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="触发状态">
                {{ active.Status === 1 ? '已触发' : '未触发' }}
              </n-descriptions-item>
              <n-descriptions-item label="有效标志">
                {{ active.Flag != null ? CONDITIONAL_FLAGS[active.Flag as keyof typeof CONDITIONAL_FLAGS] : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="运行次数">{{ active.RunCount ?? 0 }}</n-descriptions-item>
              <n-descriptions-item label="有效期">
                {{ active.Validity === 0 ? '长期有效' : orDash(active.Validity) }}
              </n-descriptions-item>
              <n-descriptions-item label="加入时间">{{ orDash(active.AddTime) }}</n-descriptions-item>
              <n-descriptions-item label="修改时间">{{ orDash(active.ModifyTime) }}</n-descriptions-item>
              <n-descriptions-item label="开始生效">{{ orDash(active.BeginTime) }}</n-descriptions-item>
              <n-descriptions-item label="结束时间">{{ orDash(active.EndTime) }}</n-descriptions-item>
              <n-descriptions-item label="触发时间">{{ orDash(active.TriggeredTime) }}</n-descriptions-item>
              <n-descriptions-item label="关闭时间">{{ orDash(active.CloseTime) }}</n-descriptions-item>
              <n-descriptions-item label="描述" :span="2">{{ orDash(active.Description) }}</n-descriptions-item>
              <n-descriptions-item label="修改原因" :span="2">{{ orDash(active.Reason) }}</n-descriptions-item>
            </n-descriptions>
          </n-tab-pane>

          <n-tab-pane name="json">
            <template #tab>原始报文</template>
            <div class="json-head">
              <n-icon :component="CodeSlashOutline" :color="accent" />
              <span>
                对应 <code>T_Conditional.Strategy</code> 字段 / Redis 键
                <code>strategy-&lt;账号&gt;</code> 中的 <code>strategy</code> 字段，
                即客户端 <code>create</code> 协议原文。
              </span>
            </div>
            <n-code :code="prettyStrategy" language="json" show-line-numbers word-wrap />
          </n-tab-pane>
        </n-tabs>
      </template>

      <template #footer>
        <!--
          ⚠️ 需求方明确「不允许删除，只允许停止、终止等状态操作」。
          删除不可逆（remove 协议一旦执行就删掉了），状态变更是可逆的。
          所以这里只有「停止/运行」与「终止」，没有删除。
        -->
        <n-button
          size="small"
          :type="active?.Mode === 1 ? 'warning' : 'primary'"
          :disabled="isTerminal(active?.Mode) || !writable"
          @click="active && setMode(active, active.Mode === 1 ? 0 : 1)"
        >
          <template #icon>
            <n-icon :component="active?.Mode === 1 ? PauseOutline : PlayOutline" />
          </template>
          {{ active?.Mode === 1 ? '停止运行' : '开始运行' }}
        </n-button>
        <n-button
          size="small"
          type="error"
          ghost
          :disabled="isTerminal(active?.Mode) || !writable"
          @click="active && setMode(active, 2)"
        >
          <template #icon>
            <n-icon :component="AlertCircleOutline" />
          </template>
          终止（INVALID）
        </n-button>
        <span v-if="isTerminal(active?.Mode)" class="terminal-hint">
          {{ active?.Mode === 3 ? '已结束（策略平台写入的终态，不可操作）' : '已终止，不可恢复' }}
        </span>
        <div class="spacer" />
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

/*
 * ⚠️ `.row-actions` 的 flex 布局写在 TS 里的**行内样式**上，不在这里。
 * 原因：列的 render() 是被 n-data-table 在它自己的渲染上下文里调用的，
 * 生成的节点带的是 n-data-table 的 scope id，本组件的 scoped 规则命中不了 ——
 * 这就是「明明写了 display:flex 却还是换行」的根因。
 *
 * 下面这条用 :deep() 从 n-data-table（这个元素在本模板里，有 scope id）往下钻，
 * 只做一件事：禁止按钮被 flex 压缩。否则列宽一紧，按钮文字会自己折成两行。
 */
.n-data-table :deep(.row-actions .n-button) {
  flex: 0 0 auto;
  white-space: nowrap;
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


.spacer {
  flex: 1;
}

/* 统计口径提示：一行小字，说明卡片不随筛选变化 */
.stats-scope-hint {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  opacity: 0.8;
}

/* 写操作未启用时的说明条 */
.write-off-hint {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-bottom: 12px;
  padding: 9px 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid #e8a33d;
}

.write-off-hint code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

/* 终态提示：告诉运维"为什么按钮是灰的"，而不是让他以为页面坏了 */
.terminal-hint {
  align-self: center;
  font-size: 12px;
  color: var(--text-secondary);
  opacity: 0.85;
}
</style>
