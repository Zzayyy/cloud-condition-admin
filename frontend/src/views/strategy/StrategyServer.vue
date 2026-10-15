<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import {
  NAlert,
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
  NInput,
  NProgress,
  NTag,
  NTooltip,
  type DataTableColumns,
  type DataTableSortState,
} from 'naive-ui'
import {
  CheckmarkCircleOutline,
  CloseCircleOutline,
  LayersOutline,
  PulseOutline,
  RefreshOutline,
  ServerOutline,
  TimeOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { strategyApi } from '@/api/endpoints'
import {
  SERVER_OFFLINE_AFTER_SEC,
  isServerOnline,
  mockStrategyApi,
} from '@/api/mock/strategy'
import { withDelay } from '@/api/mock/_shared'
import type { StrategyServer } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { relativeTime, thousands } from '@/utils/format'

/**
 * 策略服务器页：节点清单 + 在线状态 + 承载条件单用户数。
 *
 * ⚠️ 现场策略服务器**当前未开放**（实测 2026-10-15）：
 *  - 注册表 `strategy-srv` 不刷新（盯 30 秒内容不变）；
 *  - `strategyserver_beat_1` 频道上没有心跳（订阅 25 秒 0 条）。
 *
 * 心跳收不到是**预期现象**，所以后端**不订阅**心跳频道（加了也收不到，
 * 还会因 `PUBSUB CHANNELS` 只列有订阅者的频道而凭空造出订阅者、污染 Redis 管理页）。
 * 「在线」按 `lastActiveTimeLong` 判，结果接近 0 台 —— 后端用
 * `meta.registryStale` 说明「注册表没在更新」，页面据此提示，
 * 免得被读成「策略平台全挂了」。
 *
 * ⚠️ `usecount` 现场全部为 0（该字段未上报），列头已标注。
 *
 * ⚠️ 本页**只读**：不提供启停。现场 id 全是 0、mac 又各带实例后缀，
 * 没有任何字段能稳定指回「同一台机器」。
 */

const accent = '#7c5cf0'

const keyword = ref('')
const onlineFilter = ref<'all' | 'online' | 'offline'>('all')
const page = ref(1)
const pageSize = ref(10)
const sortState = ref<DataTableSortState | null>(null)

const { data, loading, error, load } = useResource(
  (params: Record<string, unknown>) => strategyApi.listServers(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockStrategyApi.listServers(params)) },
)

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    online: onlineFilter.value === 'all' ? undefined : onlineFilter.value === 'online',
    sortBy: sortState.value?.columnKey as string | undefined,
    sortOrder: sortState.value ? (sortState.value.order === 'descend' ? 'desc' : 'asc') : undefined,
  }
}

function reload() {
  load(currentParams())
}

onMounted(reload)

// 10 秒自动刷新（真实场景下节点心跳是 5s 一次）
let timer: ReturnType<typeof setInterval> | undefined
onUnmounted(() => clearInterval(timer))
timer = setInterval(reload, 10_000)

function handleSearch() {
  page.value = 1
  reload()
}

function handleFilter(value: 'all' | 'online' | 'offline') {
  onlineFilter.value = value
  page.value = 1
  reload()
}

function handlePageChange(p: number) {
  page.value = p
  reload()
}

function handlePageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  reload()
}

function handleSorter(sorter: DataTableSortState | DataTableSortState[] | null) {
  sortState.value = Array.isArray(sorter) ? sorter[0] : sorter
  reload()
}

/* ------------------------------ 汇总指标 ------------------------------ */

const rows = computed<StrategyServer[]>(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)

/**
 * 后端回传的元信息（真实态才有）。
 *
 * ⚠️ 汇总必须用 meta 的**全量**口径，不能对当前页 `filter`：
 * 后端返回的是分页后的行，按当前页统计会得出「第 1 页有几台在线」，
 * 而卡片上写的是「在线节点数」—— 翻页时数字会莫名变化。
 * （原实现就是这么错的，代码里自己写了「真实汇总应由后端单独提供接口」。）
 */
const meta = computed(() => data.value?.meta ?? null)

const summary = computed(() => {
  const m = meta.value
  if (m) {
    return {
      onlineCount: m.onlineCount,
      offlineCount: m.offlineCount,
      usecount: m.usecountTotal,
      maxUse: m.usecountMax,
    }
  }
  // 演示态（或真实态拿不到 meta 时）退回按当前页统计，并如实标注口径
  const list = rows.value
  const online = list.filter(isServerOnline)
  return {
    onlineCount: online.length,
    offlineCount: list.length - online.length,
    usecount: list.reduce((sum, s) => sum + (s.usecount ?? 0), 0),
    maxUse: list.reduce((max, s) => Math.max(max, s.usecount ?? 0), 0),
  }
})

/** 汇总是否为「当前页」口径（演示态或 meta 缺失时，页面要如实说明） */
const summaryIsPageScoped = computed(() => !meta.value)

/**
 * 现场现状提示。
 *
 * 策略服务器**当前未开放**：注册表不刷新、心跳也收不到，
 * 所以「在线 0 台」是必然结果。不说明的话，看页面的人会以为是服务挂了，
 * 从而去查节点 —— 而真正该知道的是「这个注册表本身就没在更新」。
 */
const statusNote = computed(() => {
  const m = meta.value
  if (!m) return null
  const parts: string[] = []
  if (m.redisUnavailable?.length) {
    parts.push(`有 ${m.redisUnavailable.length} 个 Redis 实例连不上（${m.redisUnavailable.join('、')}），列表可能不完整。`)
  }
  if (m.registryStale) {
    parts.push(
      `注册表 ${m.registryKey} 已${
        m.newestAgeSec == null ? '始终没有可用的心跳时间' : ` ${m.newestAgeSec} 秒`
      }未刷新（阈值 ${m.staleRegistryAfterSec} 秒）—— 说明策略服务器当前未开放，` +
        '「离线」是必然结果，不必据此排查节点。',
    )
  }
  return parts.length ? parts.join(' ') : null
})

const usageOption = computed(() => ({
  grid: { left: 8, right: 24, top: 8, bottom: 8, containLabel: true },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  xAxis: { type: 'value', name: '条件单用户数' },
  yAxis: {
    type: 'category',
    data: [...rows.value].reverse().map((s) => `${s.ip}\n:${s.id}`),
    axisLabel: { fontSize: 11 },
  },
  series: [
    {
      type: 'bar',
      name: 'usecount',
      data: [...rows.value].reverse().map((s) => s.usecount ?? 0),
      barMaxWidth: 18,
      itemStyle: {
        borderRadius: [0, 4, 4, 0],
        color: (params: { dataIndex: number }) => {
          const src = [...rows.value].reverse()[params.dataIndex]
          return src && isServerOnline(src) ? accent : '#b9bfcc'
        },
      },
    },
  ],
}))

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<StrategyServer> = [
  {
    title: '状态',
    key: 'online',
    width: 88,
    render: (row) => {
      const online = isServerOnline(row)
      return h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          type: online ? 'success' : row.enable ? 'error' : 'default',
        },
        {
          icon: () => h(NIcon, null, { default: () => h(online ? CheckmarkCircleOutline : CloseCircleOutline) }),
          default: () => (online ? '在线' : row.enable ? '离线' : '停用'),
        },
      )
    },
  },
  {
    title: '节点 ID',
    key: 'id',
    width: 84,
    sorter: true,
    render: (row) => h('span', { class: 'mono' }, String(row.id)),
  },
  {
    title: 'IP 地址',
    key: 'ip',
    width: 150,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 500 } }, row.ip),
  },
  {
    title: 'MAC',
    key: 'mac',
    width: 200,
    ellipsis: { tooltip: true },
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () => h('span', { class: 'mono' }, row.mac),
          /*
           * 这里的 mac 是「本机网卡地址 + 实例名后缀」（现场 …QLDataHub1 / …probe），
           * 所以同一台机器会出现多个不同的 mac —— 不是数据错了。
           */
          default: () =>
            '该值是「本机网卡 MAC + 实例名后缀」（现场 …QLDataHub1 / …probe），\n' +
            '同一台机器出现多个不同的 mac 是正常的，不能当唯一键',
        },
      ),
  },
  {
    title: '来源实例',
    key: 'instance',
    width: 140,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () => h('span', { class: 'mono', style: { fontSize: '12px' } }, row.instance ?? '-'),
          // 现场 id 全是 0，只有 (来源实例, 下标) 能唯一定位一行
          default: () =>
            `来源：${row.instance ?? '-'}\n数组下标：${row.arrayIndex ?? '-'}\n` +
            '（同一键在多个库上各存一份；id 会重复，故用来源+下标区分）',
        },
      ),
  },
  {
    title: '条件单用户数',
    key: 'usecount',
    width: 150,
    sorter: true,
    render: (row) => {
      const value = row.usecount ?? 0
      // 分母用**全量**最大值，否则翻页时进度条会跳（当前页最大 ≠ 全局最大）
      const pct = summary.value.maxUse > 0 ? Math.round((value / summary.value.maxUse) * 100) : 0
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h('div', { class: 'use-cell' }, [
              h('span', { class: 'use-num' }, thousands(value)),
              h(NProgress, {
                type: 'line',
                percentage: pct,
                height: 4,
                showIndicator: false,
                color: accent,
                railColor: 'var(--bg-hover)',
                borderRadius: 2,
              }),
            ]),
          /*
           * 现场该字段未上报（全为 0），必须说明 ——
           * 否则这一列（页面最重要的内容）会被读成「策略平台一个用户都没承载」。
           */
          default: () =>
            `承载条件单用户数：${value}\n` +
            '⚠️ 现场 usecount 未上报（全部为 0），不代表策略平台没有承载用户',
        },
      )
    },
  },
  {
    title: '最后活跃',
    key: 'lastActiveTimeLong',
    width: 150,
    sorter: true,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          // 注意：vueuc 要求 trigger/default 槽各自「恰好一个子节点」，
          // 返回数组会抛 "slot[default] should have exactly one child"
          trigger: () =>
            h('span', {}, [
              h(NIcon, { size: 13, style: 'vertical-align:-2px;margin-right:4px;opacity:.6', component: TimeOutline }),
              relativeTime(row.lastActiveTimeLong),
            ]),
          default: () => row['last-active-time'],
        },
      ),
  },
  {
    title: '上线时间',
    key: 'login-time',
    width: 160,
    ellipsis: { tooltip: true },
  },
  {
    title: '启用',
    key: 'enable',
    width: 88,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              NTag,
              { size: 'small', round: true, bordered: false, type: row.enable ? 'info' : 'default' },
              { default: () => (row.enable ? '已启用' : '已停用') },
            ),
          /*
           * 只读：本页不提供启停。现场 id 全是 0、mac 又各带实例后缀，
           * 没有任何字段能稳定指回「同一台机器」——
           * 按它们写回容易改到一台不属于本次运维范围的机器上。
           */
          default: () => `enable=${row.enable}\n只读展示，本页不提供启停（由现场流程操作）`,
        },
      ),
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
const active = ref<StrategyServer | null>(null)

function openDetail(row: StrategyServer) {
  active.value = row
  showDetail.value = true
}

/** 心跳新鲜度：距离阈值还有多久 */
const heartbeatAge = computed(() => {
  if (!active.value) return 0
  return Math.max(0, Math.floor(Date.now() / 1000) - active.value.lastActiveTimeLong)
})
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />

    <!--
      现场现状：策略服务器未开放 → 注册表不刷新 → 「在线 0 台」是必然结果。
      不说清的话，看页面的人会以为服务挂了，从而去查节点（方向错了）。
    -->
    <n-alert v-if="statusNote" type="warning" :bordered="false" size="small" style="margin-bottom: 14px">
      {{ statusNote }}
    </n-alert>

    <!-- 汇总指标 -->
    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="在线节点"
          :value="summary.onlineCount"
          suffix="个"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          :hint="`心跳 ${meta?.offlineAfterSec ?? 30} 秒内视为在线${summaryIsPageScoped ? '（当前页口径）' : ''}`"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="离线 / 停用"
          :value="summary.offlineCount"
          suffix="个"
          :icon="CloseCircleOutline"
          color="#d6455e"
          :hint="meta?.registryStale ? '注册表未刷新（策略服务器未开放），离线是必然结果' : '需要人工确认是否正常下线'"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="承载条件单用户"
          :value="thousands(summary.usecount)"
          suffix="个"
          :icon="LayersOutline"
          color="#7c5cf0"
          hint="⚠️ 现场 usecount 未上报（全为 0），不代表没有承载用户"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="节点总数"
          :value="total"
          suffix="个"
          :icon="ServerOutline"
          :color="accent"
          hint="跨实例全读（strategy-srv 在 136 db0 / db1 各一份）"
        />
      </n-gi>
    </n-grid>

    <!-- 负载分布 -->
    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="LayersOutline" :color="accent" :size="17" />
          节点负载分布
        </span>
      </template>
      <template #header-extra>
        <span class="text-muted" style="font-size: 12px">按 usecount 排序，灰条表示离线/停用节点</span>
      </template>
      <BaseChart :option="usageOption" :height="Math.max(180, rows.length * 42)" empty-text="暂无节点" />
    </n-card>

    <!-- 节点列表 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="ServerOutline" :color="accent" :size="17" />
          策略服务器节点
        </span>
      </template>
      <template #header-extra>
        <span class="text-muted" style="font-size: 12px">
          <n-icon :component="PulseOutline" size="12" style="vertical-align: -2px" />
          每 10 秒自动刷新
        </span>
      </template>

      <div class="toolbar">
        <SearchInput
          v-model:value="keyword"
          placeholder="搜索 IP 或 MAC"
          style="width: 240px"
          :loading="loading"
          @search="handleSearch"
          @clear="handleSearch"
        />

        <n-button-group>
          <n-button
            v-for="opt in [
              { key: 'all', label: '全部' },
              { key: 'online', label: '在线' },
              { key: 'offline', label: '离线/停用' },
            ]"
            :key="opt.key"
            size="small"
            :type="onlineFilter === opt.key ? 'primary' : 'default'"
            :quaternary="onlineFilter !== opt.key"
            @click="handleFilter(opt.key as 'all' | 'online' | 'offline')"
          >
            {{ opt.label }}
          </n-button>
        </n-button-group>

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
        :row-key="(row: StrategyServer) => `${row.instance ?? '?'}#${row.arrayIndex ?? row.id}-${row.mac}`"
        :scroll-x="1180"
        :pagination="{
          page: page,
          pageSize: pageSize,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50],
          onUpdatePage: handlePageChange,
          onUpdatePageSize: handlePageSizeChange,
          prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 个节点`,
        }"
        @update:sorter="handleSorter"
      />
    </n-card>

    <!-- 节点详情 -->
    <n-drawer v-model:show="showDetail" :width="480" placement="right">
      <n-drawer-content :title="`节点详情 · ${active?.ip ?? ''}`" closable>
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
              <n-tag
                size="small"
                round
                :bordered="false"
                :type="isServerOnline(active) ? 'success' : active.enable ? 'error' : 'default'"
              >
                {{ isServerOnline(active) ? '在线' : active.enable ? '离线' : '停用' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="承载条件单用户数">
              {{ thousands(active.usecount) }} 个
            </n-descriptions-item>
            <n-descriptions-item label="上线时间">
              {{ active['login-time'] || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="最后活跃">
              {{ active['last-active-time'] }}
              <span class="text-muted" style="margin-left: 6px">{{ relativeTime(active.lastActiveTimeLong) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="心跳间隔">
              {{ heartbeatAge }} 秒前
            </n-descriptions-item>
          </n-descriptions>

          <div class="detail-note">
            <n-icon :component="PulseOutline" :color="accent" />
            <div>
              心跳阈值 {{ SERVER_OFFLINE_AFTER_SEC }} 秒。现场策略服务器每 5 秒发一次心跳
              （<code>strategyserver_beat</code> 频道），超过阈值即判定离线。
            </div>
          </div>

          <div class="detail-note">
            <n-icon :component="ServerOutline" :color="accent" />
            <div>
              数据对应 Redis 键 <code>strategy-srv</code> 中的该条记录；
              条件单分配依据是 <code>usecount</code>。
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

.use-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.use-num {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  font-size: 13px;
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
