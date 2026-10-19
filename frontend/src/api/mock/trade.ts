/**
 * 委托服务器管理 —— 演示数据（Mock）。
 *
 * 字段名与结构照现场实测：
 *  - Redis 键 `traderserver`：节点数组 { id, ip, mac, last-active-time, lastActiveTimeLong, enable }
 *  - Redis 键 `acc-*`：账号上的 `traderserver` 分配字段（id/ip/lastactivetime/enable）
 *  - MySQL `T_Order` / `T_Order_his`：ID、StrategyID、UserAccount、Account、Code、Market、
 *    OrderPrice、OrderVolumn、BSType、OCType、OrderTime、OrderParam、OrderStatue、
 *    OrderNo、CodeName、StatueDis、Reason
 *  - MySQL `T_Trade` / `T_Trade_his`：id、StrategyID、OrderNo、TradeNO、Account、Market、
 *    Code、Volumn、Price、TradeTime、UserAccount
 *  - `OrderParam` 的真实 JSON 结构照 T_Order_his 的实测样本（createOrder 报文）
 *
 * **数值是编的，不是真实业务数据。**
 */
import type {
  CodeCountRow,
  OrderRecord,
  OrderStats,
  RedisAccount,
  TradeRecord,
  TradeStats,
  TraderServer,
  TraderServerPage,
} from '../domain'
import type { PageQuery, PageResult } from '../types'
import {
  agoBy,
  asString,
  fmt,
  fmtDate,
  floatBetween,
  intBetween,
  paginate,
  pick,
  seededRandom,
  type Rand,
} from './_shared'

const rand: Rand = seededRandom(20261010)

/* --------------------------- 委托服务器节点 --------------------------- */

/**
 * 心跳阈值。委托服务器按 5 秒发一次 tradeserver_beat，
 * 超过 30 秒没心跳即判定离线（与策略服务器一致）。
 *
 * ⚠️ **必须声明在 `buildServers()` 之前**：下面构造 SERVERS 时要用它算
 * `online` / `ageSec`，而 `const` 没有变量提升 —— 放到后面会让整个模块在
 * import 阶段抛 `ReferenceError: Cannot access 'SERVER_OFFLINE_AFTER_SEC'
 * before initialization`，表现为**整页空白**。
 * 这类错误 vue-tsc 与 vite build 都查不出来（它们不执行模块），只在浏览器里炸。
 */
export const SERVER_OFFLINE_AFTER_SEC = 30

/** 当前秒级时间戳（集中一处，便于两处判定用同一个「现在」） */
function nowSec(): number {
  return Math.floor(Date.now() / 1000)
}

/**
 * 照 Redis 键 `traderserver` 的真实结构。
 *
 * 注意：现场那个键里 7 条记录**出现了重复的 id:0**（多次上线累积），
 * 这里刻意保留了「id 不唯一」这一真实特征，页面必须能应付，
 * 不能假设 id 是主键。
 */
function buildServers(): TraderServer[] {
  const seed: Array<{ ip: string; mac: string; enable: boolean; ageSec: number }> = [
    { ip: '192.168.1.137', mac: '20474789c674Test', enable: true, ageSec: 3 },
    { ip: '192.168.1.137', mac: '20474789c674cloudcond-api', enable: true, ageSec: 6 },
    { ip: '192.168.1.136', mac: '2cea7fd9d5c0cloudcond-api', enable: true, ageSec: 12 },
    { ip: '192.168.1.136', mac: '2cea7fd9d5c0Test', enable: true, ageSec: 24 },
    { ip: '192.168.1.138', mac: '5a6b7c8d9e01WT05', enable: true, ageSec: 41 },
    { ip: '192.168.1.139', mac: '5a6b7c8d9e02WT06', enable: false, ageSec: 86400 },
    { ip: '192.168.1.140', mac: '5a6b7c8d9e03WT07', enable: false, ageSec: 172800 },
  ]

  return seed.map((row, index) => {
    const [lastActive, ts] = agoBy(row.ageSec)
    // 照现场：id 会重复，所以不能当唯一键
    const id = index < 4 ? index % 2 : 0
    /*
     * 来源实例与下标也照现场：同一个 traderserver 键在三个库上各存一份、
     * 内容不同，所以页面必须能看出「这条来自哪」；id 会重复，
     * 只有 (instance, arrayIndex) 能唯一定位一行。
     */
    const instance = index < 2 ? '192.168.1.136/db1' : index < 4 ? '192.168.1.136/db0' : '192.168.1.137/db1'
    return {
      id,
      ip: row.ip,
      mac: row.mac,
      enable: row.enable,
      'last-active-time': lastActive,
      lastActiveTimeLong: ts,
      instance,
      arrayIndex: index,
      online: nowSec() - ts <= SERVER_OFFLINE_AFTER_SEC,
      ageSec: nowSec() - ts,
    }
  })
}

const SERVERS = buildServers()

export function isServerOnline(server: TraderServer): boolean {
  if (!server.enable) return false
  return nowSec() - server.lastActiveTimeLong <= SERVER_OFFLINE_AFTER_SEC
}

/* --------------------------- 账号分配关系 --------------------------- */

const F_ACCOUNTS = [
  { account: '300130000461_1_6', user: '300130000461', name: '张国昌' },
  { account: '300130000454_1_6', user: '300130000454', name: '李明' },
  { account: '300130000455_1_6', user: '300130000455', name: '王芳' },
  { account: '300130000456_1_6', user: '300130000456', name: '赵强' },
  { account: '300130000457_1_6', user: '300130000457', name: '陈静' },
  { account: '010110018838_7_6', user: '010110018838', name: '孙丽' },
  { account: '010100011300_7_6', user: '010100011300', name: '张国昌' },
  { account: '1000006_1_6', user: '1000006', name: '刘伟' },
  { account: '914900043_7_6', user: '914900043', name: '周敏' },
  { account: '300130000462_1_6', user: '300130000462', name: '吴强' },
  { account: '300130000467_1_6', user: '300130000467', name: '郑爽' },
  { account: '999921_1_6', user: '999921', name: '冯磊' },
]

/** 照 acc-* 键的 traderserver 分配字段构造 */
function buildAssignments(): RedisAccount[] {
  return F_ACCOUNTS.map((owner, index) => {
    const server = SERVERS[index % 5]
    const [regtime] = agoBy(intBetween(rand, 86400, 86400 * 60))
    const [logintime, ] = agoBy(intBetween(rand, 60, 86400 * 5))
    const online = rand() > 0.25

    return {
      id: intBetween(rand, 100000, 999999),
      useraccount: owner.user,
      account: owner.account,
      accounttype: owner.account.endsWith('_7_6') ? 7 : 1,
      maintype: 6,
      regtime,
      logintime,
      loginIP: online ? pick(rand, ['10.0.0.21', '10.0.0.35', '192.168.1.136']) : '',
      loginMAC: online ? pick(rand, ['2cea7fd9d5c0', '20474789c674']) : '',
      loginOS: online ? pick(rand, ['Windows 10', 'Windows 11', 'macOS 14']) : '',
      name: owner.name,
      tradeAccount: owner.user,
      branchNo: '123456',
      shareHolders: JSON.stringify([
        { SAccount: 'A442523077', ExchangeNum: 1 },
        { SAccount: '0199908393', ExchangeNum: 2 },
      ]),
      regprotocal: 1,
      strategyserverid: -1,
      onlineflag: online ? 1 : 0,
      syncflag: rand() > 0.7 ? 1 : 0,
      traderserver: server,
    }
  })
}

const ASSIGNMENTS = buildAssignments()

/* ------------------------------ 委托单 ------------------------------ */

const CONTRACTS: Array<{ code: string; name: string; exchange: number; market: string; base: number }> = [
  { code: '10011743', name: '50ETF购9月2900', exchange: 1, market: '上海', base: 0.0675 },
  { code: '90007939', name: '50ETF', exchange: 2, market: '深圳', base: 0.7434 },
  { code: '90007461', name: '300ETF', exchange: 2, market: '深圳', base: 0.5435 },
  { code: '10011297', name: '300ETF购9月4000', exchange: 1, market: '上海', base: 0.1234 },
  { code: '510050', name: '50ETF基金', exchange: 1, market: '上海', base: 2.912 },
  { code: '510300', name: '沪深300ETF', exchange: 1, market: '上海', base: 3.845 },
  { code: '300059', name: '东方财富', exchange: 2, market: '深圳', base: 12.34 },
  { code: '600519', name: '贵州茅台', exchange: 1, market: '上海', base: 1685.0 },
]

/** 委托方式：照协议文档 line 304 的取值说明 */
export const MARKET_ORDER_TYPES: Record<number, string> = {
  0: '限价',
  1: '对手价',
  2: '挂盘价',
  3: '涨停价',
  4: '跌停价',
  6: '市价转限',
  7: '市价FAK',
  8: '市价FOK',
  15: '超价',
  19: '最新价',
  20: '对方最优价格',
  21: '本方最优价格',
  22: '即时成交剩余撤销',
  23: '五档即成剩撤',
  24: '全额成交或撤销',
}

/** 买卖方向：照协议文档（1 买 / 2 卖） */
export const BS_TYPES: Record<number, string> = { 1: '买', 2: '卖' }

/** 开平方向：照协议文档（1 开仓 / 2 平仓） */
export const OC_TYPES: Record<number, string> = { 1: '开仓', 2: '平仓' }

/**
 * 委托状态。
 *
 * ⚠️ 协议文档**没有**给出 OrderStatue 的取值枚举（请求接口字段.txt 里查不到），
 * 现场 T_Order_his 也只有 1 条测试数据（OrderStatue=1）。
 * 因此这里**不臆造映射表**，只把后端给出的 StatueDis 文本原样展示，
 * 数字原值同时保留在明细里。
 */
export const ORDER_STATUS_NOTE =
  'OrderStatue 取值枚举未在协议文档中定义，页面只展示后端返回的 StatueDis 文本与原值'

function buildOrderParam(
  contract: (typeof CONTRACTS)[number],
  owner: (typeof F_ACCOUNTS)[number],
  strategyId: number,
  orderNo: string,
  price: string,
  volumn: number,
  bsType: number,
  ocType: number,
  dateTime: string,
  orderId: number,
): string {
  // 照 T_Order_his 实测样本的 createOrder 结构。
  // 注：实测样本里没有 MarketOrderType（委托方式），这里补上是因为该字段
  // 来自 create 协议的 Entrust.MarketOrderType，真实生产报文会带；
  // 页面「委托方式」一行据此展示，取不到时显示 '-'。
  return JSON.stringify({
    createOrder: {
      Account: {
        FAccount: owner.user,
        AccountType: owner.account.endsWith('_7_6') ? 7 : 1,
        AccAtt: 6,
      },
      Id: orderId,
      StrategyID: String(strategyId),
      OrderNo: orderNo,
      Market: contract.market,
      Code: contract.code,
      OrderVolumn: volumn,
      OrderPrice: Number(price),
      BSType: bsType,
      OCType: ocType,
      MarketOrderType: pick(rand, [0, 1, 2, 15, 19]),
      Status: 1,
      CodeName: contract.name,
      StatusDis: '委托已提交',
      OrderDateTime: dateTime,
    },
  })
}

function buildOrders(): OrderRecord[] {
  const rows: OrderRecord[] = []
  const count = 58

  for (let i = 0; i < count; i += 1) {
    const owner = pick(rand, F_ACCOUNTS)
    const contract = pick(rand, CONTRACTS)
    const bsType = pick(rand, [1, 2])
    const ocType = pick(rand, [1, 2])
    const price = floatBetween(rand, contract.base * 0.95, contract.base * 1.05, 4)
    const volumn = intBetween(rand, 1, 50) * 10
    const seconds = intBetween(rand, 60, 86400 * 25)
    const [orderTime, ] = agoBy(seconds)
    const strategyId = Number(`2026${String(intBetween(rand, 9, 10)).padStart(2, '0')}${String(intBetween(rand, 1, 28)).padStart(2, '0')}${String(intBetween(rand, 1, 9999)).padStart(4, '0')}`)
    const orderNo = String(intBetween(rand, 10000, 99999))
    const orderId = i + 1

    // 委托状态：现场只有 1 条数据，这里造几种典型的「已回报」文本
    const statusText = pick(rand, [
      '全部成交',
      '部分成交',
      '已报',
      '已撤',
      '废单',
    ])

    rows.push({
      ID: orderId,
      StrategyID: strategyId,
      UserAccount: owner.user,
      Account: owner.account,
      Code: contract.code,
      Market: contract.market,
      OrderPrice: Number(price),
      OrderVolumn: volumn,
      BSType: bsType,
      OCType: ocType,
      OrderTime: fmtDate(new Date(orderTime.replace(' ', 'T'))),
      OrderParam: buildOrderParam(
        contract, owner, strategyId, orderNo, price, volumn, bsType, ocType, orderTime, orderId,
      ),
      OrderStatue: pick(rand, [0, 1, 2, 3, 4]),
      OrderNo: orderNo,
      CodeName: contract.name,
      StatueDis: statusText,
      Reason: rand() > 0.75 ? pick(rand, ['价格超出涨跌停', '资金不足', '用户手动撤单']) : null,
    })
  }

  return rows.sort((a, b) => b.ID - a.ID)
}

const ORDERS = buildOrders()

/* ------------------------------ 成交回报 ------------------------------ */

function buildTrades(): TradeRecord[] {
  const rows: TradeRecord[] = []
  const count = 46

  for (let i = 0; i < count; i += 1) {
    // 成交必须挂在已有委托上 —— 这是委托与成交的关联关系
    const order = pick(rand, ORDERS)
    const contract = CONTRACTS.find((c) => c.code === order.Code) ?? CONTRACTS[0]
    const seconds = intBetween(rand, 30, 86400 * 20)
    const [tradeTime, ] = agoBy(seconds)

    rows.push({
      id: i + 1,
      StrategyID: order.StrategyID,
      OrderNo: order.OrderNo,
      TradeNO: String(intBetween(rand, 600000000, 699999999)),
      Account: order.Account,
      Market: order.Market,
      Code: contract.code,
      Volumn: String(intBetween(rand, 1, 20) * 10),
      Price: Number(floatBetween(rand, contract.base * 0.95, contract.base * 1.05, 4)),
      TradeTime: fmtDate(new Date(tradeTime.replace(' ', 'T'))),
      UserAccount: order.UserAccount,
    })
  }

  return rows.sort((a, b) => b.id - a.id)
}

const TRADES = buildTrades()

/* ----------------------------- 对外接口 ----------------------------- */

export const mockTradeApi = {
  /**
   * 节点列表。
   *
   * 返回结构与真实接口一致（含 `meta`）—— 页面的「在线/停用」汇总与
   * 「有没有实例连不上」的提示都读 meta，演示态若少这一块，
   * 页面在演示态下会走进一条与真实态不同的分支，等于没验证到真实路径。
   */
  listServers(query: PageQuery = {}): TraderServerPage {
    const page = paginate(
      SERVERS,
      query,
      (row, kw) => row.ip.includes(kw) || row.mac.toLowerCase().includes(kw.toLowerCase()),
      {
        id: (r) => r.id,
        lastActiveTimeLong: (r) => r.lastActiveTimeLong,
      },
      [
        (row, q) => {
          const online = q.online
          if (typeof online !== 'boolean') return true
          return isServerOnline(row) === online
        },
      ],
    )
    /* 演示数据刻意保留现场特征：id 重复、跨实例各存一份 */
    const onlineCount = SERVERS.filter(isServerOnline).length
    return {
      ...page,
      meta: {
        registryCopies: 3,
        entryTotal: SERVERS.length,
        onlineCount,
        offlineAfterSec: SERVER_OFFLINE_AFTER_SEC,
        configuredInstances: 3,
        redisInstances: ['192.168.1.136/db0', '192.168.1.136/db1', '192.168.1.137/db1'],
        redisUnavailable: [],
        registryKey: 'traderserver',
        onlineChannel: 'tradeserver_online_1',
        beatChannel: 'tradeserver_beat_1',
        offlineChannel: 'tradeserver_offline_1',
      },
    }
  },

  listAssignments(query: PageQuery = {}): PageResult<RedisAccount> {
    return paginate(
      ASSIGNMENTS,
      query,
      (row, kw) => row.account.includes(kw) || row.useraccount.includes(kw) || row.name.includes(kw),
      { id: (r) => r.id },
      [
        (row, q) => {
          const sid = q.serverId
          if (sid === undefined || sid === '') return true
          return row.traderserver?.id === Number(sid)
        },
        (row, q) => {
          const online = q.online
          if (typeof online !== 'boolean') return true
          return (row.onlineflag === 1) === online
        },
      ],
    )
  },

  listOrders(query: PageQuery = {}): PageResult<OrderRecord> {
    return paginate(
      ORDERS,
      query,
      (row, kw) =>
        (row.Account ?? '').includes(kw) ||
        (row.Code ?? '').includes(kw) ||
        (row.CodeName ?? '').includes(kw) ||
        (row.OrderNo ?? '').includes(kw) ||
        String(row.StrategyID ?? '').includes(kw),
      {
        ID: (r) => r.ID,
        OrderTime: (r) => String(r.OrderTime),
        OrderPrice: (r) => r.OrderPrice ?? 0,
        OrderVolumn: (r) => r.OrderVolumn ?? 0,
      },
      [
        (row, q) => {
          const bs = q.bsType
          if (bs === undefined || bs === '') return true
          return row.BSType === Number(bs)
        },
        (row, q) => {
          const oc = q.ocType
          if (oc === undefined || oc === '') return true
          return row.OCType === Number(oc)
        },
        (row, q) => {
          const code = asString(q.code)
          return code ? (row.Code ?? '').includes(code) : true
        },
      ],
    )
  },

  listTrades(query: PageQuery = {}): PageResult<TradeRecord> {
    return paginate(
      TRADES,
      query,
      (row, kw) =>
        (row.Account ?? '').includes(kw) ||
        (row.Code ?? '').includes(kw) ||
        (row.OrderNo ?? '').includes(kw) ||
        (row.TradeNO ?? '').includes(kw),
      {
        id: (r) => r.id,
        TradeTime: (r) => String(r.TradeTime),
        Price: (r) => r.Price ?? 0,
      },
      [
        (row, q) => {
          const code = asString(q.code)
          return code ? (row.Code ?? '').includes(code) : true
        },
      ],
    )
  },

  /** 同一委托号下的所有成交（委托 → 成交 下钻用） */
  tradesByOrderNo(orderNo: string): TradeRecord[] {
    return TRADES.filter((t) => t.OrderNo === orderNo)
  },

  /** 某台委托服务器上承载的账号（节点 → 账号 下钻用） */
  accountsByServer(serverId: number): RedisAccount[] {
    return ASSIGNMENTS.filter((a) => a.traderserver?.id === serverId)
  },

  /** 已分配账号总数（汇总指标用） */
  assignmentCount(): number {
    return ASSIGNMENTS.length
  },

  /** 节点总数（汇总指标用，不受分页影响） */
  serverCount(): number {
    return SERVERS.length
  },
}

/* ------------------------------ 图表数据 ------------------------------ */

/**
 * 委托单汇总（演示态）。口径与后端 `OrderStats` 一致：
 * 对**全量** ORDERS 按筛选条件统计，而不是当前页。
 */
export function mockOrderStats(query: PageQuery = {}): OrderStats {
  const kw = asString(query.keyword)
  const bsType = asNumber(query.bsType)
  const ocType = asNumber(query.ocType)
  const code = asString(query.code)

  const rows = ORDERS.filter((r) => {
    if (kw) {
      const k = kw.toLowerCase()
      const hit =
        (r.Account ?? '').toLowerCase().includes(k) ||
        (r.Code ?? '').toLowerCase().includes(k) ||
        (r.CodeName ?? '').toLowerCase().includes(k) ||
        (r.OrderNo ?? '').toLowerCase().includes(k) ||
        (r.UserAccount ?? '').toLowerCase().includes(k)
      if (!hit) return false
    }
    if (bsType !== undefined && r.BSType !== bsType) return false
    if (ocType !== undefined && r.OCType !== ocType) return false
    if (code && !(r.Code ?? '').includes(code)) return false
    return true
  })

  return {
    buyCount: rows.filter((r) => r.BSType === 1).length,
    sellCount: rows.filter((r) => r.BSType === 2).length,
    abnormalCount: rows.filter((r) => r.Reason).length,
    totalAmount: rows.reduce((s, r) => s + (r.OrderPrice ?? 0) * (r.OrderVolumn ?? 0), 0),
    totalCount: rows.length,
  }
}

/**
 * 成交汇总（演示态）。口径与后端 `TradeStats` 一致。
 *
 * ⚠️ 均价用「总额 ÷ 总量」，不是各页均价的平均 —— 后者会失真。
 */
export function mockTradeStats(query: PageQuery = {}): TradeStats {
  const kw = asString(query.keyword)
  const code = asString(query.code)

  const rows = TRADES.filter((r) => {
    if (kw) {
      const k = kw.toLowerCase()
      const hit =
        (r.Account ?? '').toLowerCase().includes(k) ||
        (r.Code ?? '').toLowerCase().includes(k) ||
        (r.OrderNo ?? '').toLowerCase().includes(k) ||
        (r.TradeNO ?? '').toLowerCase().includes(k)
      if (!hit) return false
    }
    if (code && !(r.Code ?? '').includes(code)) return false
    return true
  })

  const volumn = rows.reduce((s, r) => s + Number(r.Volumn ?? 0), 0)
  const amount = rows.reduce((s, r) => s + (r.Price ?? 0) * Number(r.Volumn ?? 0), 0)
  return {
    totalCount: rows.length,
    totalVolumn: volumn,
    totalAmount: amount,
    avgPrice: volumn > 0 ? amount / volumn : 0,
  }
}

/** 按合约的成交量分布（演示态，全量口径） */
export function mockTradeCodes(query: PageQuery = {}, limit = 8): CodeCountRow[] {
  const map = new Map<string, number>()
  for (const r of TRADES) {
    const key = r.Code ?? '未知'
    map.set(key, (map.get(key) ?? 0) + Number(r.Volumn ?? 0))
  }
  return [...map.entries()]
    .map(([code, count]) => ({ code, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, limit)
}

/**
 * 近 14 天成交趋势（演示态，全量口径）。
 *
 * 口径与后端 `/trades/stats/daily` 一致：按 TradeTime 的日期分组，
 * 每组给「笔数」与「成交金额」。
 */
export function mockTradeDaily(query: PageQuery = {}): TradeDailyRow[] {
  const kw = asString(query.keyword)
  const code = asString(query.code)
  const rows = TRADES.filter((r) => {
    if (kw) {
      const k = kw.toLowerCase()
      const hit =
        (r.Account ?? '').toLowerCase().includes(k) ||
        (r.Code ?? '').toLowerCase().includes(k) ||
        (r.OrderNo ?? '').toLowerCase().includes(k) ||
        (r.TradeNO ?? '').toLowerCase().includes(k)
      if (!hit) return false
    }
    if (code && !(r.Code ?? '').includes(code)) return false
    return true
  })

  const map = new Map<string, { count: number; amount: number }>()
  for (const r of rows) {
    const day = String(r.TradeTime ?? '').slice(0, 10)
    if (!day) continue
    const cur = map.get(day) ?? { count: 0, amount: 0 }
    cur.count += 1
    cur.amount += (r.Price ?? 0) * Number(r.Volumn ?? 0)
    map.set(day, cur)
  }
  return [...map.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([date, v]) => ({ date, count: v.count, amount: v.amount }))
}

/** 买卖方向分布（全量口径，后端 /orders/stats/bs 对应） */
export function mockBsDistribution(rows: OrderRecord[]): Array<{ name: string; value: number }> {
  return [
    { name: '买', value: rows.filter((r) => r.BSType === 1).length },
    { name: '卖', value: rows.filter((r) => r.BSType === 2).length },
  ]
}

/** 各委托服务器承载的账号数（节点维度） */
export function mockServerLoad(): Array<{ label: string; value: number }> {
  const map = new Map<string, number>()
  for (const a of ASSIGNMENTS) {
    const key = a.traderserver ? `${a.traderserver.ip}` : '未分配'
    map.set(key, (map.get(key) ?? 0) + 1)
  }
  return [...map.entries()].map(([label, value]) => ({ label, value }))
}

/** 近 14 天委托量 */
export function mockDailyOrders(): Array<{ date: string; count: number }> {
  const out: Array<{ date: string; count: number }> = []
  for (let i = 13; i >= 0; i -= 1) {
    const day = fmtDate(new Date(Date.now() - i * 86400_000))
    out.push({ date: day, count: ORDERS.filter((o) => (o.OrderTime ?? '').startsWith(day)).length })
  }
  return out
}

export { ASSIGNMENTS, ORDERS, SERVERS, TRADES, fmt }
