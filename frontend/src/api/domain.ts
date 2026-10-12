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

/** 数据中台节点（Redis 侧没有节点清单，靠 datahub_* 频道 + unique_id 推导） */
export interface DatahubNode {
  id: number
  ip: string
  mac: string
  online: boolean
  lastActiveTime: string
}

/**
 * 节点详情：在基础节点上补充该实例的规模信息。
 * 这些值来自对 Redis 的只读探查（dbsize / XLEN / PUBSUB CHANNELS / unique_id）。
 */
export interface DatahubNodeDetail extends DatahubNode {
  /** 该实例已分配的唯一编号水位（唯一 string 键 unique_id，各 db 独立） */
  uniqueId?: number
  /** 该实例上的 Pub/Sub 频道数 */
  channels?: number
  /** 该实例上的 stream 数 */
  streams?: number
  /** 该实例的 stream 条目总量 */
  entryTotal?: number
  /** 是否启用（沿用 traderserver 那套 enable 约定；中台侧暂无此字段，预留） */
  enable?: boolean
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
  /** 订阅者数量 */
  subscribers: number
  /** 频道分类 */
  group?: PubSubGroup
  instance?: string
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

/* --------------------------------- 总览页 --------------------------------- */

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
  nodes: {
    /** 委托服务器：总数 / 在线数 */
    tradeTotal: number
    tradeOnline: number
    /** 策略服务器：总数 / 在线数 */
    strategyTotal: number
    strategyOnline: number
    /** 数据中台：总数 / 在线数 */
    datahubTotal: number
    datahubOnline: number
  }
  /** 条件单（T_Conditional） */
  conditional: {
    total: number
    /** Mode = 1 运行中 */
    running: number
    /** Status = 1 已触发 */
    triggered: number
  }
  /** 委托单（T_Order） */
  order: {
    total: number
    /** 已产生成交的委托单数（与成交表按 OrderNo 关联，真实可算） */
    filled: number
  }
  /** 成交回报（T_Trade） */
  trade: {
    total: number
    /** 成交量合计 */
    volume: number
  }
  /** 云单账号 */
  account: {
    total: number
    online: number
  }
  /** 消息总线（Redis Stream） */
  stream: {
    /** stream 条数 */
    count: number
    /** 入口流积压条目（DataHub_req_stream 的 XLEN 合计） */
    ingressBacklog: number
    /** 消费组 lag 合计 */
    consumerLag: number
    /** pending 合计 */
    pending: number
    /** **没有消费组**的流数量 —— 现场 137 的入口流就是这种，最危险 */
    groupLess: number
  }
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
