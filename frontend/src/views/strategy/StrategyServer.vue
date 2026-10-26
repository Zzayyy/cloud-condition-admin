<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
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
import { ACTIVITY_BUCKETS, buildActivityOption } from '@/utils/activity'

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

const { data, loading, error, load, usingMock } = useResource(
  (params: Record<string, unknown>) => strategyApi.listServers(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockStrategyApi.listServers(params)) },
)

/** 一次拉全量时的页大小（节点数远小于它；取回后截断会如实标注） */
const ALL_PAGE_SIZE = 200

/** 全量节点 —— 活跃度分布图要用它，不能用当前页（见下方注释） */
const allRows = ref<StrategyServer[]>([])
const allRowsLoaded = ref(false)

/**
 * 拉全量节点列表。
 *
 * ⚠️ 「活跃度分布」必须基于**全量**，不能拿 `rows`（当前页）——
 * 那画出来是「第 1 页的分布」，节点一多就失真，而且不报错、看不出来。
 * 委托服务器页的「各节点承载账号数」也是同一个道理（用 allAssignments）。
 *
 * ⚠️ 判定走演示分支用 `usingMock`，不用 `isMock`：后者初值 false，
 * 首个请求 await 之前会误判成真实态（见 useResource 的说明）。
 */
async function loadAllRows() {
  if (usingMock.value) {
    allRows.value = mockStrategyApi.listServers({ page: 1, pageSize: ALL_PAGE_SIZE }).list
    allRowsLoaded.value = true
    return
  }
  try {
    const res = await strategyApi.listServers({ page: 1, pageSize: ALL_PAGE_SIZE })
    allRows.value = res.list
    allRowsLoaded.value = true
  } catch {
    // 图表取数失败不该把整页打红（表格自己有错误提示）
    allRows.value = []
    allRowsLoaded.value = false
  }
}

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
  // 活跃度图用的是全量，一并刷新 —— 否则它只在首屏对一次，之后一直是旧数据
  void loadAllRows()
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
  // bottom 留 30：给 x 轴名字腾位置（containLabel 只管刻度标签，不管 axis name）
  grid: { left: 8, right: 30, top: 8, bottom: 30, containLabel: true },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  xAxis: {
    type: 'value',
    name: '条件单用户数',
    // ⚠️ 默认 nameLocation='end' 会把名字放在轴的最右端，横向柱状图那里
    //    正好是柱子末尾 + 数值，会被挤掉/裁掉。放中间（middle）才稳定可见。
    nameLocation: 'middle',
    nameGap: 24,
    nameTextStyle: { fontSize: 11 },
  },
  yAxis: {
    type: 'category',
    // ⚠️ 用 allRows（全量）而不是 rows（当前页）：用当前页会画成
    //    「第 1 页的负载」，节点一多就失真，且不报错、看不出来。
    data: [...allRows.value].reverse().map((s) => `${s.ip}\n:${s.id}`),
    axisLabel: { fontSize: 11 },
  },
  series: [
    {
      type: 'bar',
      name: 'usecount',
      data: [...allRows.value].reverse().map((s) => s.usecount ?? 0),
      barMaxWidth: 18,
      itemStyle: {
        borderRadius: [0, 4, 4, 0],
        color: (params: { dataIndex: number }) => {
          const src = [...allRows.value].reverse()[params.dataIndex]
          return src && isServerOnline(src) ? accent : '#b9bfcc'
        },
      },
    },
  ],
}))

/* ------------------------- 节点活跃度分布 ------------------------- */

/*
 * 分桶与配色在 @/utils/activity —— 委托服务器页用的是**同一份**。
 * 两页各写一份迟早会漂移，而这类不一致在页面上几乎看不出来
 * （只是某一段的柱子高低不同），排查时会被当成数据问题。
 *
 * ⚠️ 传 allRows（全量）而不是 rows（当前页）：用当前页会画成
 *    「第 1 页的分布」，节点一多就失真，而且不报错、看不出来。
 */
const activityOption = computed(() => buildActivityOption(allRows.value))

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
    width: 160,
    render: (row) => {
      /*
       * 列表已**按 mac 归并**：同一台机器在多个库里各注册一份时只留活跃最新的那条，
       * 其余份数压成 `+N`。不归并的话同一台机器会被列多遍、「节点总数」也是错的
       * （现场 2 条只对应 1 台）。
       * 副本信息不丢 —— 悬停能看到「另有几份、分别是哪个库」。
       */
      const extra = (row.copies ?? 1) - 1
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              'span',
              { class: 'mono', style: { fontSize: '12px' } },
              (row.instance ?? '-') + (extra > 0 ? ` +${extra}` : ''),
            ),
          default: () => {
            const lines = [
              `保留的是活跃时间最新的那份：${row.instance ?? '-'}（数组下标 ${row.arrayIndex ?? '-'}）`,
            ]
            if (extra > 0) {
              // 「份数」和「实例数」是两回事：同一实例里可能存了同 mac 的多条记录
              const where = (row.otherInstances ?? []).join('、') || '（实例未知）'
              lines.push(`另有 ${extra} 份旧注册，来自：${where}`)
              lines.push('列表按 mac 归并，故同一台机器只占一行')
            } else {
              lines.push('注册表里只有这一份')
            }
            return lines.join('\n')
          },
        },
      )
    },
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
          hint="按 mac 归并后的台数（同一台在 136 db0 / db1 各注册一份，只算一台）"
        />
      </n-gi>
    </n-grid>

    <!--
      两张分布图并排：一个看「承载多少用户」，一个看「还活着没」。

      ⚠️ 间距写 `margin: 16px 0`（上下都要），不能只写 margin-top：
      卡片间距原本靠 `.page-card { margin-bottom: 16px }`，
      而这一块是 n-grid 不是 .page-card —— 只给上边距的话，
      下面的节点表格会紧贴上来，两块钱连成一片。
    -->
    <n-grid
      cols="1 m:2"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      item-responsive
      style="margin: 16px 0"
    >
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false" style="height: 100%">
          <template #header>
            <span class="section-title">
              <n-icon :component="LayersOutline" :color="accent" :size="17" />
              节点负载分布
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">按 usecount 排序，灰条表示离线/停用节点</span>
          </template>
          <BaseChart
            :option="usageOption"
            :height="Math.max(180, allRows.length * 42)"
            :empty-text="allRowsLoaded ? '暂无节点（strategy-srv 键为空）' : '加载中…'"
          />
        </n-card>
      </n-gi>

      <n-gi>
        <n-card class="page-card" size="small" :bordered="false" style="height: 100%">
          <template #header>
            <span class="section-title">
              <n-icon :component="PulseOutline" :color="accent" :size="17" />
              节点活跃度分布
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">
              按最后活跃距今分桶（全量节点，已按 mac 归并）
            </span>
          </template>
          <!--
            这张图回答的是「0 台在线」背后的程度差异：
            全是 5 分钟前掉的 → 刚集体掉线，要立刻查；
            全是 2 个月前     → 历史残留，清理即可。
            策略服务器未开放时心跳本来就不通，分桶能直接看出「全是陈旧的」。
          -->
          <BaseChart
            :option="activityOption"
            :height="Math.max(180, ACTIVITY_BUCKETS.length * 42)"
            :empty-text="allRowsLoaded ? '暂无节点（strategy-srv 键为空）' : '加载中…'"
          />
        </n-card>
      </n-gi>
    </n-grid>

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
    <AppDialog v-model:show="showDetail" :title="`节点详情 · ${active?.ip ?? ''}`" :width="480" mask-closable>
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
