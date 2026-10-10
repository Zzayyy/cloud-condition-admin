import { api } from './http'
import type { PageQuery, PageResult } from './types'
import type {
  AccountLogStat,
  AccountRow,
  Conditional,
  DatahubNodeDetail,
  MysqlTable,
  MysqlTableSchema,
  OrderRecord,
  RedisAccount,
  RedisInstance,
  RedisInstanceInfo,
  RedisKeyDetail,
  RedisKeyInfo,
  RedisPubSubChannel,
  RedisStreamEntry,
  RedisStreamInfo,
  StrategyLog,
  StrategyServer,
  TradeRecord,
  TraderServer,
  UserLog,
} from './domain'

/**
 * 后端接口路径约定（全部待实现）。
 *
 * 命名按「资源 + 动作」，统一挂在 /api 下，后端可用任何语言实现。
 * 每个函数都对应一个具体的页面需求，不做无意义封装。
 */

/* ------------------------------ 策略平台管理 ------------------------------ */

export const strategyApi = {
  /** 策略服务器列表 */
  listServers: (query?: PageQuery) =>
    api.get<PageResult<StrategyServer>>('/strategy/servers', query),

  /** 条件单列表（T_Conditional + T_Conditional_his） */
  listConditionals: (query?: PageQuery) =>
    api.get<PageResult<Conditional>>('/strategy/conditionals', query),

  /** 条件单详情 */
  getConditional: (id: number) => api.get<Conditional>(`/strategy/conditionals/${id}`),

  /** 启停条件单（对应 set 协议） */
  setConditional: (id: number, running: boolean) =>
    api.post<void>(`/strategy/conditionals/${id}/state`, { running }),

  /** 删除条件单（对应 remove 协议） */
  removeConditional: (id: number) => api.delete<void>(`/strategy/conditionals/${id}`),

  /** 策略运行日志 */
  listLogs: (query?: PageQuery) => api.get<PageResult<StrategyLog>>('/strategy/logs', query),
}

/* ------------------------------ 委托服务器管理 ----------------------------- */

export const tradeApi = {
  listServers: (query?: PageQuery) =>
    api.get<PageResult<TraderServer>>('/trade/servers', query),

  /**
   * 启用/停用委托服务器。
   *
   * ⚠️ 现场 `traderserver` 键里 **id 是重复的**（多次上线累积，见 mock 注释），
   * 所以追加 mac 作为定位条件，避免误改到别的节点。
   */
  setServerEnabled: (id: number, mac: string, enable: boolean) =>
    api.post<void>(`/trade/servers/${id}/enable`, { mac, enable }),

  /** 账号 → 委托服务器分配关系 */
  listAssignments: (query?: PageQuery) =>
    api.get<PageResult<RedisAccount>>('/trade/assignments', query),

  listOrders: (query?: PageQuery) => api.get<PageResult<OrderRecord>>('/trade/orders', query),

  listTrades: (query?: PageQuery) => api.get<PageResult<TradeRecord>>('/trade/trades', query),

  /** 某委托号下的成交明细（委托 → 成交 下钻） */
  listTradesByOrder: (orderNo: string) =>
    api.get<TradeRecord[]>(`/trade/orders/${orderNo}/trades`),

  /** 某台委托服务器承载的账号 */
  listAccountsByServer: (serverId: number) =>
    api.get<RedisAccount[]>(`/trade/servers/${serverId}/accounts`),
}

/* -------------------------------- 数据中台 -------------------------------- */

export const datahubApi = {
  listNodes: (query?: PageQuery) => api.get<PageResult<DatahubNodeDetail>>('/datahub/nodes', query),

  /** Pub/Sub 频道总览（含订阅者数，用于判断「有没有人在听」） */
  listChannels: () => api.get<RedisPubSubChannel[]>('/datahub/channels'),

  /** 消息总线总览：跨实例的各流长度与消费组积压 */
  listStreams: (query?: PageQuery) => api.get<RedisStreamInfo[]>('/datahub/streams', query),

  /** 某个流的条目（分页/范围） */
  listStreamEntries: (key: string, query?: PageQuery) =>
    api.get<PageResult<RedisStreamEntry>>(`/datahub/streams/${encodeURIComponent(key)}/entries`, query),

  /** 按 request_id 串联一次请求的收发（入口 → 下发 → 回执） */
  traceRequest: (requestId: string) =>
    api.get<RedisStreamEntry[]>('/datahub/trace', { requestId }),
}

/* -------------------------------- 账户用户 -------------------------------- */

export const accountApi = {
  /**
   * 用户账号列表。
   * 返回的是 T_User（MySQL）与 acc-*（Redis）**合并后**的行，
   * 合并逻辑放后端做，前端不拼两个接口（见 README「前后端约定」）。
   */
  listAccounts: (query?: PageQuery) => api.get<PageResult<AccountRow>>('/account/accounts', query),

  /** 单个账号详情（含 MySQL 与 Redis 两侧） */
  getAccount: (account: string) => api.get<AccountRow>(`/account/accounts/${account}`),

  /** 账号在 Redis 中的实时状态（acc-* 键） */
  getRedisAccount: (account: string) => api.get<RedisAccount>(`/account/redis/${account}`),

  /** 登录日志（MySQL T_UserLog） */
  listLogs: (query?: PageQuery) => api.get<PageResult<UserLog>>('/account/logs', query),

  /** 登录日志按账号聚合（用于「登录频次」视图） */
  listLogStats: (query?: PageQuery) => api.get<PageResult<AccountLogStat>>('/account/logs/stats', query),
}

/* -------------------------------- 数据存储 -------------------------------- */

export const storageApi = {
  listRedisInstances: () => api.get<RedisInstance[]>('/storage/redis/instances'),

  /** 实例概览（对应 INFO：内存、客户端数、各 db 键数） */
  getRedisInstanceInfo: (instanceId: string) =>
    api.get<RedisInstanceInfo>('/storage/redis/info', { instanceId }),

  /**
   * 扫描键（后端必须用 SCAN 而非 KEYS，并强制条数上限）。
   *
   * ⚠️ 137 db0 有 457 万条 stream，全量扫描会拖垮实例 ——
   * 所以接口签名里没有「取全部」的选项。
   */
  scanKeys: (instanceId: string, db: number, query?: PageQuery) =>
    api.get<PageResult<RedisKeyInfo>>('/storage/redis/keys', { instanceId, db, ...query }),

  /** 单个键的内容（按类型返回不同结构） */
  getKey: (instanceId: string, db: number, key: string) =>
    api.get<RedisKeyDetail>('/storage/redis/key', { instanceId, db, key }),

  /** 删除键（高危，后端需二次确认 + 审计） */
  deleteKey: (instanceId: string, db: number, key: string) =>
    api.delete<void>('/storage/redis/key', { instanceId, db, key }),

  listPubSubChannels: (instanceId: string, db: number) =>
    api.get<RedisPubSubChannel[]>('/storage/redis/channels', { instanceId, db }),

  /* ------------------------------- MySQL ------------------------------- */

  /** 表/视图清单（含注释与近似行数） */
  listTables: (database: string) => api.get<MysqlTable[]>('/storage/mysql/tables', { database }),

  /** 单表结构：字段、索引、DDL */
  getTableSchema: (database: string, table: string) =>
    api.get<MysqlTableSchema>('/storage/mysql/schema', { database, table }),

  /**
   * 分页取表数据（**只读**）。
   * 后端必须限制 pageSize 上限（建议 ≤ 200）并禁止任意 SQL。
   */
  queryTable: (database: string, table: string, query?: PageQuery) =>
    api.get<PageResult<Record<string, unknown>>>('/storage/mysql/query', {
      database,
      table,
      ...query,
    }),

  /**
   * 只读 SQL 查询。
   * 后端**必须**校验语句为只读（拒绝 INSERT/UPDATE/DELETE/DROP/ALTER 等），
   * 并强制结果集上限。
   */
  runReadonlyQuery: (database: string, sql: string, limit?: number) =>
    api.post<PageResult<Record<string, unknown>>>('/storage/mysql/query', {
      database,
      sql,
      limit,
    }),
}

/* -------------------------------- 监控统计 -------------------------------- */

export const monitorApi = {
  /** 性能压测任务列表 */
  listPerfTasks: (query?: PageQuery) => api.get<PageResult<unknown>>('/monitor/performance/tasks', query),

  /** 破坏测试用例列表 */
  listDestroyCases: (query?: PageQuery) => api.get<PageResult<unknown>>('/monitor/destroy/cases', query),

  /** 触发破坏测试（高危） */
  startDestroyTask: (payload: { caseIds?: string[]; range?: [number, number]; mode: 'type1' | 'type2' }) =>
    api.post<{ taskId: string }>('/monitor/destroy/tasks', payload),

  /** 实时指标快照 */
  getRealtimeMetrics: () => api.get<Record<string, number>>('/monitor/realtime/metrics'),

  /** 时序指标（供 ECharts） */
  getTimeSeries: (metric: string, window?: string) =>
    api.get<{ time: number | string; value: number }[]>('/monitor/realtime/series', { metric, window }),
}

/* -------------------------------- 系统管理 -------------------------------- */

export const systemApi = {
  listConnections: () => api.get<unknown[]>('/system/connections'),
  saveConnection: (payload: unknown) => api.post<void>('/system/connections', payload),
  testConnection: (id: string) => api.post<{ ok: boolean; message: string }>(`/system/connections/${id}/test`),

  listAuditLogs: (query?: PageQuery) => api.get<PageResult<unknown>>('/system/audit', query),

  login: (payload: { username: string; password: string }) =>
    api.post<{ token: string; username: string }>('/system/auth/login', payload),

  logout: () => api.post<void>('/system/auth/logout'),

  profile: () => api.get<{ username: string; roles: string[] }>('/system/auth/profile'),
}
