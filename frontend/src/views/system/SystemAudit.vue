<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NCode,
  NDataTable,
  NDatePicker,
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
  type DataTableSortState,
} from 'naive-ui'
import {
  AlertCircleOutline,
  BarChartOutline,
  CloseCircleOutline,
  CloseOutline,
  DocumentTextOutline,
  DownloadOutline,
  FunnelOutline,
  KeyOutline,
  LocationOutline,
  LockClosedOutline,
  LogInOutline,
  PeopleOutline,
  PersonOutline,
  PulseOutline,
  RefreshOutline,
  SearchOutline,
  ShieldOutline,
  TimeOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'

import { adminApi } from '@/api/endpoints'
import { mockAdminApi, mockAuditByAction, mockDailyAudit, mockDailyLogin, mockLoginFailByIp } from '@/api/mock/system'
import { withDelay } from '@/api/mock/_shared'
import type {
  AdminLoginLog,
  AuditActionOption,
  AuditDailyRow,
  AuditLevel,
  AuditLog,
  LoginDailyRow,
  NameValueRow,
} from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { ACTION_PERMISSIONS, actionPermissionKey } from '@/config/permissions'
import { useAuthStore } from '@/stores/auth'
import { orDash, shortDateTime, thousands } from '@/utils/format'

/**
 * 系统管理 / 操作审计。
 *
 * 两个视角，用 `n-tabs` 分段切换：
 *  1. 操作审计 —— 管理端审计表 `T_AdminAudit`（README 点名的「高危操作写审计」落地）。
 *  2. 管理端登录日志 —— `T_AdminLoginLog`（**不是**云单客户的 `T_UserLog`）。
 *
 * ⚠️ 三条必须守住的线：
 *  - README 第 5 条：**多个数据源各自走 `useResource`**（审计列表 / 动作字典 / 登录日志 /
 *    4 个图表聚合各一个），绝不在 `onMounted` 里读另一条请求的 `isMock` 去决定这一条怎么取数。
 *  - README 第 4 条：图表**非演示态不得用 mock 充数**。演示态由各图表自己的 `useResource`
 *    的 mock 分支提供；真实态用后端列表接口在本地聚合；接口失败时 data 为 undefined，
 *    这里一律回落到**空数组**，`BaseChart` 显示「暂无数据」。
 *  - 后端 `backend/` 是空的：真实态失败必须如实报错（`QueryErrorAlert` / `message.warning`），
 *    **绝不伪造成功**，也绝不在非演示态把 `src/api/mock/` 里的常量当真实数据渲染。
 */

const accent = '#5b6b8c'

const message = useMessage()
const authStore = useAuthStore()

/* ============================== 公共映射与工具 ============================== */

const MODULE_LABEL: Record<string, string> = {
  dashboard: '总览',
  server: '服务器管理',
  bizdata: '业务数据',
  account: '账户与用户',
  storage: '数据存储',
  monitor: '监控与统计',
  system: '系统管理',
}

const LEVEL_LABEL: Record<AuditLevel, string> = { high: '高危', medium: '中', low: '低' }

/** high = error 红 / medium = warning / low = default */
function levelTagType(level: AuditLevel): 'error' | 'warning' | 'default' {
  if (level === 'high') return 'error'
  if (level === 'medium') return 'warning'
  return 'default'
}

function resultTagType(ok: boolean): 'success' | 'error' {
  return ok ? 'success' : 'error'
}

const pad2 = (n: number) => String(n).padStart(2, '0')

/**
 * 'YYYY-MM-DD HH:mm:ss' —— **必须与 `src/api/mock/_shared.ts` 的 `fmt()` 完全一致**。
 *
 * 演示态的 `from` / `to` 过滤是 mock 里直接做字符串比较的
 * （`(row.createdAt ?? '') < from` / `> to`），只有「定宽、零填充」的同一格式，
 * 字典序才等于时间序；格式一旦不同（例如少了前导零、或换成 ISO 带 T），
 * 比较结果会静默出错 —— 不报错，只是筛出来的行不对。
 *
 * 同理**不把 Date 对象传给后端**：接口参数是字符串。
 */
function toDateTime(ts: number): string {
  const d = new Date(ts)
  return (
    `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ` +
    `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
  )
}

/** 时间范围 → 后端参数 `from` / `to`（字符串，不用 Date 对象） */
function timeParams(range: [number, number] | null): { from?: string; to?: string } {
  if (!range) return {}
  return { from: toDateTime(range[0]), to: toDateTime(range[1]) }
}

/**
 * 图表聚合接口的参数形状：`{ from, to }` 两个字符串。
 *
 * 与列表接口是**同一套** `YYYY-MM-DD HH:mm:ss` 口径、`[from, to]` 闭区间
 * （见 `domain.ts` 的聚合口径注释）；不传时后端默认「最近 14 个自然日」。
 * 后端聚合是必须的 —— 明细接口分页，前端硬凑只能统计当前页，会被误读成全量。
 */
interface RangeParams {
  from: string
  to: string
}

/**
 * 最近 14 个自然日窗口（含今天），图表统一用它。
 *
 * 与 mock 的 `defaultDayRange()` 语义一致；真实态虽然后端有默认值，
 * 这里仍**显式传**区间，免得「页面标题写的近 14 天」和「后端默认口径」日后漂移。
 */
function last14DaysRange(): RangeParams {
  const now = Date.now()
  return {
    from: `${toDateTime(now - 13 * 86400_000).slice(0, 10)} 00:00:00`,
    to: toDateTime(now),
  }
}

/* ============================== 数据源 1：审计列表 ============================== */

const auditPage = ref(1)
const auditPageSize = ref(15)
const auditKeyword = ref('')
const auditLevel = ref<AuditLevel | null>(null)
const auditModule = ref<string | null>(null)
const auditAction = ref<string | null>(null)
const auditResult = ref<string | null>(null)
const auditOperator = ref('')
const auditRange = ref<[number, number] | null>(null)
const auditSort = ref<DataTableSortState | null>(null)

const {
  data: auditData,
  loading: auditLoading,
  error: auditError,
  isMock: auditIsMock,
  usingMock: auditUsingMock,
  load: loadAudit,
} = useResource(
  (params: Record<string, unknown>) => adminApi.listAuditLogs(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockAdminApi.listAuditLogs(params)) },
)

function auditParams(): Record<string, unknown> {
  return {
    page: auditPage.value,
    pageSize: auditPageSize.value,
    keyword: auditKeyword.value.trim() || undefined,
    level: auditLevel.value ?? undefined,
    module: auditModule.value ?? undefined,
    action: auditAction.value ?? undefined,
    result: auditResult.value ?? undefined,
    operator: auditOperator.value.trim() || undefined,
    ...timeParams(auditRange.value),
    sortBy: (auditSort.value?.columnKey as string | undefined) ?? undefined,
    sortOrder: auditSort.value ? (auditSort.value.order === 'descend' ? 'desc' : 'asc') : undefined,
  }
}

function reloadAudit() {
  void loadAudit(auditParams())
}

function handleAuditSearch() {
  auditPage.value = 1
  reloadAudit()
}

function resetAuditFilters() {
  auditKeyword.value = ''
  auditLevel.value = null
  auditModule.value = null
  auditAction.value = null
  auditResult.value = null
  auditOperator.value = ''
  auditRange.value = null
  auditSort.value = null
  auditPage.value = 1
  reloadAudit()
}

function onAuditRangeChange(value: number | [number, number] | null) {
  auditRange.value = Array.isArray(value) ? [value[0], value[1]] : null
  handleAuditSearch()
}

function onAuditSorter(sorter: DataTableSortState | DataTableSortState[] | null) {
  auditSort.value = Array.isArray(sorter) ? (sorter[0] ?? null) : sorter
  handleAuditSearch()
}

const auditRows = computed<AuditLog[]>(() => auditData.value?.list ?? [])
const auditTotal = computed(() => auditData.value?.total ?? 0)

/* ============================== 数据源 2：动作字典 ============================== */

/**
 * 动作字典（下拉用）。
 *
 * 单独一个 `useResource`：它的失败不能靠审计列表的 `isMock` 猜
 * （README 第 5 条：并发请求下别处的 `isMock` 还是 false）。
 */
const {
  data: actionDict,
  error: actionDictError,
  load: loadActionDict,
} = useResource<AuditActionOption[], undefined>(() => adminApi.listAuditActions(), {
  mock: () => withDelay(mockAdminApi.listAuditActions(), 120),
})

const actionOptions = computed(() =>
  (actionDict.value ?? []).map((a) => ({
    label: `${MODULE_LABEL[a.module] ?? a.module} / ${a.label}`,
    value: a.action,
  })),
)

/** 模块下拉：从 actions 里去重 module（不在前端写死模块清单） */
const moduleOptions = computed(() => {
  const set = new Set<string>()
  for (const a of actionDict.value ?? []) set.add(a.module)
  return [...set].map((m) => ({ label: `${MODULE_LABEL[m] ?? m}（${m}）`, value: m }))
})

const levelOptions = (['high', 'medium', 'low'] as AuditLevel[]).map((l) => ({
  label: LEVEL_LABEL[l],
  value: l,
}))

/**
 * 审计结果的筛选项 —— mock 里是 `row.result === q.result`，
 * 取值就是 `result` 字段本身（'success' / 'failure'）。
 */
const resultOptions = [
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failure' },
]

/**
 * 登录结果的筛选项 —— **注意与上面不是同一套取值**。
 *
 * mock 里是 `row.success === (q.success === 'true')`（把布尔序列化成 'true'/'false'），
 * 所以这里必须传 `'true'` / `'false'`。
 * 如果图省事复用审计那套 `'success'`，`('success' === 'true')` 恒为 false，
 * 结果就是**永远只显示失败记录且不报错** —— 又一个静默失效，所以单独列一份。
 */
const loginResultOptions = [
  { label: '成功', value: 'true' },
  { label: '失败', value: 'false' },
]

/** 权限表里的动作键集合 —— 抽屉里用来说明「动作键与权限键同源」是否成立 */
const permissionKeySet = new Set(
  ACTION_PERMISSIONS.map((a) => actionPermissionKey(a.menu, a.action)),
)
const isPermissionKey = (action: string) => permissionKeySet.has(action)
const dangerousOf = (action: string) =>
  ACTION_PERMISSIONS.some((a) => actionPermissionKey(a.menu, a.action) === action && a.dangerous)

/* ========================= 数据源 3/4：审计的两个图表 ========================= */

/**
 * 近 14 天审计量（高危 / 其他）。
 *
 * 真实态调 `adminApi.getAuditDaily({ from, to })`（后端聚合）；
 * 演示态走 `mockDailyAudit()`（与真实接口**同源**，转发 `getAuditDaily`，
 * 所以两边口径不会漂移）。
 *
 * ⚠️ 仍然守着 README 第 4 条：拿不到数据就显示「暂无数据」，
 * 绝不拿 `src/api/mock/` 充数 —— `useResource` 只在演示开关打开（或开发环境
 * 后端不可达而降级）时才会走 mock 分支，真实态失败是 `error` + 空图。
 */
const {
  data: auditDailyData,
  error: auditDailyError,
  usingMock: auditDailyUsingMock,
  load: loadAuditDaily,
} = useResource<AuditDailyRow[], RangeParams>((p) => adminApi.getAuditDaily(p), {
  mock: () => withDelay(mockDailyAudit(), 120),
})

const {
  data: auditByActionData,
  error: auditByActionError,
  usingMock: auditByActionUsingMock,
  load: loadAuditByAction,
} = useResource<NameValueRow[], RangeParams>((p) => adminApi.getAuditByAction(p), {
  mock: () => withDelay(mockAuditByAction(), 120),
})

/** 非演示态拿不到数据时一律空数组 —— 图表不允许用 mock 充数 */
const auditDailyRows = computed<AuditDailyRow[]>(() => auditDailyData.value ?? [])
const auditByActionRows = computed<NameValueRow[]>(() => auditByActionData.value ?? [])

function reloadAuditCharts() {
  const p = last14DaysRange()
  void loadAuditDaily(p)
  void loadAuditByAction(p)
}

const auditDailyOption = computed(() => {
  const rows = auditDailyRows.value
  return {
    grid: { left: 8, right: 16, top: 30, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: { data: ['高危', '其他'], top: 0, textStyle: { fontSize: 11 } },
    xAxis: {
      type: 'category',
      data: rows.map((r) => String(r.date).slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '高危',
        type: 'bar',
        stack: 'audit',
        barMaxWidth: 16,
        data: rows.map((r) => Number(r.high)),
        itemStyle: { color: '#d6455e' },
      },
      {
        name: '其他',
        type: 'bar',
        stack: 'audit',
        barMaxWidth: 16,
        data: rows.map((r) => Number(r.other)),
        itemStyle: { color: accent },
      },
    ],
  }
})

const auditByActionOption = computed(() => {
  const top = [...auditByActionRows.value]
  return {
    grid: { left: 8, right: 26, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: top.map((r) => r.name).reverse(),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 14,
        data: top.map((r) => r.value).reverse(),
        itemStyle: { color: accent, borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

/* ============================== 数据源 5：登录日志 ============================== */

const loginPage = ref(1)
const loginPageSize = ref(15)
const loginUsername = ref('')
const loginIp = ref('')
const loginSuccess = ref<string | null>(null)
const loginRange = ref<[number, number] | null>(null)
const loginSort = ref<DataTableSortState | null>(null)

const {
  data: loginData,
  loading: loginLoading,
  error: loginError,
  load: loadLogin,
} = useResource(
  (params: Record<string, unknown>) => adminApi.listLoginLogs(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockAdminApi.listLoginLogs(params)) },
)

function loginParams(): Record<string, unknown> {
  return {
    page: loginPage.value,
    pageSize: loginPageSize.value,
    username: loginUsername.value.trim() || undefined,
    ip: loginIp.value.trim() || undefined,
    success: loginSuccess.value ?? undefined,
    ...timeParams(loginRange.value),
    sortBy: (loginSort.value?.columnKey as string | undefined) ?? undefined,
    sortOrder: loginSort.value ? (loginSort.value.order === 'descend' ? 'desc' : 'asc') : undefined,
  }
}

function reloadLogin() {
  void loadLogin(loginParams())
}

function handleLoginSearch() {
  loginPage.value = 1
  reloadLogin()
}

function resetLoginFilters() {
  loginUsername.value = ''
  loginIp.value = ''
  loginSuccess.value = null
  loginRange.value = null
  loginSort.value = null
  loginPage.value = 1
  reloadLogin()
}

function onLoginRangeChange(value: number | [number, number] | null) {
  loginRange.value = Array.isArray(value) ? [value[0], value[1]] : null
  handleLoginSearch()
}

function onLoginSorter(sorter: DataTableSortState | DataTableSortState[] | null) {
  loginSort.value = Array.isArray(sorter) ? (sorter[0] ?? null) : sorter
  handleLoginSearch()
}

const loginRows = computed<AdminLoginLog[]>(() => loginData.value?.list ?? [])
const loginTotal = computed(() => loginData.value?.total ?? 0)

const loginSummary = computed(() => {
  const list = loginRows.value
  const fail = list.filter((r) => !r.success)
  return {
    failure: fail.length,
    failRate: list.length ? Math.round((fail.length / list.length) * 1000) / 10 : 0,
    abnormalIps: new Set(fail.map((r) => r.loginIP).filter(Boolean)).size,
  }
})

/* ========================= 数据源 6/7：登录的两个图表 ========================= */

/**
 * 近 14 天登录成功 / 失败，以及失败来源 IP Top。
 *
 * 真实态分别调 `adminApi.getLoginDaily` / `getLoginFailByIp`；
 * 演示态走 `mockDailyLogin()` / `mockLoginFailByIp()`（与真实接口同源）。
 * 同样守着 README 第 4 条：拿不到数据就是空图「暂无数据」，不拿 mock 充数。
 */
const {
  data: loginDailyData,
  error: loginDailyError,
  usingMock: loginDailyUsingMock,
  load: loadLoginDaily,
} = useResource<LoginDailyRow[], RangeParams>((p) => adminApi.getLoginDaily(p), {
  mock: () => withDelay(mockDailyLogin(), 120),
})

const {
  data: failIpData,
  error: failIpError,
  usingMock: failIpUsingMock,
  load: loadFailIp,
} = useResource<NameValueRow[], RangeParams>((p) => adminApi.getLoginFailByIp(p), {
  mock: () => withDelay(mockLoginFailByIp(), 120),
})

/** 非演示态一律空数组（README 第 4 条） */
const loginDailyRows = computed<LoginDailyRow[]>(() => loginDailyData.value ?? [])
const failIpRows = computed<NameValueRow[]>(() => failIpData.value ?? [])

function reloadLoginCharts() {
  const p = last14DaysRange()
  void loadLoginDaily(p)
  void loadFailIp(p)
}

const loginDailyOption = computed(() => {
  const rows = loginDailyRows.value
  return {
    grid: { left: 8, right: 16, top: 30, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: { data: ['成功', '失败'], top: 0, textStyle: { fontSize: 11 } },
    xAxis: {
      type: 'category',
      data: rows.map((r) => String(r.date).slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '成功',
        type: 'bar',
        stack: 'login',
        barMaxWidth: 16,
        data: rows.map((r) => Number(r.success)),
        itemStyle: { color: '#2f9e5f' },
      },
      {
        name: '失败',
        type: 'bar',
        stack: 'login',
        barMaxWidth: 16,
        data: rows.map((r) => Number(r.failure)),
        itemStyle: { color: '#d6455e' },
      },
    ],
  }
})

const failIpOption = computed(() => {
  const top = [...failIpRows.value]
  return {
    grid: { left: 8, right: 26, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: top.map((r) => r.name).reverse(),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 14,
        data: top.map((r) => r.value).reverse(),
        itemStyle: { color: '#d6455e', borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

/* ============================== 两个视角的顶部横幅 ============================== */

const tab = ref<'audit' | 'login'>('audit')

/** 当前页签对应的「加载失败」状态 —— 只影响横幅，不影响取数逻辑 */
const activeError = computed(() => (tab.value === 'audit' ? auditError.value : loginError.value))
const activeLoading = computed(() => (tab.value === 'audit' ? auditLoading.value : loginLoading.value))

function reloadActive() {
  if (tab.value === 'audit') {
    reloadAudit()
    reloadAuditCharts()
  } else {
    reloadLogin()
    reloadLoginCharts()
  }
}

onMounted(() => {
  reloadAudit()
  reloadAuditCharts()
  reloadLogin()
  reloadLoginCharts()
  void loadActionDict(undefined)
})

/* ============================== 审计：汇总与表格 ============================== */

/** 汇总全部按**本页口径**算（与 README「各页汇总指标当前是本页口径」一致） */
const auditSummary = computed(() => {
  const list = auditRows.value
  return {
    high: list.filter((r) => r.level === 'high').length,
    failure: list.filter((r) => r.result === 'failure').length,
    operators: new Set(list.map((r) => r.operator)).size,
  }
})

const auditColumns: DataTableColumns<AuditLog> = [
  {
    title: '时间',
    key: 'createdAt',
    width: 168,
    fixed: 'left',
    sorter: true,
    render: (row) =>
      h(NTooltip, { trigger: 'hover' }, {
        trigger: () => h('span', {}, shortDateTime(row.createdAt)),
        default: () => row.createdAt ?? '-',
      }),
  },
  {
    title: '级别',
    key: 'level',
    width: 88,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: levelTagType(row.level) },
        { default: () => LEVEL_LABEL[row.level] },
      ),
  },
  {
    title: '操作人',
    key: 'operator',
    width: 130,
    render: (row) => h('span', { class: 'mono' }, orDash(row.operator)),
  },
  {
    title: '动作',
    key: 'actionLabel',
    width: 200,
    render: (row) =>
      h(NTooltip, { trigger: 'hover' }, {
        trigger: () => h('span', {}, row.actionLabel),
        default: () => `动作键：${row.action}`,
      }),
  },
  {
    title: '模块',
    key: 'module',
    width: 140,
    render: (row) => MODULE_LABEL[row.module] ?? row.module,
  },
  {
    title: '目标',
    key: 'target',
    width: 240,
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'mono' }, orDash(row.target)),
  },
  {
    title: '结果',
    key: 'result',
    width: 88,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          type: resultTagType(row.result === 'success'),
        },
        { default: () => (row.result === 'success' ? '成功' : '失败') },
      ),
  },
  {
    title: '耗时',
    key: 'costMs',
    width: 96,
    align: 'right',
    render: (row) =>
      h('span', { class: 'num' }, row.costMs === null ? '-' : `${row.costMs} ms`),
  },
  {
    title: 'IP',
    key: 'ip',
    width: 140,
    render: (row) => h('span', { class: 'mono' }, orDash(row.ip)),
  },
  {
    title: '操作',
    key: 'actions',
    width: 88,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, onClick: () => openAuditDetail(row) },
        { default: () => '详情' },
      ),
  },
]

/* ============================== 审计：详情抽屉 ============================== */

const showAuditDetail = ref(false)
const activeAudit = ref<AuditLog | null>(null)

function openAuditDetail(row: AuditLog) {
  activeAudit.value = row
  showAuditDetail.value = true
}

/* ============================== 审计：导出 CSV ============================== */

/** 导出是独立权限点（`action:system-audit:export`），演示态由 auth store 放行 */
const canExport = computed(() => authStore.can(actionPermissionKey('system-audit', 'export')))
const exporting = ref(false)

function csvCell(value: unknown): string {
  const s = value === null || value === undefined ? '' : String(value)
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

const CSV_HEADER = [
  'id',
  '时间',
  '操作人',
  '动作键',
  '动作名',
  '模块',
  '级别',
  '目标',
  '结果',
  '耗时(ms)',
  'IP',
  '说明',
]

/** Blob + URL.createObjectURL 触发下载（演示态 CSV） */
function triggerDownload(href: string, filename: string) {
  const a = document.createElement('a')
  a.href = href
  a.download = filename
  a.rel = 'noopener'
  a.click()
}

/**
 * 演示态 CSV：Blob + URL.createObjectURL。
 *
 * 只在演示态用 —— 真实态的**文件必须由后端出**（明细接口分页，
 * 前端导出「当前页」会被当成全量；而且导出动作要在后端写审计）。
 */
function downloadCsv(rows: AuditLog[]) {
  const lines = [CSV_HEADER.join(',')]
  for (const r of rows) {
    lines.push(
      [
        r.id,
        r.createdAt,
        r.operator,
        r.action,
        r.actionLabel,
        MODULE_LABEL[r.module] ?? r.module,
        LEVEL_LABEL[r.level],
        r.target,
        r.result === 'success' ? '成功' : '失败',
        r.costMs,
        r.ip,
        r.message,
      ]
        .map(csvCell)
        .join(','),
    )
  }

  // Excel 打开 UTF-8 CSV 时没有 BOM 会中文乱码
  const blob = new Blob([`\uFEFF${lines.join('\r\n')}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  triggerDownload(url, `audit-logs-${toDateTime(Date.now()).replace(/[-: ]/g, '')}.csv`)
  window.setTimeout(() => URL.revokeObjectURL(url), 2000)
}

const EXPORT_HINT = '导出即离场，需留痕：审计日志含操作人与 IP，导出动作本身也要写审计。'

/** 导出用的查询条件：与列表同一套筛选，但**不带分页**（导出不是取一页） */
function exportParams(): Record<string, unknown> {
  const { page: _page, pageSize: _pageSize, sortBy, sortOrder, ...rest } = auditParams()
  void _page
  void _pageSize
  // 导出按时间倒序、不带分页参数：条数由后端按筛选条件决定
  return { ...rest, sortBy, sortOrder: sortOrder ?? 'desc' }
}

async function exportAudit() {
  if (!canExport.value) {
    message.warning('没有导出权限：需要 action:system-audit:export')
    return
  }

  exporting.value = true
  try {
    /*
     * 这里必须用 `auditUsingMock`（判定「导出走哪条路」），不能用 `auditIsMock`。
     *
     * `isMock` 的初值是 `false`，只有 `load()` 的 `await` 之后才置位；
     * 而 `onMounted` 不 await，所以在演示态首个请求返回前（mock 延迟 260ms）
     * `auditIsMock.value` 仍是 `false` —— 这个窗口里点导出会**误判为真实态**，
     * 去调尚未实现的 `exportAuditLogs`，然后弹一个失败，用户白点一次。
     * `usingMock` 在数据未到手时退回构建期常量 `USE_MOCK`，没有这个时序问题。
     *
     * 注意：读的是**审计列表这个资源自己的** `usingMock`（导出用的是同一套筛选条件），
     * 不是别处的状态。
     */
    if (!auditUsingMock.value) {
      /*
       * 真实态：交给后端出文件（`POST /system/audit-logs/export`）。
       * 后端同时会写一条 `action:system-audit:export` 的审计 —— 导出即离场，必须留痕。
       * 失败时**不生成任何文件**，也不伪造成功。
       */
      const res = await adminApi.exportAuditLogs(exportParams())

      if (!res.total) {
        message.warning('当前筛选条件下没有可导出的审计记录，后端未生成文件。')
        return
      }

      if (res.url) {
        // 后端给了文件地址 → 直接下载；文件名优先用后端给的
        triggerDownload(res.url, res.filename || 'audit-logs.csv')
        message.success(
          `后端已生成导出文件（共 ${res.total} 条），已开始下载：${res.filename || res.url}。${EXPORT_HINT}` +
            '（本次导出已由后端写审计。）',
        )
      } else {
        // 后端只回条数、没回文件地址：如实说明，不假装下载过
        message.success(
          `后端已生成导出文件，共 ${res.total} 条，但未返回下载地址（url 为空）。${EXPORT_HINT}` +
            '请到后端约定的导出目录取文件。',
        )
      }
      return
    }

    // 演示态（由上面的 auditUsingMock 判定进入）：本资源自己的 mock 数据，不发请求
    const rows = mockAdminApi.listAuditLogs({ ...auditParams(), page: 1, pageSize: 100000 }).list

    if (!rows.length) {
      message.warning('当前筛选条件下没有可导出的审计记录，未生成文件。')
      return
    }

    downloadCsv(rows)
    message.success(
      `已导出 ${rows.length} 条审计记录（CSV，演示数据）。${EXPORT_HINT}演示态不写审计。`,
    )
  } catch (err) {
    // 失败一律 message.error，且此时一定没有产生文件
    const detail = err instanceof Error ? err.message : String(err)
    message.error(`导出失败，未生成任何文件：${detail}。后端导出接口：POST /system/audit-logs/export。`)
  } finally {
    exporting.value = false
  }
}

/* ============================== 登录日志：表格 ============================== */

const loginColumns: DataTableColumns<AdminLoginLog> = [
  {
    title: '时间',
    key: 'loginTime',
    width: 168,
    fixed: 'left',
    sorter: true,
    render: (row) =>
      h(NTooltip, { trigger: 'hover' }, {
        trigger: () => h('span', {}, shortDateTime(row.loginTime)),
        default: () => row.loginTime ?? '-',
      }),
  },
  {
    title: '账号',
    key: 'username',
    width: 150,
    render: (row) => h('span', { class: 'mono' }, orDash(row.username)),
  },
  {
    title: '结果',
    key: 'success',
    width: 88,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: resultTagType(row.success) },
        { default: () => (row.success ? '成功' : '失败') },
      ),
  },
  {
    title: '来源 IP',
    key: 'loginIP',
    width: 150,
    render: (row) => h('span', { class: 'mono' }, orDash(row.loginIP)),
  },
  {
    title: '客户端 / OS',
    key: 'loginOS',
    width: 230,
    ellipsis: { tooltip: true },
    render: (row) => orDash(row.loginOS),
  },
  {
    title: '说明',
    key: 'info',
    minWidth: 160,
    ellipsis: { tooltip: true },
    render: (row) => orDash(row.info),
  },
  {
    title: '操作',
    key: 'actions',
    width: 88,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, onClick: () => openLoginDetail(row) },
        { default: () => '详情' },
      ),
  },
]

const showLoginDetail = ref(false)
const activeLogin = ref<AdminLoginLog | null>(null)

function openLoginDetail(row: AdminLoginLog) {
  activeLogin.value = row
  showLoginDetail.value = true
}
</script>

<template>
  <div>
    <QueryErrorAlert
      v-if="activeError"
      :message="activeError"
      :loading="activeLoading"
      @retry="reloadActive"
    />

    <n-card class="page-card" size="small" :bordered="false">
      <n-tabs v-model:value="tab" type="segment" size="small">
        <n-tab-pane name="audit">
          <template #tab>操作审计（T_AdminAudit）</template>
        </n-tab-pane>
        <n-tab-pane name="login">
          <template #tab>管理端登录日志（T_AdminLoginLog）</template>
        </n-tab-pane>
      </n-tabs>
    </n-card>

    <!-- ============================ Tab 1：操作审计 ============================ -->
    <template v-if="tab === 'audit'">
      <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <n-gi>
          <StatCard
            label="审计总数"
            :value="thousands(auditTotal)"
            suffix="条"
            :icon="DocumentTextOutline"
            :color="accent"
            hint="当前筛选条件下的总数（本页口径）"
          />
        </n-gi>
        <n-gi>
          <StatCard
            label="高危操作"
            :value="auditSummary.high"
            suffix="条"
            :icon="WarningOutline"
            color="#d6455e"
            hint="本页（当前页）口径：level = high"
          />
        </n-gi>
        <n-gi>
          <StatCard
            label="失败操作"
            :value="auditSummary.failure"
            suffix="条"
            :icon="CloseCircleOutline"
            color="#dd7a15"
            hint="本页口径：result = failure"
          />
        </n-gi>
        <n-gi>
          <StatCard
            label="涉及操作人"
            :value="auditSummary.operators"
            suffix="人"
            :icon="PeopleOutline"
            color="#3b6ff5"
            hint="本页去重（真实汇总应由后端单独出接口）"
          />
        </n-gi>
      </n-grid>

      <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
        <n-gi>
          <n-card class="page-card" size="small" :bordered="false">
            <template #header>
              <span class="section-title">
                <n-icon :component="TimeOutline" :color="accent" :size="17" />
                近 14 天审计量（高危 / 其他）
              </span>
            </template>
            <template #header-extra>
              <span class="text-muted" style="font-size: 12px">
                {{ auditDailyUsingMock ? '演示数据' : '真实数据（后端聚合）' }}
              </span>
            </template>
            <!-- 图表请求失败时如实说明，不能只显示空态让人以为「本来就没数据」 -->
            <div v-if="auditDailyError" class="chart-err">
              <n-icon :component="AlertCircleOutline" />
              聚合失败：{{ auditDailyError }}（下图按空数据处理，未用演示数据充数）
            </div>
            <BaseChart :option="auditDailyOption" :height="230" empty-text="暂无数据" />
          </n-card>
        </n-gi>
        <n-gi>
          <n-card class="page-card" size="small" :bordered="false">
            <template #header>
              <span class="section-title">
                <n-icon :component="BarChartOutline" :color="accent" :size="17" />
                动作分布 Top 8
              </span>
            </template>
            <template #header-extra>
              <span class="text-muted" style="font-size: 12px">
                {{ auditByActionUsingMock ? '演示数据' : '真实数据（后端聚合）' }}
              </span>
            </template>
            <div v-if="auditByActionError" class="chart-err">
              <n-icon :component="AlertCircleOutline" />
              聚合失败：{{ auditByActionError }}（下图按空数据处理，未用演示数据充数）
            </div>
            <BaseChart :option="auditByActionOption" :height="230" empty-text="暂无数据" />
          </n-card>
        </n-gi>
      </n-grid>

      <n-card class="page-card" size="small" :bordered="false">
        <template #header>
          <span class="section-title">
            <n-icon :component="ShieldOutline" :color="accent" :size="17" />
            操作审计明细
          </span>
        </template>
        <template #header-extra>
          <span class="text-muted" style="font-size: 12px; margin-right: 10px">
            导出即离场，需留痕
          </span>
          <n-tooltip trigger="hover" :disabled="canExport">
            <template #trigger>
              <n-button
                size="small"
                type="primary"
                ghost
                :disabled="!canExport"
                :loading="exporting"
                @click="exportAudit"
              >
                <template #icon>
                  <n-icon :component="DownloadOutline" />
                </template>
                导出 CSV
              </n-button>
            </template>
            <span>没有导出权限：需要 action:system-audit:export</span>
          </n-tooltip>
        </template>

        <div class="toolbar">
          <n-input
            v-model:value="auditKeyword"
            placeholder="目标 / 操作人 / 动作名"
            clearable
            size="small"
            style="width: 230px"
            @keyup.enter="handleAuditSearch"
            @clear="handleAuditSearch"
          >
            <template #prefix>
              <n-icon :component="SearchOutline" />
            </template>
          </n-input>

          <n-select
            v-model:value="auditLevel"
            :options="levelOptions"
            placeholder="全部级别"
            clearable
            size="small"
            style="width: 118px"
            @update:value="handleAuditSearch"
          />

          <n-select
            v-model:value="auditModule"
            :options="moduleOptions"
            placeholder="全部模块"
            clearable
            size="small"
            style="width: 180px"
            @update:value="handleAuditSearch"
          />

          <n-select
            v-model:value="auditAction"
            :options="actionOptions"
            placeholder="全部动作"
            clearable
            filterable
            size="small"
            style="width: 250px"
            @update:value="handleAuditSearch"
          />

          <n-select
            v-model:value="auditResult"
            :options="resultOptions"
            placeholder="全部结果"
            clearable
            size="small"
            style="width: 118px"
            @update:value="handleAuditSearch"
          />

          <n-input
            v-model:value="auditOperator"
            placeholder="操作人"
            clearable
            size="small"
            style="width: 150px"
            @keyup.enter="handleAuditSearch"
            @clear="handleAuditSearch"
          >
            <template #prefix>
              <n-icon :component="PersonOutline" />
            </template>
          </n-input>

          <n-date-picker
            :value="auditRange"
            type="datetimerange"
            clearable
            size="small"
            style="width: 360px"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            @update:value="onAuditRangeChange"
          />

          <n-button size="small" @click="resetAuditFilters">
            <template #icon>
              <n-icon :component="CloseOutline" />
            </template>
            重置
          </n-button>

          <n-button size="small" :loading="auditLoading" @click="reloadAudit">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>

          <span v-if="auditIsMock" class="text-muted" style="font-size: 12px">
            时间范围按 <code>from</code> / <code>to</code> 传字符串（YYYY-MM-DD HH:mm:ss），
            演示态由 mock 做同格式字符串比较。
          </span>
          <span v-if="actionDictError" class="text-muted" style="font-size: 12px">
            动作字典加载失败：{{ actionDictError }}（模块 / 动作下拉会为空）
          </span>
        </div>

        <n-data-table
          remote
          :columns="auditColumns"
          :data="auditRows"
          :loading="auditLoading"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: AuditLog) => String(row.id)"
          :scroll-x="1560"
          :row-props="(row: AuditLog) => ({ style: 'cursor:pointer', onClick: () => openAuditDetail(row) })"
          :pagination="{
            page: auditPage,
            pageSize: auditPageSize,
            itemCount: auditTotal,
            showSizePicker: true,
            pageSizes: [15, 30, 60],
            onUpdatePage: (p: number) => { auditPage = p; reloadAudit() },
            onUpdatePageSize: (s: number) => { auditPageSize = s; auditPage = 1; reloadAudit() },
            prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
          }"
          @update:sorter="onAuditSorter"
        />
      </n-card>
    </template>

    <!-- ========================= Tab 2：管理端登录日志 ========================= -->
    <template v-else>
      <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <n-gi>
          <StatCard
            label="登录次数"
            :value="thousands(loginTotal)"
            suffix="条"
            :icon="LogInOutline"
            :color="accent"
            hint="当前筛选条件下的总数（本页口径）"
          />
        </n-gi>
        <n-gi>
          <StatCard
            label="失败次数"
            :value="loginSummary.failure"
            suffix="次"
            :icon="LockClosedOutline"
            color="#d6455e"
            hint="本页口径"
          />
        </n-gi>
        <n-gi>
          <StatCard
            label="失败率"
            :value="loginSummary.failRate.toFixed(1)"
            suffix="%"
            :icon="AlertCircleOutline"
            color="#dd7a15"
            hint="本页口径；真实失败率应由后端按时间窗聚合"
          />
        </n-gi>
        <n-gi>
          <StatCard
            label="异常来源 IP"
            :value="loginSummary.abnormalIps"
            suffix="个"
            :icon="LocationOutline"
            color="#3b6ff5"
            hint="本页失败记录里的去重 IP"
          />
        </n-gi>
      </n-grid>

      <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
        <n-gi>
          <n-card class="page-card" size="small" :bordered="false">
            <template #header>
              <span class="section-title">
                <n-icon :component="TimeOutline" :color="accent" :size="17" />
                近 14 天登录成功 / 失败
              </span>
            </template>
            <template #header-extra>
              <span class="text-muted" style="font-size: 12px">
                {{ loginDailyUsingMock ? '演示数据' : '真实数据（后端聚合）' }}
              </span>
            </template>
            <div v-if="loginDailyError" class="chart-err">
              <n-icon :component="AlertCircleOutline" />
              聚合失败：{{ loginDailyError }}（下图按空数据处理，未用演示数据充数）
            </div>
            <BaseChart :option="loginDailyOption" :height="230" empty-text="暂无数据" />
          </n-card>
        </n-gi>
        <n-gi>
          <n-card class="page-card" size="small" :bordered="false">
            <template #header>
              <span class="section-title">
                <n-icon :component="PulseOutline" :color="accent" :size="17" />
                失败来源 IP Top 8
              </span>
            </template>
            <template #header-extra>
              <span class="text-muted" style="font-size: 12px">
                {{ failIpUsingMock ? '演示数据' : '真实数据（后端聚合）' }}
              </span>
            </template>
            <div v-if="failIpError" class="chart-err">
              <n-icon :component="AlertCircleOutline" />
              聚合失败：{{ failIpError }}（下图按空数据处理，未用演示数据充数）
            </div>
            <BaseChart :option="failIpOption" :height="230" empty-text="暂无数据" />
          </n-card>
        </n-gi>
      </n-grid>

      <n-card class="page-card" size="small" :bordered="false">
        <template #header>
          <span class="section-title">
            <n-icon :component="FunnelOutline" :color="accent" :size="17" />
            管理端登录日志
          </span>
        </template>

        <div class="toolbar">
          <n-input
            v-model:value="loginUsername"
            placeholder="账号"
            clearable
            size="small"
            style="width: 170px"
            @keyup.enter="handleLoginSearch"
            @clear="handleLoginSearch"
          >
            <template #prefix>
              <n-icon :component="PersonOutline" />
            </template>
          </n-input>

          <n-input
            v-model:value="loginIp"
            placeholder="来源 IP"
            clearable
            size="small"
            style="width: 170px"
            @keyup.enter="handleLoginSearch"
            @clear="handleLoginSearch"
          >
            <template #prefix>
              <n-icon :component="LocationOutline" />
            </template>
          </n-input>

          <n-select
            v-model:value="loginSuccess"
            :options="loginResultOptions"
            placeholder="全部结果"
            clearable
            size="small"
            style="width: 130px"
            @update:value="handleLoginSearch"
          />

          <n-date-picker
            :value="loginRange"
            type="datetimerange"
            clearable
            size="small"
            style="width: 360px"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            @update:value="onLoginRangeChange"
          />

          <n-button size="small" @click="resetLoginFilters">
            <template #icon>
              <n-icon :component="CloseOutline" />
            </template>
            重置
          </n-button>

          <n-button size="small" :loading="loginLoading" @click="reloadLogin">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>
        </div>

        <n-data-table
          remote
          :columns="loginColumns"
          :data="loginRows"
          :loading="loginLoading"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: AdminLoginLog) => String(row.id)"
          :scroll-x="1140"
          :row-props="(row: AdminLoginLog) => ({ style: 'cursor:pointer', onClick: () => openLoginDetail(row) })"
          :pagination="{
            page: loginPage,
            pageSize: loginPageSize,
            itemCount: loginTotal,
            showSizePicker: true,
            pageSizes: [15, 30, 60],
            onUpdatePage: (p: number) => { loginPage = p; reloadLogin() },
            onUpdatePageSize: (s: number) => { loginPageSize = s; loginPage = 1; reloadLogin() },
            prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 条`,
          }"
          @update:sorter="onLoginSorter"
        />
      </n-card>
    </template>

    <!-- ============================ 审计详情抽屉 ============================ -->
    <n-drawer v-model:show="showAuditDetail" :width="620" placement="right">
      <n-drawer-content :title="`审计详情 #${activeAudit?.id ?? ''}`" closable>
        <template v-if="activeAudit">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="记录 ID">
              <span class="mono">{{ activeAudit.id }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="操作时间">
              {{ orDash(activeAudit.createdAt) }}
            </n-descriptions-item>
            <n-descriptions-item label="操作人">
              <span class="mono">{{ orDash(activeAudit.operator) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="级别">
              <n-tag
                size="small"
                round
                :bordered="false"
                :type="levelTagType(activeAudit.level)"
              >
                {{ LEVEL_LABEL[activeAudit.level] }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="动作名">
              {{ orDash(activeAudit.actionLabel) }}
            </n-descriptions-item>
            <n-descriptions-item label="动作键">
              <span class="mono">{{ activeAudit.action }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="归属模块">
              {{ MODULE_LABEL[activeAudit.module] ?? activeAudit.module }}（{{ activeAudit.module }}）
            </n-descriptions-item>
            <n-descriptions-item label="目标">
              <span class="mono">{{ orDash(activeAudit.target) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="结果">
              <n-tag
                size="small"
                round
                :bordered="false"
                :type="resultTagType(activeAudit.result === 'success')"
              >
                {{ activeAudit.result === 'success' ? '成功' : '失败' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="耗时">
              {{ activeAudit.costMs === null ? '-' : `${activeAudit.costMs} ms` }}
            </n-descriptions-item>
            <n-descriptions-item label="来源 IP">
              <span class="mono">{{ orDash(activeAudit.ip) }}</span>
            </n-descriptions-item>
          </n-descriptions>

          <div class="detail-block">
            <div class="detail-title">
              <n-icon :component="DocumentTextOutline" :color="accent" />
              message 原文
            </div>
            <div class="audit-msg">
              <n-code :code="activeAudit.message ?? '（本条没有说明）'" language="text" word-wrap />
            </div>
            <div class="text-muted" style="font-size: 12px; margin-top: 6px">
              说明按原样展示，不做任何加工 —— 失败原因不美化。
            </div>
          </div>

          <div class="detail-note">
            <n-icon :component="KeyOutline" :color="accent" />
            <div>
              动作键与 <code>src/config/permissions.ts</code> 的权限键<b>同源</b>
              （<code>action:&lt;模块&gt;:&lt;动作&gt;</code>），所以审计筛选与权限树能对上。
              <template v-if="isPermissionKey(activeAudit.action)">
                本动作在权限表里存在<template v-if="dangerousOf(activeAudit.action)">
                ，且标记为<b>高危</b>（需二次确认 + 写审计）</template>。
              </template>
              <template v-else>
                本动作键未出现在权限表（例如 <code>system:login</code> /
                <code>system:connection</code>），属于「需要留痕但没有独立权限点」的登记型审计。
              </template>
              <br />
              记录来自管理端审计表 <code>T_AdminAudit</code>。
              导出审计日志本身也是一个审计动作（<code>action:system-audit:export</code>）。
            </div>
          </div>
        </template>

        <template #footer>
          <n-button size="small" @click="showAuditDetail = false">关闭</n-button>
        </template>
      </n-drawer-content>
    </n-drawer>

    <!-- ========================== 登录日志详情抽屉 ========================== -->
    <n-drawer v-model:show="showLoginDetail" :width="560" placement="right">
      <n-drawer-content :title="`登录记录 #${activeLogin?.id ?? ''}`" closable>
        <template v-if="activeLogin">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item label="记录 ID">
              <span class="mono">{{ activeLogin.id }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="账号">
              <span class="mono">{{ orDash(activeLogin.username) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="登录时间">
              {{ orDash(activeLogin.loginTime) }}
            </n-descriptions-item>
            <n-descriptions-item label="结果">
              <n-tag
                size="small"
                round
                :bordered="false"
                :type="resultTagType(activeLogin.success)"
              >
                {{ activeLogin.success ? '成功' : '失败' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="来源 IP">
              <span class="mono">{{ orDash(activeLogin.loginIP) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="客户端 / OS">
              {{ orDash(activeLogin.loginOS) }}
            </n-descriptions-item>
            <n-descriptions-item label="说明">
              {{ orDash(activeLogin.info) }}
            </n-descriptions-item>
          </n-descriptions>

          <div class="detail-note">
            <n-icon :component="WarningOutline" :color="accent" />
            <div>
              这是<b>管理端</b>登录日志（<code>T_AdminLoginLog</code>）。
              云单客户登录日志是 <code>T_UserLog</code>（19.1 万行，
              <code>LogIP</code> / <code>LogMAC</code> / <code>LogOS</code> 三列全 NULL），
              在「账户与用户 / 登录日志」看，<b>不要在这里找客户账号的登录记录</b>。
            </div>
          </div>
        </template>

        <template #footer>
          <n-button size="small" @click="showLoginDetail = false">关闭</n-button>
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

.detail-note code,
.toolbar code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.fact b {
  color: var(--accent, #5b6b8c);
}

.chart-err {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--danger);
  background: var(--bg-code);
  border-radius: var(--radius-sm);
}

.num {
  font-variant-numeric: tabular-nums;
}

.detail-block {
  margin-top: 20px;
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
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

.detail-note b {
  color: var(--danger);
}

/*
 * 覆盖 naive-ui 的 `.n-code`（它自己的规则是 0-1-0）。
 * README 第 4 条：特异性必须够（这里 3 个类级别选择器），
 * 否则只能写一个类会被静默盖掉。只调布局，颜色仍走 CSS 变量。
 */
.audit-msg :deep(.n-code.n-code--word-wrap) {
  display: block;
  max-height: 220px;
  overflow: auto;
  padding: 10px 12px;
  background: var(--bg-code);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
</style>
