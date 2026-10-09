import { api } from './http'
import type { PageQuery, PageResult } from './types'
import type {
  Conditional,
  DatahubNode,
  OrderRecord,
  RedisAccount,
  RedisInstance,
  RedisKeyInfo,
  RedisPubSubChannel,
  RedisStreamEntry,
  RedisStreamInfo,
  StrategyLog,
  StrategyServer,
  TradeRecord,
  TraderServer,
  UserAccount,
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

  /** 启用/停用委托服务器 */
  setServerEnabled: (id: number, enable: boolean) =>
    api.post<void>(`/trade/servers/${id}/enable`, { enable }),

  /** 账号 → 委托服务器分配关系 */
  listAssignments: (query?: PageQuery) =>
    api.get<PageResult<RedisAccount>>('/trade/assignments', query),

  listOrders: (query?: PageQuery) => api.get<PageResult<OrderRecord>>('/trade/orders', query),

  listTrades: (query?: PageQuery) => api.get<PageResult<TradeRecord>>('/trade/trades', query),
}

/* -------------------------------- 数据中台 -------------------------------- */

export const datahubApi = {
  listNodes: (query?: PageQuery) => api.get<PageResult<DatahubNode>>('/datahub/nodes', query),

  /** 消息总线总览：各流长度与消费组积压 */
  listStreams: () => api.get<RedisStreamInfo[]>('/datahub/streams'),

  /** 某个流的条目（分页/范围） */
  listStreamEntries: (key: string, query?: PageQuery) =>
    api.get<PageResult<RedisStreamEntry>>(`/datahub/streams/${encodeURIComponent(key)}/entries`, query),

  /** 按 request_id 串联一次请求的收发 */
  traceRequest: (requestId: string) =>
    api.get<RedisStreamEntry[]>('/datahub/trace', { requestId }),
}

/* -------------------------------- 账户用户 -------------------------------- */

export const accountApi = {
  listUsers: (query?: PageQuery) => api.get<PageResult<UserAccount>>('/account/users', query),

  getUser: (id: number) => api.get<UserAccount>(`/account/users/${id}`),

  /** 账号在 Redis 中的实时状态（acc-* 键） */
  getRedisAccount: (account: string) => api.get<RedisAccount>(`/account/redis/${account}`),

  listLogs: (query?: PageQuery) => api.get<PageResult<UserLog>>('/account/logs', query),
}

/* -------------------------------- 数据存储 -------------------------------- */

export const storageApi = {
  listRedisInstances: () => api.get<RedisInstance[]>('/storage/redis/instances'),

  /** 扫描键（SCAN，务必带 limit，137 的 db0 有 400 万条 stream） */
  scanKeys: (instanceId: string, db: number, query?: PageQuery) =>
    api.get<PageResult<RedisKeyInfo>>('/storage/redis/keys', { instanceId, db, ...query }),

  getKey: (instanceId: string, db: number, key: string) =>
    api.get<unknown>('/storage/redis/key', { instanceId, db, key }),

  deleteKey: (instanceId: string, db: number, key: string) =>
    api.delete<void>('/storage/redis/key', { instanceId, db, key }),

  listPubSubChannels: (instanceId: string, db: number) =>
    api.get<RedisPubSubChannel[]>('/storage/redis/channels', { instanceId, db }),

  /** MySQL 表清单与结构 */
  listTables: (database: string) => api.get<unknown[]>('/storage/mysql/tables', { database }),

  /** 通用只读查询（后端必须强制只读 + 分页上限） */
  queryTable: (database: string, table: string, query?: PageQuery) =>
    api.get<PageResult<Record<string, unknown>>>('/storage/mysql/query', {
      database,
      table,
      ...query,
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
