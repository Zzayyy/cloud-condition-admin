<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
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
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { datahubApi } from '@/api/endpoints'
import { mockDatahubApi } from '@/api/mock/datahub'
import type { DatahubNodeDetail, RedisPubSubChannel, RedisStreamInfo } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { withDelay } from '@/api/mock/_shared'
import { thousands } from '@/utils/format'

/**
 * 中台节点页。
 *
 * 数据来源（现场只读实测）：
 *  - Pub/Sub 频道 datahub_online / _beat / _offline / _select / _oncall（均带 _1 后缀）
 *  - Redis string 键 unique_id（各 db 独立：136 db0=166、db1=55；137 db1=4）
 *  - 各实例的 dbsize / stream 规模
 */

const accent = '#dd7a15'

const {
  data: nodeData,
  loading: nodeLoading,
  error: nodeError,
  isMock,
  mockReason,
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

const nodes = computed<DatahubNodeDetail[]>(() => nodeData.value?.list ?? [])

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

const channels = computed<RedisPubSubChannel[]>(() => channelData.value ?? [])

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

const summary = computed(() => {
  const list = nodes.value
  return {
    total: list.length,
    online: list.filter((n) => n.online).length,
    channels: channels.value.length,
    entryTotal: list.reduce((s, n) => s + (n.entryTotal ?? 0), 0),
  }
})

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

/* ------------------------------ 节点表格 ------------------------------ */

const columns: DataTableColumns<DatahubNodeDetail> = [
  {
    title: '状态',
    key: 'online',
    width: 88,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.online ? 'success' : 'error' },
        {
          icon: () => h(NIcon, null, { default: () => h(row.online ? CheckmarkCircleOutline : AlertCircleOutline) }),
          default: () => (row.online ? '在线' : '离线'),
        },
      ),
  },
  { title: '节点 ID', key: 'id', width: 90, render: (row) => h('span', { class: 'mono' }, String(row.id)) },
  {
    title: 'IP 地址',
    key: 'ip',
    width: 160,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 500 } }, row.ip),
  },
  { title: 'MAC', key: 'mac', width: 180, render: (row) => h('span', { class: 'mono' }, row.mac) },
  {
    title: 'unique_id 水位',
    key: 'uniqueId',
    width: 150,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h('span', { class: 'num' }, row.uniqueId != null ? thousands(row.uniqueId) : '-'),
          default: () => 'unique_id 是 string 键，各 db 独立；用于给上线服务器分配唯一编号',
        },
      ),
  },
  {
    title: 'stream 数',
    key: 'streams',
    width: 110,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, row.streams != null ? thousands(row.streams) : '-'),
  },
  {
    title: '条目总量',
    key: 'entryTotal',
    width: 130,
    align: 'right',
    render: (row) => {
      const v = row.entryTotal ?? 0
      return h(
        'span',
        { class: 'num', style: v > 1_000_000 ? 'color:var(--danger);font-weight:600' : '' },
        v ? thousands(v) : '-',
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
        'span',
        { class: 'num', style: v === 0 ? 'color:var(--warning)' : '' },
        String(v),
      )
    },
  },
  {
    title: '最后活跃',
    key: 'lastActiveTime',
    width: 165,
    render: (row) => row.lastActiveTime || '-',
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

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<DatahubNodeDetail | null>(null)

function openDetail(row: DatahubNodeDetail) {
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
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="中台实例" :value="summary.total" suffix="个" :icon="ServerOutline" :color="accent" hint="按 Redis 实例统计" />
      </n-gi>
      <n-gi>
        <StatCard
          label="在线实例"
          :value="summary.online"
          suffix="个"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          hint="datahub_beat 频道有心跳"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="Pub/Sub 频道"
          :value="summary.channels"
          suffix="个"
          :icon="WifiOutline"
          color="#7c5cf0"
          hint="全部带 _1 后缀（现场真中台订阅的那套）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="stream 条目总量"
          :value="thousands(summary.entryTotal)"
          suffix="条"
          :icon="LayersOutline"
          color="#d6455e"
          hint="入口流积压严重，详见「消息总线」"
        />
      </n-gi>
    </n-grid>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
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
          中台实例
        </span>
      </template>
      <template #header-extra>
        <n-button size="small" :loading="nodeLoading" @click="reload">
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
        :row-key="(row: DatahubNodeDetail) => String(row.id)"
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
            <span class="text-muted ch-inst mono">{{ c.instance }}</span>
          </div>
        </div>
      </div>

      <div v-if="!channels.length && !channelLoading" class="text-muted" style="padding: 12px 0">
        暂无频道数据。
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
    <n-drawer v-model:show="showDetail" :width="620" placement="right">
      <n-drawer-content :title="`中台实例详情 · ${active?.ip ?? ''}`" closable>
        <template v-if="active">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="节点 ID">
              <span class="mono">{{ active.id }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="IP 地址">
              <span class="mono">{{ active.ip }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="MAC">
              <span class="mono">{{ active.mac }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag size="small" round :bordered="false" :type="active.online ? 'success' : 'error'">
                {{ active.online ? '在线' : '离线' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="unique_id 水位">
              {{ active.uniqueId != null ? thousands(active.uniqueId) : '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="stream 数">
              {{ active.streams != null ? thousands(active.streams) : '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="条目总量">
              {{ active.entryTotal != null ? thousands(active.entryTotal) : '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="频道数">{{ active.channels ?? 0 }}</n-descriptions-item>
            <n-descriptions-item label="最后活跃">{{ active.lastActiveTime || '-' }}</n-descriptions-item>
          </n-descriptions>

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
              该实例上 <code>unique_id</code> 是 string 键且**各 db 独立**
              （现场 136 db0=166 / db1=55；137 db1=4）。
              中台没有节点清单键，在线状态只能靠 <code>datahub_*</code> 频道判断。
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
