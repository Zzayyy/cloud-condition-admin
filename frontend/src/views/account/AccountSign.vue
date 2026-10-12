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
  CheckmarkCircleOutline,
  CloseOutline,
  CodeSlashOutline,
  CopyOutline,
  CreateOutline,
  InformationCircleOutline,
  KeyOutline,
  LockClosedOutline,
  PeopleOutline,
  PersonOutline,
  RefreshOutline,
  SearchOutline,
  SwapHorizontalOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import BaseChart from '@/components/BaseChart.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { accountApi } from '@/api/endpoints'
import { mockAccountApi } from '@/api/mock/account'
import { withDelay } from '@/api/mock/_shared'
import {
  ACC_ATTS,
  ACCOUNT_TYPES,
  EXCHANGE_NUMS,
  type AccountRow,
  type Shareholder,
} from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { orDash, shortDateTime } from '@/utils/format'

/**
 * 签署与股东号页。
 *
 * 关注点：
 *  - 协议签署状态（acc_sign / acc_update / logout）
 *  - 股东号与市场（A442523077 上海 / 0199908393 深圳）
 *  - 按市场维度统计股东号覆盖情况
 *
 * ⚠️ 现场实测：`T_User.RegProtocol` 是 binary(1)，实测值为 0x31（字符 '1'），
 * 不是 0/1 数字。页面按「非空即已签署」判断，避免把 '0' 误判成未签署。
 */

const message = useMessage()
const accent = '#d94f8a'

const keyword = ref('')
const signedFilter = ref<'all' | 'yes' | 'no'>('all')
const marketFilter = ref<number | null>(null)
const page = ref(1)
const pageSize = ref(10)

const { data, loading, error, load } = useResource(
  (params: Record<string, unknown>) => accountApi.listAccounts(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockAccountApi.listAccounts(params)) },
)

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    signed: signedFilter.value === 'all' ? undefined : signedFilter.value === 'yes',
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
  signedFilter.value = 'all'
  marketFilter.value = null
  page.value = 1
  reload()
}

const rows = computed<AccountRow[]>(() => data.value?.list ?? [])

/** 按市场过滤：只保留含该市场股东号的账号 */
const filteredRows = computed(() => {
  if (marketFilter.value === null) return rows.value
  return rows.value.filter((r) => r.shareHolders.some((s) => s.ExchangeNum === marketFilter.value))
})

const total = computed(() => data.value?.total ?? 0)

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = rows.value
  const signed = list.filter((r) => r.signed)
  const withSh = list.filter((r) => r.shareHolders.length > 0)
  const totalShares = list.reduce((s, r) => s + r.shareHolders.length, 0)
  return {
    signed: signed.length,
    unsigned: list.length - signed.length,
    withShareholders: withSh.length,
    totalShares,
  }
})

const marketOptions = Object.entries(EXCHANGE_NUMS).map(([v, l]) => ({ label: l, value: Number(v) }))

/** 各市场股东号数量 */
const marketOption = computed(() => {
  const map = new Map<number, number>()
  for (const r of rows.value) {
    for (const s of r.shareHolders) {
      map.set(s.ExchangeNum, (map.get(s.ExchangeNum) ?? 0) + 1)
    }
  }
  const data = [...map.entries()].map(([k, v]) => ({
    name: EXCHANGE_NUMS[k] ?? `市场 ${k}`,
    value: v,
  }))
  return {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 个股东号（{d}%）' },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        label: { show: false },
        itemStyle: { borderWidth: 2, borderColor: 'transparent', borderRadius: 4 },
        data,
        color: ['#3b6ff5', '#d94f8a'],
      },
    ],
  }
})

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<AccountRow> = [
  {
    title: '唯一账号',
    key: 'account',
    width: 190,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono' }, row.account) },
      ),
  },
  { title: '客户名称', key: 'name', width: 100, render: (row) => orDash(row.name) },
  {
    title: '签署状态',
    key: 'signed',
    width: 110,
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
    title: '股东号',
    key: 'shareHolders',
    minWidth: 260,
    render: (row) => {
      if (!row.shareHolders.length) {
        return h('span', { class: 'text-muted' }, '无')
      }
      return h(
        'div',
        { class: 'sh-cell' },
        row.shareHolders.map((s) =>
          h('span', { class: 'sh-item' }, [
            h('span', { class: 'mono' }, s.SAccount),
            h(
              NTag,
              {
                size: 'tiny',
                round: true,
                bordered: false,
                type: s.ExchangeNum === 1 ? 'info' : 'success',
              },
              { default: () => EXCHANGE_NUMS[s.ExchangeNum] ?? s.ExchangeNum },
            ),
          ]),
        ),
      )
    },
  },
  {
    title: '账号类型',
    key: 'accounttype',
    width: 100,
    render: (row) =>
      row.accounttype != null
        ? h(NTag, { size: 'small', round: true, bordered: false, type: 'info' }, { default: () => ACCOUNT_TYPES[row.accounttype] ?? row.accounttype })
        : '-',
  },
  {
    title: '渠道',
    key: 'accatt',
    width: 88,
    render: (row) => (row.accatt != null ? ACC_ATTS[row.accatt] ?? row.accatt : '-'),
  },
  {
    title: '营业部',
    key: 'branchNo',
    width: 110,
    render: (row) => h('span', { class: 'mono' }, orDash(row.branchNo)),
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
        { default: () => '详情' },
      ),
  },
]

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<AccountRow | null>(null)
const detailTab = ref('sign')

function openDetail(row: AccountRow) {
  active.value = row
  detailTab.value = 'sign'
  showDetail.value = true
}

/** 该账号的股东号 */
const activeShares = computed<Shareholder[]>(() => active.value?.shareHolders ?? [])

/** 按市场分组的股东号 */
const sharesByMarket = computed(() => {
  const map = new Map<number, Shareholder[]>()
  for (const s of activeShares.value) {
    if (!map.has(s.ExchangeNum)) map.set(s.ExchangeNum, [])
    map.get(s.ExchangeNum)!.push(s)
  }
  return [...map.entries()]
    .map(([market, list]) => ({ market, label: EXCHANGE_NUMS[market] ?? `市场 ${market}`, list }))
    .sort((a, b) => a.market - b.market)
})

/**
 * 签署报文模板（照 请求接口字段.txt 的 acc_sign 结构）。
 * 密码字段用占位符 —— 页面上永不填真实密码。
 */
const signTemplate = computed(() =>
  JSON.stringify(
    {
      acc_sign: {
        Account: {
          Model: 0,
          AccountType: active.value?.accounttype ?? 1,
          AccAtt: active.value?.accatt ?? 6,
          FAccount: active.value?.useraccount ?? '',
        },
        TradePwd: '<交易密码>',
        ClientName: active.value?.name ?? '',
        TradeAccount: active.value?.tradeAccount ?? '',
        Shareholders: activeShares.value.map((s) => ({
          SAccount: s.SAccount,
          ExchangeNum: s.ExchangeNum,
        })),
      },
    },
    null,
    2,
  ),
)

/** 更新报文模板（acc_update，结构与 acc_sign 相同） */
const updateTemplate = computed(() =>
  JSON.stringify(
    {
      acc_update: {
        Account: {
          Model: 0,
          AccountType: active.value?.accounttype ?? 1,
          AccAtt: active.value?.accatt ?? 6,
          FAccount: active.value?.useraccount ?? '',
        },
        TradePwd: '<交易密码>',
        ClientName: active.value?.name ?? '',
        TradeAccount: active.value?.tradeAccount ?? '',
        Shareholders: activeShares.value.map((s) => ({ SAccount: s.SAccount, ExchangeNum: s.ExchangeNum })),
      },
    },
    null,
    2,
  ),
)

async function copyText(text: string, label: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(`${label}已复制`)
  } catch {
    message.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}

function handleSign() {
  message.warning('后端接口尚未实现：签署将下发 acc_sign 协议')
}

function handleLogout() {
  message.warning('后端接口尚未实现：登出将下发 logout 协议')
}

/** 协议清单（照文档，标注哪些需要加密） */
const PROTOCOL_ROWS = [
  { name: 'acc_sign', title: '账号签署', encrypted: true, desc: '为一个云条件单账号完成协议签署' },
  { name: 'acc_query', title: '账号查询', encrypted: true, desc: '查询账号是否已签署及资料' },
  { name: 'acc_update', title: '更新账号信息', encrypted: true, desc: '更新账号资料与股东号' },
  { name: 'logout', title: '账号登出', encrypted: false, desc: '云条件单账号登出' },
]

const protocolColumns: DataTableColumns<(typeof PROTOCOL_ROWS)[number]> = [
  { title: '协议', key: 'name', width: 120, render: (row) => h('span', { class: 'mono' }, row.name) },
  { title: '名称', key: 'title', width: 130 },
  {
    title: '加密',
    key: 'encrypted',
    width: 100,
    render: (row) =>
      row.encrypted
        ? h(NTag, { size: 'small', round: true, bordered: false, type: 'warning' }, {
            icon: () => h(NIcon, null, { default: () => h(LockClosedOutline) }),
            default: () => '需加密',
          })
        : h('span', { class: 'text-muted' }, '否'),
  },
  { title: '说明', key: 'desc', ellipsis: { tooltip: true } },
]
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />

    <n-alert class="page-card" type="info" :bordered="false" size="small">
      <template #header>签署状态是怎么判断的</template>
      <div class="fact">
        MySQL 侧看 <code>T_User.RegProtocol</code>，Redis 侧看 <code>acc-*.regprotocal</code>。
        <br />
        ⚠️ 现场实测 <code>RegProtocol</code> 是 <code>binary(1)</code>，值为 <code>0x31</code>（字符 <code>'1'</code>），
        <b>不是 0/1 数字</b> —— 所以页面按「有值即已签署」判断，不把它当数字比。
      </div>
    </n-alert>

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="已签署" :value="summary.signed" suffix="个" :icon="CheckmarkCircleOutline" color="#2f9e5f" hint="本页口径" />
      </n-gi>
      <n-gi>
        <StatCard
          label="未签署"
          :value="summary.unsigned"
          suffix="个"
          :icon="WarningOutline"
          color="#dd7a15"
          hint="需要人工跟进"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="有股东号的账号"
          :value="summary.withShareholders"
          suffix="个"
          :icon="PeopleOutline"
          color="#3b6ff5"
          hint="shareHolders 非空"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="股东号总数"
          :value="summary.totalShares"
          suffix="个"
          :icon="SwapHorizontalOutline"
          :color="accent"
          hint="本页口径合计"
        />
      </n-gi>
    </n-grid>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive style="margin-top: 16px">
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="SwapHorizontalOutline" :color="accent" :size="17" />
              各市场股东号数量
            </span>
          </template>
          <BaseChart :option="marketOption" :height="220" empty-text="暂无股东号" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="LockClosedOutline" :color="accent" :size="17" />
              相关协议
            </span>
          </template>
          <n-data-table
            :columns="protocolColumns"
            :data="PROTOCOL_ROWS"
            :bordered="false"
            size="small"
            :pagination="false"
          />
        </n-card>
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="CreateOutline" :color="accent" :size="17" />
          签署与股东号
        </span>
      </template>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索账号 / 户名"
          clearable
          style="width: 230px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-select
          v-model:value="marketFilter"
          :options="marketOptions"
          placeholder="按市场过滤"
          clearable
          style="width: 140px"
        />

        <n-button-group>
          <n-button
            v-for="opt in [
              { key: 'all', label: '全部' },
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
        :data="filteredRows"
        :loading="loading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: AccountRow) => row.account"
        :scroll-x="1240"
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

    <!-- 账号签署详情 -->
    <n-drawer v-model:show="showDetail" :width="660" placement="right">
      <n-drawer-content :title="`签署详情 · ${active?.account ?? ''}`" closable>
        <template v-if="active">
          <n-tabs v-model:value="detailTab" type="line" size="small">
            <n-tab-pane name="sign">
              <template #tab>签署与股东号</template>

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
                <n-descriptions-item label="签署状态">
                  <n-tag size="small" round :bordered="false" :type="active.signed ? 'success' : 'warning'">
                    {{ active.signed ? '已签署' : '未签署' }}
                  </n-tag>
                </n-descriptions-item>
                <n-descriptions-item label="营业部">
                  <span class="mono">{{ orDash(active.branchNo) }}</span>
                </n-descriptions-item>
                <n-descriptions-item label="账号类型">
                  {{ active.accounttype != null ? ACCOUNT_TYPES[active.accounttype] ?? active.accounttype : '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="渠道">
                  {{ active.accatt != null ? ACC_ATTS[active.accatt] ?? active.accatt : '-' }}
                </n-descriptions-item>
              </n-descriptions>

              <!-- 股东号按市场分组 -->
              <div class="detail-block">
                <div class="detail-title">
                  <n-icon :component="SwapHorizontalOutline" :color="accent" />
                  股东号（按市场）
                </div>
                <div v-if="sharesByMarket.length" class="market-list">
                  <div v-for="m in sharesByMarket" :key="m.market" class="market-item">
                    <div class="market-head">
                      <n-tag
                        size="small"
                        round
                        :bordered="false"
                        :type="m.market === 1 ? 'info' : 'success'"
                      >
                        {{ m.label }}
                      </n-tag>
                      <span class="text-muted" style="font-size: 12px">{{ m.list.length }} 个股东号</span>
                    </div>
                    <div class="market-body">
                      <span v-for="s in m.list" :key="s.SAccount" class="mono sh-chip">{{ s.SAccount }}</span>
                    </div>
                  </div>
                </div>
                <div v-else class="text-muted" style="font-size: 13px">
                  该账号没有股东号（现场 <code>acc-010110018838_7_6</code> 的 shareHolders 就是空数组）。
                </div>
              </div>
            </n-tab-pane>

            <n-tab-pane name="template">
              <template #tab>报文模板</template>
              <div class="json-head">
                <n-icon :component="CodeSlashOutline" :color="accent" />
                <span>
                  照 <code>请求接口字段.txt</code> 的协议结构生成，账号信息已按当前账号填好；
                  <b>密码用占位符</b>，页面上不会填真实密码。
                </span>
              </div>

              <div class="tpl-block">
                <div class="tpl-head">
                  <span class="mono">acc_sign</span>
                  <n-button size="tiny" quaternary @click="copyText(signTemplate, 'acc_sign 模板')">
                    <template #icon>
                      <n-icon :component="CopyOutline" />
                    </template>
                    复制
                  </n-button>
                </div>
                <n-code :code="signTemplate" language="json" show-line-numbers word-wrap />
              </div>

              <div class="tpl-block">
                <div class="tpl-head">
                  <span class="mono">acc_update</span>
                  <n-button size="tiny" quaternary @click="copyText(updateTemplate, 'acc_update 模板')">
                    <template #icon>
                      <n-icon :component="CopyOutline" />
                    </template>
                    复制
                  </n-button>
                </div>
                <n-code :code="updateTemplate" language="json" show-line-numbers word-wrap />
              </div>
            </n-tab-pane>
          </n-tabs>

          <div class="detail-note">
            <n-icon :component="KeyOutline" :color="accent" />
            <div>
              签署与更新都<b>需要加密并编码</b>（文档注明）。
              <code>Passwd</code> / <code>TradePwd</code> 属于敏感字段，后台不提供查看。
            </div>
          </div>
        </template>

        <template #footer>
          <div class="drawer-footer">
            <n-button size="small" type="primary" @click="handleSign">
              <template #icon>
                <n-icon :component="CreateOutline" />
              </template>
              {{ active?.signed ? '重新签署' : '签署' }}
            </n-button>
            <n-button size="small" @click="handleLogout">
              <template #icon>
                <n-icon :component="AlertCircleOutline" />
              </template>
              登出
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

.fact {
  font-size: 12.5px;
  line-height: 1.9;
}

.fact code,
.json-head code,
.detail-note code,
.market-body code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

/* 表格里的股东号 */
.sh-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sh-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 6px;
  background: var(--bg-body);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 12px;
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

/* 市场分组 */
.market-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.market-item {
  padding: 10px 12px;
  background: var(--bg-body);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.market-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.market-body {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sh-chip {
  padding: 2px 8px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: 12px;
}

/* 报文模板 */
.tpl-block + .tpl-block {
  margin-top: 18px;
}

.tpl-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.json-head {
  display: flex;
  gap: 9px;
  margin-bottom: 14px;
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

.detail-note b {
  color: var(--danger);
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
