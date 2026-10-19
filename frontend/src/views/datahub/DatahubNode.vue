<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NGi,
  NGrid,
  NIcon,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  CashOutline,
  CheckmarkCircleOutline,
  GitNetworkOutline,
  InformationCircleOutline,
  LayersOutline,
  PulseOutline,
  RefreshOutline,
  ServerOutline,
  WarningOutline,
  WifiOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { datahubApi } from '@/api/endpoints'
import { mockDatahubApi } from '@/api/mock/datahub'
import type { DatahubDbStat, DatahubNode, RedisPubSubChannel, RedisStreamInfo } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { withDelay } from '@/api/mock/_shared'
import { thousands } from '@/utils/format'

/**
 * 中台节点页。
 *
 * ⚠️ **一行 = 一台 Redis 实例，不是一台中台。**
 * 中台在 Redis 侧**没有节点清单键**（委托服务器有 `traderserver`，中台没有对应物，
 * 翻遍 136/137 只有 DataHub_req_stream / DataHub_reply_stream / unique_id）。
 * 所以只能按实例来分；`online` 的含义是「这台 Redis 上近期有没有中台在通信」，
 * **不等于**「这台机器上跑着中台」——现场中台进程在 137，连的却是 136 的 Redis。
 *
 * 数据来源（现场只读实测）：
 *  - Pub/Sub 频道 datahub_online / _beat / _offline / _select / _oncall（均带 _1 后缀）
 *  - Redis string 键 unique_id（**各 db 独立**，且部分 db 根本没有这个键）
 *  - 各实例的 DBSIZE / stream 规模
 *
 * 心跳判定说明：后端常驻订阅 `datahub_beat_1`，某实例上收到过心跳 = 在线。
 * 心跳报文**不含身份**（实测 `{"level":2,"role":1}`，没有 id/ip/mac），
 * 所以**无法**把心跳归属到具体某一台中台 —— 页面也不做这种断言。
 */

const accent = '#dd7a15'

const {
  data: nodeData,
  loading: nodeLoading,
  error: nodeError,
  usingMock: nodeUsingMock,
  load: loadNodes,
} = useResource(
  (params: Record<string, unknown>) => datahubApi.listNodes(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockDatahubApi.listNodes(params)) },
)

const PAGE = { page: 1, pageSize: 50 }

function reload() {
  loadNodes(PAGE)
  loadChannels()
  loadAllStreams({})
}

onMounted(reload)

const nodes = computed<DatahubNode[]>(() => nodeData.value?.list ?? [])
const nodeMeta = computed(() => nodeData.value?.meta ?? null)

/* ------------------------------ 频道 ------------------------------ */

/**
 * 频道走与节点相同的 useResource 机制。
 *
 * 注意：**不要**在 onMounted 里手动判断 `isMock.value` 再决定走 mock 还是接口 ——
 * 那样两个请求并发，channels 那次执行时节点的 isMock 还没被置位（仍是 false），
 * 于是去调真实接口、失败后返回空数组，页面上就是「频道 0 个」但节点却显示演示数据
 * （踩过一次）。交给 useResource 自己判断，两条数据源的降级逻辑才一致。
 */
const {
  data: channelData,
  loading: channelLoading,
  load: loadChannels,
} = useResource(
  () => datahubApi.listChannels(),
  { mock: () => withDelay(mockDatahubApi.listChannels(), 200) },
)

const channels = computed<RedisPubSubChannel[]>(() => channelData.value?.channels ?? [])

/**
 * 「只因为我们订阅才存在」的频道。
 *
 * 现场 137 原本 0 个频道；本后台为了判定心跳常驻订阅了 `datahub_beat_1`，
 * 而 `PUBSUB CHANNELS` 只列有订阅者的频道 —— 于是那个实例上会"出现"一个频道。
 * 后端已把它从 `channels` 里剔除并单列到 `selfOnlyChannels`，
 * 页面把它如实提示出来（而不是假装没有，也不是混进真实频道里）。
 */
const selfOnlyChannels = computed(() => channelData.value?.selfOnlyChannels ?? [])

/** 频道分组的中文名 */
const GROUP_LABEL: Record<string, string> = {
  trade: '委托服务器',
  strategy: '策略服务器',
  datahub: '数据中台',
  directed: '定向频道',
  other: '其他',
}

const GROUP_COLOR: Record<string, string> = {
  trade: '#0d9c9c',
  strategy: '#7c5cf0',
  datahub: '#dd7a15',
  directed: '#3b6ff5',
  other: '#5b6b8c',
}

const channelGroups = computed(() => {
  const map = new Map<string, RedisPubSubChannel[]>()
  for (const c of channels.value) {
    const g = c.group ?? 'other'
    if (!map.has(g)) map.set(g, [])
    map.get(g)!.push(c)
  }
  return [...map.entries()].map(([key, list]) => ({
    key,
    label: GROUP_LABEL[key] ?? key,
    color: GROUP_COLOR[key] ?? '#5b6b8c',
    list,
  }))
})

/** 订阅者数为 0 的频道 —— 有人在发但没人听 */
const orphanChannels = computed(() => channels.value.filter((c) => c.subscribers === 0))

/* ------------------------------ 汇总 ------------------------------ */

/**
 * 需要注意的信号 —— 全部从**当前已加载的流数据**推导，不用 mock 常量。
 *
 * 之前这里直接读 `mockGroupLessStreams()` / `mockBacklogStreams()`，
 * 导致生产构建（VITE_USE_MOCK=false、接口 500）时表格是空的、
 * 但风险区仍然列着演示数据 —— 假数据漏出来了。
 * 现在改为需求式推导：没有真实数据就是空列表，绝不显示编造的内容。
 */
const groupLess = computed(() =>
  groupLessStreams.value.length ? groupLessStreams.value : [],
)

const backlog = computed(() =>
  backlogStreams.value.length ? backlogStreams.value : [],
)

/** 全部流（含实例/db/角色），用于图表与告警推导 */
const {
  data: allStreamData,
  load: loadAllStreams,
} = useResource(
  (params: Record<string, unknown>) => datahubApi.listStreams(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockDatahubApi.listStreams(params)) },
)

const allStreams = computed<RedisStreamInfo[]>(() => allStreamData.value ?? [])

/** 无消费组的流 */
const groupLessStreams = computed(() => allStreams.value.filter((s) => s.groups.length === 0))

/** 有积压的流（lag 或 pending > 0） */
const backlogStreams = computed(() =>
  allStreams.value
    .filter((s) => {
      const g = s.groups[0]
      return g && ((g.lag ?? 0) > 0 || g.pending > 0)
    })
    .map((s) => ({
      key: s.key,
      instance: s.instance ?? '',
      db: s.db ?? 0,
      lag: s.groups[0].lag ?? 0,
      pending: s.groups[0].pending,
    }))
    .sort((a, b) => b.lag - a.lag),
)

/**
 * 汇总指标。
 *
 * ⚠️ 在线/离线/未知**三态分开计数**：合并会把「我们没在看」混进「它离线了」，
 * 而这两件事的处置完全不同（一个是查我们自己，一个是查现场）。
 * 真实态直接用后端 meta 的口径；演示态退回本地统计。
 */
const summary = computed(() => {
  const m = nodeMeta.value
  const list = nodes.value
  const total = m?.total ?? list.length
  const online = m?.onlineCount ?? list.filter((n) => n.online === true).length
  const offline = m?.offlineCount ?? list.filter((n) => n.online === false).length
  const unknown = m?.unknownCount ?? total - online - offline
  return {
    total,
    online,
    offline,
    unknown,
    channels: channels.value.length,
    entryTotal: list.reduce((s, n) => s + (n.entryTotal ?? 0), 0),
  }
})

/**
 * 观测能力提示。
 *
 * 「在线 0 个」有两种完全不同的原因：真的没有中台，或者我们**根本没在听**
 * （订阅关闭 / 连接失败 / 频道名听错了）。必须能区分，否则会误判成现场故障。
 */
const observeNote = computed(() => {
  if (nodeUsingMock.value) return null
  const m = nodeMeta.value
  if (!m) return null
  const parts: string[] = []
  if (!m.heartbeatEnabled) {
    parts.push('后端未开启心跳订阅，在线状态显示为「未知」（不是离线）。')
  }
  if (m.redisUnavailable?.length) {
    parts.push(`有 ${m.redisUnavailable.length} 个实例连不上（${m.redisUnavailable.join('、')}），统计可能不完整。`)
  }
  if (m.unknownCount > 0) {
    parts.push(
      `有 ${m.unknownCount} 个实例状态为「未知」：` +
        '可能是刚启动还没等到第一条心跳，或该实例的订阅没建立成功。',
    )
  }
  return parts.length ? parts.join(' ') : null
})

/** 实际订阅的频道名（确认「我们听的是对的那一套」） */
const beatChannels = computed(() => nodeMeta.value?.heartbeatChannels ?? [])

const loadOption = computed(() => {
  // 从当前流数据推导各实例条目数（非演示态下没有数据就是空图）
  const map = new Map<string, number>()
  for (const s of allStreams.value) {
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
    yAxis: {
      type: 'category',
      data: data.map((d) => d.label).reverse(),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        data: data.map((d) => d.value).reverse(),
        barMaxWidth: 18,
        itemStyle: {
          color: (p: { dataIndex: number }) =>
            (data[data.length - 1 - p.dataIndex]?.value ?? 0) > 1_000_000 ? '#d6455e' : accent,
          borderRadius: [0, 4, 4, 0],
        },
      },
    ],
  }
})

/** 各实例的频道数对比 */
const channelOption = computed(() => {
  const map = new Map<string, number>()
  for (const c of channels.value) {
    const k = c.instance ?? '未知'
    map.set(k, (map.get(k) ?? 0) + 1)
  }
  const entries = [...map.entries()]
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 个频道' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data: entries.map(([name, value]) => ({ name, value })),
        color: ['#3b6ff5', '#0d9c9c', '#7c5cf0'],
      },
    ],
  }
})

/**
 * 中台实例表格。
 *
 * ⚠️ 这里**没有** MAC / 最后活跃 两列：Redis 侧没有这两个数据源
 * （心跳报文里没有 id/ip/mac，中台也没有节点清单键）。
 * 早先显示这两列时值是编的 —— 留着只会让人以为「没读到」。
 */
const columns: DataTableColumns<DatahubNode> = [
  {
    title: '状态',
    key: 'state',
    width: 96,
    render: (row) => {
      // 三态：在线 / 离线 / **未知**。未知不能显示成离线。
      const type = row.online === true ? 'success' : row.online === false ? 'error' : 'default'
      const label = row.online === true ? '在线' : row.online === false ? '离线' : '未知'
      const icon = row.online === true
        ? CheckmarkCircleOutline
        : row.online === false
          ? AlertCircleOutline
          : InformationCircleOutline
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              NTag,
              { size: 'small', round: true, bordered: false, type },
              { icon: () => h(NIcon, null, { default: () => h(icon) }), default: () => label },
            ),
          default: () =>
            row.online === null
              ? '未知：我们观测不到（订阅关闭 / 连接失败 / 还没收到第一条心跳）\n' +
                '这不是「离线」—— 两者处置方式完全不同\n' +
                (row.subscribeError ? `订阅失败原因：${row.subscribeError}` : '')
              : `该实例上近期${row.online ? '收到过' : '没有'}中台心跳\n` +
                `累计心跳 ${row.beatCount} 条` +
                (row.lastBeatAgeSec != null ? `，最近一次在 ${row.lastBeatAgeSec} 秒前` : '') +
                `\n阈值 ${nodeMeta?.offlineAfterSec ?? 30} 秒`,
        },
      )
    },
  },
  {
    title: '实例地址',
    key: 'ip',
    width: 170,
    render: (row) =>
      h('span', { class: 'mono', style: { fontWeight: 500 } }, `${row.ip}:${row.port ?? '6379'}`),
  },
  {
    title: '各 db 键数',
    key: 'dbs',
    width: 230,
    render: (row) => {
      if (!row.dbs?.length) {
        return h('span', { class: 'text-muted' }, '无键')
      }
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              'span',
              { class: 'mono', style: { fontSize: '12px' } },
              row.dbs.map((d) => `db${d.db}=${thousands(d.keys)}`).join('  '),
            ),
          /*
           * 键数与 unique_id 必须一起看：unique_id 是 string 键、**各 db 独立**，
           * 且部分 db 根本没有它（137 db0/db2）。显示成 0 会让人以为水位是 0。
           */
          default: () =>
            row.dbs
              .map(
                (d) =>
                  `db${d.db}：${thousands(d.keys)} 个键，${thousands(d.entryTotal)} 条目，` +
                  `${d.streamTotal} 条流，unique_id=${d.uniqueId ?? '（该 db 无此键）'}`,
              )
              .join('\n'),
        },
      )
    },
  },
  {
    title: 'stream 数',
    key: 'streamTotal',
    width: 110,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, row.streamTotal != null ? thousands(row.streamTotal) : '-'),
  },
  {
    title: '条目总量',
    key: 'entryTotal',
    width: 130,
    align: 'right',
    render: (row) => {
      const v = row.entryTotal ?? 0
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          /*
           * 「键数 ≠ 条目数」是本页最容易误读的地方：137 db0 只有 117 个键，
           * 但单个入口流就有 1400 万条目。两个数混着看会得出错误的容量判断。
           */
          trigger: () =>
            h(
              'span',
              { class: 'num', style: v > 1_000_000 ? 'color:var(--danger);font-weight:600' : '' },
              v ? thousands(v) : '-',
            ),
          default: () => `各 db 的 stream 条目合计（XLEN 口径）\n⚠️ 与「键数」不是一回事：\n137 db0 只有 117 个键，但入口流一条就有 1400 万条目`,
        },
      )
    },
  },
  {
    title: '频道数',
    key: 'channels',
    width: 100,
    align: 'right',
    render: (row) => {
      const v = row.channels ?? 0
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h('span', { class: 'num', style: v === 0 ? 'color:var(--warning)' : '' }, String(v)),
          default: () => 'Pub/Sub 频道是实例级的，与 db 无关（订阅不区分 db）',
        },
      )
    },
  },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openDetail(row) },
        { default: () => '详情' },
      ),
  },
]

/** 各 db 明细表（抽屉里） */
const dbColumns: DataTableColumns<DatahubDbStat> = [
  { title: 'db', key: 'db', width: 60, align: 'right', render: (row) => `db${row.db}` },
  {
    title: '键数',
    key: 'keys',
    width: 100,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, thousands(row.keys)),
  },
  {
    title: 'unique_id',
    key: 'uniqueId',
    width: 110,
    align: 'right',
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              'span',
              { class: 'num', style: row.uniqueId == null ? 'color:var(--text-muted)' : '' },
              row.uniqueId == null ? '无此键' : thousands(row.uniqueId),
            ),
          // 与 0 区分开：0 是一个真实水位，不能与「没有这个键」混用
          default: () =>
            row.uniqueId == null
              ? '该 db 没有 unique_id 这个键（现场 137 db0/db2 就是这样）\n注意：这不是 0 —— 0 是一个真实水位'
              : 'unique_id 是 string 键，**各 db 独立**（不是全局计数器）',
        },
      ),
  },
  {
    title: 'stream 数',
    key: 'streamTotal',
    width: 100,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, thousands(row.streamTotal)),
  },
  {
    title: '条目总量',
    key: 'entryTotal',
    width: 120,
    align: 'right',
    render: (row) =>
      h(
        'span',
        { class: 'num', style: row.entryTotal > 1_000_000 ? 'color:var(--danger);font-weight:600' : '' },
        thousands(row.entryTotal),
      ),
  },
]

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<DatahubNode | null>(null)

function openDetail(row: DatahubNode) {
  active.value = row
  showDetail.value = true
}

/** 该节点相关频道 */
const nodeChannels = computed(() =>
  active.value ? channels.value.filter((c) => c.instance === active.value?.ip) : [],
)

/** 该节点的积压流 */
const nodeBacklog = computed(() =>
  active.value ? backlog.value.filter((b) => b.instance === active.value?.ip) : [],
)

const backlogColumns: DataTableColumns<{ key: string; db: number; lag: number; pending: number }> = [
  { title: '流', key: 'key', render: (row) => h('span', { class: 'mono' }, row.key) },
  { title: 'db', key: 'db', width: 60, align: 'right' },
  {
    title: 'lag',
    key: 'lag',
    width: 90,
    align: 'right',
    render: (row) => h('span', { class: 'num', style: row.lag > 0 ? 'color:var(--warning)' : '' }, thousands(row.lag)),
  },
  {
    title: 'pending',
    key: 'pending',
    width: 100,
    align: 'right',
    render: (row) => h('span', { class: 'num', style: row.pending > 0 ? 'color:var(--warning)' : '' }, thousands(row.pending)),
  },
]
</script>

<template>
  <div>
    <QueryErrorAlert v-if="nodeError" :message="nodeError" :loading="nodeLoading" @retry="reload" />

    <!--
      「在线 0 个」有两种完全不同的原因：真的没中台，或我们根本没在听。
      必须显式说清，否则会被误判成现场故障。
    -->
    <n-alert
      v-if="observeNote"
      type="warning"
      :bordered="false"
      size="small"
      style="margin-bottom: 14px"
    >
      {{ observeNote }}
    </n-alert>

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="Redis 实例"
          :value="summary.total"
          suffix="个"
          :icon="ServerOutline"
          :color="accent"
          hint="一行 = 一台 Redis 实例，不是「中台个数」"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="有中台活动的实例"
          :value="summary.online"
          suffix="个"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          :hint="`近期收到过心跳（阈值 ${nodeMeta?.offlineAfterSec ?? 30} 秒）`"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="无活动 / 未知"
          :value="`${summary.offline} / ${summary.unknown}`"
          suffix="个"
          :icon="AlertCircleOutline"
          color="#d6455e"
          hint="前者无心跳；后者观测不到（≠离线）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="stream 条目总量"
          :value="thousands(summary.entryTotal)"
          suffix="条"
          :icon="LayersOutline"
          color="#dd7a15"
          hint="XLEN 口径（≠ 键数）"
        />
      </n-gi>
    </n-grid>

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
              各实例条目堆积
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">红色 = 超过 100 万条</span>
          </template>
          <BaseChart :option="loadOption" :height="200" empty-text="暂无数据" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="WifiOutline" :color="accent" :size="17" />
              各实例频道数
            </span>
          </template>
          <BaseChart :option="channelOption" :height="200" empty-text="暂无频道" />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 节点列表 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="ServerOutline" :color="accent" :size="17" />
          Redis 实例（中台通信侧）
        </span>
      </template>
      <template #header-extra>
        <n-tag v-if="beatChannels.length" size="tiny" round :bordered="false" type="info">
          监听 {{ beatChannels.join(' / ') }}
        </n-tag>
        <n-button size="small" :loading="nodeLoading" @click="reload" style="margin-left: 8px">
          <template #icon>
            <n-icon :component="RefreshOutline" />
          </template>
          刷新
        </n-button>
      </template>

      <n-data-table
        :columns="columns"
        :data="nodes"
        :loading="nodeLoading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: DatahubNode) => row.id"
        :scroll-x="1260"
        :pagination="false"
      />
    </n-card>

    <!-- 频道列表 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="WifiOutline" :color="accent" :size="17" />
          Pub/Sub 频道
        </span>
      </template>
      <template #header-extra>
        <n-tag v-if="orphanChannels.length" size="small" type="warning" round :bordered="false">
          {{ orphanChannels.length }} 个频道无订阅者
        </n-tag>
        <n-tag v-else size="small" type="success" round :bordered="false">全部有订阅者</n-tag>
      </template>

      <div class="ch-group" v-for="g in channelGroups" :key="g.key">
        <div class="ch-group-head">
          <span class="ch-dot" :style="{ background: g.color }" />
          <span class="ch-group-name">{{ g.label }}</span>
          <span class="text-muted" style="font-size: 12px">{{ g.list.length }} 个</span>
        </div>
        <div class="ch-list">
          <div v-for="c in g.list" :key="c.name + c.instance" class="ch-item">
            <span class="mono ch-name">{{ c.name }}</span>
            <n-tag
              size="tiny"
              round
              :bordered="false"
              :type="c.subscribers > 0 ? 'success' : 'warning'"
            >
              订阅者 {{ c.subscribers }}
            </n-tag>
            <!-- 「只有我们在听」与「真有人在听」含义完全不同，必须标出来 -->
            <n-tag v-if="c.selfSubscribed" size="tiny" round :bordered="false" type="info">
              含本后台
            </n-tag>
            <span class="text-muted ch-inst mono">{{ c.instance }}</span>
          </div>
        </div>
      </div>

      <div v-if="!channels.length && !channelLoading" class="text-muted" style="padding: 12px 0">
        暂无频道数据。
      </div>

      <!--
        观测者效应提示：我们自己订阅出来的频道必须说出来。
        不说的话，看 Redis 管理页的人会以为现场真有个频道。
      -->
      <div v-if="selfOnlyChannels.length" class="detail-note" style="margin-top: 14px">
        <n-icon :component="InformationCircleOutline" :color="accent" />
        <div>
          <b>{{ selfOnlyChannels.length }} 个频道只因为本后台在订阅才存在</b>，已从上面的列表剔除：
          <code v-for="c in selfOnlyChannels" :key="c" style="margin-right: 6px">{{ c }}</code>
          <br />
          本后台为判定中台心跳而常驻订阅了 <code>datahub_beat_1</code>，
          而 Redis 的 <code>PUBSUB CHANNELS</code> 只列「有订阅者的频道」——
          这些频道在现场本来是不存在的（现场 137 原本 0 个频道）。
        </div>
      </div>
    </n-card>

    <!-- 风险提示 -->
    <n-card class="page-card risk-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="WarningOutline" color="#d6455e" :size="17" />
          需要注意的信号
        </span>
      </template>

      <div v-if="groupLess.length" class="warn-block">
        <div class="warn-title">
          <n-icon :component="AlertCircleOutline" color="#d6455e" />
          没有消费组的流（{{ groupLess.length }} 条）—— 有人在写，但没有任何消费组，消息不会被处理
        </div>
        <n-data-table
          :columns="[
            { title: '流', key: 'key', render: (row: any) => h('span', { class: 'mono' }, row.key) },
            { title: '实例', key: 'instance', width: 150, render: (row: any) => h('span', { class: 'mono' }, row.instance) },
            { title: 'db', key: 'db', width: 60, align: 'right' },
            { title: '条目数', key: 'length', width: 130, align: 'right', render: (row: any) => h('span', { class: 'num' }, thousands(row.length)) },
          ]"
          :data="groupLess"
          :bordered="false"
          size="small"
          :pagination="false"
          :max-height="220"
        />
      </div>

      <div v-if="backlog.length" class="warn-block">
        <div class="warn-title">
          <n-icon :component="WarningOutline" color="#dd7a15" />
          有积压的流（{{ backlog.length }} 条）—— 消费组 lag 或 pending 大于 0
        </div>
        <n-data-table
          :columns="backlogColumns"
          :data="backlog"
          :bordered="false"
          size="small"
          :pagination="false"
          :max-height="220"
        />
      </div>

      <div class="detail-note">
        <n-icon :component="InformationCircleOutline" :color="accent" />
        <div>
          数据来自 Redis 的 <code>PUBSUB CHANNELS</code> / <code>NUMSUB</code> /
          <code>XLEN</code> / <code>XINFO GROUPS</code> / <code>unique_id</code>，
          全部为只读命令。数值为探查时的快照。
        </div>
      </div>
    </n-card>

    <!-- 节点详情 -->
    <AppDialog v-model:show="showDetail" :title="`中台实例详情 · ${active?.ip ?? ''}`" :width="620" mask-closable>
      <template v-if="active">
        <n-descriptions :column="1" label-placement="left" size="small" bordered>
          <n-descriptions-item label="实例地址">
            <span class="mono">{{ active.ip }}:{{ active.port ?? '6379' }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="状态">
            <!-- 三态：未知不能显示成离线 -->
            <n-tag
              size="small"
              round
              :bordered="false"
              :type="active.online === true ? 'success' : active.online === false ? 'error' : 'default'"
            >
              {{ active.online === true ? '在线' : active.online === false ? '离线' : '未知' }}
            </n-tag>
            <span v-if="active.online === null" class="text-muted" style="margin-left: 8px; font-size: 12px">
              观测不到，不等于离线
            </span>
          </n-descriptions-item>
          <n-descriptions-item label="心跳">
            <template v-if="active.beatCount > 0">
              累计 {{ thousands(active.beatCount) }} 条
              <span v-if="active.lastBeatAgeSec != null" class="text-muted">
                ，最近一次 {{ active.lastBeatAgeSec }} 秒前
              </span>
            </template>
            <span v-else class="text-muted">尚未收到任何心跳</span>
          </n-descriptions-item>
          <n-descriptions-item label="订阅状态">
            <n-tag size="tiny" round :bordered="false" :type="active.subscribed ? 'success' : 'warning'">
              {{ active.subscribed ? '已订阅' : '未建立' }}
            </n-tag>
            <span v-if="active.subscribeError" class="text-muted" style="margin-left: 8px; font-size: 12px">
              {{ active.subscribeError }}
            </span>
          </n-descriptions-item>
          <n-descriptions-item label="键数合计">
            {{ thousands(active.dbKeysTotal) }}
            <span class="text-muted" style="margin-left: 6px; font-size: 12px">（DBSIZE 口径，≠ 条目数）</span>
          </n-descriptions-item>
          <n-descriptions-item label="stream 数">
            {{ active.streamTotal != null ? thousands(active.streamTotal) : '-' }}
          </n-descriptions-item>
          <n-descriptions-item label="条目总量">
            {{ active.entryTotal != null ? thousands(active.entryTotal) : '-' }}
          </n-descriptions-item>
          <n-descriptions-item label="频道数">{{ active.channels ?? 0 }}</n-descriptions-item>
        </n-descriptions>

        <!-- 各 db 明细：unique_id 各 db 独立，且部分 db 没有这个键 -->
        <div class="detail-block">
          <div class="detail-title">
            <n-icon :component="LayersOutline" :color="accent" />
            各 db 明细
          </div>
          <n-data-table
            :columns="dbColumns"
            :data="active.dbs ?? []"
            :bordered="false"
            size="small"
            :pagination="false"
            :max-height="240"
          />
        </div>

        <div class="detail-block">
          <div class="detail-title">
            <n-icon :component="WifiOutline" :color="accent" />
            该实例的频道（{{ nodeChannels.length }}）
          </div>
          <div v-if="nodeChannels.length" class="ch-list">
            <div v-for="c in nodeChannels" :key="c.name" class="ch-item">
              <span class="mono ch-name">{{ c.name }}</span>
              <n-tag size="tiny" round :bordered="false" :type="c.subscribers > 0 ? 'success' : 'warning'">
                订阅者 {{ c.subscribers }}
              </n-tag>
            </div>
          </div>
          <div v-else class="text-muted" style="font-size: 13px">
            该实例上没有 Pub/Sub 频道（现场 137 实测为 0 个）。
          </div>
        </div>

        <div v-if="nodeBacklog.length" class="detail-block">
          <div class="detail-title">
            <n-icon :component="WarningOutline" color="#dd7a15" />
            该实例的积压流（{{ nodeBacklog.length }}）
          </div>
          <n-data-table
            :columns="backlogColumns"
            :data="nodeBacklog"
            :bordered="false"
            size="small"
            :pagination="false"
            :max-height="240"
          />
        </div>

        <div class="detail-note">
          <n-icon :component="CashOutline" :color="accent" />
          <div>
            <b>这一行是一个 Redis 实例，不是一台中台。</b>
            中台在 Redis 侧没有节点清单键，所以「在线」只能靠该实例上有没有
            <code>datahub_beat</code> 心跳来判断 —— 它<b>不等于</b>「这台机器上跑着中台」
            （现场中台进程在 137，连的却是 136 的 Redis）。
            <br />
            心跳报文不含 id / ip / mac（实测只有 <code>{"level":2,"role":1}</code>），
            所以<b>无法</b>归属到具体某一台中台；<code>level</code>/<code>role</code>
            的语义协议未定义，也不当作节点 ID。
            <br />
            <code>unique_id</code> 是 string 键且<b>各 db 独立</b>
            （现场 136 db0=217 / db1=56；137 db1=4，而 137 db0/db2 没有这个键）。
          </div>
        </div>
      </template>

      <template #footer>
        <n-button size="small" @click="showDetail = false">关闭</n-button>
      </template>
    </AppDialog>
  </div>
</template>

<style scoped>
/*
 * 四张指标卡等高。
 *
 * 它们的 hint 文字长短不一（1~3 行不等），内容高度天然不同：
 * 实测 149/149/132/132，视觉上参差不齐。
 * 靠「把文字都压到一样长」治标不治本 —— 一旦某条 hint 因数据变化折行数改变，
 * 又会对不齐。所以让栅格单元等高、卡片撑满：
 * 高度由最高的一张决定，其余底部对齐（hint 居上，多余空白留在卡片内）。
 *
 * 注意 `:deep()` 的写法：`<n-gi>` 渲染出来是**无类名的 div**，
 * 不能用 `.n-gi` 选择（详见总览页同类注释）。
 */
.n-grid :deep(> div > .stat-card) {
  height: 100%;
  box-sizing: border-box;
}

.num {
  font-variant-numeric: tabular-nums;
}

/* 频道分组 */
.ch-group + .ch-group {
  margin-top: 16px;
}

.ch-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.ch-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.ch-group-name {
  font-size: 13px;
  font-weight: 600;
}

.ch-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 8px;
}

.ch-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: var(--bg-body);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.ch-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}

.ch-inst {
  font-size: 11px;
}

/* 风险卡 */
.risk-card {
  border-left: 3px solid var(--danger) !important;
}

.warn-block + .warn-block {
  margin-top: 18px;
}

.warn-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
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

.detail-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
</style>
