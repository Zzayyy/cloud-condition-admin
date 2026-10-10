<script setup lang="ts">
import { computed, h, ref } from 'vue'
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
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import {
  ArrowForwardOutline,
  BookOutline,
  CashOutline,
  CheckmarkCircleOutline,
  CodeSlashOutline,
  CopyOutline,
  GitNetworkOutline,
  InformationCircleOutline,
  LayersOutline,
  LockClosedOutline,
  SearchOutline,
  SwapHorizontalOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import MockDataAlert from '@/components/MockDataAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { MSG_TYPES, PROTOCOLS, type ProtocolDef } from '@/api/domain'

/**
 * 协议报文页：协议速查 + 报文模板 + MsgType 对照。
 *
 * 这里是**纯文档型页面**，数据全部来自现场协议文档
 * （请求接口字段.txt / 请求接口doc.txt / 数据中台to策略平台.txt），
 * 不依赖后端接口，因此页面始终真实可用。
 */

const message = useMessage()
const accent = '#dd7a15'

const keyword = ref('')
const categoryTab = ref<'all' | '账号类' | '条件单类'>('all')

/* ---------------------------- 协议字段定义 ---------------------------- */

/**
 * 各协议的请求字段（照 请求接口字段.txt 整理）。
 * 只列关键路径，完整说明请看抽屉里的「字段说明」。
 */
interface FieldRow {
  path: string
  type: string
  desc: string
}

const PROTOCOL_FIELDS: Record<string, FieldRow[]> = {
  acc_sign: [
    { path: 'Account.Model', type: 'int', desc: '云单运行模式：0 内嵌（客户端填），1 独立（Web 服务器填）' },
    { path: 'Account.AccountType', type: 'int', desc: '账号类型：1 资金账号，7 客户号' },
    { path: 'Account.AccAtt', type: 'int', desc: '渠道：0 股票，6 期权' },
    { path: 'Account.FAccount', type: 'string', desc: '云单账号（客户端登录后返回）' },
    { path: 'TradePwd', type: 'string', desc: '交易密码，同时作为云单账号密码' },
    { path: 'ClientName', type: 'string', desc: '账号名称' },
    { path: 'TradeAccount', type: 'string', desc: '交易账号' },
    { path: 'Shareholders[].SAccount', type: 'string', desc: '股东号' },
    { path: 'Shareholders[].ExchangeNum', type: 'int', desc: '市场：1 上海，2 深圳' },
  ],
  acc_query: [
    { path: 'Account.Model', type: 'int', desc: '云单运行模式' },
    { path: 'Account.AccountType', type: 'int', desc: '账号类型' },
    { path: 'Account.AccAtt', type: 'int', desc: '渠道' },
    { path: 'Account.FAccount', type: 'string', desc: '云单账号' },
    { path: 'TradePwd', type: 'string', desc: '交易密码' },
  ],
  acc_update: [
    { path: 'Account.*', type: 'obj', desc: '同 acc_sign 的账号对象' },
    { path: 'TradePwd', type: 'string', desc: '交易密码' },
    { path: 'ClientName', type: 'string', desc: '账号名称' },
    { path: 'TradeAccount', type: 'string', desc: '交易账号' },
    { path: 'Shareholders[]', type: 'array', desc: '股东号数组' },
  ],
  logout: [{ path: 'Account.*', type: 'obj', desc: '账号对象（标识要登出的账号）' }],
  create: [
    { path: 'Account', type: 'obj', desc: '账号对象' },
    { path: 'CondType', type: 'int', desc: '条件单类型' },
    { path: 'CondName / CondDesc', type: 'string', desc: '条件单名称 / 描述' },
    { path: 'ValidDate', type: 'string', desc: '有效期' },
    { path: 'Entrust', type: 'obj', desc: '委托参数（合约、价格、数量、买卖、开平…）' },
    { path: 'Entrust.MarketOrderType', type: 'int', desc: '委托方式：0 限价，1 对手价，2 挂盘价，3 涨停价，4 跌停价，15 超价，19 最新价…' },
    { path: 'Entrust.BSType', type: 'int', desc: '买卖类型：1 买，2 卖' },
    { path: 'Entrust.OCType', type: 'int', desc: '仓位方向：1 开仓，2 平仓' },
    { path: 'CfgExceedPrice', type: 'obj', desc: '超价配置（涨跌停价步长等）' },
    { path: 'CfgAutoWithdraw', type: 'obj', desc: '自动撤单（WithdrawSec 未成交等待秒数）' },
    { path: 'CfgFixedSplit / CfgRandSplit', type: 'obj', desc: '固定 / 随机拆单配置' },
    { path: 'CfgAppend', type: 'obj', desc: '追单配置' },
    { path: 'CondPrice', type: 'obj', desc: '价格条件（Op、TriggerPrice）' },
    { path: 'CondPercent', type: 'obj', desc: '涨跌幅条件（Op、TriggerPercent）' },
    { path: 'CondTime', type: 'obj', desc: '时间条件（TriggerDate、TriggerTime）' },
    { path: 'CondLoss / CondTargetLoss', type: 'obj', desc: '止损 / 标的止损' },
    { path: 'CondProfit / CondTargetProfit', type: 'obj', desc: '止盈 / 标的止盈（含 WithdrawType、Withdraw）' },
  ],
  query: [
    { path: 'Account', type: 'obj', desc: '账号对象' },
    { path: 'BeginDate / EndDate', type: 'string', desc: '查询区间' },
  ],
  modify: [
    { path: 'Account', type: 'obj', desc: '账号对象' },
    { path: 'Ref', type: 'string', desc: '云单引用（要修改的条件单编号）' },
    { path: '其余字段', type: 'obj', desc: '与 create 同构' },
  ],
  set: [
    { path: 'Account', type: 'obj', desc: '账号对象' },
    { path: 'Refs', type: 'array', desc: '要运行/停止的条件单编号数组' },
    { path: 'Mode', type: 'int', desc: '目标状态' },
  ],
  remove: [
    { path: 'Account', type: 'obj', desc: '账号对象' },
    { path: 'Refs', type: 'array', desc: '要删除的条件单编号数组' },
  ],
}

/* ---------------------------- 报文模板 ---------------------------- */

/** 精简模板（照 请求接口doc.txt 的 create 报文，去掉注释，便于复制） */
const TEMPLATES: Record<string, string> = {
  create: JSON.stringify(
    {
      create: {
        Account: { Model: 0, FAccount: '010110018838', AccountType: 7, AccAtt: 6 },
        CondType: 2,
        CondName: 'name1',
        CondDesc: 'desc1',
        ValidDate: '2026-08-31',
        Entrust: {
          ContractCode: '90007939',
          ExchangeNum: 2,
          EntrustPrice: '0.7434',
          MarketOrderType: 15,
          CoveredType: false,
          BSType: 1,
          OCType: 1,
          PriceUnit: '0.0001',
          EntrustAmount: 20,
        },
        CondPrice: { ContractCode: '90007939', ExchangeNum: 2, Op: '>', TriggerPrice: '0.234' },
        CondPercent: { ContractCode: '90007939', ExchangeNum: 2, Op: '>', TriggerPercent: '5.25' },
        CondTime: {
          ContractCode: '90007939',
          ExchangeNum: 2,
          TriggerDate: '20260813',
          TriggerTime: '135100',
        },
        CondLoss: { ContractCode: '10011743', ExchangeNum: 1, Method: 1, ValueType: 1, Value: '0.0675' },
        CondProfit: {
          ContractCode: '10011743',
          ExchangeNum: 1,
          Method: 1,
          ValueType: 1,
          Value: '0.0675',
          WithdrawType: 2,
          Withdraw: '0.50',
        },
      },
    },
    null,
    2,
  ),
  remove: JSON.stringify(
    {
      remove: {
        Account: { AccountType: 7, AccAtt: 7, FAccount: '12345679' },
        Refs: ['20260604000001'],
      },
    },
    null,
    2,
  ),
  acc_sign: JSON.stringify(
    {
      acc_sign: {
        Account: { Model: 0, AccountType: 1, AccAtt: 6, FAccount: '300130000461' },
        TradePwd: '123123',
        ClientName: '张国昌',
        TradeAccount: '300130000461',
        Shareholders: [
          { SAccount: 'A442523077', ExchangeNum: 1 },
          { SAccount: '0199908393', ExchangeNum: 2 },
        ],
      },
    },
    null,
    2,
  ),
}

/** 客户端 → 数据中台的 XADD 信封（照 请求接口doc.txt 第 6 行） */
const XADD_TEMPLATE = `XADD 'DataHub_req_stream' * \\
  request_id 'WT-12345680-0' \\
  server_id '0' \\
  server_type 'WT' \\
  reply_req_stream 'WT-0' \\
  task '<data>'`

/** 中台 → 平台的推送报文（照 请求接口字段.txt 2.2.10） */
const NOTICE_TEMPLATE = JSON.stringify(
  {
    Account: { Model: 0, AccountType: 1, AccAtt: 6, FAccount: '300130000461' },
    Notices: [
      { NtType: 1, Ref: '26319550', Mode: 1, Removed: false },
      {
        NtType: 2,
        Ref: '26319550',
        TriggerDateTime: '2026-05-11 14:45:02',
        Reason: '期权最新价格>0.0566，条件单被触发',
      },
    ],
  },
  null,
  2,
)

/** 通知类型（照文档 2.2.10） */
const NOTICE_TYPES = [
  { value: 0, label: '保留', desc: '预留，暂未使用' },
  { value: 1, label: '云单状态变化', desc: 'Mode 变化 / Removed 删除标记' },
  { value: 2, label: '云单被触发', desc: '带 TriggerDateTime 与 Reason' },
  { value: 3, label: '委托回报', desc: '条件单触发后产生的委托' },
  { value: 4, label: '成交回报', desc: '委托对应的成交' },
]

/** Pub/Sub 订阅发布频道（照 请求接口doc.txt 二） */
const PUBSUB_DOCS = [
  {
    name: 'tradeserver_online',
    by: '委托服务器',
    payload: '{"unique_string":"WT-789-AABBCCDDEEJJ","id":1,"ip":"127.0.0.3","mac":"AABBCCDDEEJJ"}',
    reply: 'PUBLISH WT-789-AABBCCDDEEJJ \'{"id":2,"dataHubString":"test"}\'',
    desc: '上线；unique_string 为中台返回时发布到的 channel，回复里的 id 是分配的唯一编号',
  },
  {
    name: 'tradeserver_beat',
    by: '委托服务器',
    payload: '{"unique_string":"WT-789-AABBCCDDEEJJ","id":1,"ip":"127.0.0.3","mac":"AABBCCDDEEJJ"}',
    reply: '—',
    desc: '心跳包，id 为上线报文返回的唯一 ID',
  },
  {
    name: 'tradeserver_offline',
    by: '委托服务器',
    payload: '{"id":1}',
    reply: '—',
    desc: '下线，id 为上线报文返回的唯一 ID',
  },
  {
    name: 'strategyserver_online',
    by: '策略服务器',
    payload: '{"unique_string":"ST-789-AABBCCDDEEJJ","id":1,"ip":"127.0.0.3","mac":"AABBCCDDEEJJ","usecount":3}',
    reply: 'PUBLISH ST-789-AABBCCDDEEJJ \'{"id":2,"dataHubString":"test"}\'',
    desc: 'usecount 为该策略服务器上运行条件单用户的数量，中台据此分配条件单',
  },
  {
    name: 'strategyserver_beat',
    by: '策略服务器',
    payload: '{"unique_string":"ST-789-AABBCCDDEEJJ","id":1,...,"usecount":3}',
    reply: '—',
    desc: '心跳包，同样带 usecount',
  },
  {
    name: 'strategyserver_offline',
    by: '策略服务器',
    payload: '{"id":1}',
    reply: '—',
    desc: '下线',
  },
  { name: 'datahub_online', by: '数据中台', payload: '—', reply: '—', desc: '文档中仅列出名称，未给出报文' },
  { name: 'datahub_beat', by: '数据中台', payload: '—', reply: '—', desc: '文档中仅列出名称，未给出报文' },
  {
    name: '（中台 → 在线服务器）',
    by: '数据中台',
    payload: 'PUBLISH <channel> \'{"id":1,"dataHubString":"test1"}\'',
    reply: '—',
    desc: '中台向所有在线委托/策略服务器发心跳，channel 为各服务器上报的 unique_string',
  },
]

/* ---------------------------- 过滤 ---------------------------- */

const filteredProtocols = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return PROTOCOLS.filter((p) => {
    if (categoryTab.value !== 'all' && p.category !== categoryTab.value) return false
    if (!kw) return true
    return (
      p.name.toLowerCase().includes(kw) ||
      p.title.includes(kw) ||
      p.desc.includes(kw)
    )
  })
})

const protocolColumns: DataTableColumns<ProtocolDef> = [
  {
    title: '协议名',
    key: 'name',
    width: 130,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 600 } }, row.name),
  },
  { title: '名称', key: 'title', width: 150 },
  {
    title: '分类',
    key: 'category',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.category === '账号类' ? 'success' : 'info' },
        { default: () => row.category },
      ),
  },
  {
    title: '加密',
    key: 'encrypted',
    width: 96,
    render: (row) =>
      row.encrypted
        ? h(
            NTooltip,
            { trigger: 'hover' },
            {
              trigger: () =>
                h(NTag, { size: 'small', round: true, bordered: false, type: 'warning' }, {
                  icon: () => h(NIcon, null, { default: () => h(LockClosedOutline) }),
                  default: () => '需加密',
                }),
              default: () => '文档注明：此协议需要进行加密并编码',
            },
          )
        : h('span', { class: 'text-muted' }, '否'),
  },
  { title: '说明', key: 'desc', ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    fixed: 'right',
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          { size: 'tiny', quaternary: true, onClick: () => openDetail(row) },
          { default: () => '字段' },
        ),
        h(
          NButton,
          {
            size: 'tiny',
            quaternary: true,
            type: 'primary',
            disabled: !TEMPLATES[row.name],
            onClick: () => copyTemplate(row.name),
          },
          { default: () => '模板' },
        ),
      ]),
  },
]

const msgTypeColumns: DataTableColumns<{ value: number; label: string }> = [
  {
    title: 'MsgType',
    key: 'value',
    width: 110,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 600 } }, String(row.value)),
  },
  { title: '含义', key: 'label' },
]

const msgTypeRows = Object.entries(MSG_TYPES).map(([v, label]) => ({
  value: Number(v),
  label,
}))

const noticeColumns: DataTableColumns<{ value: number; label: string; desc: string }> = [
  {
    title: 'NtType',
    key: 'value',
    width: 100,
    render: (row) => h('span', { class: 'mono', style: { fontWeight: 600 } }, String(row.value)),
  },
  { title: '类型', key: 'label', width: 150 },
  { title: '说明', key: 'desc', ellipsis: { tooltip: true } },
]

/* ---------------------------- 详情抽屉 ---------------------------- */

const showDetail = ref(false)
const active = ref<ProtocolDef | null>(null)
const detailTab = ref('fields')

function openDetail(row: ProtocolDef) {
  active.value = row
  detailTab.value = 'fields'
  showDetail.value = true
}

const activeFields = computed<FieldRow[]>(() =>
  active.value ? (PROTOCOL_FIELDS[active.value.name] ?? []) : [],
)

const activeTemplate = computed(() =>
  active.value ? (TEMPLATES[active.value.name] ?? '') : '',
)

async function copyText(text: string, label: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(`${label}已复制到剪贴板`)
  } catch {
    message.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}

function copyTemplate(name: string) {
  const tpl = TEMPLATES[name]
  if (tpl) void copyText(tpl, `${name} 报文模板`)
}
</script>

<template>
  <div>
    <!-- 这页是纯文档页，不依赖后端，所以不会有演示数据横幅；
         但仍提示一下来源，避免被当成实时数据 -->
    <n-card class="page-card" size="small" :bordered="false">
      <div class="doc-head">
        <n-icon :component="BookOutline" :color="accent" :size="18" />
        <div>
          <div class="doc-title">本页为协议速查，内容来自现场文档</div>
          <div class="doc-sub">
            <code>请求接口字段.txt</code>（9 个协议）、<code>请求接口doc.txt</code>、
            <code>数据中台to策略平台.txt</code>。不依赖后端接口，因此始终可用。
          </div>
        </div>
      </div>
    </n-card>

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard label="客户端协议" :value="PROTOCOLS.length" suffix="个" :icon="CodeSlashOutline" color="#3b6ff5" hint="账号类 4 + 条件单类 5" />
      </n-gi>
      <n-gi>
        <StatCard label="中台下发 MsgType" :value="msgTypeRows.length" suffix="种" :icon="ArrowForwardOutline" :color="accent" hint="4/8/11/17/18" />
      </n-gi>
      <n-gi>
        <StatCard label="推送通知 NtType" :value="NOTICE_TYPES.length" suffix="种" :icon="LayersOutline" color="#7c5cf0" hint="含委托/成交回报" />
      </n-gi>
      <n-gi>
        <StatCard label="Pub/Sub 频道" :value="PUBSUB_DOCS.length" suffix="个" :icon="SwapHorizontalOutline" color="#0d9c9c" hint="上线 / 心跳 / 下线" />
      </n-gi>
    </n-grid>

    <!-- 协议清单 -->
    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="CodeSlashOutline" :color="accent" :size="17" />
          客户端协议清单
        </span>
      </template>

      <n-tabs v-model:value="categoryTab" type="segment" size="small" style="margin-bottom: 14px">
        <n-tab-pane name="all">
          <template #tab>全部（{{ PROTOCOLS.length }}）</template>
        </n-tab-pane>
        <n-tab-pane name="账号类">
          <template #tab>账号类</template>
        </n-tab-pane>
        <n-tab-pane name="条件单类">
          <template #tab>条件单类</template>
        </n-tab-pane>
      </n-tabs>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索协议名 / 名称 / 说明"
          clearable
          style="width: 260px"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>
      </div>

      <n-data-table
        :columns="protocolColumns"
        :data="filteredProtocols"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: ProtocolDef) => row.name"
        :scroll-x="900"
        :pagination="false"
      />
    </n-card>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="ArrowForwardOutline" :color="accent" :size="17" />
              中台 → 平台 MsgType
            </span>
          </template>
          <n-data-table
            :columns="msgTypeColumns"
            :data="msgTypeRows"
            :bordered="false"
            size="small"
            :pagination="false"
          />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="LayersOutline" :color="accent" :size="17" />
              推送通知 NtType
            </span>
          </template>
          <n-data-table
            :columns="noticeColumns"
            :data="NOTICE_TYPES"
            :bordered="false"
            size="small"
            :pagination="false"
          />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 报文模板 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="CodeSlashOutline" :color="accent" :size="17" />
          常用报文模板
        </span>
      </template>

      <n-tabs type="line" size="small">
        <n-tab-pane name="xadd">
          <template #tab>XADD 信封</template>
          <div class="json-head">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <span>
              委托服务器 → 数据中台的入口报文，写入 <code>DataHub_req_stream</code>。
              <code>task</code> 里放具体业务报文。
            </span>
          </div>
          <div class="code-wrap">
            <n-button
              class="copy-btn"
              size="tiny"
              quaternary
              @click="copyText(XADD_TEMPLATE, 'XADD 模板')"
            >
              <template #icon>
                <n-icon :component="CopyOutline" />
              </template>
              复制
            </n-button>
            <n-code :code="XADD_TEMPLATE" language="bash" show-line-numbers word-wrap />
          </div>
        </n-tab-pane>

        <n-tab-pane v-for="(tpl, name) in TEMPLATES" :key="name" :name="String(name)">
          <template #tab>{{ name }}</template>
          <div class="json-head">
            <n-icon :component="CodeSlashOutline" :color="accent" />
            <span>客户端 <code>{{ name }}</code> 协议请求报文（结构照现场文档）。</span>
          </div>
          <div class="code-wrap">
            <n-button class="copy-btn" size="tiny" quaternary @click="copyText(tpl, `${name} 模板`)">
              <template #icon>
                <n-icon :component="CopyOutline" />
              </template>
              复制
            </n-button>
            <n-code :code="tpl" language="json" show-line-numbers word-wrap />
          </div>
        </n-tab-pane>

        <n-tab-pane name="notice">
          <template #tab>推送通知</template>
          <div class="json-head">
            <n-icon :component="GitNetworkOutline" :color="accent" />
            <span>
              服务器推送条件单状态变化（文档 2.2.10），含云单状态变化与被触发两种通知。
            </span>
          </div>
          <div class="code-wrap">
            <n-button class="copy-btn" size="tiny" quaternary @click="copyText(NOTICE_TEMPLATE, '通知模板')">
              <template #icon>
                <n-icon :component="CopyOutline" />
              </template>
              复制
            </n-button>
            <n-code :code="NOTICE_TEMPLATE" language="json" show-line-numbers word-wrap />
          </div>
        </n-tab-pane>
      </n-tabs>
    </n-card>

    <!-- 订阅发布频道 -->
    <n-card class="page-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="SwapHorizontalOutline" :color="accent" :size="17" />
          订阅发布频道
        </span>
      </template>
      <template #header-extra>
        <n-tag size="small" type="warning" round :bordered="false">
          现场真中台订阅的是带 _1 后缀那套
        </n-tag>
      </template>

      <div class="ps-list">
        <div v-for="p in PUBSUB_DOCS" :key="p.name" class="ps-item">
          <div class="ps-head">
            <span class="mono ps-name">{{ p.name }}</span>
            <n-tag size="tiny" round :bordered="false" type="info">{{ p.by }}</n-tag>
          </div>
          <div class="ps-body">
            <div class="ps-row">
              <span class="ps-k">发送</span>
              <span class="mono ps-v">{{ p.payload }}</span>
            </div>
            <div class="ps-row">
              <span class="ps-k">回复</span>
              <span class="mono ps-v">{{ p.reply }}</span>
            </div>
            <div class="ps-row">
              <span class="ps-k">说明</span>
              <span class="ps-v">{{ p.desc }}</span>
            </div>
          </div>
        </div>
      </div>
    </n-card>

    <!-- 提示 -->
    <n-card class="page-card tip-card" size="small" :bordered="false">
      <div class="tip">
        <n-icon :component="WarningOutline" color="#dd7a15" :size="17" />
        <div>
          <b>文档缺口提醒</b>：<code>datahub_online</code> / <code>datahub_beat</code>
          在文档中<b>只列了名字、没给报文格式</b>；<code>OrderStatue</code> 的取值枚举也没有定义。
          这两处需要向现场确认，页面不做臆测。
        </div>
      </div>
    </n-card>

    <!-- 协议详情 -->
    <n-drawer v-model:show="showDetail" :width="680" placement="right">
      <n-drawer-content :title="`协议详情 · ${active?.name ?? ''}`" closable>
        <template v-if="active">
          <n-descriptions :column="2" label-placement="top" size="small" bordered>
            <n-descriptions-item label="协议名">
              <span class="mono">{{ active.name }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="名称">{{ active.title }}</n-descriptions-item>
            <n-descriptions-item label="分类">{{ active.category }}</n-descriptions-item>
            <n-descriptions-item label="是否需加密">
              <n-tag v-if="active.encrypted" size="small" round :bordered="false" type="warning">
                需加密并编码
              </n-tag>
              <span v-else class="text-muted">文档未要求</span>
            </n-descriptions-item>
          </n-descriptions>

          <n-tabs v-model:value="detailTab" type="line" size="small" style="margin-top: 16px">
            <n-tab-pane name="fields">
              <template #tab>关键字段（{{ activeFields.length }}）</template>
              <n-data-table
                :columns="[
                  { title: '字段路径', key: 'path', width: 230, render: (row: any) => h('span', { class: 'mono' }, row.path) },
                  { title: '类型', key: 'type', width: 90 },
                  { title: '说明', key: 'desc' },
                ]"
                :data="activeFields"
                :bordered="false"
                size="small"
                :pagination="false"
                :max-height="420"
              />
              <div v-if="!activeFields.length" class="text-muted" style="padding: 16px 0">
                该协议的字段说明尚未从文档整理，请查阅 请求接口字段.txt。
              </div>
            </n-tab-pane>

            <n-tab-pane name="template" :disabled="!activeTemplate">
              <template #tab>报文模板</template>
              <div v-if="activeTemplate">
                <n-button size="tiny" quaternary style="margin-bottom: 10px" @click="copyText(activeTemplate, `${active.name} 模板`)">
                  <template #icon>
                    <n-icon :component="CopyOutline" />
                  </template>
                  复制模板
                </n-button>
                <n-code :code="activeTemplate" language="json" show-line-numbers word-wrap />
              </div>
              <div v-else class="text-muted" style="padding: 16px 0">
                暂无该协议的报文模板。
              </div>
            </n-tab-pane>
          </n-tabs>

          <div class="detail-note">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <div>
              字段说明整理自 <code>请求接口字段.txt</code>；
              若与现场实际不一致，以现场文档为准。
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
.doc-head {
  display: flex;
  gap: 11px;
  align-items: flex-start;
}

.doc-title {
  font-size: 13.5px;
  font-weight: 600;
}

.doc-sub {
  margin-top: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
}

.doc-sub code,
.tip code,
.json-head code,
.detail-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}

.row-actions {
  display: flex;
  gap: 2px;
}

/* 代码块 + 悬浮复制按钮 */
.code-wrap {
  position: relative;
}

.copy-btn {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 2;
}

.json-head {
  display: flex;
  gap: 9px;
  margin-bottom: 10px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid v-bind(accent);
}

/* 订阅发布频道 */
.ps-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 12px;
}

.ps-item {
  padding: 12px 14px;
  background: var(--bg-body);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.ps-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.ps-name {
  font-size: 13px;
  font-weight: 600;
}

.ps-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ps-row {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
  line-height: 1.6;
}

.ps-k {
  flex: none;
  width: 42px;
  color: var(--text-muted);
}

.ps-v {
  min-width: 0;
  word-break: break-all;
}

/* 提示卡 */
.tip-card {
  border-left: 3px solid var(--warning) !important;
}

.tip {
  display: flex;
  gap: 10px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.tip b {
  color: var(--warning);
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
