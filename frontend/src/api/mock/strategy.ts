/**
 * 策略平台管理 —— 演示数据（Mock）。
 *
 * 后端接口尚未实现，这里用一份**贴近现场真实结构**的假数据把页面撑起来，
 * 先看交互与视觉效果。字段名、取值含义、数据规模都照只读探查到的真实
 * 结构来（strategy-srv 键 / T_Conditional 表 / T_StrategyLog 表），
 * 但**数值是编的，不是真实业务数据**。
 *
 * 后端接通后 useResource 会自动改走真实接口，本文件可以整个删掉。
 */
import type { Conditional, StrategyLog, StrategyServer } from '../domain'
import type { PageQuery, PageResult } from '../types'
import {
  agoBy,
  asNumber,
  asString,
  fmt,
  intBetween,
  paginate,
  pick,
  seededRandom,
  withDelay,
  type Rand,
} from './_shared'

/* ------------------------------ 工具 ------------------------------ */

const rand: Rand = seededRandom(20261009)

/* --------------------------- 策略服务器 --------------------------- */

/**
 * 字段照 Redis 键 `strategy-srv` 的真实结构：
 *   { id, usecount, ip, "login-time", "last-active-time", mac, lastActiveTimeLong, enable }
 */
function buildServers(): StrategyServer[] {
  // 现场实测只见到 1 个节点（192.168.1.136 / mac 2cea7fd9d5c0probe），
  // 这里按同构扩成一组，覆盖在线/离线/停用三种形态。
  const seedRows: Array<Partial<StrategyServer> & { ip: string; mac: string }> = [
    { ip: '192.168.1.136', mac: '2cea7fd9d5c0probe' },
    { ip: '192.168.1.137', mac: '20474789c674Test' },
    { ip: '192.168.1.136', mac: '2cea7fd9d5c0cloudcond' },
    { ip: '192.168.1.138', mac: '5a6b7c8d9e01ST02' },
    { ip: '192.168.1.139', mac: '5a6b7c8d9e02ST03' },
    { ip: '192.168.1.140', mac: '5a6b7c8d9e03ST04' },
    { ip: '192.168.1.141', mac: '5a6b7c8d9e04ST05' },
    { ip: '192.168.1.142', mac: '5a6b7c8d9e05ST06' },
  ]

  return seedRows.map((row, index) => {
    // 前 5 个心跳新鲜（在线），第 6 个刚掉线，最后 2 个停用
    const offsetSec = index < 5 ? intBetween(rand, 2, 8) : index === 5 ? intBetween(rand, 40, 120) : intBetween(rand, 3600, 86400)
    const [lastActive, ts] = agoBy(offsetSec)
    const [loginTime] = agoBy(offsetSec + intBetween(rand, 3600, 200000))

    return {
      id: index,
      ip: row.ip,
      mac: row.mac,
      usecount: index < 5 ? intBetween(rand, 0, 860) : 0,
      'login-time': loginTime,
      'last-active-time': lastActive,
      lastActiveTimeLong: ts,
      enable: index < 6,
    }
  })
}

const SERVERS = buildServers()

/** 心跳超过 30s 视为离线（现场策略服务器 5s 一次心跳） */
export const SERVER_OFFLINE_AFTER_SEC = 30

export function isServerOnline(server: StrategyServer): boolean {
  if (!server.enable) return false
  const now = Math.floor(Date.now() / 1000)
  return now - server.lastActiveTimeLong <= SERVER_OFFLINE_AFTER_SEC
}

/* ---------------------------- 条件单策略 ---------------------------- */

const F_ACCOUNTS = [
  { account: '300130000461_1_6', user: '300130000461', name: '张国昌' },
  { account: '010100011300_7_6', user: '010100011300', name: '张国昌' },
  { account: '300130000454_1_6', user: '300130000454', name: '李明' },
  { account: '300130000455_1_6', user: '300130000455', name: '王芳' },
  { account: '300130000456_1_6', user: '300130000456', name: '赵强' },
  { account: '300130000457_1_6', user: '300130000457', name: '陈静' },
  { account: '914900043_7_6', user: '914900043', name: '刘伟' },
  { account: '010110018838_7_6', user: '010110018838', name: '孙丽' },
]

const CONTRACTS = ['10011743', '90007939', '90007461', '10011297', '510050', '510300']
const COND_NAMES = [
  '50ETF 止盈',
  '50ETF 止损',
  '沪深300 回落买入',
  '个股突破追涨',
  '期权对冲',
  '定时建仓',
  '移动止盈',
  '分批减仓',
]

const COND_TYPES = [1, 2, 3, 4] as const
const MODES = [0, 1, 2, 3] as const
const FLAGS = [0, 1, 2] as const

/** 参考 请求接口字段.txt 的 create 报文结构 */
function buildStrategyJson(contract: string, condType: number): string {
  return JSON.stringify({
    create: {
      Account: { FAccount: '300130000461', AccountType: 1, AccAtt: 6 },
      CondType: condType,
      CondName: pick(rand, COND_NAMES),
      CondDesc: '演示数据',
      ValidDate: '2026-12-31',
      Entrust: {
        ContractCode: contract,
        ExchangeNum: pick(rand, [1, 2]),
        EntrustPrice: (rand() * 12 + 0.5).toFixed(4),
        MarketOrderType: 15,
        CoveredType: false,
        BSType: pick(rand, [1, 2]),
        OCType: pick(rand, [1, 2]),
        PriceUnit: '0.0001',
        EntrustAmount: intBetween(rand, 1, 100) * 10,
      },
      CondPrice: {
        ContractCode: contract,
        ExchangeNum: 2,
        Op: pick(rand, ['>', '<']),
        TriggerPrice: (rand() * 12 + 0.5).toFixed(4),
      },
      CondPercent: {
        ContractCode: contract,
        ExchangeNum: 2,
        Op: pick(rand, ['>', '<']),
        TriggerPercent: (rand() * 8 + 1).toFixed(2),
      },
      CondTime: {
        ContractCode: contract,
        ExchangeNum: 2,
        TriggerDate: '20261231',
        TriggerTime: '135100',
      },
      CondLoss: {
        ContractCode: contract,
        ExchangeNum: 1,
        Method: 1,
        ValueType: 1,
        Value: (rand() * 0.2).toFixed(4),
      },
      CondProfit: {
        ContractCode: contract,
        ExchangeNum: 1,
        Method: 1,
        ValueType: 1,
        Value: (rand() * 0.2).toFixed(4),
        WithdrawType: 2,
        Withdraw: '0.50',
      },
    },
  })
}

function buildConditionals(): Conditional[] {
  const rows: Conditional[] = []
  const count = 46

  for (let i = 0; i < count; i += 1) {
    const owner = pick(rand, F_ACCOUNTS)
    const contract = pick(rand, CONTRACTS)
    const condType = pick(rand, COND_TYPES)
    const mode = pick(rand, MODES)
    const flag = mode === 3 ? 2 : pick(rand, FLAGS)

    const addSeconds = intBetween(rand, 60, 60 * 60 * 24 * 45)
    const addDate = new Date(Date.now() - addSeconds * 1000)
    const modifyDate = new Date(addDate.getTime() + intBetween(rand, 0, addSeconds) * 1000)
    const runCount = intBetween(rand, 0, 320)
    const triggered = mode === 3 || (runCount > 0 && rand() > 0.6)

    rows.push({
      id: Number(`2026${String(intBetween(rand, 10, 12)).padStart(2, '0')}${String(intBetween(rand, 1, 28)).padStart(2, '0')}${String(i + 1).padStart(4, '0')}`),
      Account: owner.account,
      UserAccount: owner.user,
      Strategy: buildStrategyJson(contract, condType),
      AddTime: fmt(addDate),
      RunTime: runCount > 0 ? fmt(new Date(addDate.getTime() + 60000)) : null,
      CloseTime: mode === 3 ? fmt(new Date(addDate.getTime() + 3600_000)) : null,
      RunCount: runCount,
      BeginTime: fmt(addDate),
      EndTime: '2026-12-31 23:59:59',
      Status: triggered ? 1 : 0,
      Flag: flag,
      Description: pick(rand, COND_NAMES),
      Validity: 0,
      ModifyTime: fmt(modifyDate),
      TriggeredTime: triggered ? fmt(new Date(addDate.getTime() + 1800_000)) : null,
      Reason: rand() > 0.7 ? '用户手动调整触发价' : null,
      Mode: mode,
      Type: condType,
      Name: pick(rand, COND_NAMES),
    })
  }

  // 按加入时间倒序，最新的在前
  return rows.sort((a, b) => String(b.AddTime).localeCompare(String(a.AddTime)))
}

const CONDITIONALS = buildConditionals()

/* --------------------------- 策略运行日志 --------------------------- */

const LOG_STATUS = ['触发成功', '触发失败', '已暂停', '已恢复', '已删除', '校验失败', '参数错误'] as const

function buildLogs(): StrategyLog[] {
  const rows: StrategyLog[] = []
  const count = 90

  for (let i = 0; i < count; i += 1) {
    const src = pick(rand, CONDITIONALS)
    const status = pick(rand, LOG_STATUS)
    const seconds = intBetween(rand, 30, 60 * 60 * 24 * 20)
    const [occur] = agoBy(seconds)

    rows.push({
      id: i + 1,
      Strategy: src.id,
      Status: status,
      Info:
        status === '触发成功'
          ? `条件单触发，已发出委托 ${pick(rand, CONTRACTS)}，委托号 ${intBetween(rand, 20000, 99999)}`
          : status === '触发失败'
            ? `触发失败：委托被拒绝，${pick(rand, ['资金不足', '持仓不足', '价格超出涨跌停', '合约不可交易'])}`
            : status === '校验失败'
              ? `协议校验失败：${pick(rand, ['EntrustAmount 必须大于 0', 'TriggerPrice 格式错误', 'ExchangeNum 取值非法'])}`
              : `${status}，操作来源 ${pick(rand, ['客户端', 'Web 服务器', '管理员'])}`,
      OccurTIme: occur,
      FromIP: pick(rand, ['192.168.1.136', '192.168.1.137', '10.0.0.21', '10.0.0.35', '']),
      FromMac: pick(rand, ['2cea7fd9d5c0', '20474789c674', '5a6b7c8d9e01', '']),
      FromAccount: pick(rand, [...F_ACCOUNTS.map((a) => a.user), 'admin']),
    })
  }

  return rows.sort((a, b) => b.id - a.id)
}

const LOGS = buildLogs()

/* ----------------------------- 对外接口 ----------------------------- */

export const mockStrategyApi = {
  listServers(query: PageQuery = {}): PageResult<StrategyServer> {
    const online = query.online
    let rows = SERVERS
    if (typeof online === 'boolean') {
      rows = rows.filter((s) => isServerOnline(s) === online)
    }
    return paginate(
      rows,
      query,
      (row, kw) => row.ip.includes(kw) || row.mac.toLowerCase().includes(kw.toLowerCase()),
      { id: (r) => r.id, usecount: (r) => r.usecount, lastActiveTimeLong: (r) => r.lastActiveTimeLong },
    )
  },

  listConditionals(query: PageQuery = {}): PageResult<Conditional> {
    let rows = CONDITIONALS

    const account = asString(query.account)
    if (account) rows = rows.filter((r) => (r.Account ?? '').includes(account))

    const type = asNumber(query.type)
    if (type !== undefined) rows = rows.filter((r) => r.Type === type)

    const mode = asNumber(query.mode)
    if (mode !== undefined) rows = rows.filter((r) => r.Mode === mode)

    // 数据源：running=运行中主表，history=历史表，all=并集（对应 V_Conditional）
    const source = asString(query.source)
    if (source === 'history') rows = rows.filter((r) => r.Mode === 3)
    else if (source === 'running') rows = rows.filter((r) => r.Mode !== 3)

    return paginate(
      rows,
      query,
      (row, kw) =>
        (row.Account ?? '').includes(kw) ||
        (row.Name ?? '').includes(kw) ||
        String(row.id).includes(kw),
      { id: (r) => r.id, AddTime: (r) => String(r.AddTime), RunCount: (r) => r.RunCount ?? 0 },
    )
  },

  listLogs(query: PageQuery = {}): PageResult<StrategyLog> {
    let rows = LOGS

    const strategy = asNumber(query.strategy)
    if (strategy !== undefined) rows = rows.filter((r) => r.Strategy === strategy)

    const status = asString(query.status)
    if (status) rows = rows.filter((r) => r.Status === status)

    const fromIp = asString(query.fromIp)
    if (fromIp) rows = rows.filter((r) => (r.FromIP ?? '').includes(fromIp))

    return paginate(
      rows,
      query,
      (row, kw) =>
        (row.Info ?? '').includes(kw) ||
        String(row.Strategy ?? '').includes(kw) ||
        (row.FromAccount ?? '').includes(kw),
      { id: (r) => r.id, OccurTIme: (r) => String(r.OccurTIme), Strategy: (r) => r.Strategy ?? 0 },
    )
  },

  /** 页面上用到的下拉选项，也从这里出，避免页面里散落魔法值 */
  logStatusOptions: LOG_STATUS,
}

/** 供图表用：按条件单类型统计数量 */
export function mockTypeDistribution(): Array<{ type: number; name: string; count: number }> {
  const names: Record<number, string> = {
    1: '开仓条件单',
    2: '平仓条件单',
    3: '按合约止盈止损',
    4: '按标的止盈止损',
  }
  return [1, 2, 3, 4].map((type) => ({
    type,
    name: names[type],
    count: CONDITIONALS.filter((c) => c.Type === type).length,
  }))
}

/** 供图表用：最近 14 天新增条件单数量 */
export function mockDailyCreated(): Array<{ date: string; count: number }> {
  const out: Array<{ date: string; count: number }> = []
  for (let i = 13; i >= 0; i -= 1) {
    const d = new Date(Date.now() - i * 86400_000)
    const day = fmt(d).slice(0, 10)
    out.push({ date: day, count: CONDITIONALS.filter((c) => (c.AddTime ?? '').startsWith(day)).length })
  }
  return out
}

export { CONDITIONALS, LOGS, SERVERS }
