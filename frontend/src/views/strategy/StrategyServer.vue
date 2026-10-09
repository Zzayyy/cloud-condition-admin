<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
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
  NInput,
  NProgress,
  NSwitch,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
  type DataTableSortState,
} from 'naive-ui'
import {
  CheckmarkCircleOutline,
  CloseCircleOutline,
  LayersOutline,
  PulseOutline,
  RefreshOutline,
  SearchOutline,
  ServerOutline,
  TimeOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { strategyApi } from '@/api/endpoints'
import {
  SERVER_OFFLINE_AFTER_SEC,
  isServerOnline,
  mockStrategyApi,
  withDelay,
} from '@/api/mock/strategy'
import type { StrategyServer } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { relativeTime, thousands } from '@/utils/format'

/** 策略服务器页：节点清单 + 在线状态 + 承载条件单用户数 */

const message = useMessage()
const accent = '#7c5cf0'

const keyword = ref('')
const onlineFilter = ref<'all' | 'online' | 'offline'>('all')
const page = ref(1)
const pageSize = ref(10)
const sortState = ref<DataTableSortState | null>(null)

const { data, loading, error, isMock, mockReason, load } = useResource(
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
 * 汇总统计基于**当前页**数据计算，并如实标注口径。
 * 真实汇总应由后端单独提供接口，避免前端只统计了一页却当成全量。
 */
const summary = computed(() => {
  const list = rows.value
  const online = list.filter(isServerOnline)
  const usecount = list.reduce((sum, s) => sum + (s.usecount ?? 0), 0)
  const maxUse = list.reduce((max, s) => Math.max(max, s.usecount ?? 0), 0)
  return {
    totalOnPage: list.length,
    onlineCount: online.length,
    offlineCount: list.length - online.length,
    usecount,
    maxUse,
  }
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
    width: 180,
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'mono' }, row.mac),
  },
  {
    title: '条件单用户数',
    key: 'usecount',
    width: 150,
    sorter: true,
    render: (row) => {
      const value = row.usecount ?? 0
      const pct = summary.value.maxUse > 0 ? Math.round((value / summary.value.maxUse) * 100) : 0
      return h('div', { class: 'use-cell' }, [
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
      ])
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
    width: 76,
    render: (row) =>
      h(NSwitch, {
        size: 'small',
        value: row.enable,
        'onUpdate:value': () => {
          message.warning('后端接口尚未实现，启停开关暂不落库')
        },
      }),
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
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <!-- 汇总指标 -->
    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="在线节点"
          :value="summary.onlineCount"
          suffix="个"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          hint="心跳 30 秒内视为在线"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="离线 / 停用"
          :value="summary.offlineCount"
          suffix="个"
          :icon="CloseCircleOutline"
          color="#d6455e"
          hint="需要人工确认是否正常下线"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="承载条件单用户"
          :value="thousands(summary.usecount)"
          suffix="个"
          :icon="LayersOutline"
          color="#7c5cf0"
          hint="usecount 合计，决定条件单分配"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="节点总数"
          :value="total"
          suffix="个"
          :icon="ServerOutline"
          :color="accent"
          hint="本页口径，未含全量分页"
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
        <n-input
          v-model:value="keyword"
          placeholder="搜索 IP 或 MAC"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

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
        :row-key="(row: StrategyServer) => String(row.id) + row.mac"
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
