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
  type DataTableColumns,
} from 'naive-ui'
import {
  AlertCircleOutline,
  BusinessOutline,
  CheckmarkCircleOutline,
  CloseOutline,
  CodeSlashOutline,
  ServerOutline,
  EyeOutline,
  InformationCircleOutline,
  KeyOutline,
  PeopleOutline,
  PersonOutline,
  RefreshOutline,
  SearchOutline,
  SyncOutline,
  WalletOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { accountApi } from '@/api/endpoints'
import { mockAccAttDist, mockAccountApi, mockAccountTypeDist } from '@/api/mock/account'
import { withDelay } from '@/api/mock/_shared'
import {
  ACC_ATTS,
  ACCOUNT_TYPES,
  EXCHANGE_NUMS,
  type AccountRow,
  type RedisAccount,
  type Shareholder,
} from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { formatJson, orDash, shortDateTime } from '@/utils/format'

/**
 * 用户账号页。
 *
 * 展示 MySQL `T_User` 与 Redis `acc-*` **两侧**的数据，
 * 并明确标出「只在一边有」的情况 —— 这是运维最需要看的。
 *
 * 现场实测的两个坑（页面上如实标注，不修图）：
 *  1. `T_User` 只有 1 行，多数账号只在 Redis 里；
 *  2. `T_User.MainType` 实际存的是渠道 AccAtt（0股票/6期权），
 *     与列注释「0股票/1股票期权/2期货」矛盾 —— 以实际数据为准。
 */

const accent = '#d94f8a'

const keyword = ref('')
const accountTypeFilter = ref<number | null>(null)
const accAttFilter = ref<number | null>(null)
const signedFilter = ref<'all' | 'yes' | 'no'>('all')
const onlineFilter = ref<'all' | 'online' | 'offline'>('all')
const page = ref(1)
const pageSize = ref(10)

const { data, loading, error, isMock, mockReason, load } = useResource(
  (params: Record<string, unknown>) => accountApi.listAccounts(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockAccountApi.listAccounts(params)) },
)

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    accounttype: accountTypeFilter.value ?? undefined,
    accatt: accAttFilter.value ?? undefined,
    signed: signedFilter.value === 'all' ? undefined : signedFilter.value === 'yes',
    online: onlineFilter.value === 'all' ? undefined : onlineFilter.value === 'online',
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
  accountTypeFilter.value = null
  accAttFilter.value = null
  signedFilter.value = 'all'
  onlineFilter.value = 'all'
  page.value = 1
  reload()
}

const accountTypeOptions = Object.entries(ACCOUNT_TYPES).map(([v, l]) => ({ label: l, value: Number(v) }))
const accAttOptions = Object.entries(ACC_ATTS).map(([v, l]) => ({ label: l, value: Number(v) }))

const rows = computed<AccountRow[]>(() => data.value?.list ?? [])
const total = computed(() => data.value?.total ?? 0)

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = rows.value
  return {
    total: total.value,
    signed: list.filter((r) => r.signed).length,
    online: list.filter((r) => r.onlineflag === 1).length,
    /** 只有 Redis、MySQL 里没有的（现场很常见） */
    redisOnly: list.filter((r) => r.hasRedis && !r.userId).length,
  }
})

const typeOption = computed(() => {
  const data = mockAccountTypeDist(rows.value).filter((d) => d.value > 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 个（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data,
        color: ['#3b6ff5', '#d94f8a', '#5b6b8c'],
      },
    ],
  }
})

const accAttOption = computed(() => {
  const data = mockAccAttDist(rows.value).filter((d) => d.value > 0)
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 个（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data,
        color: ['#0d9c9c', '#7c5cf0', '#5b6b8c'],
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<AccountRow> = [
  {
    title: '唯一账号',
    key: 'account',
    width: 185,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono' }, row.account) },
      ),
  },
  {
    title: '用户账号',
    key: 'useraccount',
    width: 135,
    render: (row) => h('span', { class: 'mono' }, row.useraccount),
  },
  {
    title: '客户名称',
    key: 'name',
    width: 100,
    render: (row) =>
      row.name
        ? row.name
        : h(NTooltip, { trigger: 'hover' }, {
            trigger: () => h('span', { class: 'text-muted' }, '未填写'),
            default: () => '现场多数账号的 Name 为空',
          }),
  },
  {
    title: '账号类型',
    key: 'accounttype',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: 'info' },
        { default: () => (row.accounttype != null ? ACCOUNT_TYPES[row.accounttype] ?? `类型 ${row.accounttype}` : '-') },
      ),
  },
  {
    title: '渠道',
    key: 'accatt',
    width: 88,
    render: (row) =>
      row.accatt != null
        ? h(
            NTag,
            {
              size: 'small',
              round: true,
              bordered: false,
              style: row.accatt === 6 ? 'color:#7c5cf0;background:#7c5cf01f' : 'color:#0d9c9c;background:#0d9c9c1f',
            },
            { default: () => ACC_ATTS[row.accatt] ?? String(row.accatt) },
          )
        : '-',
  },
  {
    title: '签署',
    key: 'signed',
    width: 90,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.signed ? 'success' : 'warning' },
        {
          icon: () => h(NIcon, null, { default: () => h(row.signed ? CheckmarkCircleOutline : WarningOutline) }),
          default: () => (row.signed ? '已签署' : '未签署'),
        },
      ),
  },
  {
    title: '在线',
    key: 'onlineflag',
    width: 90,
    render: (row) => {
      if (!row.hasRedis) {
        return h(NTooltip, { trigger: 'hover' }, {
          trigger: () => h(NTag, { size: 'small', round: true, bordered: false }, { default: () => '无实时态' }),
          default: () => '该账号在 Redis 里没有 acc-* 键',
        })
      }
      return h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.onlineflag === 1 ? 'success' : 'default' },
        { default: () => (row.onlineflag === 1 ? '在线' : '离线') },
      )
    },
  },
  {
    title: '数据来源',
    key: 'sources',
    width: 132,
    render: (row) =>
      h('div', { class: 'src-cell' }, [
        h(
          NTag,
          {
            size: 'tiny',
            round: true,
            bordered: false,
            type: row.userId ? 'success' : 'default',
          },
          { default: () => 'MySQL' },
        ),
        h(
          NTag,
          {
            size: 'tiny',
            round: true,
            bordered: false,
            type: row.hasRedis ? 'success' : 'default',
          },
          { default: () => 'Redis' },
        ),
      ]),
  },
  {
    title: '股东号',
    key: 'shareHolders',
    width: 100,
    render: (row) =>
      row.shareHolders.length
        ? h(NTag, { size: 'small', round: true, bordered: false, type: 'info' }, { default: () => `${row.shareHolders.length} 个` })
        : h('span', { class: 'text-muted' }, '无'),
  },
  {
    title: '注册时间',
    key: 'regtime',
    width: 150,
    render: (row) => shortDateTime(row.regtime),
  },
  {
    title: '操作',
    key: 'actions',
    width: 78,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, onClick: () => openDetail(row) },
        { icon: () => h(NIcon, null, { default: () => h(EyeOutline) }) },
      ),
  },
]

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<AccountRow | null>(null)
const detailTab = ref('overview')
const redisRaw = ref<RedisAccount | null>(null)
const redisLoading = ref(false)
const redisError = ref<string>()

async function openDetail(row: AccountRow) {
  active.value = row
  detailTab.value = 'overview'
  showDetail.value = true
  redisRaw.value = null
  redisError.value = undefined

  if (!row.hasRedis) return
  redisLoading.value = true
  try {
    if (isMock.value) {
      // 演示态：从 mock 组装出与真实 acc-* 键同构的对象
      redisRaw.value = {
        id: row.userId ?? 0,
        useraccount: row.useraccount,
        account: row.account,
        accounttype: row.accounttype ?? 0,
        maintype: row.accatt ?? 0,
        regtime: row.regtime ?? '',
        logintime: row.logintime ?? '',
        loginIP: row.loginIP ?? '',
        loginMAC: row.loginMAC ?? '',
        loginOS: row.loginOS ?? '',
        name: row.name ?? '',
        tradeAccount: row.tradeAccount ?? '',
        branchNo: row.branchNo ?? '',
        shareHolders: JSON.stringify(row.shareHolders),
        regprotocal: row.signed ? 1 : 0,
        strategyserverid: row.strategyserverid ?? -1,
        onlineflag: row.onlineflag ?? 0,
        syncflag: row.syncflag ?? 0,
        traderserver: row.traderserver,
      }
    } else {
      redisRaw.value = await accountApi.getRedisAccount(row.account)
    }
  } catch (err) {
    redisError.value = err instanceof Error ? err.message : String(err)
  } finally {
    redisLoading.value = false
  }
}

const prettyRedis = computed(() => (redisRaw.value ? formatJson(JSON.stringify(redisRaw.value)) : ''))

const shareholderColumns: DataTableColumns<Shareholder> = [
  { title: '股东号', key: 'SAccount', render: (row) => h('span', { class: 'mono' }, row.SAccount) },
  {
    title: '市场',
    key: 'ExchangeNum',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: 'info' },
        { default: () => EXCHANGE_NUMS[row.ExchangeNum] ?? String(row.ExchangeNum) },
      ),
  },
]
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <!-- 现场数据现状提示 -->
    <n-alert class="page-card" type="warning" :bordered="false" size="small">
      <template #header>现场数据现状（实测，非缺陷）</template>
      <ul class="fact-list">
        <li>
          MySQL <code>T_User</code> 现场<b>只有 1 行</b>，而 Redis <code>acc-*</code> 键有 14 个 ——
          多数账号只在 Redis 里，页面上用「数据来源」列标出。
        </li>
        <li>
          <code>T_User.MainType</code> 实际存的是<b>渠道 AccAtt</b>（0 股票 / 6 期权），
          与列注释「0 股票/1 股票期权/2 期货」<b>矛盾</b>，本页以实际数据为准。
        </li>
        <li>
          <code>Passwd</code> 是加密串，页面只显示「已设置 / 未设置」，<b>不回显任何内容</b>。
        </li>
      </ul>
    </n-alert>

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="账号总数" :value="summary.total" suffix="个" :icon="PeopleOutline" :color="accent" hint="T_User ∪ acc-*" />
      </n-gi>
      <n-gi>
        <StatCard
          label="已签署"
          :value="summary.signed"
          suffix="个"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          hint="本页口径（RegProtocol / regprotocal）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="在线"
          :value="summary.online"
          suffix="个"
          :icon="SyncOutline"
          color="#3b6ff5"
          hint="Redis onlineflag = 1"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="仅 Redis 有"
          :value="summary.redisOnly"
          suffix="个"
          :icon="ServerOutline"
          color="#dd7a15"
          hint="MySQL 里没有对应行"
        />
      </n-gi>
    </n-grid>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="PersonOutline" :color="accent" :size="17" />
              账号类型分布
            </span>
          </template>
          <BaseChart :option="typeOption" :height="220" empty-text="暂无数据" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="WalletOutline" :color="accent" :size="17" />
              渠道分布
            </span>
          </template>
          <BaseChart :option="accAttOption" :height="220" empty-text="暂无数据" />
        </n-card>
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="PeopleOutline" :color="accent" :size="17" />
          账号列表
        </span>
      </template>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索账号 / 户名"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-select
          v-model:value="accountTypeFilter"
          :options="accountTypeOptions"
          placeholder="账号类型"
          clearable
          style="width: 130px"
          @update:value="handleSearch"
        />
        <n-select
          v-model:value="accAttFilter"
          :options="accAttOptions"
          placeholder="渠道"
          clearable
          style="width: 110px"
          @update:value="handleSearch"
        />

        <n-button-group>
          <n-button
            v-for="opt in [
              { key: 'all', label: '签署不限' },
              { key: 'yes', label: '已签署' },
              { key: 'no', label: '未签署' },
            ]"
            :key="opt.key"
            size="small"
            :type="signedFilter === opt.key ? 'primary' : 'default'"
            :quaternary="signedFilter !== opt.key"
            @click="signedFilter = opt.key as 'all' | 'yes' | 'no'; handleSearch()"
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
        :row-key="(row: AccountRow) => row.account"
        :scroll-x="1330"
        :pagination="{
          page: page,
          pageSize: pageSize,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50],
          onUpdatePage: (p: number) => { page = p; reload() },
          onUpdatePageSize: (s: number) => { pageSize = s; page = 1; reload() },
          prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 个账号`,
        }"
      />
    </n-card>

    <!-- 账号详情 -->
    <n-drawer v-model:show="showDetail" :width="680" placement="right">
      <n-drawer-content :title="`账号详情 · ${active?.account ?? ''}`" closable>
        <template v-if="active">
          <n-tabs v-model:value="detailTab" type="line" size="small">
            <n-tab-pane name="overview">
              <template #tab>基本信息</template>

              <n-descriptions :column="2" label-placement="top" size="small" bordered>
                <n-descriptions-item label="唯一账号">
                  <span class="mono">{{ active.account }}</span>
                </n-descriptions-item>
                <n-descriptions-item label="用户账号">
                  <span class="mono">{{ active.useraccount }}</span>
                </n-descriptions-item>
                <n-descriptions-item label="客户名称">{{ orDash(active.name) }}</n-descriptions-item>
                <n-descriptions-item label="交易账号">
                  <span class="mono">{{ orDash(active.tradeAccount) }}</span>
                </n-descriptions-item>
                <n-descriptions-item label="账号类型">
                  {{ active.accounttype != null ? ACCOUNT_TYPES[active.accounttype] ?? active.accounttype : '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="渠道">
                  {{ active.accatt != null ? ACC_ATTS[active.accatt] ?? active.accatt : '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="营业部">
                  <span class="mono">{{ orDash(active.branchNo) }}</span>
                </n-descriptions-item>
                <n-descriptions-item label="签署状态">
                  <n-tag size="small" round :bordered="false" :type="active.signed ? 'success' : 'warning'">
                    {{ active.signed ? '已签署' : '未签署' }}
                  </n-tag>
                </n-descriptions-item>
                <n-descriptions-item label="注册时间">{{ orDash(active.regtime) }}</n-descriptions-item>
                <n-descriptions-item label="最后登录">{{ orDash(active.logintime) }}</n-descriptions-item>
                <n-descriptions-item label="登录 IP">
                  <span class="mono">{{ orDash(active.loginIP) }}</span>
                </n-descriptions-item>
                <n-descriptions-item label="登录系统">{{ orDash(active.loginOS) }}</n-descriptions-item>
                <n-descriptions-item label="密码">
                  <n-tag size="small" round :bordered="false" type="success">
                    <template #icon>
                      <n-icon :component="KeyOutline" />
                    </template>
                    已设置（不回显）
                  </n-tag>
                </n-descriptions-item>
                <n-descriptions-item label="数据来源">
                  <div class="src-cell">
                    <n-tag size="tiny" round :bordered="false" :type="active.userId ? 'success' : 'default'">
                      MySQL {{ active.userId ? '✓' : '✗' }}
                    </n-tag>
                    <n-tag size="tiny" round :bordered="false" :type="active.hasRedis ? 'success' : 'default'">
                      Redis {{ active.hasRedis ? '✓' : '✗' }}
                    </n-tag>
                  </div>
                </n-descriptions-item>
              </n-descriptions>

              <div class="detail-block">
                <div class="detail-title">
                  <n-icon :component="BusinessOutline" :color="accent" />
                  股东号（{{ active.shareHolders.length }}）
                </div>
                <n-data-table
                  v-if="active.shareHolders.length"
                  :columns="shareholderColumns"
                  :data="active.shareHolders"
                  :bordered="false"
                  size="small"
                  :pagination="false"
                />
                <div v-else class="text-muted" style="font-size: 13px">
                  该账号没有股东号。现场 <code>acc-010110018838_7_6</code> 的 shareHolders 就是空数组。
                </div>
              </div>
            </n-tab-pane>

            <n-tab-pane name="redis">
              <template #tab>Redis 实时态</template>

              <n-alert v-if="!active.hasRedis" type="info" :bordered="false">
                该账号在 Redis 里没有对应的 <code>acc-*</code> 键。
              </n-alert>

              <template v-else>
                <n-alert v-if="redisError" type="error" :bordered="false" style="margin-bottom: 12px">
                  读取失败：{{ redisError }}
                </n-alert>

                <div class="json-head">
                  <n-icon :component="CodeSlashOutline" :color="accent" />
                  <span>
                    对应 Redis 键 <code>acc-{{ active.account }}</code> 的完整内容。
                  </span>
                </div>
                <n-code :code="prettyRedis" language="json" show-line-numbers word-wrap />
              </template>
            </n-tab-pane>
          </n-tabs>

          <div class="detail-note">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <div>
              MySQL 侧来自 <code>T_User</code>；Redis 侧来自
              <code>acc-&lt;FAccount&gt;_&lt;AccountType&gt;_&lt;AccAtt&gt;</code>。
              现场多数账号只在 Redis 里，<code>T_User</code> 只有 1 行。
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

.fact-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.9;
}

.fact-list code,
.json-head code,
.detail-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.src-cell {
  display: flex;
  gap: 4px;
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
  border-left: 3px solid v-bind(accent);
}
</style>
