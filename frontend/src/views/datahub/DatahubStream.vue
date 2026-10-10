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
  NProgress,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  AnalyticsOutline,
  ArrowForwardOutline,
  CloseOutline,
  CodeSlashOutline,
  EyeOutline,
  GitNetworkOutline,
  InformationCircleOutline,
  LayersOutline,
  RefreshOutline,
  SearchOutline,
  TimeOutline,
  TrendingUpOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { datahubApi, monitorApi } from '@/api/endpoints'
import { mockDatahubApi } from '@/api/mock/datahub'
import { TREND_METRICS, trendSeries } from '@/api/mock/monitor'
import { withDelay } from '@/api/mock/_shared'
import {
  SERVER_TYPES,
  type RedisStreamEntry,
  type RedisStreamInfo,
  type StreamRole,
  type TimeSeriesPoint,
} from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { formatJson, thousands } from '@/utils/format'

/**
 * 消息总线页：跨实例的 Redis Stream 监控。
 *
 * 现场实测的关键事实（都写在界面上，避免误判）：
 *  - 137 db0 的 DataHub_req_stream 有 407 万条，**且没有消费组**
 *  - 136 db0 的 ST-0 有 595 pending
 *  - 消费组名固定 user_group，consumer 名 = 流名本身
 */

const accent = '#dd7a15'

const keyword = ref('')
const instanceFilter = ref<string | null>(null)
const roleFilter = ref<StreamRole | null>(null)

const {
  data: streamData,
  loading,
  error,
  isMock,
  mockReason,
  load,
} = useResource(
  (params: Record<string, unknown>) => datahubApi.listStreams(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockDatahubApi.listStreams(params)) },
)

function currentParams() {
  return {
    keyword: keyword.value.trim() || undefined,
    instance: instanceFilter.value ?? undefined,
    role: roleFilter.value ?? undefined,
  }
}

function reload() {
  load(currentParams())
}

onMounted(reload)

const instanceOptions = [
  { label: '192.168.1.136', value: '192.168.1.136' },
  { label: '192.168.1.137', value: '192.168.1.137' },
]

const ROLE_LABEL: Record<StreamRole, string> = {
  ingress: '入口流',
  reply: '回包流',
  downstream: '下发流',
  ack: '回执流',
  other: '其他',
}

const ROLE_COLOR: Record<StreamRole, string> = {
  ingress: '#d6455e',
  reply: '#2f9e5f',
  downstream: '#3b6ff5',
  ack: '#7c5cf0',
  other: '#5b6b8c',
}

const roleOptions = (Object.keys(ROLE_LABEL) as StreamRole[]).map((r) => ({
  label: ROLE_LABEL[r],
  value: r,
}))

const streams = computed<RedisStreamInfo[]>(() => streamData.value ?? [])

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = streams.value
  const totalEntries = list.reduce((s, x) => s + x.length, 0)
  const backlogCount = list.filter((s) => {
    const g = s.groups[0]
    return g && ((g.lag ?? 0) > 0 || g.pending > 0)
  }).length
  const noGroup = list.filter((s) => s.groups.length === 0)
  return {
    count: list.length,
    totalEntries,
    backlogCount,
    noGroupCount: noGroup.length,
    noGroupEntries: noGroup.reduce((s, x) => s + x.length, 0),
    pending: list.reduce((s, x) => s + (x.groups[0]?.pending ?? 0), 0),
    lag: list.reduce((s, x) => s + (x.groups[0]?.lag ?? 0), 0),
  }
})

/* ------------------------------ 趋势卡 ------------------------------ */

/**
 * 趋势卡的指标与数据。
 *
 * ⚠️ 这里**必须**给趋势单独走一个 useResource，不能在 onMounted 里读
 * 列表那个资源的 `isMock` —— 那样会因为请求还没回来而读到 false，
 * 于是去调真实接口、失败后返回空图（这个坑在别处踩过，见 README 第 5 条）。
 * 每个数据源各自解析，降级逻辑才一致。
 */
const trendKey = ref<string>(TREND_METRICS[1]?.key ?? 'consumerLag')
const trendWindow = ref(15)

const {
  data: trendData,
  isMock: trendIsMock,
  load: loadTrendSeries,
} = useResource(
  (params: { metric: string; windowMin: number }) =>
    monitorApi.getTimeSeries(params.metric, `${params.windowMin}m`),
  {
    mock: (params: { metric: string; windowMin: number }) =>
      withDelay(trendSeries(params.metric, Math.max(10, params.windowMin)), 120),
  },
)

const trendPoints = computed<TimeSeriesPoint[]>(() => trendData.value ?? [])
/** 趋势是否为演示态 —— 用于决定是否显示「非真实历史」提示 */
const trendIsDemo = computed(() => trendIsMock.value)

function loadTrend() {
  loadTrendSeries({ metric: trendKey.value, windowMin: trendWindow.value })
}

function changeTrend(key: string) {
  trendKey.value = key
  loadTrend()
}

function changeTrendWindow(min: number) {
  trendWindow.value = min
  loadTrend()
}

onMounted(loadTrend)

const trendOption = computed(() => {
  const def = TREND_METRICS.find((t) => t.key === trendKey.value)
  const data = trendPoints.value

  return {
    grid: { left: 8, right: 20, top: 24, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      formatter: (params: Array<{ value: number; axisValue: string }>) => {
        const p = params[0]
        if (!p) return ''
        return `${p.axisValue}<br/>${def?.label ?? ''}：${thousands(Math.round(p.value))}`
      },
    },
    xAxis: {
      type: 'category',
      data: data.map((d) => {
        const t = typeof d.time === 'number' ? new Date(d.time) : new Date(String(d.time))
        return `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`
      }),
      axisLabel: { fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 11, formatter: (v: number) => thousands(v) },
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: data.map((d) => d.value),
        lineStyle: { width: 2.5, color: accent },
        itemStyle: { color: accent },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(221,122,21,0.28)' },
              { offset: 1, color: 'rgba(221,122,21,0.02)' },
            ],
          },
        },
        markLine: def?.warnAt
          ? {
              silent: true,
              symbol: 'none',
              lineStyle: { color: '#d6455e', type: 'dashed', width: 1 },
              label: { formatter: '告警阈值', fontSize: 10, color: '#d6455e' },
              data: [{ yAxis: def.warnAt }],
            }
          : undefined,
      },
    ],
  }
})

const loadOption = computed(() => {
  // 从当前流数据推导（非演示态下没有数据就是空图，不用 mock 常量充数）
  const map = new Map<string, number>()
  for (const s of streams.value) {
    const key = `${s.instance} db${s.db}`
    map.set(key, (map.get(key) ?? 0) + s.length)
  }
  const data = [...map.entries()]
    .map(([label, value]) => ({ label, value }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8)

  return {
    grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', name: '条目数' },
    yAxis: { type: 'category', data: data.map((d) => d.label).reverse(), axisLabel: { fontSize: 11 } },
    series: [
      {
        type: 'bar',
        data: data.map((d) => d.value).reverse(),
        barMaxWidth: 18,
        itemStyle: { color: accent, borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

/** 各角色条目占比 */
const roleOption = computed(() => {
  const map = new Map<StreamRole, number>()
  for (const s of streams.value) {
    const r = s.role ?? 'other'
    map.set(r, (map.get(r) ?? 0) + s.length)
  }
  const entries = [...map.entries()].filter(([, v]) => v > 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 条（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data: entries.map(([r, v]) => ({ name: ROLE_LABEL[r], value: v })),
        color: entries.map(([r]) => ROLE_COLOR[r]),
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<RedisStreamInfo> = [
  {
    title: '流名',
    key: 'key',
    width: 190,
    fixed: 'left',
    render: (row) =>
      h('span', { class: 'mono', style: { fontWeight: 500 } }, row.key),
  },
  {
    title: '角色',
    key: 'role',
    width: 92,
    render: (row) => {
      const r = (row.role ?? 'other') as StreamRole
      return h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          style: { color: ROLE_COLOR[r], background: `${ROLE_COLOR[r]}1f`, fontWeight: 600 },
        },
        { default: () => ROLE_LABEL[r] },
      )
    },
  },
  {
    title: '实例',
    key: 'instance',
    width: 145,
    render: (row) => h('span', { class: 'mono' }, row.instance ?? '-'),
  },
  { title: 'db', key: 'db', width: 56, align: 'right' },
  {
    title: '条目数',
    key: 'length',
    width: 120,
    align: 'right',
    sorter: (a, b) => a.length - b.length,
    render: (row) =>
      h(
        'span',
        {
          class: 'num',
          style: row.length > 1_000_000 ? 'color:var(--danger);font-weight:700' : 'font-weight:600',
        },
        thousands(row.length),
      ),
  },
  {
    title: '消费组',
    key: 'groups',
    width: 110,
    render: (row) => {
      if (!row.groups.length) {
        return h(
          NTooltip,
          { trigger: 'hover' },
          {
            trigger: () =>
              h(NTag, { size: 'small', round: true, bordered: false, type: 'error' }, { default: () => '无消费组' }),
            default: () => '没有消费组 —— 消息只进不出，不会被任何消费者处理',
          },
        )
      }
      return h(NTag, { size: 'small', round: true, bordered: false, type: 'info' }, {
        default: () => `${row.groups.length} 个`,
      })
    },
  },
  {
    title: 'lag（未投递）',
    key: 'lag',
    width: 125,
    align: 'right',
    render: (row) => {
      const v = row.groups[0]?.lag ?? 0
      return h(
        'span',
        { class: 'num', style: v > 0 ? 'color:var(--warning);font-weight:600' : '' },
        row.groups.length ? thousands(v) : '-',
      )
    },
  },
  {
    title: 'pending（未确认）',
    key: 'pending',
    width: 140,
    align: 'right',
    render: (row) => {
      const v = row.groups[0]?.pending ?? 0
      return h(
        'span',
        { class: 'num', style: v > 0 ? 'color:var(--warning);font-weight:600' : '' },
        row.groups.length ? thousands(v) : '-',
      )
    },
  },
  {
    title: '消费进度',
    key: 'progress',
    width: 110,
    render: (row) => {
      // 已投递 / 总长度 —— 直观看出「消费跟不跟得上」。
      // 完整数值（lag/pending/百分比）在详情抽屉里看，表里只给一个概览。
      const g = row.groups[0]
      if (!g || row.length === 0) return h('span', { class: 'text-muted' }, '-')
      const lag = g.lag ?? 0
      const delivered = Math.max(0, row.length - lag)
      const pct = Math.round((delivered / row.length) * 100)
      return h('div', { class: 'prog-cell' }, [
        h('span', { class: 'prog-txt' }, `${pct}%`),
        h(NProgress, {
          type: 'line',
          percentage: pct,
          height: 4,
          showIndicator: false,
          color: pct === 100 ? '#2f9e5f' : '#dd7a15',
          railColor: 'var(--bg-hover)',
          borderRadius: 2,
        }),
      ])
    },
  },
  {
    title: '操作',
    key: 'actions',
    width: 76,
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
const active = ref<RedisStreamInfo | null>(null)
const entries = ref<RedisStreamEntry[]>([])
const entriesLoading = ref(false)
const detailTab = ref('entries')

const entriesError = ref<string>()

/**
 * 载入流的最近条目。
 *
 * 这里读 `isMock` 是在**点击时**（不是 onMounted），此时真实/演示已确定，
 * 所以可以安全地分支。真实接口失败时把错误显示出来，而不是静默给空表格。
 */
async function loadEntries(stream: RedisStreamInfo) {
  entriesLoading.value = true
  entriesError.value = undefined
  entries.value = []
  try {
    if (isMock.value) {
      const r = mockDatahubApi.listStreamEntries(stream.key, { page: 1, pageSize: 10, total: 10 })
      entries.value = r.list
    } else {
      const r = await datahubApi.listStreamEntries(stream.key, { page: 1, pageSize: 10 })
      entries.value = r.list
    }
  } catch (err) {
    entriesError.value = err instanceof Error ? err.message : String(err)
  } finally {
    entriesLoading.value = false
  }
}

function openDetail(row: RedisStreamInfo) {
  active.value = row
  detailTab.value = 'entries'
  showDetail.value = true
  void loadEntries(row)
}

const entryColumns: DataTableColumns<RedisStreamEntry> = [
  {
    title: '条目 ID',
    key: 'id',
    width: 160,
    render: (row) => h('span', { class: 'mono' }, row.id),
  },
  {
    title: 'request_id',
    key: 'request_id',
    width: 230,
    render: (row) => h('span', { class: 'mono' }, row.fields.request_id ?? '-'),
  },
  {
    title: 'server_type',
    key: 'server_type',
    width: 150,
    render: (row) => {
      const t = row.fields.server_type
      if (!t) return h('span', { class: 'text-muted' }, '-')
      return h(NTag, { size: 'small', round: true, bordered: false, type: 'info' }, {
        default: () => `${t} ${SERVER_TYPES[t] ?? ''}`.trim(),
      })
    },
  },
  {
    title: 'server_id',
    key: 'server_id',
    width: 100,
    align: 'right',
    render: (row) => h('span', { class: 'mono' }, row.fields.server_id ?? '-'),
  },
  {
    title: 'task（截断）',
    key: 'task',
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'mono', style: 'font-size:12px' }, row.fields.task ?? '-'),
  },
]

/** 当前条目的格式化 task */
const selectedEntry = ref<RedisStreamEntry | null>(null)

function openEntry(e: RedisStreamEntry) {
  selectedEntry.value = e
  detailTab.value = 'raw'
}

const prettyTask = computed(() => formatJson(selectedEntry.value?.fields.task))

/** 入口流的「一次请求串联」：request_id → 下发流 / 回执流 */
const traceHint = computed(() => {
  const e = selectedEntry.value
  if (!e) return null
  return {
    requestId: e.fields.request_id,
    replyReq: e.fields.reply_req_stream,
    replyReply: e.fields.reply_reply_stream,
  }
})
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="流的数量" :value="summary.count" suffix="条" :icon="LayersOutline" :color="accent" hint="跨 136/137 全部 db" />
      </n-gi>
      <n-gi>
        <StatCard
          label="条目总量"
          :value="thousands(summary.totalEntries)"
          suffix="条"
          :icon="AnalyticsOutline"
          color="#3b6ff5"
          hint="全部 stream 的 XLEN 之和"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="lag 合计"
          :value="thousands(summary.lag)"
          suffix="条"
          :icon="WarningOutline"
          color="#dd7a15"
          hint="已写入但未投递给消费组"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="无消费组的流"
          :value="summary.noGroupCount"
          suffix="条"
          :icon="AlertCircleOutline"
          color="#d6455e"
          :hint="`共 ${thousands(summary.noGroupEntries)} 条只进不出`"
        />
      </n-gi>
    </n-grid>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="AnalyticsOutline" :color="accent" :size="17" />
              条目堆积 Top 8
            </span>
          </template>
          <BaseChart :option="loadOption" :height="240" empty-text="暂无数据" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="GitNetworkOutline" :color="accent" :size="17" />
              按流角色分布
            </span>
          </template>
          <BaseChart :option="roleOption" :height="240" empty-text="暂无数据" />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 趋势卡：给出「积压与消费健康」的时间维度。
         原先独立成一个「实时指标」菜单，但经逐项核对，那页 11 项指标
         没有一项是别处看不到的，唯一独有的就是这个时间趋势，故合并到这里。 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="TrendingUpOutline" :color="accent" :size="17" />
          指标趋势
        </span>
      </template>
      <template #header-extra>
        <div class="trend-ctrl">
          <n-radio-group :value="trendKey" size="small" @update:value="(v: string) => changeTrend(v)">
            <n-radio-button v-for="t in TREND_METRICS" :key="t.key" :value="t.key">
              {{ t.label }}
            </n-radio-button>
          </n-radio-group>
          <n-radio-group :value="trendWindow" size="small" @update:value="(v: number) => changeTrendWindow(v)">
            <n-radio-button :value="15">15 分</n-radio-button>
            <n-radio-button :value="60">60 分</n-radio-button>
          </n-radio-group>
        </div>
      </template>

      <BaseChart :option="trendOption" :height="240" empty-text="暂无数据" />

      <div v-if="trendIsDemo" class="trend-note">
        <n-icon :component="InformationCircleOutline" color="#dd7a15" />
        <span>
          现场<b>没有时序库</b>，这条曲线在演示态下是围绕当前基线生成的、<b>不是真实历史</b>；
          真实历史需要后端接入指标存储（如 Prometheus）后提供。
        </span>
      </div>
    </n-card>

    <!-- 流列表 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="LayersOutline" :color="accent" :size="17" />
          Redis Stream 列表
        </span>
      </template>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索流名"
          clearable
          style="width: 200px"
          @keyup.enter="reload"
          @clear="reload"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-select
          v-model:value="instanceFilter"
          :options="instanceOptions"
          placeholder="全部实例"
          clearable
          style="width: 180px"
          @update:value="reload"
        />

        <n-select
          v-model:value="roleFilter"
          :options="roleOptions"
          placeholder="全部角色"
          clearable
          style="width: 150px"
          @update:value="reload"
        />

        <n-button
          size="small"
          @click="keyword = ''; instanceFilter = null; roleFilter = null; reload()"
        >
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
        :columns="columns"
        :data="streams"
        :loading="loading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: RedisStreamInfo) => `${row.instance}-${row.db}-${row.key}`"
        :scroll-x="1164"
        :pagination="{ pageSize: 15 }"
      />
    </n-card>

    <!-- 报文流向说明 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="ArrowForwardOutline" :color="accent" :size="17" />
          报文流向
        </span>
      </template>
      <div class="flow">
        <div class="flow-node" style="--c: #d6455e">
          <div class="flow-title">委托 / 策略服务器</div>
          <div class="flow-sub">发请求</div>
        </div>
        <div class="flow-arrow">
          <n-icon :component="ArrowForwardOutline" />
          <div class="flow-label mono">DataHub_req_stream</div>
        </div>
        <div class="flow-node" style="--c: #dd7a15">
          <div class="flow-title">数据中台</div>
          <div class="flow-sub">处理并分配</div>
        </div>
        <div class="flow-arrow">
          <n-icon :component="ArrowForwardOutline" />
          <div class="flow-label mono">ST-&lt;编号&gt; / WT-&lt;编号&gt;</div>
        </div>
        <div class="flow-node" style="--c: #3b6ff5">
          <div class="flow-title">策略平台 / 委托服务器</div>
          <div class="flow-sub">执行并回执</div>
        </div>
        <div class="flow-arrow">
          <n-icon :component="ArrowForwardOutline" />
          <div class="flow-label mono">-reply + DataHub_reply_stream</div>
        </div>
        <div class="flow-node" style="--c: #2f9e5f">
          <div class="flow-title">回执汇总</div>
          <div class="flow-sub">落库 / 回调</div>
        </div>
      </div>

      <div class="detail-note">
        <n-icon :component="InformationCircleOutline" :color="accent" />
        <div>
          消费组名固定为 <code>user_group</code>，consumer 名 = 流名本身。
          点击任意流可查看报文原文；入口流的
          <code>request_id</code> 可串联到对应的下发流与回执流。
        </div>
      </div>
    </n-card>

    <!-- 流详情 -->
    <n-drawer v-model:show="showDetail" :width="860" placement="right">
      <n-drawer-content :title="`流详情 · ${active?.key ?? ''}`" closable>
        <template v-if="active">
          <n-descriptions :column="3" label-placement="top" size="small" bordered>
            <n-descriptions-item label="流名">
              <span class="mono">{{ active.key }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="实例 / db">
              <span class="mono">{{ active.instance }} / db{{ active.db }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="角色">
              {{ ROLE_LABEL[(active.role ?? 'other') as StreamRole] }}
            </n-descriptions-item>
            <n-descriptions-item label="条目数">
              <span class="num">{{ thousands(active.length) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="消费组">
              {{ active.groups.length ? active.groups.map((g) => g.name).join(', ') : '无' }}
            </n-descriptions-item>
            <n-descriptions-item label="lag / pending">
              <span class="num">
                {{ thousands(active.groups[0]?.lag ?? 0) }} / {{ thousands(active.groups[0]?.pending ?? 0) }}
              </span>
            </n-descriptions-item>
          </n-descriptions>

          <n-tabs v-model:value="detailTab" type="line" size="small" style="margin-top: 16px">
            <n-tab-pane name="entries">
              <template #tab>最近条目</template>
              <n-alert v-if="entriesError" type="error" :bordered="false" style="margin-bottom: 12px">
                条目加载失败：{{ entriesError }}
              </n-alert>
              <n-data-table
                :columns="entryColumns"
                :data="entries"
                :loading="entriesLoading"
                :bordered="false"
                size="small"
                :pagination="false"
                :max-height="380"
                :row-props="(row: RedisStreamEntry) => ({ style: 'cursor:pointer', onClick: () => openEntry(row) })"
              />
              <div class="text-muted" style="margin-top: 10px; font-size: 12.5px">
                点击任意行可查看该条报文的完整 JSON。
              </div>
            </n-tab-pane>

            <n-tab-pane name="raw">
              <template #tab>报文原文</template>
              <div v-if="selectedEntry">
                <div class="json-head">
                  <n-icon :component="CodeSlashOutline" :color="accent" />
                  <span>
                    条目 <code>{{ selectedEntry.id }}</code>
                    的 <code>task</code> 字段（即实际业务报文）。
                  </span>
                </div>
                <n-code :code="prettyTask" language="json" show-line-numbers word-wrap />
              </div>
              <div v-else class="text-muted" style="padding: 16px 0">
                请先在「最近条目」里点选一行。
              </div>
            </n-tab-pane>
          </n-tabs>

          <!-- 请求串联提示 -->
          <div v-if="traceHint" class="detail-block">
            <div class="detail-title">
              <n-icon :component="GitNetworkOutline" :color="accent" />
              一次请求的串联关系
            </div>
            <div class="trace-grid">
              <div class="trace-row">
                <span class="trace-k">request_id</span>
                <span class="mono">{{ traceHint.requestId || '-' }}</span>
              </div>
              <div class="trace-row">
                <span class="trace-k">下发流</span>
                <span class="mono">{{ traceHint.replyReq || '-' }}</span>
              </div>
              <div class="trace-row">
                <span class="trace-k">回执流</span>
                <span class="mono">{{ traceHint.replyReply || '-' }}</span>
              </div>
            </div>
          </div>

          <div class="detail-note">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <div>
              现场实测：<b>137 db0 的 DataHub_req_stream 有 407 万条且没有消费组</b>，
              136 db0 的 ST-0 有 595 pending。这两类信号在列表里都标了色。
              数值为探查快照，接入后端后会实时刷新。
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

/* 趋势卡 */
.trend-ctrl {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.trend-note {
  display: flex;
  gap: 9px;
  margin-top: 12px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--warning);
}

.trend-note b {
  color: var(--warning);
}

.prog-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.prog-txt {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

/* 流向图 */
.flow {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.flow-node {
  flex: none;
  min-width: 150px;
  padding: 12px 16px;
  text-align: center;
  background: color-mix(in srgb, var(--c) 8%, var(--bg-surface));
  border: 1px solid color-mix(in srgb, var(--c) 35%, var(--border));
  border-radius: var(--radius);
}

.flow-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c);
}

.flow-sub {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--text-muted);
}

.flow-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  color: var(--text-muted);
}

.flow-label {
  font-size: 11px;
  white-space: nowrap;
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

.trace-grid {
  padding: 12px 14px;
  background: var(--bg-body);
  border-radius: var(--radius-sm);
}

.trace-row {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 4px 0;
  font-size: 13px;
}

.trace-k {
  flex: none;
  width: 100px;
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

.json-head code,
.detail-note code {
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

.detail-note b {
  color: var(--danger);
}
</style>
