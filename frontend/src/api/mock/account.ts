/**
 * 账户与用户 —— 演示数据（Mock）。
 *
 * 结构与取值照现场只读实测：
 *  - MySQL `T_User`：id / Account / AccType / MainType / Passwd / RegTime / LoginTime /
 *    LoginIP / LoginMAC / LoginOS / RegProtocol(binary) / UserAccount / Name /
 *    TradeAccount / ShareHolders(text, JSON) / BranchNo
 *  - Redis `acc-<账号>` 键：useraccount / account / accounttype / maintype / regtime /
 *    logintime / name / tradeAccount / branchNo / shareHolders / regprotocal /
 *    strategyserverid / onlineflag / syncflag / traderserver
 *  - MySQL `T_UserLog`：id / Account / LogTime / LogIP / LogMAC / LogOS / Info
 *
 * ⚠️ 现场真实情况（已如实反映到页面，不要"修正"成好看的样子）：
 *  1. `T_User` 只有 **1 行**；`acc-*` 键 136 db1 有 12 个、db0 有 1 个、137 db1 有 1 个。
 *  2. `T_UserLog` 有 **19.1 万行**，但 `LogIP` / `LogMAC` / `LogOS` **全部为 NULL** ——
 *     所以「登录日志」页拿不到来源设备信息，页面上必须说清楚这一点。
 *  3. `T_User.MainType` 存的是**渠道 AccAtt**（0 股票 / 6 期权），
 *     与列注释「0股票/1股票期权/2期货」矛盾 —— 以实际数据为准。
 *  4. `Passwd` 是加密串（base64，形如 `UDq/BLfrqv...=`），页面只显示「已设置」不显示内容。
 *  5. 现场真实密码**绝不写进本文件**。
 *
 * 数值是编的，但结构、取值范围、以及上面的坑都是真的。
 */
import type { AccountLogStat, AccountRow, Shareholder, UserLog } from '../domain'
import type { PageQuery, PageResult } from '../types'
import { asNumber, asString, fmt, intBetween, paginate, pick, seededRandom, type Rand } from './_shared'

const rand: Rand = seededRandom(20261012)

/* ---------------------------- 账号基础数据 ---------------------------- */

/** 现场实测出现过的账号（真实串） + 同构扩展示例 */
const ACCOUNT_SEEDS: Array<{
  account: string
  user: string
  name: string
  accType: number
  accAtt: number
  branch: string
  signed: boolean
  hasRedis: boolean
  /** 该账号在 MySQL T_User 里是否有行（现场只有 010100011300_7_6 那一行） */
  hasMysql: boolean
  online?: number
  sync?: number
  srvId?: number
  traderserver?: { id: number; ip: string }
}> = [
  // ---- 以下 3 条是现场真实存在的 ----
  {
    // 现场 T_User 唯一的一行就是它
    account: '010100011300_7_6', user: '010100011300', name: '张国昌',
    accType: 7, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: true,
    online: 1, sync: 0, srvId: 0,
    traderserver: { id: -1, ip: '' },
  },
  {
    account: '010110018838_7_6', user: '010110018838', name: '',
    accType: 7, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: false,
    online: 1, sync: 0, srvId: -1,
    traderserver: { id: 1, ip: '192.168.1.137' },
  },
  {
    account: '1000006_1_6', user: '1000006', name: '',
    accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: false,
    online: 1, sync: 0, srvId: -1,
    traderserver: { id: 3, ip: '192.168.1.137' },
  },
  // ---- 以下为同构扩展示例；照现场规律：多数只在 Redis，不在 T_User ----
  { account: '300130000454_1_6', user: '300130000454', name: '李明', accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: false, online: 1, sync: 0, srvId: -1, traderserver: { id: 3, ip: '192.168.1.137' } },
  { account: '300130000455_1_6', user: '300130000455', name: '王芳', accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: false, online: 1, sync: 0, srvId: -1, traderserver: { id: 3, ip: '192.168.1.137' } },
  { account: '300130000456_1_6', user: '300130000456', name: '赵强', accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: false, online: 0, sync: 1, srvId: -1, traderserver: { id: 3, ip: '192.168.1.137' } },
  { account: '300130000457_1_6', user: '300130000457', name: '陈静', accType: 1, accAtt: 6, branch: '123456', signed: false, hasRedis: true, hasMysql: false, online: 0, sync: 0, srvId: -1, traderserver: { id: 3, ip: '192.168.1.137' } },
  { account: '300130000458_1_6', user: '300130000458', name: '刘伟', accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: false, hasMysql: false },
  { account: '300130000461_1_6', user: '300130000461', name: '张国昌', accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: false, online: 1, sync: 0, srvId: -1, traderserver: { id: 3, ip: '192.168.1.137' } },
  { account: '300130000462_1_6', user: '300130000462', name: '周敏', accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: true, hasMysql: false, online: 1, sync: 0, srvId: -1, traderserver: { id: 3, ip: '192.168.1.137' } },
  { account: '300130000467_1_6', user: '300130000467', name: '吴强', accType: 1, accAtt: 6, branch: '123456', signed: false, hasRedis: true, hasMysql: false, online: 0, sync: 0, srvId: -1, traderserver: { id: 3, ip: '192.168.1.137' } },
  { account: '914900043_7_6', user: '914900043', name: '周敏', accType: 7, accAtt: 6, branch: '123456', signed: true, hasRedis: false, hasMysql: false },
  { account: '999921_1_6', user: '999921', name: '冯磊', accType: 1, accAtt: 6, branch: '123456', signed: true, hasRedis: false, hasMysql: false },
]

/** 股东号：现场实测就是这两条（上交所 A442523077 / 深交所 0199908393） */
function buildShareholders(withShareholders: boolean): Shareholder[] {
  if (!withShareholders) return []
  return [
    { SAccount: 'A442523077', ExchangeNum: 1 },
    { SAccount: '0199908393', ExchangeNum: 2 },
  ]
}

function buildAccounts(): AccountRow[] {
  return ACCOUNT_SEEDS.map((seed, index) => {
    const regSeconds = intBetween(rand, 86400 * 10, 86400 * 120)
    const loginSeconds = intBetween(rand, 60, regSeconds)
    const regtime = fmt(new Date(Date.now() - regSeconds * 1000))
    const logintime = fmt(new Date(Date.now() - loginSeconds * 1000))
    const online = seed.online === 1
    const hasLoginInfo = online && rand() > 0.4

    return {
      account: seed.account,
      useraccount: seed.user,
      name: seed.name || null,
      accounttype: seed.accType,
      accatt: seed.accAtt,
      tradeAccount: seed.user,
      branchNo: seed.branch,
      regtime,
      logintime,
      loginIP: hasLoginInfo ? pick(rand, ['10.0.0.21', '10.0.0.35', '192.168.1.136']) : '',
      loginMAC: hasLoginInfo ? pick(rand, ['2cea7fd9d5c0', '20474789c674']) : '',
      loginOS: hasLoginInfo ? pick(rand, ['Windows 10', 'Windows 11', 'macOS 14']) : '',
      shareHolders: buildShareholders(seed.hasRedis && index % 5 !== 0),
      signed: seed.signed,
      hasRedis: seed.hasRedis,
      onlineflag: seed.online,
      syncflag: seed.sync,
      strategyserverid: seed.srvId,
      traderserver: seed.traderserver
        ? {
            id: seed.traderserver.id,
            ip: seed.traderserver.ip,
            mac: '',
            enable: true,
            'last-active-time': fmt(new Date(Date.now() - intBetween(rand, 60, 86400 * 20) * 1000)),
            lastActiveTimeLong: Math.floor(Date.now() / 1000) - intBetween(rand, 60, 86400 * 20),
          }
        : undefined,
      userId: seed.hasMysql ? 70697 : undefined,
    }
  })
}

const ACCOUNTS = buildAccounts()

/* ------------------------------ 登录日志 ------------------------------ */

/**
 * 现场 `T_UserLog` 的 `Info` 实测只有一种值：`"user registed"`（原文如此，拼写就是 registed）。
 * `LogIP` / `LogMAC` / `LogOS` 全部为 NULL。
 *
 * 演示数据里为了页面可用，给一部分行补上 IP/设备，
 * 但**页面上会明确标注「现场该三列全为 NULL」**，避免误以为能查到来源设备。
 */
const LOG_INFO_VALUES = ['user registed', 'user login', 'user logout', 'pwd changed', 'account updated']

function buildLogs(): UserLog[] {
  const rows: UserLog[] = []
  const accounts = ACCOUNT_SEEDS.filter((a) => a.hasRedis)

  // 现场日志量极大（19.1 万），演示数据造 260 条足够展示分页与聚合
  for (let i = 0; i < 260; i += 1) {
    const src = pick(rand, accounts)
    const seconds = intBetween(rand, 60, 86400 * 40)
    const withDevice = rand() > 0.55

    rows.push({
      id: 192186 - i,
      Account: src.account,
      LogTime: fmt(new Date(Date.now() - seconds * 1000)),
      // 现场这三列全是 NULL —— 这里刻意保留一部分 NULL，贴近真实
      LogIP: withDevice ? pick(rand, ['10.0.0.21', '10.0.0.35', '192.168.1.136', '127.0.0.1']) : null,
      LogMAC: withDevice ? pick(rand, ['2cea7fd9d5c0', '20474789c674', 'aabbccddeeff']) : null,
      LogOS: withDevice ? pick(rand, ['Windows 10', 'Windows 11', 'macOS 14', 'CentOS 7']) : null,
      Info: pick(rand, LOG_INFO_VALUES),
    })
  }

  return rows.sort((a, b) => b.id - a.id)
}

const LOGS = buildLogs()

/** 按账号聚合的登录统计 */
function buildLogStats(): AccountLogStat[] {
  const map = new Map<string, { count: number; times: string[] }>()
  for (const log of LOGS) {
    const key = log.Account ?? ''
    if (!map.has(key)) map.set(key, { count: 0, times: [] })
    const entry = map.get(key)!
    entry.count += 1
    if (log.LogTime) entry.times.push(log.LogTime)
  }
  return [...map.entries()]
    .map(([Account, v]) => ({
      Account,
      count: v.count,
      firstTime: v.times.length ? v.times.reduce((a, b) => (a < b ? a : b)) : null,
      lastTime: v.times.length ? v.times.reduce((a, b) => (a > b ? a : b)) : null,
    }))
    .sort((a, b) => b.count - a.count)
}

const LOG_STATS = buildLogStats()

/* ----------------------------- 对外接口 ----------------------------- */

export const mockAccountApi = {
  listAccounts(query: PageQuery = {}): PageResult<AccountRow> {
    return paginate(
      ACCOUNTS,
      query,
      (row, kw) =>
        row.account.includes(kw) ||
        row.useraccount.includes(kw) ||
        (row.name ?? '').includes(kw) ||
        (row.tradeAccount ?? '').includes(kw),
      { account: (r) => r.account, regtime: (r) => String(r.regtime), logintime: (r) => String(r.logintime) },
      [
        (row, q) => {
          const t = asNumber(q.accounttype)
          return t === undefined ? true : row.accounttype === t
        },
        (row, q) => {
          const t = asNumber(q.accatt)
          return t === undefined ? true : row.accatt === t
        },
        (row, q) => {
          const signed = q.signed
          if (typeof signed !== 'boolean') return true
          return row.signed === signed
        },
        (row, q) => {
          const online = q.online
          if (typeof online !== 'boolean') return true
          return (row.onlineflag === 1) === online
        },
        (row, q) => {
          const branch = asString(q.branchNo)
          return branch ? (row.branchNo ?? '').includes(branch) : true
        },
      ],
    )
  },

  listLogs(query: PageQuery = {}): PageResult<UserLog> {
    return paginate(
      LOGS,
      query,
      (row, kw) =>
        (row.Account ?? '').includes(kw) || (row.Info ?? '').includes(kw),
      { id: (r) => r.id, LogTime: (r) => String(r.LogTime), Account: (r) => String(r.Account) },
      [
        (row, q) => {
          const account = asString(q.account)
          return account ? (row.Account ?? '').includes(account) : true
        },
        (row, q) => {
          const info = asString(q.info)
          return info ? (row.Info ?? '').includes(info) : true
        },
        (row, q) => {
          // 只看有设备信息的 / 只看缺设备信息的
          const withDevice = q.withDevice
          if (typeof withDevice !== 'boolean') return true
          const has = Boolean(row.LogIP || row.LogMAC || row.LogOS)
          return has === withDevice
        },
      ],
    )
  },

  listLogStats(query: PageQuery = {}): PageResult<AccountLogStat> {
    return paginate(
      LOG_STATS,
      query,
      (row, kw) => row.Account.includes(kw),
      { count: (r) => r.count, Account: (r) => r.Account },
    )
  },

  /** 全部日志条数（汇总用） */
  logCount(): number {
    return LOGS.length
  },

  /** 缺少来源设备信息的日志数 */
  logMissingDeviceCount(): number {
    return LOGS.filter((l) => !l.LogIP && !l.LogMAC && !l.LogOS).length
  },
}

/* ------------------------------ 图表数据 ------------------------------ */

/** 账号类型分布（照当前数据算） */
export function mockAccountTypeDist(rows: AccountRow[]): Array<{ name: string; value: number }> {
  const map = new Map<string, number>()
  for (const r of rows) {
    const key = r.accounttype === 7 ? '客户号' : r.accounttype === 1 ? '资金账号' : '未知'
    map.set(key, (map.get(key) ?? 0) + 1)
  }
  return [...map.entries()].map(([name, value]) => ({ name, value }))
}

/** 渠道分布 */
export function mockAccAttDist(rows: AccountRow[]): Array<{ name: string; value: number }> {
  const map = new Map<string, number>()
  for (const r of rows) {
    const key = r.accatt === 6 ? '期权' : r.accatt === 0 ? '股票' : '未知'
    map.set(key, (map.get(key) ?? 0) + 1)
  }
  return [...map.entries()].map(([name, value]) => ({ name, value }))
}

/** 近 14 天日志量 */
export function mockDailyLogs(): Array<{ date: string; count: number }> {
  const out: Array<{ date: string; count: number }> = []
  for (let i = 13; i >= 0; i -= 1) {
    const day = fmt(new Date(Date.now() - i * 86400_000)).slice(0, 10)
    out.push({ date: day, count: LOGS.filter((l) => (l.LogTime ?? '').startsWith(day)).length })
  }
  return out
}

export { ACCOUNTS, LOGS, LOG_STATS }
