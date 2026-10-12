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
  NDrawer,
  NDrawerContent,
  NGi,
  NGrid,
  NIcon,
  NInput,
  NSelect,
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  CloseOutline,
  CodeSlashOutline,
  EyeOutline,
  InformationCircleOutline,
  LayersOutline,
  PlayOutline,
  RefreshOutline,
  SearchOutline,
  ServerOutline,
  GridOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { storageApi } from '@/api/endpoints'
import { mockStorageApi, MYSQL_TABLES } from '@/api/mock/storage'
import { withDelay } from '@/api/mock/_shared'
import type { MysqlColumn, MysqlTable, MysqlTableSchema } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { thousands } from '@/utils/format'

/**
 * 数据库管理页 —— 表结构浏览 + 只读查询。
 *
 * 这里**刻意不做业务列表**（条件单/委托/成交在各自页面里，
 * 那些页面已经是按业务语义做好的）。本页只回答：
 *  - 库里有哪些表/视图、各自多少行、注释是什么
 *  - 表结构长什么样（字段、类型、注释、索引、DDL）
 *  - 手工跑一条只读 SQL 看看（强制只读 + 结果上限）
 *
 * 两个必须写清楚的口径问题：
 *  1. `information_schema.TABLE_ROWS` **不准**（实测 T_StrategyLog 报 16566、实际 17430），
 *     页面同时显示近似值与真实 COUNT，并标明差异。
 *  2. 现场索引极稀疏（除主键外只有 T_Conditional_his 一个 Name_UNIQUE），
 *     查询必须强制分页上限。
 */

const message = useMessage()
const accent = '#2f9e5f'

const DATABASE = 'qianlong_cloud'

/* ------------------------------ 表清单 ------------------------------ */

const keyword = ref('')
const typeFilter = ref<'all' | 'BASE TABLE' | 'VIEW'>('all')

const {
  data: tableData,
  loading: tableLoading,
  error: tableError,
  isMock,
  load: loadTables,
} = useResource(
  () => storageApi.listTables(DATABASE),
  { mock: () => withDelay(mockStorageApi.listTables(DATABASE), 200) },
)

onMounted(() => loadTables())

const tables = computed<MysqlTable[]>(() => tableData.value ?? [])

const filteredTables = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return tables.value.filter((t) => {
    if (typeFilter.value !== 'all' && t.type !== typeFilter.value) return false
    if (!kw) return true
    return t.name.toLowerCase().includes(kw) || (t.comment ?? '').toLowerCase().includes(kw)
  })
})

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = tables.value
  const base = list.filter((t) => t.type === 'BASE TABLE')
  const views = list.filter((t) => t.type === 'VIEW')
  const rows = base.reduce((s, t) => s + (t.exactRows ?? 0), 0)
  const dataLen = base.reduce((s, t) => s + (t.dataLength ?? 0), 0)
  return { total: list.length, base: base.length, views: views.length, rows, dataLen }
})

/** 各行数 Top 表 */
const rowChartOption = computed(() => {
  const top = [...tables.value]
    .filter((t) => (t.exactRows ?? 0) > 0)
    .sort((a, b) => (b.exactRows ?? 0) - (a.exactRows ?? 0))
    .slice(0, 8)
  return {
    grid: { left: 8, right: 40, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', name: '行数' },
    yAxis: { type: 'category', data: top.map((t) => t.name).reverse(), axisLabel: { fontSize: 11 } },
    series: [
      {
        type: 'bar',
        data: top.map((t) => t.exactRows ?? 0).reverse(),
        barMaxWidth: 18,
        itemStyle: { color: accent, borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

/* ------------------------------ 表表格 ------------------------------ */

const tableColumns: DataTableColumns<MysqlTable> = [
  {
    title: '表 / 视图',
    key: 'name',
    width: 185,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openSchema(row) },
        { default: () => h('span', { class: 'mono', style: { fontWeight: 500 } }, row.name) },
      ),
  },
  {
    title: '类型',
    key: 'type',
    width: 96,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          type: row.type === 'VIEW' ? 'info' : 'default',
        },
        { default: () => (row.type === 'VIEW' ? '视图' : '表') },
      ),
  },
  { title: '引擎', key: 'engine', width: 90, render: (row) => row.engine ?? '—' },
  {
    title: '真实行数',
    key: 'exactRows',
    width: 130,
    align: 'right',
    sorter: (a, b) => (a.exactRows ?? 0) - (b.exactRows ?? 0),
    render: (row) => {
      if (row.exactRows === null || row.exactRows === undefined) {
        return h('span', { class: 'text-muted' }, '—')
      }
      return h(
        'span',
        {
          class: 'num',
          style: row.exactRows > 100000 ? 'font-weight:700' : '',
        },
        thousands(row.exactRows),
      )
    },
  },
  {
    title: '近似行数',
    key: 'approxRows',
    width: 130,
    align: 'right',
    render: (row) => {
      if (row.approxRows === null || row.approxRows === undefined) {
        return h('span', { class: 'text-muted' }, '—')
      }
      const exact = row.exactRows ?? 0
      const diff = exact !== row.approxRows
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              'span',
              {
                class: 'num',
                style: diff ? 'color:var(--warning)' : 'color:var(--text-muted)',
              },
              thousands(row.approxRows),
            ),
          default: () =>
            diff
              ? `information_schema 报 ${thousands(row.approxRows)}，真实 COUNT = ${thousands(exact)}，差 ${thousands(Math.abs(exact - row.approxRows))}`
              : '与真实行数一致',
        },
      )
    },
  },
  {
    title: '数据大小',
    key: 'dataLength',
    width: 110,
    align: 'right',
    render: (row) => {
      if (row.dataLength === null) return h('span', { class: 'text-muted' }, '—')
      const kb = row.dataLength / 1024
      return h('span', { class: 'num' }, kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${kb.toFixed(0)} KB`)
    },
  },
  {
    title: '备注',
    key: 'comment',
    ellipsis: { tooltip: true },
    render: (row) =>
      row.comment
        ? h('span', { style: 'font-size: 12.5px' }, row.comment)
        : h('span', { class: 'text-muted' }, '—'),
  },
  {
    title: '操作',
    key: 'actions',
    width: 130,
    fixed: 'right',
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          { size: 'tiny', quaternary: true, onClick: () => openSchema(row) },
          { default: () => '结构' },
        ),
        h(
          NButton,
          {
            size: 'tiny',
            quaternary: true,
            type: 'primary',
            onClick: () => openData(row),
          },
          { default: () => '数据' },
        ),
      ]),
  },
]

/* ------------------------------ 结构抽屉 ------------------------------ */

const showSchema = ref(false)
const activeTable = ref<MysqlTable | null>(null)
const schema = ref<MysqlTableSchema | null>(null)
const schemaLoading = ref(false)
const schemaError = ref<string>()
const schemaTab = ref('columns')

async function openSchema(row: MysqlTable) {
  activeTable.value = row
  schema.value = null
  schemaError.value = undefined
  schemaTab.value = 'columns'
  showSchema.value = true
  schemaLoading.value = true
  try {
    if (isMock.value) {
      schema.value = mockStorageApi.getTableSchema(DATABASE, row.name)
    } else {
      schema.value = await storageApi.getTableSchema(DATABASE, row.name)
    }
  } catch (err) {
    schemaError.value = err instanceof Error ? err.message : String(err)
  } finally {
    schemaLoading.value = false
  }
}

const columnColumns: DataTableColumns<MysqlColumn> = [
  {
    title: '字段',
    key: 'name',
    width: 160,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 500 } }, row.name),
  },
  {
    title: '类型',
    key: 'columnType',
    width: 150,
    render: (row) => h('span', { class: 'mono' }, row.columnType),
  },
  {
    title: '允许空',
    key: 'nullable',
    width: 82,
    render: (row) =>
      h(
        NTag,
        { size: 'tiny', round: true, bordered: false, type: row.nullable ? 'default' : 'warning' },
        { default: () => (row.nullable ? 'YES' : 'NO') },
      ),
  },
  {
    title: '键',
    key: 'key',
    width: 70,
    render: (row) =>
      row.key === 'PRI'
        ? h(NTag, { size: 'tiny', round: true, bordered: false, type: 'info' }, { default: () => 'PRI' })
        : row.key || h('span', { class: 'text-muted' }, '—'),
  },
  {
    title: '默认值',
    key: 'defaultValue',
    width: 100,
    render: (row) =>
      row.defaultValue === null
        ? h('span', { class: 'text-muted mono' }, 'NULL')
        : h('span', { class: 'mono' }, row.defaultValue),
  },
  {
    title: '注释',
    key: 'comment',
    ellipsis: { tooltip: true },
    render: (row) =>
      row.comment
        ? h('span', { style: 'font-size: 12.5px; white-space: pre-line' }, row.comment)
        : h('span', { class: 'text-muted' }, '—'),
  },
]

const indexColumns: DataTableColumns<MysqlTableSchema['indexes'][number]> = [
  {
    title: '索引名',
    key: 'name',
    width: 190,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 500 } }, row.name),
  },
  {
    title: '唯一',
    key: 'unique',
    width: 88,
    render: (row) =>
      h(
        NTag,
        { size: 'tiny', round: true, bordered: false, type: row.unique ? 'success' : 'default' },
        { default: () => (row.unique ? 'YES' : 'NO') },
      ),
  },
  {
    title: '列',
    key: 'columns',
    render: (row) => h('span', { class: 'mono' }, row.columns.join(', ')),
  },
]

/* ------------------------------ 数据抽屉 ------------------------------ */

const showData = ref(false)
const dataTable = ref<MysqlTable | null>(null)
const dataRows = ref<Array<Record<string, unknown>>>([])
const dataLoading = ref(false)
const dataError = ref<string>()
const dataPage = ref(1)
const dataPageSize = ref(20)
const dataTotal = ref(0)

async function fetchTableData() {
  if (!dataTable.value) return
  dataLoading.value = true
  dataError.value = undefined
  try {
    const params = { page: dataPage.value, pageSize: dataPageSize.value }
    const res = isMock.value
      ? mockStorageApi.queryTable(DATABASE, dataTable.value.name, params)
      : await storageApi.queryTable(DATABASE, dataTable.value.name, params)
    dataRows.value = res.list
    dataTotal.value = res.total
  } catch (err) {
    dataError.value = err instanceof Error ? err.message : String(err)
    dataRows.value = []
  } finally {
    dataLoading.value = false
  }
}

async function openData(row: MysqlTable) {
  dataTable.value = row
  dataPage.value = 1
  showData.value = true
  await fetchTableData()
}

/** 依据返回行动态生成列 —— 表结构未知时也能显示 */
const dataColumns = computed<DataTableColumns<Record<string, unknown>>>(() => {
  const first = dataRows.value[0]
  if (!first) return []
  return Object.keys(first).map((k) => ({
    title: k,
    key: k,
    minWidth: 130,
    ellipsis: { tooltip: true },
    render: (row: Record<string, unknown>) => {
      const v = row[k]
      if (v === null || v === undefined) return h('span', { class: 'text-muted mono' }, 'NULL')
      const s = String(v)
      return h('span', { class: 'mono', style: 'font-size:12.5px' }, s.length > 120 ? s.slice(0, 120) + '…' : s)
    },
  }))
})

/* ------------------------------ 只读查询 ------------------------------ */

const sql = ref(`SELECT * FROM T_Conditional LIMIT 20`)
const sqlResult = ref<Array<Record<string, unknown>>>([])
const sqlRunning = ref(false)
const sqlError = ref<string>()
const sqlRan = ref(false)
/** 演示态下的说明（不能假装真执行了 SQL） */
const sqlNote = ref<string>()

/**
 * 危险语句检测 —— 前端先拦一道，后端**必须**再拦一次。
 * 前端拦截只是提示，不能当安全边界。
 */
const FORBIDDEN = /\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|REPLACE|GRANT|REVOKE|RENAME|LOAD\s+DATA|INTO\s+OUTFILE)\b/i

const sqlForbiddenHit = computed(() => {
  const m = FORBIDDEN.exec(sql.value)
  return m ? m[0].toUpperCase() : null
})

async function runQuery() {
  if (sqlForbiddenHit.value) {
    sqlError.value = `检测到写操作关键字 ${sqlForbiddenHit.value} —— 本页只允许只读查询`
    sqlRan.value = false
    return
  }
  if (!sql.value.trim()) return

  sqlRunning.value = true
  sqlError.value = undefined
  sqlNote.value = undefined
  try {
    if (isMock.value) {
      // 演示态无法真执行 SQL —— 只认出表名后返回该表演示行，并明确说明
      const r = mockStorageApi.runQuery(sql.value)
      sqlResult.value = r.rows
      sqlNote.value = r.note
      sqlRan.value = true
      if (!r.rows.length) message.info('该表当前没有数据')
    } else {
      const res = await storageApi.runReadonlyQuery(DATABASE, sql.value, 200)
      sqlResult.value = res.list
      sqlRan.value = true
      if (!res.list.length) message.info('查询成功，但没有返回数据')
    }
  } catch (err) {
    sqlError.value = err instanceof Error ? err.message : String(err)
    sqlResult.value = []
    sqlRan.value = false
  } finally {
    sqlRunning.value = false
  }
}

const sqlColumns = computed<DataTableColumns<Record<string, unknown>>>(() => {
  const first = sqlResult.value[0]
  if (!first) return []
  return Object.keys(first).map((k) => ({
    title: k,
    key: k,
    minWidth: 130,
    ellipsis: { tooltip: true },
    render: (row: Record<string, unknown>) => {
      const v = row[k]
      if (v === null || v === undefined) return h('span', { class: 'text-muted mono' }, 'NULL')
      const s = String(v)
      return h('span', { class: 'mono', style: 'font-size:12.5px' }, s.length > 120 ? s.slice(0, 120) + '…' : s)
    },
  }))
})

/** 示例 SQL 一键填入 */
const SQL_SAMPLES = [
  { label: '条件单（运行中）', sql: 'SELECT id, Account, Name, Mode, Status FROM T_Conditional LIMIT 20' },
  { label: '条件单视图（含历史）', sql: 'SELECT id, Account, Name, Mode FROM V_Conditional LIMIT 20' },
  { label: '用户账号', sql: 'SELECT id, Account, AccType, MainType, UserAccount, Name FROM T_User LIMIT 20' },
  { label: '登录日志（最近）', sql: 'SELECT id, Account, LogTime, Info FROM T_UserLog ORDER BY id DESC LIMIT 20' },
  { label: '策略日志', sql: 'SELECT id, Strategy, Status, OccurTIme, FromIP FROM T_StrategyLog ORDER BY id DESC LIMIT 20' },
  { label: '表清单', sql: "SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='qianlong_cloud'" },
]

function useSample(s: string) {
  sql.value = s
  sqlError.value = undefined
}
</script>

<template>
  <div>
    <QueryErrorAlert v-if="tableError" :message="tableError" :loading="tableLoading" @retry="() => loadTables()" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="表 / 视图" :value="summary.total" suffix="个" :icon="GridOutline" :color="accent" hint="qianlong_cloud" />
      </n-gi>
      <n-gi>
        <StatCard label="基础表" :value="summary.base" suffix="个" :icon="ServerOutline" color="#3b6ff5" hint="BASE TABLE" />
      </n-gi>
      <n-gi>
        <StatCard label="视图" :value="summary.views" suffix="个" :icon="LayersOutline" color="#7c5cf0" hint="如 V_Conditional" />
      </n-gi>
      <n-gi>
        <StatCard
          label="数据总量"
          :value="(summary.dataLen / 1024 / 1024).toFixed(1)"
          suffix="MB"
          :icon="CodeSlashOutline"
          color="#dd7a15"
          hint="各表 data_length 合计"
        />
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="LayersOutline" :color="accent" :size="17" />
          行数 Top 8
        </span>
      </template>
      <template #header-extra>
        <span class="text-muted" style="font-size: 12px">真实 COUNT，非 information_schema</span>
      </template>
      <BaseChart :option="rowChartOption" :height="240" empty-text="暂无有数据的表" />
    </n-card>

    <!-- 表清单 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="GridOutline" :color="accent" :size="17" />
          表清单
        </span>
      </template>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索表名 / 备注"
          clearable
          style="width: 220px"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-button-group>
          <n-button
            v-for="opt in [
              { key: 'all', label: '全部' },
              { key: 'BASE TABLE', label: '表' },
              { key: 'VIEW', label: '视图' },
            ]"
            :key="opt.key"
            size="small"
            :type="typeFilter === opt.key ? 'primary' : 'default'"
            :quaternary="typeFilter !== opt.key"
            @click="typeFilter = opt.key as 'all' | 'BASE TABLE' | 'VIEW'"
          >
            {{ opt.label }}
          </n-button>
        </n-button-group>

        <n-button size="small" @click="keyword = ''; typeFilter = 'all'">
          <template #icon>
            <n-icon :component="CloseOutline" />
          </template>
          重置
        </n-button>

        <n-button size="small" :loading="tableLoading" @click="loadTables()">
          <template #icon>
            <n-icon :component="RefreshOutline" />
          </template>
          刷新
        </n-button>
      </div>

      <n-data-table
        :columns="tableColumns"
        :data="filteredTables"
        :loading="tableLoading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: MysqlTable) => row.name"
        :scroll-x="1220"
        :pagination="false"
      />
    </n-card>

    <!-- 只读查询 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="PlayOutline" :color="accent" :size="17" />
          只读 SQL 查询
        </span>
      </template>
      <template #header-extra>
        <n-tag size="small" round :bordered="false" type="warning">
          仅允许只读，后端必须二次校验
        </n-tag>
      </template>

      <div class="samples">
        <span class="text-muted" style="font-size: 12.5px; margin-right: 4px">示例：</span>
        <n-button
          v-for="s in SQL_SAMPLES"
          :key="s.label"
          size="tiny"
          quaternary
          @click="useSample(s.sql)"
        >
          {{ s.label }}
        </n-button>
      </div>

      <n-input
        v-model:value="sql"
        type="textarea"
        :rows="4"
        placeholder="SELECT ..."
        class="sql-input"
        @keydown.ctrl.enter="runQuery"
      />

      <div class="sql-actions">
        <n-button type="primary" size="small" :loading="sqlRunning" @click="runQuery">
          <template #icon>
            <n-icon :component="PlayOutline" />
          </template>
          执行（Ctrl+Enter）
        </n-button>
        <span class="text-muted" style="font-size: 12.5px">
          结果集上限 200 行
        </span>
        <n-tag v-if="sqlForbiddenHit" size="small" type="error" round :bordered="false">
          含写操作关键字 {{ sqlForbiddenHit }}
        </n-tag>
      </div>

      <n-alert v-if="sqlError" type="error" :bordered="false" style="margin-top: 12px">
        {{ sqlError }}
      </n-alert>

      <div v-if="sqlRan" class="sql-result">
        <n-alert v-if="sqlNote" type="warning" :bordered="false" size="small" style="margin-bottom: 12px">
          {{ sqlNote }}
        </n-alert>
        <div class="detail-title" style="margin-bottom: 10px">
          <n-icon :component="GridOutline" :color="accent" />
          返回 {{ sqlResult.length }} 行
        </div>
        <n-data-table
          :columns="sqlColumns"
          :data="sqlResult"
          :bordered="false"
          size="small"
          :pagination="false"
          :max-height="360"
          :scroll-x="900"
        />
      </div>
    </n-card>

    <!-- 表结构抽屉 -->
    <n-drawer v-model:show="showSchema" :width="780" placement="right">
      <n-drawer-content :title="`表结构 · ${activeTable?.name ?? ''}`" closable>
        <template v-if="activeTable">
          <n-alert v-if="schemaError" type="error" :bordered="false" style="margin-bottom: 12px">
            读取失败：{{ schemaError }}
          </n-alert>

          <n-descriptions :column="3" label-placement="top" size="small" bordered>
            <n-descriptions-item label="表名">
              <span class="mono">{{ activeTable.name }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="类型">
              {{ activeTable.type === 'VIEW' ? '视图' : '基础表' }}
            </n-descriptions-item>
            <n-descriptions-item label="引擎">{{ activeTable.engine ?? '—' }}</n-descriptions-item>
            <n-descriptions-item label="真实行数">
              <span class="num">{{ activeTable.exactRows !== null && activeTable.exactRows !== undefined ? thousands(activeTable.exactRows) : '—' }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="近似行数">
              {{ activeTable.approxRows !== null && activeTable.approxRows !== undefined ? thousands(activeTable.approxRows) : '—' }}
            </n-descriptions-item>
            <n-descriptions-item label="数据大小">
              {{ activeTable.dataLength ? (activeTable.dataLength / 1024).toFixed(0) + ' KB' : '—' }}
            </n-descriptions-item>
          </n-descriptions>

          <n-tabs v-model:value="schemaTab" type="line" size="small" style="margin-top: 16px">
            <n-tab-pane name="columns">
              <template #tab>字段（{{ schema?.columns.length ?? 0 }}）</template>
              <n-data-table
                :columns="columnColumns"
                :data="schema?.columns ?? []"
                :loading="schemaLoading"
                :bordered="false"
                size="small"
                :pagination="false"
                :max-height="420"
                :scroll-x="820"
              />
            </n-tab-pane>

            <n-tab-pane name="indexes">
              <template #tab>索引（{{ schema?.indexes.length ?? 0 }}）</template>
              <n-data-table
                :columns="indexColumns"
                :data="schema?.indexes ?? []"
                :bordered="false"
                size="small"
                :pagination="false"
              />
              <div v-if="schema && schema.indexes.length <= 1" class="detail-note" style="margin-top: 14px">
                <n-icon :component="WarningOutline" color="#dd7a15" />
                <div>
                  该表<b>除主键外没有其它索引</b>。现场多数表都是这样，
                  查询时要注意全表扫描的代价，务必带 LIMIT。
                </div>
              </div>
            </n-tab-pane>

            <n-tab-pane name="ddl">
              <template #tab>DDL</template>
              <n-code :code="schema?.ddl ?? ''" language="sql" show-line-numbers word-wrap />
            </n-tab-pane>
          </n-tabs>
        </template>

        <template #footer>
          <n-button size="small" @click="showSchema = false">关闭</n-button>
        </template>
      </n-drawer-content>
    </n-drawer>

    <!-- 表数据抽屉 -->
    <n-drawer v-model:show="showData" :width="900" placement="right">
      <n-drawer-content :title="`表数据 · ${dataTable?.name ?? ''}`" closable>
        <n-alert v-if="dataError" type="error" :bordered="false" style="margin-bottom: 12px">
          读取失败：{{ dataError }}
        </n-alert>

        <div class="json-head">
          <n-icon :component="InformationCircleOutline" :color="accent" />
          <span>
            分页读取 <code>{{ DATABASE }}.{{ dataTable?.name }}</code>，共
            <b>{{ thousands(dataTotal) }}</b> 行。
            <template v-if="!dataRows.length && !dataLoading">
              该表当前没有数据（现场多数业务表就是 0 行）。
            </template>
          </span>
        </div>

        <n-data-table
          :columns="dataColumns"
          :data="dataRows"
          :loading="dataLoading"
          :bordered="false"
          size="small"
          :scroll-x="1000"
          :max-height="520"
          :pagination="{
            page: dataPage,
            pageSize: dataPageSize,
            itemCount: dataTotal,
            pageSizes: [20, 50, 100],
            showSizePicker: true,
            onUpdatePage: (p: number) => { dataPage = p; fetchTableData() },
            onUpdatePageSize: (s: number) => { dataPageSize = s; dataPage = 1; fetchTableData() },
            prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 行`,
          }"
        />
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

.json-head code,
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

/* SQL 区 */
.samples {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  align-items: center;
  margin-bottom: 10px;
}

.sql-input {
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.sql-input :deep(textarea) {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}

.sql-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 10px;
}

.sql-result {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--border);
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
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
  border-left: 3px solid #dd7a15;
}
</style>
