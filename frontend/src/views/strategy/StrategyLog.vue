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
  NInput,
  NSelect,
  NTag,
  NTimeline,
  NTimelineItem,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  CheckmarkCircleOutline,
  CloseOutline,
  DocumentTextOutline,
  InformationCircleOutline,
  LocationOutline,
  PersonOutline,
  RefreshOutline,
  SearchOutline,
  TimeOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { strategyApi } from '@/api/endpoints'
import { mockStrategyApi, withDelay } from '@/api/mock/strategy'
import type { StrategyLog } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { orDash, shortDateTime, thousands } from '@/utils/format'

/** 策略运行日志页：检索 + 生命周期时间线 + 触发设备溯源 */

const accent = '#7c5cf0'

const keyword = ref('')
const statusFilter = ref<string | null>(null)
const fromIp = ref('')
const page = ref(1)
const pageSize = ref(15)

const { data, loading, error, isMock, mockReason, load } = useResource(
  (params: Record<string, unknown>) => strategyApi.listLogs(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockStrategyApi.listLogs(params)) },
)

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    status: statusFilter.value ?? undefined,
    fromIp: fromIp.value.trim() || undefined,
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
  statusFilter.value = null
  fromIp.value = ''
  page.value = 1
  reload()
}

const statusOptions = mockStrategyApi.logStatusOptions.map((s) => ({ label: s, value: s }))

const rows = computed(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)

/* ------------------------------ 汇总 ------------------------------ */

/** 状态 → 语义色 */
function statusType(status: string | null): 'success' | 'error' | 'warning' | 'info' | 'default' {
  if (status === '触发成功' || status === '已恢复') return 'success'
  if (status === '触发失败' || status === '校验失败' || status === '参数错误') return 'error'
  if (status === '已暂停') return 'warning'
  if (status === '已删除') return 'info'
  return 'default'
}

function statusIcon(status: string | null) {
  if (status === '触发成功' || status === '已恢复') return CheckmarkCircleOutline
  if (status === '已暂停') return WarningOutline
  if (status === '已删除') return InformationCircleOutline
  return AlertCircleOutline
}

const summary = computed(() => {
  const list = rows.value
  const ok = list.filter((r) => statusType(r.Status) === 'success').length
  const fail = list.filter((r) => statusType(r.Status) === 'error').length
  return {
    ok,
    fail,
    rate: list.length ? Math.round((ok / list.length) * 100) : 0,
    sources: new Set(list.map((r) => r.FromAccount).filter(Boolean)).size,
  }
})

/** 状态分布柱状图（本页口径） */
const statusChartOption = computed(() => {
  const counts = new Map<string, number>()
  for (const r of rows.value) {
    const key = r.Status ?? '未知'
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  const entries = [...counts.entries()].sort((a, b) => b[1] - a[1])
  const colorOf: Record<string, string> = {
    触发成功: '#2f9e5f',
    已恢复: '#2f9e5f',
    触发失败: '#d6455e',
    校验失败: '#d6455e',
    参数错误: '#d6455e',
    已暂停: '#dd7a15',
    已删除: '#5b6b8c',
  }
  return {
    grid: { left: 8, right: 24, top: 12, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: entries.map((e) => e[0]).reverse(), axisLabel: { fontSize: 12 } },
    series: [
      {
        type: 'bar',
        data: entries
          .map((e) => ({ value: e[1], itemStyle: { color: colorOf[e[0]] ?? accent, borderRadius: [0, 4, 4, 0] } }))
          .reverse(),
        barMaxWidth: 20,
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<StrategyLog> = [
  {
    title: '时间',
    key: 'OccurTIme',
    width: 150,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () => h('span', {}, shortDateTime(row.OccurTIme)),
          default: () => row.OccurTIme ?? '-',
        },
      ),
  },
  {
    title: '状态',
    key: 'Status',
    width: 116,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: statusType(row.Status) },
        {
          icon: () => h(NIcon, null, { default: () => h(statusIcon(row.Status)) }),
          default: () => orDash(row.Status),
        },
      ),
  },
  {
    title: '条件单号',
    key: 'Strategy',
    width: 150,
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono' }, orDash(row.Strategy)) },
      ),
  },
  {
    title: '日志内容',
    key: 'Info',
    ellipsis: { tooltip: true },
    minWidth: 320,
    render: (row) => orDash(row.Info),
  },
  {
    title: '来源 IP',
    key: 'FromIP',
    width: 140,
    render: (row) => (row.FromIP ? h('span', { class: 'mono' }, row.FromIP) : h('span', { class: 'text-muted' }, '-')),
  },
  {
    title: '来源账号',
    key: 'FromAccount',
    width: 130,
    render: (row) => h('span', { class: 'mono' }, orDash(row.FromAccount)),
  },
]

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<StrategyLog | null>(null)

function openDetail(row: StrategyLog) {
  active.value = row
  showDetail.value = true
}

/** 该条件单在**当前页**里的相关记录，按时间正序展示成时间线 */
const relatedTimeline = computed(() => {
  if (!active.value) return []
  return rows.value
    .filter((r) => r.Strategy === active.value?.Strategy)
    .slice()
    .sort((a, b) => String(a.OccurTIme).localeCompare(String(b.OccurTIme)))
})
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="成功"
          :value="summary.ok"
          suffix="条"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          hint="本页口径"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="失败 / 异常"
          :value="summary.fail"
          suffix="条"
          :icon="AlertCircleOutline"
          color="#d6455e"
          hint="需要重点关注"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="成功率"
          :value="summary.rate"
          suffix="%"
          :icon="DocumentTextOutline"
          :color="accent"
          hint="本页口径，非全量"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="涉及账号"
          :value="summary.sources"
          suffix="个"
          :icon="PersonOutline"
          color="#0d9c9c"
          hint="本页去重统计"
        />
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="DocumentTextOutline" :color="accent" :size="17" />
          状态分布
        </span>
      </template>
      <template #header-extra>
        <span class="text-muted" style="font-size: 12px">按当前页 {{ rows.length }} 条统计</span>
      </template>
      <BaseChart :option="statusChartOption" :height="Math.max(160, summary.ok + summary.fail > 0 ? 200 : 160)" />
    </n-card>

    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="TimeOutline" :color="accent" :size="17" />
          运行日志
        </span>
      </template>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索日志内容 / 条件单号 / 账号"
          clearable
          style="width: 260px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          placeholder="全部状态"
          clearable
          style="width: 150px"
          @update:value="handleSearch"
        />

        <n-input
          v-model:value="fromIp"
          placeholder="来源 IP"
          clearable
          style="width: 150px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="LocationOutline" />
          </template>
        </n-input>

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
        :row-key="(row: StrategyLog) => String(row.id)"
        :scroll-x="1100"
        :pagination="{
          page: page,
          pageSize: pageSize,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [15, 30, 60],
          onUpdatePage: (p: number) => { page = p; reload() },
          onUpdatePageSize: (s: number) => { pageSize = s; page = 1; reload() },
          prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
        }"
      />
    </n-card>

    <!-- 日志详情 -->
    <n-drawer v-model:show="showDetail" :width="560" placement="right">
      <n-drawer-content :title="`日志详情 #${active?.id ?? ''}`" closable>
        <template v-if="active">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="发生时间">{{ orDash(active.OccurTIme) }}</n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag size="small" round :bordered="false" :type="statusType(active.Status)">
                {{ orDash(active.Status) }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="条件单号">
              <span class="mono">{{ orDash(active.Strategy) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="来源账号">
              <span class="mono">{{ orDash(active.FromAccount) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="来源 IP">
              <span class="mono">{{ orDash(active.FromIP) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="来源 MAC">
              <span class="mono">{{ orDash(active.FromMac) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="日志内容">{{ orDash(active.Info) }}</n-descriptions-item>
          </n-descriptions>

          <div v-if="relatedTimeline.length > 1" class="timeline-block">
            <div class="timeline-title">
              <n-icon :component="TimeOutline" :color="accent" />
              该条件单的轨迹（当前页 {{ relatedTimeline.length }} 条）
            </div>
            <n-timeline>
              <n-timeline-item
                v-for="item in relatedTimeline"
                :key="item.id"
                :type="statusType(item.Status)"
                :title="item.Status ?? ''"
                :content="`${shortDateTime(item.OccurTIme)} · ${item.Info ?? ''}`"
              />
            </n-timeline>
          </div>

          <div class="detail-note">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <div>
              数据来自 MySQL 表 <code>T_StrategyLog</code>；
              条件单号对应 <code>Strategy</code> 字段，可据此回到「条件单策略」页查看完整明细。
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

.timeline-block {
  margin-top: 20px;
}

.timeline-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
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
