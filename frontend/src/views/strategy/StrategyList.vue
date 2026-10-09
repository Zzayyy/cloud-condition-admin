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
  SearchOutline,
  TimeOutline,
  TrashOutline,
  TrendingUpOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { strategyApi } from '@/api/endpoints'
import {
  mockDailyCreated,
  mockStrategyApi,
  mockTypeDistribution,
  withDelay,
} from '@/api/mock/strategy'
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

const { data, loading, error, isMock, mockReason, load } = useResource(
  (params: Record<string, unknown>) => strategyApi.listConditionals(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockStrategyApi.listConditionals(params)) },
)

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

const summary = computed(() => {
  const list = rows.value
  return {
    running: list.filter((r) => r.Mode === 1).length,
    triggered: list.filter((r) => r.Status === 1).length,
    runCount: list.reduce((sum, r) => sum + (r.RunCount ?? 0), 0),
    paused: list.filter((r) => r.Flag === 1).length,
  }
})

const typeChartOption = computed(() => {
  const dist = mockTypeDistribution()
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
  const daily = mockDailyCreated()
  return {
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: daily.map((d) => d.date.slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: daily.map((d) => d.count),
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
    width: 175,
    render: (row) => h('span', { class: 'mono' }, orDash(row.Account)),
  },
  {
    title: '名称',
    key: 'Name',
    width: 150,
    ellipsis: { tooltip: true },
    render: (row) => orDash(row.Name),
  },
  {
    title: '类型',
    key: 'Type',
    width: 150,
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
    width: 96,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, thousands(row.RunCount ?? 0)),
  },
  {
    title: '加入时间',
    key: 'AddTime',
    width: 150,
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
    width: 168,
    fixed: 'right',
    render: (row) =>
      // 用单个 div 包裹，避免多子节点结构问题
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          { size: 'tiny', quaternary: true, onClick: () => openDetail(row) },
          { icon: () => h(NIcon, null, { default: () => h(EyeOutline) }), default: () => '明细' },
        ),
        h(
          NPopconfirm,
          { onPositiveClick: () => toggleRunning(row), positiveText: '确认', negativeText: '取消' },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: row.Mode === 1 ? 'warning' : 'primary' },
                {
                  icon: () =>
                    h(NIcon, null, { default: () => h(row.Mode === 1 ? PauseOutline : PlayOutline) }),
                  default: () => (row.Mode === 1 ? '停止' : '运行'),
                },
              ),
            default: () => `确认${row.Mode === 1 ? '停止' : '运行'}条件单 ${row.id}？`,
          },
        ),
        h(
          NPopconfirm,
          { onPositiveClick: () => removeConditional(row), positiveText: '删除', negativeText: '取消' },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'error' },
                { icon: () => h(NIcon, null, { default: () => h(TrashOutline) }) },
              ),
            default: () => `确认删除条件单 ${row.id}？此操作会下发 remove 协议。`,
          },
        ),
      ]),
  },
]

function toggleRunning(row: Conditional) {
  // 后端未接通：这里如实提示，不做本地假状态变更，避免误以为已生效
  message.warning(`后端接口尚未实现：启停将下发 set 协议（条件单 ${row.id}）`)
}

function removeConditional(row: Conditional) {
  message.warning(`后端接口尚未实现：删除将下发 remove 协议（条件单 ${row.id}）`)
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
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="运行中"
          :value="summary.running"
          suffix="条"
          :icon="PlayOutline"
          color="#2f9e5f"
          hint="本页口径（Mode=1）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="已触发"
          :value="summary.triggered"
          suffix="条"
          :icon="TrendingUpOutline"
          color="#dd7a15"
          hint="本页口径（Status=1）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="暂停 / 失效"
          :value="summary.paused"
          suffix="条"
          :icon="PauseOutline"
          color="#d6455e"
          hint="本页口径（Flag=1）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="累计运行次数"
          :value="thousands(summary.runCount)"
          suffix="次"
          :icon="RefreshOutline"
          :color="accent"
          hint="本页口径（RunCount 合计）"
        />
      </n-gi>
    </n-grid>

    <!-- 图表 -->
    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
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
          <BaseChart :option="dailyChartOption" :height="230" />
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

      <!-- 数据源切换：对应 T_Conditional / T_Conditional_his / V_Conditional
           注意：naive-ui 2.45.x 的 n-tab-pane 只认 #tab 插槽，不认 tab 属性，
           写成 tab="..." 会渲染成空标签，所以这里必须用插槽。 -->
      <n-tabs v-model:value="source" type="segment" size="small" style="margin-bottom: 14px" @update:value="handleSearch">
        <n-tab-pane name="running">
          <template #tab>运行中（T_Conditional）</template>
        </n-tab-pane>
        <n-tab-pane name="history">
          <template #tab>历史（T_Conditional_his）</template>
        </n-tab-pane>
        <n-tab-pane name="all">
          <template #tab>全部（V_Conditional 视图）</template>
        </n-tab-pane>
      </n-tabs>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索条件单号 / 账号 / 名称"
          clearable
          style="width: 250px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

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
        :scroll-x="1340"
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

    <!-- 明细抽屉 -->
    <n-drawer v-model:show="showDetail" :width="640" placement="right">
      <n-drawer-content :title="`条件单明细 · ${active?.id ?? ''}`" closable>
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
          <div class="drawer-footer">
            <n-button
              size="small"
              :type="active?.Mode === 1 ? 'warning' : 'primary'"
              @click="active && toggleRunning(active)"
            >
              <template #icon>
                <n-icon :component="active?.Mode === 1 ? PauseOutline : PlayOutline" />
              </template>
              {{ active?.Mode === 1 ? '停止运行' : '开始运行' }}
            </n-button>
            <n-button size="small" type="error" ghost @click="active && removeConditional(active)">
              <template #icon>
                <n-icon :component="TrashOutline" />
              </template>
              删除
            </n-button>
            <div class="spacer" />
            <n-button size="small" @click="showDetail = false">关闭</n-button>
          </div>
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

.row-actions {
  display: flex;
  gap: 2px;
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

.drawer-footer {
  display: flex;
  gap: 8px;
  align-items: center;
}

.spacer {
  flex: 1;
}
</style>
