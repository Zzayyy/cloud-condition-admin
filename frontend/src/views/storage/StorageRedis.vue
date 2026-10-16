<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
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
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  CloseOutline,
  CloudOutline,
  CodeSlashOutline,
  EyeOutline,
  FolderOpenOutline,
  InformationCircleOutline,
  LayersOutline,
  RefreshOutline,
  ServerOutline,
  TimeOutline,
  TrashOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { storageApi } from '@/api/endpoints'
import { mockStorageApi, REDIS_INSTANCES } from '@/api/mock/storage'
import { withDelay } from '@/api/mock/_shared'
import type { RedisInstance, RedisInstanceInfo, RedisKeyDetail, RedisKeyDomain, RedisKeyInfo, RedisValueType } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { formatJson, thousands } from '@/utils/format'

/**
 * Redis 管理页 —— 逃生舱 + 运维工具。
 *
 * 这里**刻意不做业务列表**（那些在策略/委托/账户页里）。
 * 它回答的是业务页回答不了的问题：
 *  - 这个键到底在不在、什么类型、有没有 TTL
 *  - 有哪些键**没有任何业务页面覆盖**（如 strategy_server_lock_*）
 *  - 手工删键（高危，二次确认）
 *
 * 安全约束：现场 137 db0 有 457 万条 stream，**绝不做全量扫描**，
 * 一律用 SCAN 且强制条数上限。
 */

const message = useMessage()
const accent = '#2f9e5f'

/* ------------------------------ 实例与 DB ------------------------------ */

/**
 * 当前实例。
 *
 * ⚠️ **初值不能写死**（原来写的是 `ref('136')`）。
 *
 * 后端的 `instanceId` 参数收的是**主机地址**（`192.168.1.136`），
 * 而 `'136'` 是 mock 时代的旧 id —— 拿它去请求，后端会如实返回
 * 「未知的 Redis 主机：136」。
 * 而 `onMounted` 会立刻发请求，那时实例清单还没回来，
 * 所以必须**先等清单拿到，再用第一个真实实例去取数**（见 onMounted）。
 */
const instanceId = ref('')
const db = ref(0)

const {
  data: infoData,
  loading: infoLoading,
  load: loadInfo,
} = useResource(
  (params: Record<string, unknown>) => storageApi.getRedisInstanceInfo(String(params.instanceId)),
  { mock: (params: Record<string, unknown>) => withDelay(mockStorageApi.getRedisInstanceInfo(String(params.instanceId)), 180) },
)

const info = computed<RedisInstanceInfo | undefined>(() => infoData.value)

/**
 * 实例清单。
 *
 * ⚠️ **真实态必须用后端返回的实例，不能用 mock 里硬编码的 REDIS_INSTANCES。**
 *
 * `mock/storage.ts` 里写死了 `136 / 137` 两个实例；而现场配置里实际是
 * 「实例 + db」的组合（136 db0、136 db1、137 db1 …）。
 * 硬编码会在实例增减时让页面指向不存在的目标，
 * 且 `switchInstance` 里若拿 mock 的 dbKeys 去复位 db，真实态会复位错。
 */
const { data: instancesData, load: loadInstances } = useResource<RedisInstance[], undefined>(
  () => storageApi.listRedisInstances(),
  { mock: () => withDelay([...REDIS_INSTANCES]) },
)

const instanceOptions = computed(() =>
  (instancesData.value ?? []).map((i) => ({
    label: `${i.host}:${i.port}${i.db ? ` db${i.db}` : ''}`,
    value: i.id,
  })),
)

/** 当前实例有键的 db 列表 */
const dbOptions = computed(() => {
  const list = info.value?.dbKeys ?? []
  return list.map((d) => ({ label: `db${d.db}（${d.keys} 键）`, value: d.db }))
})

function switchInstance(id: string) {
  instanceId.value = id
  /*
   * 切实例时把 db 复位到「该实例有键的第一个 db」。
   *
   * ⚠️ 这里**不能读 `infoData.value`** —— 它是**上一个实例**的 info，
   * 还没被新请求覆盖。用它复位会把上一个实例的 db 带到新实例上
   * （比如从 136 的 db1 切到 137，137 若没有 db1 就会一个键都查不到）。
   *
   * 所以复位只能依赖**配置里的起始 db**（后端 listInstances 已经算好了
   * 「该主机有键的第一个 db」）。新实例的 info 回来后，
   * dbOptions 会按真实 dbKeys 刷新，用户再手动选具体 db。
   */
  const fromConfig = instancesData.value?.find((i) => i.id === id)?.db
  db.value = fromConfig ?? 0
  scanPage.value = 1
  loadInfo({ instanceId: id })
  reloadKeys()
}

function switchDb(value: number) {
  db.value = value
  scanPage.value = 1
  reloadKeys()
}

/* ------------------------------ 键列表 ------------------------------ */

const keyword = ref('')
const domainFilter = ref<RedisKeyDomain | null>(null)
const typeFilter = ref<RedisValueType | null>(null)
const ttlFilter = ref<'all' | 'has' | 'none'>('all')
const scanPage = ref(1)
const scanPageSize = ref(15)

const {
  data: keyData,
  loading: keyLoading,
  error: keyError,
  isMock,
  load: loadKeys,
} = useResource(
  (params: { instanceId: string; db: number } & Record<string, unknown>) => storageApi.scanKeys(params.instanceId, params.db, params),
  {
    mock: (params: { instanceId: string; db: number } & Record<string, unknown>) =>
      withDelay(mockStorageApi.scanKeys(params.instanceId, params.db, params)),
  },
)

function keyParams() {
  return {
    instanceId: instanceId.value,
    db: db.value,
    page: scanPage.value,
    pageSize: scanPageSize.value,
    keyword: keyword.value.trim() || undefined,
    domain: domainFilter.value ?? undefined,
    type: typeFilter.value ?? undefined,
    hasTtl: ttlFilter.value === 'all' ? undefined : ttlFilter.value === 'has',
  }
}

function reloadKeys() {
  loadKeys(keyParams())
}

/**
 * 首次进入：**先取实例清单，再取该实例的数据**。
 *
 * ⚠️ 顺序不能反。原来这里是
 * `loadInfo({ instanceId: instanceId.value }); reloadKeys()`
 * 直接拿初值去请求 —— 而初值 `'136'` 是 mock 时代的旧 id，
 * 后端如实回「未知的 Redis 主机：136」。
 *
 * 即使把初值改成 `'192.168.1.136'` 也仍然是硬编码，
 * 所以正确做法是：等清单回来 → 用第一个真实实例 → 再取数据。
 * 清单为空（后端未就绪 / 无可用实例）时不发后续请求，
 * 让页面显示「加载失败」，而不是发一个注定 400 的请求。
 */
onMounted(async () => {
  await loadInstances()
  const first = instancesData.value?.[0]
  if (!first) {
    return
  }
  instanceId.value = first.id
  db.value = first.db ?? 0
  loadInfo({ instanceId: instanceId.value })
  reloadKeys()
})

function handleSearch() {
  scanPage.value = 1
  reloadKeys()
}

function resetFilters() {
  keyword.value = ''
  domainFilter.value = null
  typeFilter.value = null
  ttlFilter.value = 'all'
  scanPage.value = 1
  reloadKeys()
}

const DOMAIN_LABEL: Record<RedisKeyDomain, string> = {
  account: '账号',
  strategy: '策略',
  trade: '委托',
  stream: '消息流',
  lock: '锁',
  meta: '元数据',
  other: '其他',
}

const DOMAIN_COLOR: Record<RedisKeyDomain, string> = {
  account: '#d94f8a',
  strategy: '#7c5cf0',
  trade: '#0d9c9c',
  stream: '#dd7a15',
  lock: '#d6455e',
  meta: '#5b6b8c',
  other: '#8a94a6',
}

const domainOptions = (Object.keys(DOMAIN_LABEL) as RedisKeyDomain[]).map((d) => ({
  label: DOMAIN_LABEL[d],
  value: d,
}))

const typeOptions: Array<{ label: string; value: RedisValueType }> = [
  { label: 'string', value: 'string' },
  { label: 'stream', value: 'stream' },
  { label: 'ReJSON-RL', value: 'ReJSON-RL' },
  { label: 'hash', value: 'hash' },
  { label: 'list', value: 'list' },
  { label: 'set', value: 'set' },
  { label: 'zset', value: 'zset' },
]

const keys = computed<RedisKeyInfo[]>(() => keyData.value?.list ?? [])
const keyTotal = computed(() => keyData.value?.total ?? 0)

/* ------------------------------ TTL 格式化 ------------------------------ */

/** PTTL 毫秒 → 可读文本（-1 永不过期，-2 不存在） */
function ttlText(pttl: number): string {
  if (pttl === -1) return '永不过期'
  if (pttl === -2) return '不存在'
  if (pttl < 1000) return `${pttl} 毫秒`
  const s = Math.floor(pttl / 1000)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分 ${s % 60} 秒`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 时 ${m % 60} 分`
  return `${Math.floor(h / 24)} 天 ${h % 24} 时`
}

/** 是否带 TTL —— 这是本页最值得看的一列 */
function hasTtl(row: RedisKeyInfo): boolean {
  return row.pttl >= 0
}

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = keys.value
  return {
    shown: list.length,
    total: keyTotal.value,
    withTtl: list.filter(hasTtl).length,
    dbKeys: info.value?.dbKeys.reduce((s, d) => s + d.keys, 0) ?? 0,
  }
})

/**
 * 当前 db 的类型分布。
 *
 * ⚠️ 原来这里无条件调 `mockStorageApi.typeDistribution(...)` ——
 * **真实态也在用假数据画饼图**，而且不报错、看不出来。
 * 现改为按**当前页真实键列表**统计；真实态不再触碰 mock
 * （前端 README 第 4 条：图表也不许拿 mock 充数）。
 *
 * 口径说明：这是「本页口径」（当前页的键），不是全库分布 ——
 * 后端 SCAN 有硬上限，全库分布无法在单次请求里准确给出。
 * 页面标题已写明「本页口径」。
 */
const typeChartOption = computed(() => {
  const counts = new Map<string, number>()
  for (const k of keys.value) {
    const t = k.type ?? 'unknown'
    counts.set(t, (counts.get(t) ?? 0) + 1)
  }
  const dist = [...counts.entries()]
    .map(([name, value]) => ({ name, value }))
    .sort((a, b) => b.value - a.value)
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 个键（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data: dist,
        color: ['#dd7a15', '#d94f8a', '#3b6ff5', '#7c5cf0', '#0d9c9c', '#2f9e5f'],
      },
    ],
  }
})

/** 各 db 键数 */
const dbChartOption = computed(() => {
  const list = info.value?.dbKeys ?? []
  return {
    grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', name: '键数', minInterval: 1 },
    yAxis: { type: 'category', data: list.map((d) => `db${d.db}`).reverse(), axisLabel: { fontSize: 11 } },
    series: [
      {
        type: 'bar',
        data: list.map((d) => d.keys).reverse(),
        barMaxWidth: 18,
        itemStyle: { color: accent, borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<RedisKeyInfo> = [
  {
    title: '键',
    key: 'key',
    minWidth: 240,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono', style: 'word-break:break-all' }, row.key) },
      ),
  },
  {
    title: '类型',
    key: 'type',
    width: 120,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.type === 'ReJSON-RL' ? 'info' : 'default' },
        { default: () => row.type },
      ),
  },
  {
    title: '归属',
    key: 'domain',
    width: 100,
    render: (row) => {
      const d = (row.domain ?? 'other') as RedisKeyDomain
      return h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          style: { color: DOMAIN_COLOR[d], background: `${DOMAIN_COLOR[d]}1f` },
        },
        { default: () => DOMAIN_LABEL[d] },
      )
    },
  },
  {
    title: '长度',
    key: 'length',
    width: 120,
    align: 'right',
    sorter: (a, b) => (a.length ?? 0) - (b.length ?? 0),
    render: (row) =>
      row.length === undefined
        ? h('span', { class: 'text-muted' }, '—')
        : h('span', { class: 'num' }, thousands(row.length)),
  },
  {
    title: 'TTL',
    key: 'pttl',
    width: 150,
    sorter: (a, b) => a.pttl - b.pttl,
    render: (row) => {
      const ttl = hasTtl(row)
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h('span', {}, [
              h(NIcon, {
                size: 13,
                style: `vertical-align:-2px;margin-right:4px;opacity:.7;color:${ttl ? 'var(--warning)' : 'inherit'}`,
                component: TimeOutline,
              }),
              h('span', { style: ttl ? 'color:var(--warning);font-weight:600' : '' }, ttlText(row.pttl)),
            ]),
          default: () => (ttl ? `PTTL = ${row.pttl} 毫秒，会过期` : 'PTTL = -1，永不过期'),
        },
      )
    },
  },
  {
    title: '操作',
    key: 'actions',
    width: 96,
    fixed: 'right',
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          { size: 'tiny', quaternary: true, onClick: () => openDetail(row) },
          { icon: () => h(NIcon, null, { default: () => h(EyeOutline) }) },
        ),
        h(
          NPopconfirm,
          {
            onPositiveClick: () => deleteKey(row),
            positiveText: '删除',
            negativeText: '取消',
          },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'error' },
                { icon: () => h(NIcon, null, { default: () => h(TrashOutline) }) },
              ),
            default: () => `确认删除键 ${row.key}？此操作不可撤销。`,
          },
        ),
      ]),
  },
]

/* ------------------------------ 键详情 ------------------------------ */

const showDetail = ref(false)
const active = ref<RedisKeyInfo | null>(null)
const detail = ref<RedisKeyDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref<string>()

async function openDetail(row: RedisKeyInfo) {
  active.value = row
  detail.value = null
  detailError.value = undefined
  showDetail.value = true
  detailLoading.value = true
  try {
    if (isMock.value) {
      detail.value = mockStorageApi.getKey(instanceId.value, db.value, row.key)
    } else {
      detail.value = await storageApi.getKey(instanceId.value, db.value, row.key)
    }
  } catch (err) {
    detailError.value = err instanceof Error ? err.message : String(err)
  } finally {
    detailLoading.value = false
  }
}

const prettyJson = computed(() => (detail.value?.json ? formatJson(detail.value.json) : ''))

function deleteKey(row: RedisKeyInfo) {
  // 后端未接通：如实提示，不做本地假删除
  message.warning(`后端接口尚未实现：删除键 ${row.key} 将调用 DEL（高危操作，需后端审计）`)
}
</script>

<template>
  <div>
    <QueryErrorAlert v-if="keyError" :message="keyError" :loading="keyLoading" @retry="reloadKeys" />

    <!-- 实例与 DB 选择 -->
    <n-card class="page-card" size="small" :bordered="false">
      <div class="toolbar">
        <span class="lbl">实例</span>
        <n-select
          :value="instanceId"
          :options="instanceOptions"
          style="width: 210px"
          @update:value="switchInstance"
        />
        <span class="lbl">DB</span>
        <n-select
          :value="db"
          :options="dbOptions"
          style="width: 190px"
          @update:value="switchDb"
        />
        <n-button size="small" :loading="infoLoading" @click="loadInfo({ instanceId })">
          <template #icon>
            <n-icon :component="RefreshOutline" />
          </template>
          刷新实例信息
        </n-button>
      </div>

      <n-grid v-if="info" :cols="4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <n-gi span="4 s:2 m:1">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="版本">
              <span class="mono">{{ info.version }}</span>
              <span class="text-muted" style="margin-left: 6px">{{ info.mode }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="运行时长">
              {{ Math.floor(info.uptimeInSeconds / 86400) }} 天
            </n-descriptions-item>
            <n-descriptions-item label="客户端数">
              <span class="num">{{ info.connectedClients }}</span>
            </n-descriptions-item>
          </n-descriptions>
        </n-gi>
        <n-gi span="4 s:2 m:1">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="已用内存">{{ info.usedMemoryHuman }}</n-descriptions-item>
            <n-descriptions-item label="内存峰值">
              <n-tooltip v-if="info.usedMemory < 500_000_000 && info.usedMemoryPeakHuman.includes('G')" trigger="hover">
                <template #trigger>
                  <span style="color: var(--warning); font-weight: 600">{{ info.usedMemoryPeakHuman }}</span>
                </template>
                当前内存远低于历史峰值，说明曾经涨到过这个量级，值得关注
              </n-tooltip>
              <span v-else>{{ info.usedMemoryPeakHuman }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="内存上限">
              <n-tag v-if="info.maxmemory === 0" size="small" round :bordered="false" type="warning">
                无上限
              </n-tag>
              <span v-else>{{ info.maxmemory }}</span>
              <span class="text-muted" style="margin-left: 6px">{{ info.maxmemoryPolicy }}</span>
            </n-descriptions-item>
          </n-descriptions>
        </n-gi>
        <n-gi span="4 s:2 m:1">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="命令总数">
              <span class="num">{{ thousands(info.totalCommandsProcessed) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="命中次数">
              <span class="num">{{ thousands(info.keyspaceHits) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="未命中">
              <span class="num">{{ thousands(info.keyspaceMisses) }}</span>
            </n-descriptions-item>
          </n-descriptions>
        </n-gi>
        <n-gi span="4 s:2 m:1">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="系统">
              <span style="font-size: 12px">{{ info.os }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="键总数">
              <span class="num">{{ thousands(summary.dbKeys) }}</span>
              <span class="text-muted" style="margin-left: 4px">（各 db 合计）</span>
            </n-descriptions-item>
          </n-descriptions>
        </n-gi>
      </n-grid>
    </n-card>

    <!-- 汇总 + 图表 -->
    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="当前 db 键数"
          :value="summary.total"
          suffix="个"
          :icon="FolderOpenOutline"
          :color="accent"
          hint="SCAN 命中的总数"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="本页列出"
          :value="summary.shown"
          suffix="个"
          :icon="LayersOutline"
          color="#3b6ff5"
          hint="分页后的条数"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="带 TTL 的键"
          :value="summary.withTtl"
          suffix="个"
          :icon="TimeOutline"
          color="#dd7a15"
          hint="会过期的键，本页重点"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="实例总键数"
          :value="thousands(summary.dbKeys)"
          suffix="个"
          :icon="ServerOutline"
          color="#7c5cf0"
          hint="该实例各 db 合计"
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
              <n-icon :component="CloudOutline" :color="accent" :size="17" />
              当前 db 类型分布
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">{{ instanceId }} / db{{ db }}</span>
          </template>
          <BaseChart :option="typeChartOption" :height="230" empty-text="暂无键" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="LayersOutline" :color="accent" :size="17" />
              各 db 键数
            </span>
          </template>
          <BaseChart :option="dbChartOption" :height="230" empty-text="暂无数据" />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 键列表 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="CloudOutline" :color="accent" :size="17" />
          键浏览 <span class="mono text-muted" style="font-size: 12px; font-weight: 400">{{ instanceId }} / db{{ db }}</span>
        </span>
      </template>

      <div class="toolbar">
        <SearchInput
          v-model:value="keyword"
          placeholder="匹配键名（SCAN MATCH）"
          style="width: 230px"
          :loading="keyLoading"
          @search="handleSearch"
          @clear="handleSearch"
        />

        <n-select
          v-model:value="domainFilter"
          :options="domainOptions"
          placeholder="全部归属"
          clearable
          style="width: 130px"
          @update:value="handleSearch"
        />
        <n-select
          v-model:value="typeFilter"
          :options="typeOptions"
          placeholder="全部类型"
          clearable
          style="width: 140px"
          @update:value="handleSearch"
        />

        <n-button-group>
          <n-button
            v-for="opt in [
              { key: 'all', label: 'TTL 不限' },
              { key: 'has', label: '有 TTL' },
              { key: 'none', label: '永不过期' },
            ]"
            :key="opt.key"
            size="small"
            :type="ttlFilter === opt.key ? 'primary' : 'default'"
            :quaternary="ttlFilter !== opt.key"
            @click="ttlFilter = opt.key as 'all' | 'has' | 'none'; handleSearch()"
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

        <n-button size="small" :loading="keyLoading" @click="reloadKeys">
          <template #icon>
            <n-icon :component="RefreshOutline" />
          </template>
          刷新
        </n-button>
      </div>

      <n-data-table
        remote
        :columns="columns"
        :data="keys"
        :loading="keyLoading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: RedisKeyInfo) => row.key"
        :scroll-x="1010"
        :pagination="{
          page: scanPage,
          pageSize: scanPageSize,
          itemCount: keyTotal,
          showSizePicker: true,
          pageSizes: [15, 30, 60],
          onUpdatePage: (p: number) => { scanPage = p; reloadKeys() },
          onUpdatePageSize: (s: number) => { scanPageSize = s; scanPage = 1; reloadKeys() },
          prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 个键`,
        }"
      />
    </n-card>

    <!-- 键详情 -->
    <AppDialog v-model:show="showDetail" :title="`键详情 · ${active?.key ?? ''}`" :width="680" mask-closable>
      <template v-if="active">
        <n-alert v-if="detailError" type="error" :bordered="false" style="margin-bottom: 12px">
          读取失败：{{ detailError }}
        </n-alert>

        <n-descriptions :column="2" label-placement="top" size="small" bordered>
          <n-descriptions-item label="键名" :span="2">
            <span class="mono" style="word-break: break-all">{{ active.key }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="类型">
            <n-tag size="small" round :bordered="false" type="info">{{ active.type }}</n-tag>
          </n-descriptions-item>
          <n-descriptions-item label="归属">
            {{ DOMAIN_LABEL[(active.domain ?? 'other') as RedisKeyDomain] }}
          </n-descriptions-item>
          <n-descriptions-item label="长度">
            {{ active.length !== undefined ? thousands(active.length) : '—' }}
          </n-descriptions-item>
          <n-descriptions-item label="TTL">
            <span :style="hasTtl(active) ? 'color:var(--warning);font-weight:600' : ''">
              {{ ttlText(active.pttl) }}
            </span>
          </n-descriptions-item>
          <n-descriptions-item label="编码">
            <span class="mono">{{ detail?.encoding ?? '-' }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="内存占用">
            <span class="num">{{ detail?.memoryUsage ? thousands(detail.memoryUsage) + ' 字节' : '—' }}</span>
          </n-descriptions-item>
        </n-descriptions>

        <!-- 按类型渲染 -->
        <div v-if="detailLoading" class="text-muted" style="padding: 18px 0; text-align: center">
          读取中…
        </div>

        <div v-else-if="detail" class="detail-block">
          <template v-if="detail.json">
            <div class="detail-title">
              <n-icon :component="CodeSlashOutline" :color="accent" />
              JSON 内容
            </div>
            <n-code :code="prettyJson" language="json" show-line-numbers word-wrap />
          </template>

          <template v-else-if="detail.value !== undefined">
            <div class="detail-title">
              <n-icon :component="CodeSlashOutline" :color="accent" />
              字符串值
            </div>
            <n-code :code="detail.value" language="text" show-line-numbers word-wrap />
          </template>

          <template v-else-if="detail.entries?.length">
            <div class="detail-title">
              <n-icon :component="LayersOutline" :color="accent" />
              最近条目（{{ detail.entries.length }}）
            </div>
            <div v-for="e in detail.entries" :key="e.id" class="entry-card">
              <div class="entry-id mono">{{ e.id }}</div>
              <div v-for="(v, k) in e.fields" :key="k" class="entry-row">
                <span class="entry-k mono">{{ k }}</span>
                <span class="entry-v mono">{{ v }}</span>
              </div>
            </div>
          </template>

          <template v-else-if="detail.members?.length">
            <div class="detail-title">
              <n-icon :component="LayersOutline" :color="accent" />
              成员（{{ detail.members.length }}）
            </div>
            <div class="members">
              <span v-for="m in detail.members" :key="m" class="mono member-chip">{{ m }}</span>
            </div>
          </template>

          <div v-else class="text-muted" style="font-size: 13px">该键没有可展示的内容。</div>
        </div>

        <div class="detail-note">
          <n-icon :component="InformationCircleOutline" :color="accent" />
          <div>
            数据来自 Redis<template v-if="detail?.type === 'stream'">（
              <code>XLEN</code> / <code>XRANGE</code>，只取最近若干条，不读全量）</template>。
            删除键将执行 <code>DEL</code>，<b>不可撤销</b>，需后端加审计。
          </div>
        </div>
      </template>

      <template #footer>
        <n-popconfirm
          :on-positive-click="() => active && deleteKey(active)"
          positive-text="删除"
          negative-text="取消"
        >
          <template #trigger>
            <n-button size="small" type="error" ghost>
              <template #icon>
                <n-icon :component="TrashOutline" />
              </template>
              删除此键
            </n-button>
          </template>
          确认删除键 {{ active?.key }}？此操作不可撤销。
        </n-popconfirm>
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

.lbl {
  font-size: 12.5px;
  color: var(--text-muted);
}

.detail-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.detail-note b {
  color: var(--warning);
}

.num {
  font-variant-numeric: tabular-nums;
}

.row-actions {
  display: flex;
  gap: 2px;
}

.detail-block {
  margin-top: 18px;
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
}

.entry-card {
  padding: 10px 12px;
  background: var(--bg-body);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.entry-card + .entry-card {
  margin-top: 10px;
}

.entry-id {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--text-muted);
}

.entry-row {
  display: flex;
  gap: 10px;
  padding: 3px 0;
  font-size: 12px;
  line-height: 1.6;
}

.entry-k {
  flex: none;
  width: 130px;
  color: var(--text-muted);
}

.entry-v {
  min-width: 0;
  word-break: break-all;
}

.members {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.member-chip {
  padding: 2px 8px;
  background: var(--bg-body);
  border: 1px solid var(--border);
  border-radius: 999px;
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


.spacer {
  flex: 1;
}
</style>
