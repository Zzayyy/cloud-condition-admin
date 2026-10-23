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
  /** 是否启用（只读展示，本页不提供启停） */
  enable: boolean
  /** 最后活跃时间（字符串形式） */
  'last-active-time': string
  lastActiveTimeLong: number
  /**
   * 来源实例（如 `192.168.1.136/db1`）。
   *
   * 现场同一个 `traderserver` 键在多个实例上**各存一份且内容不同**
   * （136 db0 三条、136 db1 七条、137 db1 四条），
   * 所以必须能看出这一行是从哪个库读到的。
   *
   * ⚠️ 列表已按 `mac` 归并：这里给的是**活跃时间最新**的那份所在的库。
   */
  instance?: string
  /**
   * 同一个 `mac` 在几个实例的注册表里出现过。
   *
   * `1` = 只有一份；`>1` = 另有 `copies-1` 份旧注册。
   * （不归并的话同一台机器会被列 3~5 遍，「台数」直接是错的：现场 14 条只有 4 台。）
   */
  copies?: number
  /** 其余副本的来源实例（不含被保留的那个） */
  otherInstances?: string[]
  /**
   * 在该实例数组里的下标。
   *
   * 现场 `id` 会重复（136 db1 的 7 条里有 4 个 `id=0`），
   * 只有 `(instance, arrayIndex)` 能唯一定位一行 —— 表格的 row-key 用它。
   */
  arrayIndex?: number
  /** 后端按心跳阈值算好的在线判定（页面本地的 isServerOnline 会再按当前时间算一次） */
  online?: boolean
  /** 心跳年龄（秒），后端在响应时刻算好 */
  ageSec?: number | null
}

/**
 * 策略服务器（Redis JSON 键 strategy-srv 中的元素）。
 *
 * ⚠️ `usecount` 现场**全部为 0**（该字段未上报，策略服务器当前未开放）——
 * 不代表「策略平台没有承载条件单用户」。页面已在列头标注这件事。
 */
export interface StrategyServer {
  id: number
  ip: string
  mac: string
  /** 该策略服务器上运行条件单用户的数量。⚠️ 现场未上报，全为 0 */
  usecount: number
  /** 上线时间 */
  'login-time': string
  'last-active-time': string
  lastActiveTimeLong: number
  /** 是否启用（只读展示，本页不提供启停） */
  enable: boolean
  /* ---- 非契约的排查字段（后端 meta 之外的补充） ---- */
  /** 后端按心跳阈值算好的在线判定 */
  online?: boolean
  /** 距上次心跳秒数 */
  ageSec?: number | null
  /** 来源实例（现场 136 db0 / 136 db1 各一份且内容不同） */
  instance?: string
  /** 在该实例数组里的下标 —— id 会重复（现场都是 0），只有它+实例能唯一定位一行 */
  arrayIndex?: number
  /**
   * 同一个 `mac` 在几个实例的注册表里出现过。
   *
   * 列表已按 `mac` 归并 —— 否则同一台机器会被列多遍、「节点总数」也是错的
   * （现场 2 条只对应 1 台）。
   */
  copies?: number
  /** 其余副本的来源实例（不含被保留的那个） */
  otherInstances?: string[]
}

/** 策略服务器列表的附加信息（后端 meta，非表格契约字段） */
export interface StrategyServerListMeta {
  /** 全量在线数（不是当前页） */
  onlineCount: number
  offlineCount: number
  /** 全量节点总数 = **按 mac 归并后的台数**（现场 2 条 = 1 台） */
  total: number
  /** 摊平后的原始条数（未去重）；`entryTotal - total` = 重复注册的份数 */
  entryTotal: number
  /** 全量 usecount 合计 */
  usecountTotal: number
  /** 全量 usecount 最大值 —— 进度条的分母 */
  usecountMax: number
  offlineAfterSec: number
  registryCopies: number
  registryKey: string
  redisInstances: string[]
  redisUnavailable: string[]
  /** 注册表里最新那条心跳距今多少秒；一条都没有时为 null */
  newestAgeSec: number | null
  /** true = 注册表已超过阈值没变化（现场策略服务器未开放就是这种） */
  registryStale: boolean
  staleRegistryAfterSec: number
  /** 后端给的如实说明（策略服务器未开放 / usecount 未上报） */
  note: string
}

/** 策略服务器列表返回 */
export interface StrategyServerPage {
  list: StrategyServer[]
  total: number
  page: number
  pageSize: number
  meta: StrategyServerListMeta
}

/**
 * 数据中台「节点」。
 *
 * ⚠️ **一行 = 一台 Redis 实例，不是一台中台。**
 * 中台在 Redis 侧**没有节点清单键**（委托服务器有 `traderserver`，中台没有对应物），
 * 所以只能按实例分；`online` 的含义是「这台 Redis 上近期有没有中台在通信」，
 * **不等于**「这台机器上跑着中台」——现场中台进程在 137，连的却是 136 的 Redis。
 *
 * ⚠️ 原来这里有 `mac` / `lastActiveTime` 两个字段：**Redis 侧根本没有这两个数据源**
 * （心跳报文只有 `{"level":2,"role":1}`，没有 id/ip/mac），
 * 留着只会显示成永远空白的列，让人以为「没读到」。现已删除，页面也不再显示这两列。
 */
export interface DatahubNode {
  /** 实例标识，就是主机地址（与 Redis 管理页同一口径） */
  id: string
  ip: string
  port: string
  /**
   * 该实例上有没有中台在通信。
   *
   * **`null` = 未知**（订阅关闭 / 连不上 / 还没收到第一条心跳），
   * 与 `false`（确认离线）是两件事 —— 对专门看「活着吗」的页面，
   * 把「我们没在看」显示成「它离线了」是最危险的错。
   */
  online: boolean | null
  /** 状态原文：ONLINE / OFFLINE / UNKNOWN */
  state: 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
  /** 距上次心跳秒数；从未收到为 null */
  lastBeatAgeSec: number | null
  /** 累计收到的心跳条数；0 = 一条都没收到 */
  beatCount: number
  /** 该实例的心跳订阅是否正常建立 */
  subscribed: boolean
  /** 订阅失败原因（没有则 null） */
  subscribeError: string | null
  /** 各 db 统计（键数 / unique_id 水位 / stream 规模） */
  dbs: DatahubDbStat[]
  dbKeysTotal: number
  entryTotal: number
  streamTotal: number
  /** Pub/Sub 频道数（实例级，与 db 无关） */
  channels: number
}

/** 一个 db 的统计 */
export interface DatahubDbStat {
  db: number
  /** 键数（DBSIZE 口径）。⚠️ 键数 ≠ 条目数 */
  keys: number
  /** 该 db 的 unique_id 水位；该 db 没有这个键时为 **null**（0 是真实水位，不能混用） */
  uniqueId: number | null
  /** 该 db 的 stream 条目合计 */
  entryTotal: number
  streamTotal: number
}

/**
 * 频道扫描结果。
 *
 * 分两部分是为消除**观测者效应**：本后台为判定中台心跳而常驻订阅了
 * `datahub_beat_1`，而 Redis 的 `PUBSUB CHANNELS` 只列「有订阅者的频道」——
 * 我们一订阅，那个频道就从「不存在」变成「存在」。
 */
export interface DatahubChannelScan {
  /** 真实存在的频道（订阅者数已扣除本后台自己那一份） */
  channels: RedisPubSubChannel[]
  /**
   * **只因为我们订阅才出现**的频道（如现场 137 原本 0 个频道）。
   *
   * 单列出来而不是混进 `channels`，让「这个实例上其实没有频道」这个事实
   * 仍然看得见；也不是悄悄丢掉，否则会让人以为我们没在听。
   */
  selfOnlyChannels: string[]
}

/**
 * 中台实例列表的附加信息（后端 meta）。
 *
 * `unknownCount` 必须与 `offlineCount` 分开：合并会失去「我们没在看」这个信号。
 */
export interface DatahubNodeListMeta {
  total: number
  onlineCount: number
  offlineCount: number
  /** **未知**实例数（观测不到，不等于离线） */
  unknownCount: number
  heartbeatEnabled: boolean
  /** 实际订阅的频道名 —— 用来确认「听的是对的那一套频道」 */
  heartbeatChannels: string[]
  offlineAfterSec: number
  observedInstances: number
  redisInstances: string[]
  redisUnavailable: string[]
  /** 后端给的如实说明（「节点=Redis 实例」这件事） */
  note: string
}

/** 中台实例列表返回 */
export interface DatahubNodePage {
  list: DatahubNode[]
  total: number
  page: number
  pageSize: number
  meta: DatahubNodeListMeta
}

/**
 * 流条目分页结果。
 *
 * ⚠️ `truncatedWindow` 是本接口最重要的字段：现场入口流有 1400 万条，
 * 只在**尾部窗口**内取，所以「查不到」**不等于**「不存在」。
 */
export interface DatahubEntryPage {
  list: RedisStreamEntry[]
  total: number
  page: number
  pageSize: number
  /** 本次是在最近多少条里查的 */
  windowSize: number
  /** 该流真实总长度（XLEN） */
  streamLength: number
  /** true = 只查了尾部窗口，窗口外可能还有数据 */
  truncatedWindow: boolean
  instance: string
  db: number
  key: string
}

/** 按 request_id 追踪的结果 */
export interface DatahubTrace {
  requestId: string
  hits: Array<{
    instance: string
    db: number
    stream: string
    entryId: string
    fields: Record<string, string>
  }>
  /** 在哪些 (实例/db/流) 的尾部窗口里找过 —— 便于判断「确实没有」还是「窗口太小」 */
  searched: string[]
  windowSize: number
  truncatedWindow: boolean
  found: boolean
}

/** 委托服务器节点列表的附加信息（后端 meta，非表格契约字段） */
export interface TraderServerListMeta {
  /** 读到注册表的份数（每存在该键的实例算一份） */
  registryCopies: number
  /** 摊平后的条目总数（**未去重**，即注册表原始记录数） */
  entryTotal: number
  /**
   * 按 `mac` 归并后的**台数**。
   *
   * ⚠️ 离线数、卡片里的"总台数"都要用这个，不能用 `entryTotal` ——
   * 后者会把同一台机器在多个库里的历史残留各算一次。
   */
  uniqueTotal: number
  /** 其中在线数（后端按 offlineAfterSec 判定；按归并后的台数统计） */
  onlineCount: number
  /** 心跳阈值（秒）——用于自检与前端常量是否一致 */
  offlineAfterSec: number
  configuredInstances: number
  redisInstances: string[]
  /** 非空表示列表可能不完整（不是数据没有，是我们没连上） */
  redisUnavailable: string[]
  registryKey: string
  onlineChannel: string
  beatChannel: string
  offlineChannel: string
}

/**
 * 委托服务器节点列表返回。
 *
 * 比通用 `PageResult` 多一个 `meta` —— 这个键跨三个实例且大多是陈旧残留，
 * 「一共读了几份、有没有连不上」必须能传到页面上。
 */
export interface TraderServerPage {
  list: TraderServer[]
  total: number
  page: number
  pageSize: number
  meta: TraderServerListMeta
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

/**
 * 云条件单。
 *
 * 数据**只来自 MySQL** 的 `T_Conditional` / `T_Conditional_his` / `V_Conditional`。
 *
 * ⚠️ 2026-10-22 从「Redis + MySQL 双源」改回「纯 MySQL」：现场确认
 * 「MySQL 和 Redis 应该是一致的（不一致只会发生在收盘后未开盘）」，
 * 两边都读必然重复，而 Redis 数据分散在各账号的 `strategy-` 键里、读起来更麻烦。
 * 写操作（改状态）仍需读 Redis 定位目标编号，但那不在这个契约里。
 */
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

/**
 * 条件单状态变更的结果。
 *
 * ⭐ **本地（Redis + MySQL）永远都改**，四个档位说的是"通知策略平台"的结果：
 * - `success` —— 本地已改 + 平台回执明确成功
 * - `local_only` —— 本地已改 + **无需/未下发通知**（账号未连策略服务器等）。
 *   这是**正常路径**，不是失败。
 * - `timeout` —— 本地已改 + 已下发但没等到回执。平台状态未知，可能不一致。
 * - `business_failed` —— 本地已改 + 平台回执失败。**两边已经不一致**，要人工核对。
 */
export interface ConditionalWriteResult {
  /** 条件单号 */
  ref: string
  /** 用的协议：setByStrategy */
  protocol: string
  /** 目标策略服务器编号 */
  serverId: string | null
  /** 报文发到了哪条流 */
  targetStream: string
  /** 本次请求的 request_id（回执按它匹配） */
  requestId: string
  /** 写进流里的条目 id（排查用） */
  entryId: string | null
  /** success / business_failed / timeout / local_only */
  outcome: 'success' | 'business_failed' | 'timeout' | 'local_only'
  /** 回执里的错误码（无回执为 null） */
  errCode: number | null
  /** 回执原文 / 说明 */
  message: string
  /** 回执报文原文（排查用） */
  replyRaw: string | null
  /**
   * 本地同步时写到了哪个 Redis 实例，如 `192.168.1.136/db1`。
   * `null` 表示 Redis 里没定位到这条单（只改了 MySQL）。
   */
  sourceInstance: string | null
  /** 补充提示 */
  note: string | null
  /** 是否真的下发了协议（false = 只改了本地） */
  dispatched: boolean
  /** 本地同步结果（redis / mysql 分别怎么写），如 `redis=ok；mysql=ok（1 行）` */
  localSync: string | null
}

/** 条件单写操作的现状（`GET /strategy/conditionals/instructions`） */
export interface ConditionalWriteStatus {
  /** 状态变更是否已实现 */
  implemented: boolean
  /** 说明 */
  reason: string
  /** 所需权限点 */
  requiredPermission: string
  /** 是否支持新增（当前不支持） */
  createSupported: boolean
  /**
   * **当前是否可写**（`app.conditional.enabled`）。
   * 页面据此提前置灰按钮，而不是等点了才报"未启用"。
   */
  enabled: boolean
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

/**
 * 账号类型（T_User.AccType / 协议里的 AccountType）
 * 照 请求接口字段.txt：1 资金账号，7 客户号。
 */
export const ACCOUNT_TYPES: Record<number, string> = {
  1: '资金账号',
  7: '客户号',
}

/**
 * 渠道（T_User.MainType / 协议里的 AccAtt）
 * 照 请求接口字段.txt：0 股票，6 期权。
 *
 * ⚠️ 注意：T_User.MainType 的**列注释写的是**「0 股票账户 / 1 股票期权账户 / 2 期货账户」，
 * 但现场实际值 = 6（期权），且账号串形如 `010100011300_7_6`（FAccount_AccountType_AccAtt），
 * 与协议的 AccAtt 定义一致。**以实际数据为准**，不要按列注释做映射。
 */
export const ACC_ATTS: Record<number, string> = {
  0: '股票',
  6: '期权',
}

/** 市场（Shareholders[].ExchangeNum） */
export const EXCHANGE_NUMS: Record<number, string> = {
  1: '上海',
  2: '深圳',
}

/** 股东号（账号上的 Shareholders 数组元素） */
export interface Shareholder {
  /** 股东号 */
  SAccount: string
  /** 市场：1 上海，2 深圳 */
  ExchangeNum: number
}

/** 登录日志按账号聚合（对应 SQL: GROUP BY Account） */
export interface AccountLogStat {
  Account: string
  /** 日志条数 */
  count: number
  /** 最早一条 */
  firstTime: string | null
  /** 最近一条 */
  lastTime: string | null
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

/**
 * 「用户账号」页用的聚合行：把 MySQL 的 T_User 与 Redis 的 acc-* 合并展示。
 *
 * 两边字段有重叠也有差异（Redis 侧多了 onlineflag / syncflag / strategyserverid /
 * traderserver，MySQL 侧多了 Passwd），页面上要能看出「哪边有、哪边没有」。
 */
export interface AccountRow {
  /** 唯一账号，如 300130000461_1_6 */
  account: string
  /** 用户账号（FAccount） */
  useraccount: string
  /** 客户名称 */
  name: string | null
  /** 账号类型：1 资金账号 7 客户号 */
  accounttype: number | null
  /** 渠道：0 股票 6 期权 */
  accatt: number | null
  tradeAccount: string | null
  branchNo: string | null
  regtime: string | null
  logintime: string | null
  loginIP: string | null
  loginMAC: string | null
  loginOS: string | null
  shareHolders: Shareholder[]
  /** 是否已签署协议 */
  signed: boolean
  /** 该账号是否有 Redis 实时对象 */
  hasRedis: boolean
  /** Redis 侧：在线标志 */
  onlineflag?: number
  /** Redis 侧：同步标志 */
  syncflag?: number | boolean
  /** Redis 侧：分配到哪台策略服务器 */
  strategyserverid?: number
  /** Redis 侧：分配到哪台委托服务器 */
  traderserver?: TraderServer
  /** MySQL 侧主键 */
  userId?: number
}

/* ---------------------------------- Redis --------------------------------- */

export type RedisValueType = 'string' | 'list' | 'set' | 'zset' | 'hash' | 'stream' | 'ReJSON-RL'

export interface RedisKeyInfo {
  key: string
  type: RedisValueType
  /**
   * TTL 毫秒：-1 永不过期，-2 键不存在（PTTL 语义）。
   * 用毫秒是因为现场有 4 秒 / 14 秒这种短 TTL 的锁键，秒级精度会丢信息。
   */
  pttl: number
  /** 长度/条目数，非容器类型为 undefined */
  length?: number
  /** 该键由哪个业务模块使用（按命名规律推断，用于分组显示） */
  domain?: RedisKeyDomain
}

/**
 * 键的业务归属 —— 按现场实测的命名规律推断，用于「按模块分组」。
 * 仅作归类参考，以 key 的真实前缀为准。
 */
export type RedisKeyDomain =
  | 'account' // acc-*
  | 'strategy' // strategy-*、strategy-srv、strategysrv-*
  | 'trade' // traderserver、WT-*
  | 'stream' // DataHub_*_stream、ST-*
  | 'lock' // strategy_server_lock_*
  | 'meta' // unique_id 等
  | 'other'

export interface RedisInstance {
  id: string
  name: string
  host: string
  port: number
  db: number
}

/**
 * Redis 实例概览（对应 `INFO` 的常用字段 + 各 db 键数）。
 *
 * 现场实测值得盯的两个字段：
 *  - `maxmemory` = 0 且 `maxmemoryPolicy` = noeviction → **内存无上限**，
 *    写满会直接报错而不是淘汰旧数据。
 *  - `usedMemoryPeak` 可能远大于当前值（137 曾达 12.45G，当前 1.47G）。
 */
export interface RedisInstanceInfo {
  host: string
  port: number
  version: string
  mode: string
  os: string
  /** 运行时长（秒） */
  uptimeInSeconds: number
  connectedClients: number
  usedMemory: number
  usedMemoryHuman: string
  usedMemoryPeakHuman: string
  /** 0 表示无上限 */
  maxmemory: number
  maxmemoryPolicy: string
  totalCommandsProcessed: number
  keyspaceHits: number
  keyspaceMisses: number
  /** 各 db 的键数（只含有键的 db） */
  dbKeys: Array<{ db: number; keys: number }>
}

/* -------------------------------- MySQL --------------------------------- */

/**
 * 单个 Redis 键的内容。
 * 按类型返回不同结构 —— 页面据此选渲染方式（JSON / 表格 / 原文）。
 */
export interface RedisKeyDetail {
  key: string
  type: RedisValueType
  pttl: number
  length?: number
  /** string 类型的原始值 */
  value?: string
  /** ReJSON-RL / hash 等结构化内容（已解析或格式化后的字符串） */
  json?: string
  /** stream 的最近条目（最多若干条） */
  entries?: Array<{ id: string; fields: Record<string, string> }>
  /** 集合类类型（list/set/zset）的成员 */
  members?: string[]
  /** 内存占用（MEMORY USAGE，字节） */
  memoryUsage?: number
  /** 编码方式（OBJECT ENCODING，如 listpack / quicklist / stream） */
  encoding?: string
}

/** 表/视图摘要（对应 information_schema.TABLES） */
export interface MysqlTable {
  name: string
  /** BASE TABLE / VIEW */
  type: string
  engine: string | null
  /**
   * information_schema 报告的近似行数。
   * ⚠️ **不准**（实测 T_StrategyLog 报 16566、实际 17430），
   * 需要精确值时用 `exactRows`。
   */
  approxRows: number | null
  /** 真实行数（COUNT(*)），仅对小表自动算；大表由后端按需提供 */
  exactRows?: number | null
  dataLength: number | null
  indexLength: number | null
  comment: string | null
}

/** 字段定义（对应 information_schema.COLUMNS） */
export interface MysqlColumn {
  name: string
  /** 完整类型，如 varchar(45) / binary(1) / datetime */
  columnType: string
  nullable: boolean
  key: string
  defaultValue: string | null
  comment: string | null
  /** 是否自增 */
  extra?: string
}

/** 表结构 */
export interface MysqlTableSchema {
  name: string
  columns: MysqlColumn[]
  indexes: Array<{
    name: string
    unique: boolean
    columns: string[]
  }>
  /** SHOW CREATE TABLE 的原文 */
  ddl: string
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
  /** 所属实例（数据中台页面要跨 136/137 展示） */
  instance?: string
  db?: number
  /** 流的角色分类 */
  role?: StreamRole
  /**
   * 最后一条**保留中**条目的 ID（形如 `1791946676151-1`）。
   * Stream 条目 ID 前一段是毫秒时间戳，可直接当时间用。
   */
  lastEntryId?: string | null
  /**
   * 最后一条条目距今多少秒 —— 回答「这条流还在进数据吗」。
   *
   * ⚠️ 这是本页很有用的一个数：实测 137 db0 的 `DataHub_req_stream`
   * 最后一条在 **24 小时前**、`ST-15` 在 **15 天前**，
   * 说明那 1400 万条是**历史堆积**而非当前流量。
   * 只看 XLEN 会误判成「流量很大」。
   *
   * 空流为 `null`（不返回 0 冒充「刚刚」）。
   */
  lastEntryAgeSec?: number | null
}

/**
 * 流的角色分类 —— 照现场实测的命名规律：
 *   DataHub_req_stream    委托/策略服务器 → 数据中台（入口）
 *   DataHub_reply_stream  数据中台回包
 *   ST-<编号>             中台 → 策略平台（下发）
 *   ST-<编号>-reply       策略平台回执
 *   WT-<编号>             中台 → 委托服务器（下发）
 *   WT-<编号>-reply       委托服务器回执
 */
export type StreamRole = 'ingress' | 'reply' | 'downstream' | 'ack' | 'other'

/** 服务端类型（见 DataHub_req_stream 的 server_type 字段） */
export const SERVER_TYPES: Record<string, string> = {
  WT: '委托服务器',
  ST: '策略服务器',
  STTEST: '策略服务器（测试）',
}

/** 入口/下发报文的信封字段（照现场实测的字段名） */
export interface StreamEnvelope {
  request_id?: string
  server_id?: string
  server_type?: string
  reply_req_stream?: string
  reply_reply_stream?: string
  task?: string
}

export interface RedisStreamEntry {
  id: string
  fields: Record<string, string>
}

export interface RedisPubSubChannel {
  name: string
  /** 订阅者数量（已扣除本后台自己的订阅，否则会永远多 1） */
  subscribers: number
  /** 频道分类 */
  group?: PubSubGroup
  instance?: string
  /**
   * 是否**只有本后台在订阅**。
   *
   * 本后台为判定中台心跳而常驻订阅了 `datahub_beat_1`，
   * 所以这个标记用于区分「真有人听」与「只有我们在听」——
   * 两者含义完全不同，不能静默合并。
   */
  selfSubscribed?: boolean
}

/**
 * 频道分组 —— 现场实测 136 上共 14 个，**全部带 `_1` 后缀**：
 *   tradeserver_*      委托服务器上线/心跳/下线
 *   strategyserver_*   策略服务器上线/心跳/下线
 *   datahub_*          中台自身心跳/上下线/选取/点名
 *   WT-*-<mac>         中台定向发给某个委托服务器的专用频道
 * 137 上实测 **0 个频道**。
 */
export type PubSubGroup = 'trade' | 'strategy' | 'datahub' | 'directed' | 'other'

/* -------------------------------- 协议报文 -------------------------------- */

/** 客户端协议清单（来自 请求接口字段.txt 的 9 个协议） */
export interface ProtocolDef {
  name: string
  title: string
  category: '账号类' | '条件单类'
  desc: string
  /** 文档里注明「此协议需要进行加密并编码」 */
  encrypted?: boolean
  /** 现场是否已有可用的测试样例 */
  sampleAvailable?: boolean
}

export const PROTOCOLS: ProtocolDef[] = [
  { name: 'acc_sign', title: '账号签署', category: '账号类', desc: '为一个云条件单账号完成协议签署', encrypted: true, sampleAvailable: true },
  { name: 'acc_query', title: '账号查询', category: '账号类', desc: '查询账号是否已签署及资料', encrypted: true, sampleAvailable: true },
  { name: 'acc_update', title: '更新账号信息', category: '账号类', desc: '更新账号资料与股东号', encrypted: true, sampleAvailable: true },
  { name: 'logout', title: '账号登出', category: '账号类', desc: '云条件单账号登出', sampleAvailable: true },
  { name: 'create', title: '创建云条件单', category: '条件单类', desc: '新建一个云条件单', sampleAvailable: true },
  { name: 'query', title: '查询云条件单', category: '条件单类', desc: '按账号与日期查询条件单', sampleAvailable: true },
  { name: 'modify', title: '修改云条件单', category: '条件单类', desc: '修改已存在的条件单', sampleAvailable: true },
  { name: 'set', title: '运行/停止', category: '条件单类', desc: '启停指定条件单', sampleAvailable: true },
  { name: 'remove', title: '删除云条件单', category: '条件单类', desc: '删除条件单', sampleAvailable: true },
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

/**
 * 可画趋势的**指标键**（供「消息总线」的趋势卡使用）。
 *
 * 只保留「积压与消费健康」三类 —— 这正是消息总线那一页回答的问题。
 * 内存类指标归属 Redis 管理页，不在这里。
 */
export type TrendMetricKey =
  /** 入口流积压条目数（DataHub_req_stream 的 XLEN 合计） */
  | 'ingressBacklog'
  /** 消费组 lag 合计（已写入未投递） */
  | 'consumerLag'
  /** pending 合计（已投递未确认） */
  | 'consumerPending'

/** 时序指标点（供 ECharts） */
export interface TimeSeriesPoint {
  /** 时间戳（毫秒）或时间字符串 */
  time: number | string
  value: number
}

/**
 * 趋势序列（`/monitor/series` 的返回）。
 *
 * ⚠️ 关键字段是 `historyAvailable`：现场**没有时序库**，所以只有
 * 「能从 Redis 现有数据回溯出来」的指标才有曲线（条目到达速率 ——
 * 靠 Stream 条目 ID 里的毫秒时间戳）。`lag` / `pending` 只有当前快照，
 * 此时 `points` 是**空数组**、`currentValue` 有值、`note` 说明原因。
 *
 * 页面必须据此显示空态 + 说明，**不能**拿 `currentValue` 去凑一条曲线。
 */
export interface TimeSeriesView {
  metric: string
  label: string
  unit: string
  /** 无历史时为空数组 */
  points: TimeSeriesPoint[]
  /** 是否是**真实历史**（false 表示该指标无法回溯） */
  historyAvailable: boolean
  /** 当前值（即便没有历史也一定有） */
  currentValue: number | null
  source: string
  /** 需要向用户说明的限制 */
  note: string | null
  windowMin: number
  /** 每个点代表多少秒（无历史时为 0） */
  bucketSec: number
}

/** 可选的趋势指标（含「有没有历史」，页面不用猜） */
export interface MonitorMetricOption {
  key: string
  label: string
  unit: string
  historyAvailable: boolean
}

/* --------------------------------- 总览页 --------------------------------- */

/**
 * 服务器节点健康度。
 *
 * ⚠️ 三类**必须分开看**，且数据中台有 `datahubUnknown`：
 * 三类「离线」的含义完全不同 ——
 * 委托服务器是真有活的；策略服务器是**服务未开放**（离线是必然）；
 * 数据中台是**观测不到**（不等于离线）。
 * 合并成一个「离线 N 个」会让人误判成「大面积宕机」。
 */
export interface DashboardNodeSummary {
  tradeTotal: number
  tradeOnline: number
  strategyTotal: number
  strategyOnline: number
  datahubTotal: number
  datahubOnline: number
  /** 数据中台里**观测不到**的实例数（订阅关闭/连接失败/还没收到心跳） */
  datahubUnknown: number
  /** 策略服务器注册表是否已陈旧（服务未开放时为 true） */
  strategyRegistryStale: boolean
  /** 数据中台心跳订阅是否开启（关掉时在线数不可信） */
  datahubHeartbeatEnabled: boolean
}

/**
 * 消息总线汇总。
 *
 * ⚠️ `staleStreams` 与 `ingressBacklogAgeSec` 是最该被看见的两个字段：
 * 它们回答「这些积压是活的还是死的」。
 * 只看 `ingressBacklog`（上千万条）会把一潭死水读成「流量很大」。
 */
export interface DashboardStreamSummary {
  count: number
  ingressBacklog: number
  consumerLag: number
  pending: number
  /** **没有消费组**的流数 —— 写入后无人投递，最危险的一类 */
  groupLess: number
  /** 已超过 1 小时没有新条目的流数 */
  staleStreams: number
  /** 入口流里**最新**那条条目距今多少秒；无数据时为 null */
  ingressBacklogAgeSec: number | null
  entryTotal: number
}

/**
 * 总览页的汇总数据。
 *
 * **为什么单独出一个汇总接口、而不是让前端把各业务页的列表拼起来**：
 * 列表接口都是**分页**的，前端拿列表自己数只能得到「当前这一页」，
 * 很容易被当成全量读 —— 这类误读在运维场景里会直接导致误判
 * （项目里已经踩过这个坑，见 README 的汇总口径说明）。
 * 所以汇总一律由后端用一个接口给出全量口径，前端只负责展示。
 */
export interface DashboardSummary {
  /** 服务器节点健康度（三类节点分开统计） */
  nodes: DashboardNodeSummary
  /** 条件单（V_Conditional 并集视图） */
  conditional: {
    total: number
    /** Mode = 1 运行中 */
    running: number
    /** Status = 1 已触发 */
    triggered: number
  }
  /** 委托单（T_Order + T_Order_his） */
  order: {
    total: number
    /** 已产生成交的委托单数（按 OrderNo 关联成交表，真实可算） */
    filled: number
  }
  /** 成交回报（T_Trade + T_Trade_his） */
  trade: {
    total: number
    /** 成交量合计 */
    volume: number
  }
  /** 云单账号（MySQL 与 Redis 合并口径） */
  account: {
    total: number
    online: number
    /** 只在 Redis 有实时对象的账号数 */
    redisOnly: number
    /** 只在 MySQL 有的账号数 */
    mysqlOnly: number
  }
  /** 消息总线（Redis Stream） */
  stream: DashboardStreamSummary
  /** 现场当前是否没有在发数据 */
  idle: boolean
  /** 沉默的原因（null = 一切正常） */
  idleReason: string | null
  generatedAt: string
}

/** 数据来源的一行（总览页的「数据来源与规模」表） */
export interface DashboardSourceRow {
  name: string
  kind: 'redis' | 'mysql'
  kindLabel: string
  scale: string
  /** 数值口径，便于排序或后续接后端时对齐 */
  hint: string
}

/** 近 N 天的一条趋势（条件单新增 / 委托 / 成交三条线共用） */
export interface DashboardDailyRow {
  /** YYYY-MM-DD */
  date: string
  conditional: number
  order: number
  trade: number
}

/**
 * 「按天计数」的通用形状（各模块的趋势图都用它）。
 *
 * 对应后端的 `DailyCreatedRow` / `DailyOrderRow` / `DailyLogRow` ——
 * 那三个 record 的字段完全一样（`date` + `count`），所以前端也共用一个类型。
 */
export interface DailyCountRow {
  /** YYYY-MM-DD */
  date: string
  count: number
}

/* ------------------------- 全量汇总（卡片用，非本页口径） ------------------------- */

/*
 * 下面这几个 `*Stats` 是**后端全量口径**的汇总，取代原先「对当前页 rows
 * 做 filter / reduce」的算法。
 *
 * 为什么必须由后端算：卡片标签写的是「运行中」「成交金额」这种像全量的说法，
 * 而按当前页算出来的数字**翻一页就变**，且没有总数可对照 —— 会被读成总量。
 * 每个 Stats 都带 `totalCount`，它应与列表接口的 `total` 相等；
 * 两者不等就说明筛选条件在两边写岔了，联调时可直接据此发现。
 */

/** 条件单汇总（对应后端 ConditionalStats） */
export interface ConditionalStats {
  /** Mode = 1 */
  runningCount: number
  /** Status = 1 */
  triggeredCount: number
  /** Flag = 1 */
  pausedCount: number
  /** RunCount 求和 */
  totalRunCount: number
  /** 当前筛选下的总条数（应等于列表 total） */
  totalCount: number
}

/** 委托单汇总（对应后端 OrderStats） */
export interface OrderStats {
  buyCount: number
  sellCount: number
  abnormalCount: number
  /** OrderPrice × OrderVolumn 求和 */
  totalAmount: number
  totalCount: number
}

/** 成交汇总（对应后端 TradeStats） */
export interface TradeStats {
  totalCount: number
  totalVolumn: number
  totalAmount: number
  /** 均价 = totalAmount / totalVolumn（由后端算，不是各页均价的平均） */
  avgPrice: number
}

/** 按合约的成交量（对应后端 CodeCountRow） */
export interface CodeCountRow {
  code: string
  count: number
}

/** 按天的成交笔数与金额（对应后端 TradeDailyRow） */
export interface TradeDailyRow {
  /** YYYY-MM-DD */
  date: string
  /** 当天成交笔数 */
  count: number
  /** 当天成交金额（后端已把 varchar 的 Volumn 转 DECIMAL 求和） */
  amount: number
}

/** 策略日志汇总（对应后端 StrategyLogStats） */
export interface StrategyLogStats {
  okCount: number
  failCount: number
  /** 0-100 取整；状态列为空时为 0 且无意义 */
  successRate: number
  sourceCount: number
  totalCount: number
  /** Status 列是否整列为空 —— 为空时成功率不代表「全都失败」 */
  statusColumnEmpty: boolean
}

/** 按 Status 分组的计数（状态分布柱状图，全量口径） */
export interface StatusCountRow {
  /** null 表示这些行的 Status 是 NULL（现场整列如此） */
  status: string | null
  count: number
}

/** 管理账号汇总（全量口径，对应后端 AdminUserStats） */
export interface AdminUserStats {
  totalCount: number
  enabledCount: number
  disabledCount: number
  lockedCount: number
}

/** 操作审计汇总（全量口径，对应后端 AuditStats） */
export interface AuditStats {
  /** level = 'high' */
  highCount: number
  /** result = 'failure' */
  failureCount: number
  /** 去重后的操作人数 */
  operatorCount: number
  totalCount: number
}

/** 管理端登录日志汇总（全量口径，对应后端 LoginStats） */
export interface LoginStats {
  successCount: number
  failureCount: number
  totalCount: number
}

/**
 * 账号汇总（全量口径，对应后端 AccountService.AccountStats）。
 *
 * 口径：账号列表是「MySQL T_User 与 Redis acc-* 合并」的结果，
 * 所以这里的全量 = 合并并集经筛选后的全部行，与列表 `total` 同源。
 */
export interface AccountStats {
  totalCount: number
  signedCount: number
  onlineCount: number
  redisOnlyCount: number
  withShareholdersCount: number
  totalShares: number
  /** 已签署 且 有股东号（正常） */
  signedWithShares: number
  /** 已签署 但 无股东号 —— 协议签了却配不了单，最该跟进的一类 */
  signedNoShares: number
  /** 未签署 但 有股东号 */
  unsignedWithShares: number
  /** 未签署 且 无股东号 */
  unsignedNoShares: number
  /** 各市场股东号数量：key = ExchangeNum（1 上海 / 2 深圳） */
  marketShareCounts: Record<string, number>
}

/** 键类型 → 数量（对应后端 TypeCountRow） */
export interface RedisTypeCountRow {
  type: string
  count: number
}

/**
 * Redis 键扫描结果。
 *
 * 比通用 `PageResult` 多带几个字段（后端 `ScanResult` 会一并返回，前端此前忽略了）：
 *  - `dbSize`：该 db 的**真实键数**（DBSIZE），与 `total`（过滤后条数）不是一回事；
 *  - `truncated` / `scanLimit`：是否因服务端上限只扫了一部分；
 *  - `typeCounts`：类型分布，**基于本次扫描到的键**（受上限约束），不是全库。
 */
export interface RedisScanResult {
  list: RedisKeyInfo[]
  total: number
  page: number
  pageSize: number
  instance: string
  db: number
  dbSize: number
  truncated: boolean
  scanLimit: number
  typeCounts: RedisTypeCountRow[]
}

/** 总览页一次性返回的全部内容 */
export interface DashboardOverview {
  summary: DashboardSummary
  /** 数据来源与规模 */
  sources: DashboardSourceRow[]
  /** 近 14 天趋势 */
  daily: DashboardDailyRow[]
  /** 数据生成时间（页面据此显示「快照于 …」） */
  generatedAt: string
}

/* ---------------------------- 管理端：用户与权限 ---------------------------- */

/**
 * 后台管理端**自己的**账号体系。
 *
 * ⚠️ 与「账户与用户」模块的云单业务用户 `T_User` **完全无关**：
 * `T_User` 是客户的钱包账号（会挂在策略/委托服务器上跑条件单），
 * 这里是自己人登录后台用的账号。两套东西不要混在一个列表里。
 *
 * 后端落地时的表：`T_AdminUser` / `T_AdminRole` / `T_AdminRolePermission` /
 * `T_AdminLoginLog` / `T_AdminPasswordPolicy`（表名待与后端确认）。
 */
export interface AdminRole {
  id: number
  /** 角色编码，唯一，如 super_admin；建后不建议改（审计要靠它认人） */
  code: string
  name: string
  desc: string | null
  /** 内置角色：不可删除、不可改名，权限受保护（见菜单页说明） */
  builtin: boolean
  /**
   * 权限键集合，键的生成规则见 `src/config/permissions.ts`
   * （`menu:<菜单name>` / `action:<模块>:<动作>`，超管为 `['*']`）。
   */
  permissions: string[]
  /** 引用该角色的管理用户数（后端聚合，前端只读） */
  userCount: number
  createdAt: string | null
  updatedAt: string | null
}

/** 后台管理账号（`T_AdminUser`） */
export interface AdminUser {
  id: number
  /** 登录名，唯一 */
  username: string
  displayName: string | null
  /** 分配的角色 id（与 `AdminRole.id` 对应） */
  roleIds: number[]
  /** 启用状态：停用后不能登录 */
  enabled: boolean
  /** 是否因连续登录失败被锁定 */
  locked: boolean
  /** 当前连续失败次数（用于「还剩几次锁定」提示） */
  failedAttempts: number
  /** 是否需要下次登录强制改密 */
  mustChangePassword: boolean
  lastLoginTime: string | null
  lastLoginIP: string | null
  /** 累计成功登录次数 */
  loginCount: number
  createdAt: string | null
  updatedAt: string | null
  remark: string | null
}

/** 新建/编辑管理用户的请求体（密码只写不读，接口永不回显） */
export interface AdminUserPayload {
  username: string
  displayName?: string | null
  roleIds: number[]
  enabled: boolean
  remark?: string | null
  /** **仅新建时**必填明文密码；编辑时留空表示不改密码 */
  password?: string
}

/** 新建/编辑角色的请求体 */
export interface AdminRolePayload {
  code: string
  name: string
  desc?: string | null
  permissions: string[]
}

/** 管理端登录日志（`T_AdminLoginLog`）—— 与云单用户登录日志 `T_UserLog` 不是一张表 */
export interface AdminLoginLog {
  id: number
  username: string
  loginTime: string | null
  loginIP: string | null
  loginOS: string | null
  /** 登录是否成功；失败时 `info` 是原因 */
  success: boolean
  info: string | null
}

/**
 * 密码策略 + 登录失败锁定策略。
 *
 * 对应菜单待办里的「密码策略与登录失败锁定」：
 * 前端**只用它做即时校验提示**，真正的判定必须在后端做
 * （前端校验是体验，不是安全边界）。
 */
export interface PasswordPolicy {
  /** 最小长度 */
  minLength: number
  requireUpper: boolean
  requireLower: boolean
  requireDigit: boolean
  requireSymbol: boolean
  /** 连续失败达到该次数即锁定，0 表示不锁定 */
  maxFailedAttempts: number
  /** 锁定时长（分钟） */
  lockMinutes: number
  /** 密码有效期（天），0 表示永不过期 */
  expireDays: number
  /** 首次登录是否强制改密 */
  forceChangeOnFirstLogin: boolean
  /** 禁止与最近 N 次密码重复，0 表示不校验历史 */
  historySize: number
}

/** 一条密码策略校验结果（前端即时提示用） */
export interface PasswordCheckItem {
  key: string
  label: string
  passed: boolean
}

/**
 * 管理端操作审计（`T_AdminAudit`）。
 *
 * README 里点名的「高危操作必须写审计」就是这张表；
 * 危险级别不是给人看的装饰，导出/清理类操作要靠它筛。
 */
export interface AuditLog {
  id: number
  /** 操作人（管理端账号） */
  operator: string
  /** 动作键，如 action:storage-redis:delete-key，与 permissions.ts 的权限键同源 */
  action: string
  /** 动作中文名 */
  actionLabel: string
  /** 归属模块（一级菜单 name，如 storage） */
  module: string
  /** high=高危（删除/清理/下发协议），medium=普通写操作，low=只读与导出 */
  level: AuditLevel
  /** 目标对象，如键名 / 表名 / 条件单号 */
  target: string | null
  /** 结果 */
  result: 'success' | 'failure'
  /** 失败原因或补充说明 */
  message: string | null
  /** 请求来源 IP */
  ip: string | null
  /** 耗时（毫秒） */
  costMs: number | null
  /** 操作时间 */
  createdAt: string | null
}

export type AuditLevel = 'high' | 'medium' | 'low'

/** 审计动作的可选项（供筛选用，由后端返回或前端从 permissions.ts 推导） */
export interface AuditActionOption {
  action: string
  label: string
  module: string
  level: AuditLevel
}

/* --------------------------- 审计/登录的聚合结果 --------------------------- */

/**
 * 聚合口径说明（前后端必须一致，否则界面上「最近 14 天」会跟后端对不上）：
 *  - 聚合接口接收与列表接口**同一套** `from` / `to` 字符串（`YYYY-MM-DD HH:mm:ss`），
 *    含义是 **[from, to] 闭区间**；
 *  - 未传 `from`/`to` 时默认「最近 14 个自然日」（含今天）。
 *
 * 为什么单独出聚合接口、而不是让前端拿明细自己算：
 * 明细接口是**分页**的，前端硬凑只能统计「当前这一页」，
 * 会被当成全量读（这类误读在运维场景里很危险）。
 */
export interface AuditDailyRow {
  /** `YYYY-MM-DD` */
  date: string
  /** 当天高危操作数（level = high） */
  high: number
  /** 当天其余操作数（level = medium / low） */
  other: number
}

export interface LoginDailyRow {
  /** `YYYY-MM-DD` */
  date: string
  success: number
  failure: number
}

/** 通用「名称 → 计数」聚合行（动作分布、失败 IP Top 都用它） */
export interface NameValueRow {
  name: string
  value: number
}

/* ------------------------------ 登录鉴权 ------------------------------ */

/**
 * 登录失败的业务错误码。
 *
 * 前端按 **code** 分派提示文案，不去匹配 message 字符串 ——
 * 后端换一次措辞就会让前端的判断静默失效（这类坑项目里已经踩过）。
 *
 * ⚠️ `BAD_CREDENTIALS` 刻意把「账号不存在」与「密码错误」**合并**成同一个码：
 * 分开返回等于给攻击者一个**账号枚举**接口（能逐个试出哪些账号真实存在）。
 * 真实后端也必须这么合并，前端提示文案也要写成「账号或密码错误」。
 */
export const LOGIN_ERROR_CODES = {
  BAD_CREDENTIALS: 40101,
  /** 账号已停用 */
  DISABLED: 40102,
  /** 账号因连续失败被锁定 */
  LOCKED: 40103,
} as const

export type LoginErrorCode = (typeof LOGIN_ERROR_CODES)[keyof typeof LOGIN_ERROR_CODES]

/** 登录成功返回（`POST /system/auth/login`） */
export interface LoginResult {
  token: string
  username: string
  displayName?: string
  roles: string[]
  /** 权限键集合，见 `src/config/permissions.ts` */
  permissions: string[]
  /** 是否需要下次登录强制改密 */
  mustChangePassword?: boolean
}
