/**
 * 业务实体类型 —— 依据现场真实结构整理（非臆造）：
 *  - MySQL: qianlong_cloud 库（见 README「数据字典」）
 *  - Redis: 136/137 的 stream 与 ReJSON 键
 *
 * 后端接口落地后，这里是前后端的共同契约。
 */

/* ------------------------------- 服务器节点 ------------------------------- */

/** 委托服务器（Redis JSON 键 traderserver 中的元素） */
export interface TraderServer {
  id: number
  ip: string
  mac: string
  /** 是否启用 */
  enable: boolean
  /** 最后活跃时间（字符串形式） */
  'last-active-time': string
  lastActiveTimeLong: number
}

/** 策略服务器（Redis JSON 键 strategy-srv 中的元素） */
export interface StrategyServer {
  id: number
  ip: string
  mac: string
  /** 该策略服务器上运行条件单用户的数量 */
  usecount: number
  /** 上线时间 */
  'login-time': string
  'last-active-time': string
  lastActiveTimeLong: number
  enable: boolean
}

/** 数据中台节点（由 datahub_* 频道推导，Redis 侧仅有 unique_id） */
export interface DatahubNode {
  id: number
  ip: string
  mac: string
  online: boolean
  lastActiveTime: string
  /** 该节点已分配的唯一编号水位 */
  uniqueId?: number
}

/* ------------------------------- 条件单/策略 ------------------------------- */

/** 条件单类型（T_Conditional.Type） */
export const CONDITIONAL_TYPES = {
  0: '保留',
  1: '开仓条件单',
  2: '平仓条件单',
  3: '按合约止盈止损',
  4: '按标的止盈止损',
} as const

/** 条件单运行状态（T_Conditional.Mode） */
export const CONDITIONAL_MODES = {
  0: '停止',
  1: '运行',
  2: '失效',
  3: '结束',
} as const

/** 有效标志（T_Conditional.Flag） */
export const CONDITIONAL_FLAGS = {
  0: '有效',
  1: '暂停',
  2: '删除',
} as const

/** MySQL T_Conditional / T_Conditional_his / V_Conditional */
export interface Conditional {
  id: number
  /** 拼接的唯一账号信息，如 300130000461_1_6 */
  Account: string | null
  /** 用户账号 */
  UserAccount: string | null
  /** 条件单原始内容（JSON 字符串） */
  Strategy: string | null
  AddTime: string | null
  RunTime: string | null
  CloseTime: string | null
  RunCount: number | null
  BeginTime: string | null
  EndTime: string | null
  /** 触发状态：0 未触发，1 已触发 */
  Status: number | null
  /** 有效标志：0 有效，1 暂停，2 删除 */
  Flag: number | null
  Description: string | null
  /** 有效期，0 为长期有效 */
  Validity: number | null
  ModifyTime: string | null
  TriggeredTime: string | null
  Reason: string | null
  /** 云单状态：0 停止，1 运行，2 失效，3 结束 */
  Mode: number | null
  /** 云条件单类型 */
  Type: number | null
  Name: string | null
}

/** MySQL T_StrategyLog */
export interface StrategyLog {
  id: number
  Strategy: number | null
  Status: string | null
  Info: string | null
  OccurTIme: string | null
  FromIP: string | null
  FromMac: string | null
  FromAccount: string | null
}

/* --------------------------------- 委托成交 -------------------------------- */

/** MySQL T_Order / T_Order_his */
export interface OrderRecord {
  ID: number
  StrategyID: number | null
  UserAccount: string | null
  Account: string | null
  Code: string | null
  Market: string | null
  OrderPrice: number | null
  OrderVolumn: number | null
  BSType: number | null
  OCType: number | null
  OrderTime: string | null
  OrderParam: string | null
  OrderStatue: number | null
  OrderNo: string | null
  CodeName: string | null
  StatueDis: string | null
  Reason: string | null
}

/** MySQL T_Trade / T_Trade_his */
export interface TradeRecord {
  id: number
  StrategyID: number | null
  OrderNo: string | null
  TradeNO: string | null
  Account: string | null
  Market: string | null
  Code: string | null
  Volumn: string | null
  Price: number | null
  TradeTime: string | null
  UserAccount: string | null
}

/* ---------------------------------- 账号 ---------------------------------- */

/** MySQL T_User */
export interface UserAccount {
  id: number
  Account: string | null
  /** 0 资金账号，1 客户号 */
  AccType: number | null
  /** 0 股票，1 股票期权，2 期货 */
  MainType: number | null
  Passwd: string | null
  RegTime: string | null
  LoginTime: string | null
  LoginIP: string | null
  LoginMAC: string | null
  LoginOS: string | null
  RegProtocol: string | null
  /** 唯一账号，如 300130000461_1_6 */
  UserAccount: string | null
  Name: string | null
  TradeAccount: string | null
  ShareHolders: string | null
  BranchNo: string | null
}

/** MySQL T_UserLog */
export interface UserLog {
  id: number
  Account: string | null
  LogTime: string | null
  LogIP: string | null
  LogMAC: string | null
  LogOS: string | null
  Info: string | null
}

/** Redis 键 acc-<账号>_<账号类型>_<渠道> 的值 */
export interface RedisAccount {
  id: number
  useraccount: string
  account: string
  accounttype: number
  maintype: number
  regtime: string
  logintime: string
  loginIP: string
  loginMAC: string
  loginOS: string
  name: string
  tradeAccount: string
  branchNo: string
  shareHolders: string
  regprotocal: number | boolean
  strategyserverid: number
  onlineflag: number
  syncflag: number | boolean
  traderserver?: TraderServer
}

/* ---------------------------------- Redis --------------------------------- */

export type RedisValueType = 'string' | 'list' | 'set' | 'zset' | 'hash' | 'stream' | 'ReJSON-RL'

export interface RedisKeyInfo {
  key: string
  type: RedisValueType
  /** TTL 秒，-1 表示永不过期 */
  ttl: number
  /** 长度/条目数，非容器类型为 undefined */
  length?: number
}

export interface RedisInstance {
  id: string
  name: string
  host: string
  port: number
  db: number
}

export interface RedisStreamGroup {
  name: string
  consumers: number
  pending: number
  'last-delivered-id': string
  lag?: number
}

export interface RedisStreamInfo {
  key: string
  length: number
  groups: RedisStreamGroup[]
}

export interface RedisStreamEntry {
  id: string
  fields: Record<string, string>
}

export interface RedisPubSubChannel {
  name: string
  /** 订阅者数量 */
  subscribers: number
}

/* -------------------------------- 协议报文 -------------------------------- */

/** 客户端协议清单（来自 请求接口字段.txt 的 9 个协议） */
export interface ProtocolDef {
  name: string
  title: string
  category: '账号类' | '条件单类'
  desc: string
}

export const PROTOCOLS: ProtocolDef[] = [
  { name: 'acc_sign', title: '账号签署', category: '账号类', desc: '为一个云条件单账号完成协议签署' },
  { name: 'acc_query', title: '账号查询', category: '账号类', desc: '查询账号是否已签署及资料' },
  { name: 'acc_update', title: '更新账号信息', category: '账号类', desc: '更新账号资料与股东号' },
  { name: 'logout', title: '账号登出', category: '账号类', desc: '云条件单账号登出' },
  { name: 'create', title: '创建云条件单', category: '条件单类', desc: '新建一个云条件单' },
  { name: 'query', title: '查询云条件单', category: '条件单类', desc: '按账号与日期查询条件单' },
  { name: 'modify', title: '修改云条件单', category: '条件单类', desc: '修改已存在的条件单' },
  { name: 'set', title: '运行/停止', category: '条件单类', desc: '启停指定条件单' },
  { name: 'remove', title: '删除云条件单', category: '条件单类', desc: '删除条件单' },
]

/** 中台 → 策略平台的 MsgType */
export const MSG_TYPES = {
  4: 'create 创建条件单',
  8: 'remove 删除条件单',
  11: 'modify 修改条件单',
  17: 'pwdUpdate 密码更新',
  18: 'account 用户信息',
} as const

/* -------------------------------- 监控统计 -------------------------------- */

/** 性能压测指标（口径来自现有测试工具） */
export interface PerfMetrics {
  /** 每秒请求数 */
  qps: number
  /** 每秒发送字节数 */
  sendBytesPerSec: number
  /** 每秒接收字节数 */
  recvBytesPerSec: number
  /** 平均响应时间（微秒） */
  avgLatencyUs: number
  /** 客户端 CPU 利用率（0~1） */
  cpuUsage: number
  /** 已发送总数 */
  totalSent: number
  /** 成功数 */
  totalOk: number
  /** 失败数 */
  totalFail: number
}

export interface TimeSeriesPoint {
  /** 时间戳（毫秒）或时间字符串 */
  time: number | string
  value: number
}
