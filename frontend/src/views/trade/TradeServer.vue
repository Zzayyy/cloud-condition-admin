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
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  useMessage,
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
  SearchOutline,
  ServerOutline,
  SwapHorizontalOutline,
  TimeOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { tradeApi } from '@/api/endpoints'
import { mockServerLoad, mockTradeApi, SERVER_OFFLINE_AFTER_SEC, isServerOnline } from '@/api/mock/trade'
import { withDelay } from '@/api/mock/_shared'
import type { RedisAccount, TraderServer } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { orDash, relativeTime, thousands } from '@/utils/format'

/**
 * 委托服务器页：节点清单 + 账号分配关系。
 *
 * 两个 Tab 分别对应两类数据：
 *  - 节点：Redis 键 `traderserver`
 *  - 账号分配：Redis 键 `acc-*` 里的 `traderserver` 字段
 */

const message = useMessage()
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
  isMock: serverIsMock,
  mockReason: serverMockReason,
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

onMounted(reloadServers)

// 10 秒自动刷新（委托服务器心跳 5 秒一次）
let timer: ReturnType<typeof setInterval> | undefined
onUnmounted(() => clearInterval(timer))
timer = setInterval(reloadServers, 10_000)

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
  isMock: accIsMock,
  mockReason: accMockReason,
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
}

function searchAssignments() {
  accPage.value = 1
  reloadAssignments()
}

/** 切到分配 Tab 时首次加载 */
function handleTabChange(value: string) {
  if (value === 'assignments' && !accData.value) reloadAssignments()
}

const serverRows = computed(() => serverData.value?.list ?? [])
const serverTotal = computed(() => serverData.value?.total ?? 0)
const accRows = computed(() => accData.value?.list ?? [])
const accTotal = computed(() => accData.value?.total ?? 0)

/** 页面级：任一数据源处于演示态即提示一次 */
const isMock = computed(() => serverIsMock.value || accIsMock.value)
const mockReason = computed(() => serverMockReason.value ?? accMockReason.value)

/**
 * 已分配账号总数。
 * 走真实接口时用后端返回的总数；演示数据下用 mock 的全量计数，
 * 避免只统计到当前页却当成全量。
 */
const assignedCount = computed(() =>
  serverIsMock.value ? mockTradeApi.assignmentCount() : accTotal.value,
)

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = serverRows.value
  const online = list.filter(isServerOnline)
  return {
    online: online.length,
    offline: list.length - online.length,
    total: serverTotal.value,
  }
})

/** 节点负载：各 IP 承载账号数 */
const loadOption = computed(() => {
  const data = mockServerLoad()
  return {
    grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', name: '账号数', minInterval: 1 },
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

/* ------------------------------ 节点表格 ------------------------------ */

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
    render: (row) =>
      h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h('span', {}, [
              h(NIcon, {
                size: 13,
                style: 'vertical-align:-2px;margin-right:4px;opacity:.6',
                component: TimeOutline,
              }),
              relativeTime(row.lastActiveTimeLong),
            ]),
          default: () => row['last-active-time'],
        },
      ),
  },
  {
    title: '启用',
    key: 'enable',
    width: 80,
    render: (row) =>
      h(NSwitch, {
        size: 'small',
        value: row.enable,
        'onUpdate:value': () => {
          // 后端未接通：如实提示，不做本地假状态变更
          message.warning('后端接口尚未实现，委托服务器的启停开关暂不落库')
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
      if (!s) return h('span', { class: 'text-muted' }, '未分配')
      const online = s.enable && Math.floor(Date.now() / 1000) - s.lastActiveTimeLong <= SERVER_OFFLINE_AFTER_SEC
      return h('div', { class: 'srv-cell' }, [
        h('span', { class: 'mono' }, s.ip),
        h(
          NTag,
          { size: 'tiny', round: true, bordered: false, type: online ? 'success' : 'default' },
          { default: () => `id=${s.id}` },
        ),
      ])
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
  if (serverIsMock.value) {
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
  return Math.max(0, Math.floor(Date.now() / 1000) - active.value.lastActiveTimeLong)
})
</script>

<template>
  <div>
    <QueryErrorAlert v-if="serverError" :message="serverError" :loading="serverLoading" @retry="reloadServers" />
    <MockDataAlert v-if="isMock" :reason="mockReason" />

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

    <!-- 节点分布 -->
    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="GitNetworkOutline" :color="accent" :size="17" />
          各节点承载账号数
        </span>
      </template>
      <template #header-extra>
        <span class="text-muted" style="font-size: 12px">按 acc-* 键的分配关系统计</span>
      </template>
      <BaseChart :option="loadOption" :height="200" empty-text="暂无分配数据" />
    </n-card>

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
          <n-input
            v-model:value="serverKeyword"
            placeholder="搜索 IP 或 MAC"
            clearable
            style="width: 240px"
            @keyup.enter="searchServers"
            @clear="searchServers"
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
          :row-key="(row: TraderServer) => `${row.id}-${row.mac}`"
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
          <n-input
            v-model:value="accKeyword"
            placeholder="搜索账号 / 户名"
            clearable
            style="width: 230px"
            @keyup.enter="searchAssignments"
            @clear="searchAssignments"
          >
            <template #prefix>
              <n-icon :component="SearchOutline" />
            </template>
          </n-input>

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
    <n-drawer v-model:show="showDetail" :width="560" placement="right">
      <n-drawer-content :title="`节点详情 · ${active?.ip ?? ''}`" closable>
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
              <n-tag
                size="small"
                round
                :bordered="false"
                :type="isServerOnline(active) ? 'success' : active.enable ? 'error' : 'default'"
              >
                {{ isServerOnline(active) ? '在线' : active.enable ? '离线' : '停用' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="最后活跃">
              {{ active['last-active-time'] }}
              <span class="text-muted" style="margin-left: 6px">{{ relativeTime(active.lastActiveTimeLong) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="心跳间隔">
              {{ heartbeatAge }} 秒前
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
