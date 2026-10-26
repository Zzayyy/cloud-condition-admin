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
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  type DataTableColumns,
  type DataTableSortState,
} from 'naive-ui'
import {
  CheckmarkCircleOutline,
  CloseCircleOutline,
  GitNetworkOutline,
  PeopleOutline,
  PersonOutline,
  PulseOutline,
  RefreshOutline,
  ServerOutline,
  SwapHorizontalOutline,
  TimeOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { tradeApi } from '@/api/endpoints'
import { mockServerLoad, mockTradeApi, SERVER_OFFLINE_AFTER_SEC, isServerOnline } from '@/api/mock/trade'
import { withDelay } from '@/api/mock/_shared'
import type { RedisAccount, TraderServer } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { orDash, relativeTime, thousands } from '@/utils/format'
import { buildActivityOption } from '@/utils/activity'

/**
 * 委托服务器页：节点清单 + 账号分配关系。
 *
 * 两个 Tab 分别对应两类数据：
 *  - 节点：Redis 键 `traderserver`（**跨实例全读**，现场三个库各存一份且内容不同）
 *  - 账号分配：Redis 键 `acc-*` 里的 `traderserver` 字段
 *
 * ⚠️ 本页**只读**：不提供启停。现场 `traderserver` 键里的 id 与 mac 都可能重复
 * （136 db1 的 7 条里有 4 个 `id=0`），后端拿不到「这台机器是不是本次运维范围内的
 * 实例」这一信息，按 id/mac 猜着写回很容易改到一台并不属于本次范围的机器上。
 */

const accent = '#0d9c9c'

/** 当前 Tab */
const tab = ref<'servers' | 'assignments'>('servers')

/* ------------------------------ 节点列表 ------------------------------ */

const serverKeyword = ref('')
const serverOnline = ref<'all' | 'online' | 'offline'>('all')
const serverPage = ref(1)
const serverPageSize = ref(10)
const serverSort = ref<DataTableSortState | null>(null)

/**
 * 两个列表各自独立取数。
 *
 * 这里把 ref 解构出来并重命名，**不要**把整个对象留在模板里用
 * `servers.error` —— 那样拿到的是 Ref 对象（永远真值），
 * 会导致错误提示无条件显示（踩过一次）。
 */
const {
  data: serverData,
  loading: serverLoading,
  error: serverError,
  usingMock: serverUsingMock,
  load: loadServers,
} = useResource(
  (params: Record<string, unknown>) => tradeApi.listServers(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockTradeApi.listServers(params)) },
)

function serverParams() {
  return {
    page: serverPage.value,
    pageSize: serverPageSize.value,
    keyword: serverKeyword.value.trim() || undefined,
    online: serverOnline.value === 'all' ? undefined : serverOnline.value === 'online',
    sortBy: serverSort.value?.columnKey as string | undefined,
    sortOrder: serverSort.value ? (serverSort.value.order === 'descend' ? 'desc' : 'asc') : undefined,
  }
}

function reloadServers() {
  loadServers(serverParams())
}

/**
 * 首屏就要把「分配关系」也拉一次 —— 页面顶部的两个指标（已分配账号数）与
 * 「各节点承载账号数」图表都依赖它，而它们与当前是哪个 Tab **无关**。
 *
 * ⚠️ 早先只在 `reloadAssignments()` 里加载（切到分配 Tab 才触发），
 * 导致刚进页面时图表显示「暂无分配数据」、指标显示 0 —— 而数据其实是有的，
 * 看起来就像现场没有账号分配。切一次 Tab 又"好了"，那只是把缺失掩盖掉。
 *
 * `reloadServers` 里一并触发，而不是各写一遍，避免以后漏掉其中一个。
 */
function reloadAll() {
  reloadServers()
  // 「活跃度分布」图要的是**全量节点**，不能只用当前页（那会画成"第 1 页的分布"）
  void loadAllServers()
  void loadAllAssignments()
}

onMounted(reloadAll)

// 10 秒自动刷新（委托服务器心跳 5 秒一次）
let timer: ReturnType<typeof setInterval> | undefined
onUnmounted(() => clearInterval(timer))
timer = setInterval(reloadAll, 10_000)

function searchServers() {
  serverPage.value = 1
  reloadServers()
}

function filterServers(v: 'all' | 'online' | 'offline') {
  serverOnline.value = v
  serverPage.value = 1
  reloadServers()
}

/* --------------------------- 账号分配列表 --------------------------- */

const accKeyword = ref('')
const accOnline = ref<'all' | 'online' | 'offline'>('all')
const accServerId = ref<string | null>(null)
const accPage = ref(1)
const accPageSize = ref(10)

const {
  data: accData,
  loading: accLoading,
  load: loadAssignments,
} = useResource(
  (params: Record<string, unknown>) => tradeApi.listAssignments(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockTradeApi.listAssignments(params)) },
)

function accParams() {
  return {
    page: accPage.value,
    pageSize: accPageSize.value,
    keyword: accKeyword.value.trim() || undefined,
    online: accOnline.value === 'all' ? undefined : accOnline.value === 'online',
    serverId: accServerId.value ?? undefined,
  }
}

function reloadAssignments() {
  loadAssignments(accParams())
  // 图表要的是**全量**分布，不能只用当前页（那会得出「第 1 页的分布」）
  void loadAllAssignments()
}

function searchAssignments() {
  accPage.value = 1
  reloadAssignments()
}

/** 切到分配 Tab 时首次加载 */
function handleTabChange(value: string) {
  if (value === 'assignments' && !accData.value) reloadAssignments()
}

/* --------------------------- 全量分配（图表用） --------------------------- */

/**
 * 全量分配关系，供「各节点承载账号数」图表统计。
 *
 * ⚠️ 后端 pageSize 上限是 200（与前几页一致）。现场 acc-* 只有 14 个，
 * 远不到上限；但**将来若涨过 200，图表会静默少算** —— 所以这里记下
 * 是否被截断，并在图表下方如实提示，而不是让运维看着一根偏矮的柱子
 * 以为「这台机器就承载了这么多」。真要涨上去，应改成后端出一个聚合接口。
 */
const ALL_PAGE_SIZE = 200
const allAssignments = ref<RedisAccount[]>([])
const allAssignmentsTruncated = ref(false)
/** 全量分配是否已取回（用于区分「确实没有分配」与「还没加载」） */
const allAssignmentsLoaded = ref(false)

async function loadAllAssignments() {
  /*
   * 用 `serverUsingMock` 而不是 `serverIsMock`：后者初值是 false，只在 load() 的
   * await **之后**才落定。本函数与 loadServers 同时触发，那一刻它还是 false ——
   * 会误判成真实态、去调后端（演示态下白跑一次真实请求）。见 useResource 的说明。
   */
  if (serverUsingMock.value) {
    allAssignments.value = mockTradeApi.listAssignments({ page: 1, pageSize: ALL_PAGE_SIZE }).list
    allAssignmentsTruncated.value = false
    allAssignmentsLoaded.value = true
    return
  }
  try {
    const res = await tradeApi.listAssignments({ page: 1, pageSize: ALL_PAGE_SIZE })
    allAssignments.value = res.list
    allAssignmentsTruncated.value = res.total > res.list.length
    allAssignmentsLoaded.value = true
  } catch {
    // 图表取数失败不该把整页打红（表格自己有错误提示），静默留空并如实标注
    allAssignments.value = []
    allAssignmentsTruncated.value = false
    allAssignmentsLoaded.value = false
  }
}

/* ------------------------- 全量节点（供活跃度图用） ------------------------- */

const allServers = ref<TraderServer[]>([])
const allServersLoaded = ref(false)

/**
 * 拉全量节点列表。
 *
 * ⚠️ 「活跃度分布」必须基于**全量**，不能拿 `serverData.list`（当前页）——
 * 那画出来是"第 1 页的分布"，节点一多就失真，而且不报错、看不出来。
 * 这与「各节点承载账号数」用 `allAssignments` 而非 `accRows` 是同一个道理。
 */
async function loadAllServers() {
  if (serverUsingMock.value) {
    allServers.value = mockTradeApi.listServers({ page: 1, pageSize: ALL_PAGE_SIZE }).list
    allServersLoaded.value = true
    return
  }
  try {
    const res = await tradeApi.listServers({ page: 1, pageSize: ALL_PAGE_SIZE })
    allServers.value = res.list
    allServersLoaded.value = true
  } catch {
    // 图表取数失败不该把整页打红，表格自己有错误提示
    allServers.value = []
    allServersLoaded.value = false
  }
}

const serverRows = computed(() => serverData.value?.list ?? [])
const serverTotal = computed(() => serverData.value?.total ?? 0)
const accRows = computed(() => accData.value?.list ?? [])
const accTotal = computed(() => accData.value?.total ?? 0)
const accRowsAll = computed(() => allAssignments.value)

/**
 * 已分配账号总数。
 *
 * ⚠️ 用 `allAssignments`（首屏就加载的全量）而**不是** `accTotal` ——
 * 后者属于「账号分配关系」那个 Tab 的分页结果，进页面时根本没加载，
 * 于是这个指标会一直显示 0，直到用户切一次 Tab 才"突然"变成 13。
 * 这是上面 `reloadAll` 要一并加载分配关系的同一个原因。
 *
 * 只数「真的分配了服务器」的：现场 `acc-010100011300_7_6` 的
 * `traderserver.id = -1`（未分配），把它算进去会让「已分配账号」
 * 比节点页上实际能下钻到的账号还多，两个数字对不上。
 */
const assignedCount = computed(() => {
  const list = allAssignments.value
  return list.filter((row) => {
    const id = row.traderserver?.id
    return id !== undefined && id !== null && id >= 0
  }).length
})

/**
 * 后端回传的节点元信息（真实态才有）。
 *
 * 现场这个键跨三个实例、且大多数记录是几个月前的残留，
 * 所以「一共读了几份注册表、有没有实例连不上」必须能看见 ——
 * 否则「只有 2 条」会被读成「现场就 2 台」。
 */
const serverMeta = computed(() => serverData.value?.meta ?? null)

const nodeStatusNote = computed(() => {
  // 用 usingMock：settled 之前也退回构建期常量，避开 isMock 初值的时序窗口
  if (serverUsingMock.value) return null
  const m = serverMeta.value
  if (!m) return null
  const parts: string[] = []
  if (m.redisUnavailable?.length) {
    parts.push(
      `有 ${m.redisUnavailable.length} 个 Redis 实例连不上（${m.redisUnavailable.join('、')}），` +
        '本列表可能不完整。',
    )
  }
  if (m.offlineAfterSec !== SERVER_OFFLINE_AFTER_SEC) {
    parts.push(
      `⚠️ 心跳阈值不一致：后端 ${m.offlineAfterSec} 秒、前端常量 ${SERVER_OFFLINE_AFTER_SEC} 秒，` +
        '两侧的「在线」判定会出现偏差。',
    )
  }
  return parts.length ? parts.join(' ') : null
})

/* ------------------------------ 汇总 ------------------------------ */

/**
 * 汇总指标。
 *
 * ⚠️ 在线/停用数**必须用后端 meta 的全量口径**，不能对当前页 `filter` ——
 * 后端返回的是分页后的行，对当前页统计会得出「第 1 页有 1 台在线，总数 13」，
 * 而实际上现场就那一台是活的，页面读起来像是「还有 12 台状态不明」。
 */
const summary = computed(() => {
  const m = serverMeta.value
  if (m) {
    return {
      online: m.onlineCount,
      // ⚠️ 用 uniqueTotal（按 mac 归并后的台数），不能用 entryTotal：
      //    后者是注册表原始记录数，同一台机器在多个库里各算一次，
      //    算出来的「离线」会把历史残留全当成离线机器。
      offline: Math.max(0, m.uniqueTotal - m.onlineCount),
      total: serverTotal.value,
    }
  }
  // 演示态（或真实态拿不到 meta 时）退回按当前页统计
  const list = serverRows.value
  const online = list.filter(isServerOnline)
  return { online: online.length, offline: list.length - online.length, total: serverTotal.value }
})

/**
 * 各节点承载账号数。
 *
 * ⚠️ 这里**必须按当前数据源分支**：早先的写法是无条件 `mockServerLoad()`，
 * 于是真实态下也在用演示数据画这张图，而且不报错 —— 与 README 里记的
 * 「Redis 管理页类型分布图无条件调 mock」是同一类坑。
 * 真实态下按**全量分配关系**统计（不能只用当前页，那会得出「第 1 页的分布」）。
 *
 * 统计维度是分配给的那个 ip（现场 acc-* 里存的是 id + ip），
 * 故同一个 ip 上有多条 id 不同的记录时会合并成一根柱。
 */
const loadOption = computed(() => {
  const buckets = new Map<string, number>()
  if (serverUsingMock.value) {
    for (const d of mockServerLoad()) buckets.set(d.label, d.value)
  } else {
    for (const row of accRowsAll.value) {
      const ts = row.traderserver
      /*
       * `id < 0` / 无 ip 的是**未分配**（现场 acc-010100011300_7_6 就是 id=-1）。
       * 归到「未分配」一根柱，而不是拼出一个空 ip 的标签 ——
       * 那样图上会出现一根没有名字的柱子，看不出是哪个节点。
       * 口径与上面的「已分配账号」指标一致（那边也不把这些算作已分配）。
       */
      const assigned = ts && ts.id !== undefined && ts.id !== null && ts.id >= 0 && ts.ip
      const label = assigned ? ts.ip!.trim() : '未分配'
      buckets.set(label, (buckets.get(label) ?? 0) + 1)
    }
  }
  const data = [...buckets.entries()].map(([label, value]) => ({ label, value }))
  return {
    // bottom 从 8 加到 30：给 x 轴名字留出空间（containLabel 只管刻度标签，不管 axis name）
    grid: { left: 8, right: 34, top: 8, bottom: 30, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'value',
      name: '账号数',
      // ⚠️ 默认 nameLocation='end' 会把名字放在轴的**最右端**，
      //    横向柱状图那里正好是柱子末尾 + 数值，于是被挤掉/裁掉。
      //    放到轴中间（middle）既不会被裁，也更容易读。
      nameLocation: 'middle',
      nameGap: 24,
      nameTextStyle: { fontSize: 11 },
      minInterval: 1,
    },
    yAxis: {
      type: 'category',
      data: data.map((d) => d.label).reverse(),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        data: data.map((d) => d.value).reverse(),
        barMaxWidth: 20,
        itemStyle: { color: accent, borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

/* ------------------------- 节点活跃度分布 ------------------------- */

/*
 * 分桶与配色在 @/utils/activity 里 —— 策略服务器页用的是**同一份**。
 * 两页各写一份迟早会漂移，而这类不一致在页面上几乎看不出来
 * （只是某一段的柱子高低不同），排查时会被当成数据问题。
 *
 * ⚠️ 传 allServers（全量）而不是 serverRows（当前页）：
 *    用当前页会画成「第 1 页的分布」，节点一多就失真，而且不报错、看不出来。
 */
const activityOption = computed(() => buildActivityOption(allServers.value))

/* ------------------------------ 节点表格 ------------------------------ */

/**
 * 「陈旧」阈值：超过这么久没上报心跳，列表里把该行的时间弱化。
 *
 * ⚠️ 与 `SERVER_OFFLINE_AFTER_SEC`（30 秒，判在线/离线）**不是一回事**：
 * 那个回答"现在通不通"，这个回答"这条记录是不是早就不用了"。
 * 现场大量记录停在几个月前，两者都需要，但混用会得出荒谬结论
 * （比如把一台昨天还在跑的机器标成"陈旧"）。
 */
const STALE_AFTER_SEC = 7 * 24 * 3600

const serverColumns: DataTableColumns<TraderServer> = [
  {
    title: '状态',
    key: 'online',
    width: 88,
    render: (row) => {
      const online = isServerOnline(row)
      return h(
        NTag,
        { size: 'small', round: true, bordered: false, type: online ? 'success' : row.enable ? 'error' : 'default' },
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
    width: 100,
    sorter: true,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () => h('span', { class: 'mono' }, String(row.id)),
          // 现场 traderserver 键里 id 会重复，这里如实说明
          default: () => '现场该键的 id 会重复（多次上线累积），不能当唯一键',
        },
      ),
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
    width: 220,
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'mono' }, row.mac),
  },
  {
    title: '最后活跃',
    key: 'lastActiveTimeLong',
    width: 150,
    sorter: true,
    render: (row) => {
      /*
       * 三档呈现：
       *   · 从未上报 → 「-」+ tooltip 写明原因（不是"0 秒前"，那是编的）
       *   · 超 7 天  → 整格文字降为 muted。现场就是这样：几台停在几个月前，
       *                不弱化的话它们和"昨天刚动过"的看起来一样重，
       *                「状态」列的在线/离线只覆盖 30 秒阈值，管不到这种陈旧度。
       *   · 其余     → 正常色
       */
      const ts = row.lastActiveTimeLong
      const never = !ts
      const stale = !never && Date.now() / 1000 - ts > STALE_AFTER_SEC

      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              'span',
              { style: stale ? 'color:var(--text-muted)' : '' },
              never
                ? ['-']
                : [
                    h(NIcon, {
                      size: 13,
                      style: 'vertical-align:-2px;margin-right:4px;opacity:.6',
                      component: TimeOutline,
                    }),
                    relativeTime(ts),
                  ],
            ),
          default: () =>
            never
              ? '该记录从未上报过心跳时间（字段缺失，不是"0 秒前"）'
              : `${row['last-active-time'] ?? '-'}${stale ? '（已超过 7 天未上报心跳）' : ''}`,
        },
      )
    },
  },
  {
    title: '启用',
    key: 'enable',
    width: 96,
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
          // 只读：本页不提供启停（现场 id/mac 都可能重复，猜着写回容易改错机器）
          default: () =>
            `enable=${row.enable}\n只读展示，本页不提供启停（由现场流程操作）`,
        },
      ),
  },
  {
    title: '来源实例',
    key: 'instance',
    width: 150,
    render: (row) => {
      /*
       * 列表已**按 mac 归并**：同一台机器在多个库里各注册一份时只留活跃最新的那条，
       * 其余份数压成 `+N` 标在这里。不归并的话同一台机器会被列 3~5 遍，
       * 「台数」直接是错的（现场 14 条只有 4 台）。
       * 但副本信息不能丢 —— 悬停能看到「另有几处旧注册、分别是哪个库」。
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
    title: '操作',
    key: 'actions',
    width: 90,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openServerDetail(row) },
        { default: () => '详情' },
      ),
  },
]

/* ------------------------------ 分配表格 ------------------------------ */

const accColumns: DataTableColumns<RedisAccount> = [
  {
    title: '在线',
    key: 'onlineflag',
    width: 82,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.onlineflag === 1 ? 'success' : 'default' },
        { default: () => (row.onlineflag === 1 ? '在线' : '离线') },
      ),
  },
  {
    title: '唯一账号',
    key: 'account',
    width: 185,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 500 } }, row.account),
  },
  {
    title: '用户账号',
    key: 'useraccount',
    width: 150,
    render: (row) => h('span', { class: 'mono' }, row.useraccount),
  },
  { title: '客户名称', key: 'name', width: 110, render: (row) => orDash(row.name) },
  {
    title: '账号类型',
    key: 'accounttype',
    width: 100,
    render: (row) =>
      h(NTag, { size: 'small', round: true, bordered: false, type: 'info' }, {
        default: () => (row.accounttype === 7 ? '客户号' : '资金账号'),
      }),
  },
  {
    title: '分配到的委托服务器',
    key: 'traderserver',
    width: 230,
    render: (row) => {
      const s = row.traderserver
      /*
       * 这几种都按「未分配」显示：
       *  - 没有 traderserver 字段；
       *  - id < 0（现场 acc-010100011300_7_6 就是 id=-1，且 ip 是空串）。
       *    若照原样显示会变成「id=-1」加一个空 IP，看着像是分配到了一台机器。
       */
      if (!s || s.id === undefined || s.id === null || s.id < 0 || !s.ip) {
        return h('span', { class: 'text-muted' }, '未分配')
      }
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h('div', { class: 'srv-cell' }, [
              h('span', { class: 'mono' }, s.ip),
              h(
                NTag,
                { size: 'tiny', round: true, bordered: false, type: 'info' },
                { default: () => `id=${s.id}` },
              ),
            ]),
          /*
           * ⚠️ 这里**不**用 traderserver 里的时间着色成「在线/离线」：
           * 那个 lastActiveTimeLong 是**当时分配的时刻**，不是该节点当前的心跳
           * （现场普遍是几天到两个月前，例如 id=3 停在 2026-09-04）。
           * 拿它判在线会出现「上面的节点表说这台在线、这一列说它离线」的自相矛盾。
           * 节点是否在线以上面的节点表为准。
           */
          default: () =>
            `分配编号：id=${s.id}\n启用：${s.enable ?? '-'}\n` +
            `分配时刻：${s.lastactivetime ?? '-'}\n` +
            '（该时间是分配时刻，不是节点心跳；节点在线状态请看上方节点表）',
        },
      )
    },
  },
  {
    title: '同步标志',
    key: 'syncflag',
    width: 96,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.syncflag ? 'warning' : 'default' },
        { default: () => (row.syncflag ? '待同步' : '已同步') },
      ),
  },
  {
    title: '最后登录',
    key: 'logintime',
    width: 150,
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () => h('span', {}, shortDate(row.logintime)),
          default: () => `登录时间：${row.logintime || '-'}\n登录 IP：${row.loginIP || '-'}\n登录系统：${row.loginOS || '-'}`,
        },
      ),
  },
]

function shortDate(v: string | null | undefined) {
  return v ? v.slice(0, 16) : '-'
}

/* ------------------------------ 节点详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<TraderServer | null>(null)

/**
 * 该节点承载的账号（下钻）。
 * 真实接口：GET /trade/servers/{id}/accounts；演示态下读 mock。
 */
const nodeAccounts = ref<RedisAccount[]>([])

async function loadNodeAccounts(serverId: number) {
  // usingMock 与其它处一致：数据未落定时退回构建期常量，不赌 isMock 的时序
  if (serverUsingMock.value) {
    nodeAccounts.value = mockTradeApi.accountsByServer(serverId)
    return
  }
  try {
    nodeAccounts.value = await tradeApi.listAccountsByServer(serverId)
  } catch {
    nodeAccounts.value = []
  }
}

const nodeAccountColumns: DataTableColumns<RedisAccount> = [
  {
    title: '唯一账号',
    key: 'account',
    render: (row) => h('span', { class: 'mono' }, row.account),
  },
  { title: '客户名称', key: 'name', width: 100, render: (row) => orDash(row.name) },
  {
    title: '在线',
    key: 'onlineflag',
    width: 80,
    render: (row) =>
      h(NTag, { size: 'small', round: true, bordered: false, type: row.onlineflag === 1 ? 'success' : 'default' },
        { default: () => (row.onlineflag === 1 ? '在线' : '离线') }),
  },
]

function openServerDetail(row: TraderServer) {
  active.value = row
  showDetail.value = true
  nodeAccounts.value = []
  void loadNodeAccounts(row.id)
}

const heartbeatAge = computed(() => {
  if (!active.value) return 0
  // 真实态用后端算好的 ageSec（与 online 同一时刻的口径）；演示态没有则本地算
  if (typeof active.value.ageSec === 'number') return Math.max(0, active.value.ageSec)
  return Math.max(0, Math.floor(Date.now() / 1000) - active.value.lastActiveTimeLong)
})

/** 详情抽屉里的在线判定：真实态优先用后端口径，避免与汇总卡打架 */
const isActiveOnline = computed(() => {
  if (!active.value) return false
  if (typeof active.value.online === 'boolean') return active.value.online
  return isServerOnline(active.value)
})
</script>

<template>
  <div>
    <QueryErrorAlert v-if="serverError" :message="serverError" :loading="serverLoading" @retry="reloadServers" />

    <!--
      真实态的现场提示：这个键跨三个实例、且绝大多数记录是几个月前的残留。
      「有实例连不上」与「阈值两边不一致」都必须显式说出来 ——
      前者会让节点数偏少（看少了几台），后者会让两侧的「在线」判定打架。
    -->
    <n-alert
      v-if="nodeStatusNote"
      type="warning"
      :bordered="false"
      size="small"
      style="margin-bottom: 14px"
    >
      {{ nodeStatusNote }}
    </n-alert>

    <!-- 汇总指标 -->
    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="在线节点"
          :value="summary.online"
          suffix="个"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          hint="心跳 30 秒内视为在线"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="离线 / 停用"
          :value="summary.offline"
          suffix="个"
          :icon="CloseCircleOutline"
          color="#d6455e"
          hint="需要人工确认是否正常下线"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="节点总数"
          :value="summary.total"
          suffix="个"
          :icon="ServerOutline"
          :color="accent"
          hint="Redis 键 traderserver"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="已分配账号"
          :value="assignedCount"
          suffix="个"
          :icon="PeopleOutline"
          color="#7c5cf0"
          hint="Redis 键 acc-* 的 traderserver 字段"
        />
      </n-gi>
    </n-grid>

    <!--
      两张分布图并排：一个看「分了多少账号」，一个看「还活着没」。

      ⚠️ 间距写 `margin: 16px 0`（上下都要），不能只写 margin-top：
      卡片间距原本靠 `.page-card { margin-bottom: 16px }`，
      而这一块是 n-grid 不是 .page-card —— 只给上边距的话，
      下面的表格卡片会紧贴上来，两块钱连成一片。
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
              <n-icon :component="GitNetworkOutline" :color="accent" :size="17" />
              各节点承载账号数
            </span>
          </template>
          <template #header-extra>
            <span class="text-muted" style="font-size: 12px">
              按 acc-* 键的分配关系统计（全量）
              <template v-if="allAssignmentsTruncated">；⚠️ 已截断到前 200 条，实际更多</template>
            </span>
          </template>
          <!--
            空态文案必须区分「还没加载」与「确实没有分配」：
            后者要现场去查，前者只是等一下。共用一个「暂无数据」会让人误判。
          -->
          <BaseChart
            :option="loadOption"
            :height="200"
            :empty-text="allAssignmentsLoaded ? '暂无分配数据（acc-* 里没有 traderserver 字段）' : '加载中…'"
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
            两者处置完全不同，只给一个「离线 4 个」说不清。
          -->
          <BaseChart
            :option="activityOption"
            :height="200"
            :empty-text="allServersLoaded ? '暂无节点数据（traderserver 键为空）' : '加载中…'"
          />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 主体：两个 Tab -->
    <n-card class="page-card" size="small" :bordered="false">
      <n-tabs :value="tab" type="segment" size="small" style="margin-bottom: 14px" @update:value="(v: string) => { tab = v as 'servers' | 'assignments'; handleTabChange(v) }">
        <n-tab-pane name="servers">
          <template #tab>委托服务器节点</template>
        </n-tab-pane>
        <n-tab-pane name="assignments">
          <template #tab>账号分配关系</template>
        </n-tab-pane>
      </n-tabs>

      <!-- 节点 -->
      <template v-if="tab === 'servers'">
        <div class="toolbar">
          <SearchInput
            v-model:value="serverKeyword"
            placeholder="搜索 IP 或 MAC"
            style="width: 240px"
            :loading="serverLoading"
            @search="searchServers"
            @clear="searchServers"
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
              :type="serverOnline === opt.key ? 'primary' : 'default'"
              :quaternary="serverOnline !== opt.key"
              @click="filterServers(opt.key as 'all' | 'online' | 'offline')"
            >
              {{ opt.label }}
            </n-button>
          </n-button-group>

          <n-button size="small" :loading="serverLoading" @click="reloadServers">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>

          <span class="text-muted auto-refresh">
            <n-icon :component="PulseOutline" size="12" style="vertical-align: -2px" />
            每 10 秒自动刷新
          </span>
        </div>

        <n-data-table
          remote
          :columns="serverColumns"
          :data="serverRows"
          :loading="serverLoading"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: TraderServer) => `${row.instance ?? '?'}#${row.arrayIndex ?? row.id}-${row.mac}`"
          :scroll-x="1080"
          :pagination="{
            page: serverPage,
            pageSize: serverPageSize,
            itemCount: serverTotal,
            showSizePicker: true,
            pageSizes: [10, 20, 50],
            onUpdatePage: (p: number) => { serverPage = p; reloadServers() },
            onUpdatePageSize: (s: number) => { serverPageSize = s; serverPage = 1; reloadServers() },
            prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 个节点`,
          }"
          @update:sorter="(s: DataTableSortState | DataTableSortState[] | null) => { serverSort = Array.isArray(s) ? s[0] : s; reloadServers() }"
        />
      </template>

      <!-- 账号分配 -->
      <template v-else>
        <div class="toolbar">
          <SearchInput
            v-model:value="accKeyword"
            placeholder="搜索账号 / 户名"
            style="width: 230px"
            :loading="accLoading"
            @search="searchAssignments"
            @clear="searchAssignments"
          />

          <n-select
            v-model:value="accServerId"
            :options="serverRows.map((s) => ({ label: `${s.ip} (id=${s.id})`, value: String(s.id) }))"
            placeholder="全部委托服务器"
            clearable
            style="width: 230px"
            @update:value="searchAssignments"
          />

          <n-button-group>
            <n-button
              v-for="opt in [
                { key: 'all', label: '全部' },
                { key: 'online', label: '在线' },
                { key: 'offline', label: '离线' },
              ]"
              :key="opt.key"
              size="small"
              :type="accOnline === opt.key ? 'primary' : 'default'"
              :quaternary="accOnline !== opt.key"
              @click="accOnline = opt.key as 'all' | 'online' | 'offline'; searchAssignments()"
            >
              {{ opt.label }}
            </n-button>
          </n-button-group>

          <n-button size="small" :loading="accLoading" @click="reloadAssignments">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>
        </div>

        <n-data-table
          remote
          :columns="accColumns"
          :data="accRows"
          :loading="accLoading"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: RedisAccount) => row.account"
          :scroll-x="1330"
          :pagination="{
            page: accPage,
            pageSize: accPageSize,
            itemCount: accTotal,
            showSizePicker: true,
            pageSizes: [10, 20, 50],
            onUpdatePage: (p: number) => { accPage = p; reloadAssignments() },
            onUpdatePageSize: (s: number) => { accPageSize = s; accPage = 1; reloadAssignments() },
            prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 个账号`,
          }"
        />
      </template>
    </n-card>

    <!-- 节点详情 -->
    <AppDialog v-model:show="showDetail" :title="`节点详情 · ${active?.ip ?? ''}`" :width="560" mask-closable>
      <template v-if="active">
        <n-descriptions :column="1" label-placement="left" size="small" bordered>
          <n-descriptions-item label="节点 ID">
            <span class="mono">{{ active.id }}</span>
            <span class="text-muted" style="margin-left: 8px; font-size: 12px">（现场会重复，非唯一键）</span>
          </n-descriptions-item>
          <n-descriptions-item label="IP 地址">
            <span class="mono">{{ active.ip }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="MAC">
            <span class="mono">{{ active.mac }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="状态">
            <!--
              真实态优先用后端给的 online（与汇总卡同一口径，页面自算那份会随时间跳变，
              两者可能显示不一致）；演示态没有该字段时退回本地判定。
            -->
            <n-tag
              size="small"
              round
              :bordered="false"
              :type="isActiveOnline ? 'success' : active.enable ? 'error' : 'default'"
            >
              {{ isActiveOnline ? '在线' : active.enable ? '离线' : '停用' }}
            </n-tag>
          </n-descriptions-item>
          <n-descriptions-item label="最后活跃">
            {{ active['last-active-time'] }}
            <span class="text-muted" style="margin-left: 6px">{{ relativeTime(active.lastActiveTimeLong) }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="心跳间隔">
            {{ heartbeatAge }} 秒前
          </n-descriptions-item>
          <n-descriptions-item label="来源实例">
            <span class="mono">{{ active.instance ?? '-' }}</span>
            <span class="text-muted" style="margin-left: 8px; font-size: 12px">
              （数组下标 {{ active.arrayIndex ?? '-' }}；同一键在多个库上各存一份且内容不同）
            </span>
          </n-descriptions-item>
          <n-descriptions-item label="承载账号数">
            {{ nodeAccounts.length }} 个（按 id 匹配，因 id 会重复仅供参考）
          </n-descriptions-item>
        </n-descriptions>

        <div class="detail-block">
          <div class="detail-title">
            <n-icon :component="PersonOutline" :color="accent" />
            该节点上的账号
          </div>
          <n-data-table
            :columns="nodeAccountColumns"
            :data="nodeAccounts"
            :bordered="false"
            size="small"
            :pagination="false"
            :max-height="240"
          />
        </div>

        <div class="detail-note">
          <n-icon :component="SwapHorizontalOutline" :color="accent" />
          <div>
            数据对应 Redis 键 <code>traderserver</code>；账号的分配关系存在
            <code>acc-&lt;账号&gt;</code> 键的 <code>traderserver</code> 字段里。
            心跳阈值 {{ SERVER_OFFLINE_AFTER_SEC }} 秒（<code>tradeserver_beat</code> 频道）。
            <br />
            <b>本页只读</b>：不提供启停。现场该键里的 <code>id</code> 与 <code>mac</code>
            都可能重复，按它们猜着写回容易改到一台并不属于本次范围的机器。
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

.auto-refresh {
  font-size: 12px;
  margin-left: auto;
}

.srv-cell {
  display: flex;
  align-items: center;
  gap: 6px;
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
