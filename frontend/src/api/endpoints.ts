import { api } from './http'
import type { PageQuery, PageResult } from './types'
import type {
  AccountLogStat,
  AccountRow,
  AdminLoginLog,
  AdminRole,
  AdminRolePayload,
  AdminUser,
  AdminUserPayload,
  AuditActionOption,
  AuditDailyRow,
  AuditLog,
  Conditional,
  DashboardOverview,
  DatahubNodeDetail,
  LoginDailyRow,
  LoginResult,
  MysqlTable,
  MysqlTableSchema,
  NameValueRow,
  OrderRecord,
  PasswordPolicy,
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
  TimeSeriesPoint,
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

/* -------------------------------- 总览 --------------------------------- */

/**
 * 总览页。
 *
 * **一个接口返回整页所需的全部数据**（汇总 + 数据来源 + 近 14 天趋势）。
 * 为什么不拆成多个接口让页面并发拉：总览是「一眼看全局」的页面，
 * 拆开后会出现「一半卡片有数、一半转圈」的中间态，反而更难读；
 * 而且这些汇总都在同一份数据上算，后端一次扫描更省。
 */
export const dashboardApi = {
  getOverview: () => api.get<DashboardOverview>('/dashboard/overview'),
}

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

  /**
   * Status 列的**真实**去重取值。
   *
   * ⚠️ 必须用这个接口而不是 `mock/strategy.ts` 的 `LOG_STATUS`：
   * 那 7 个值（触发成功 / 触发失败 / 已暂停 / …）是演示数据编的，
   * 现场 `T_StrategyLog.Status` **整列为 NULL**，一个都不存在。
   * 从 mock 取选项会造成「下拉里有 7 个选项、每个都查出 0 条」
   * 这种极具误导性的界面。
   * 现在后端如实返回空数组 → 页面应显示「该列现场为空」而不是假选项。
   */
  listLogStatuses: () => api.get<string[]>('/strategy/logs/statuses'),

  /** 某条件单的完整日志轨迹（时间线，按时间升序） */
  listLogTimeline: (strategy: number) =>
    api.get<StrategyLog[]>(`/strategy/logs/${strategy}/timeline`),
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

/**
 * 只保留时序接口 —— 供「消息总线」的趋势卡画曲线。
 *
 * **性能压测 / 破坏测试 / 实时指标页面的接口都已删除**：
 *  - 压测与破坏测试是研发发版前用命令行工具做的活（`datahub_test`、
 *    `strategy_test` 里已有完整 PySide6 GUI），且破坏测试能让数据中台崩溃，
 *    做成网页按钮会让高危操作变成「登录就能点」。
 *  - 「实时指标」页面的 11 项指标经逐项核对，**没有一项是其它页面看不到的**，
 *    已删除；它唯一独有的「时间趋势」搬到了消息总线。
 */
export const monitorApi = {
  /**
   * 时序指标（供 ECharts 画趋势）。
   * window 为时间窗，如 '15m' / '1h'。
   */
  getTimeSeries: (metric: string, window?: string) =>
    api.get<TimeSeriesPoint[]>('/monitor/series', { metric, window }),
}

/* -------------------------------- 系统管理 -------------------------------- */

/**
 * 后台管理端账号体系（管理用户 / 角色权限 / 操作审计 / 登录日志）。
 *
 * **与 `T_User` 无关**：那是云单业务用户（客户），这里是能登录后台的自己人。
 * 两条线共用 `/system` 前缀但资源不同名，不要误接。
 */
export const adminApi = {
  /* ---------------------------- 管理用户 ---------------------------- */

  listUsers: (query?: PageQuery) => api.get<PageResult<AdminUser>>('/system/users', query),

  createUser: (payload: AdminUserPayload) => api.post<AdminUser>('/system/users', payload),

  updateUser: (id: number, payload: AdminUserPayload) =>
    api.put<AdminUser>(`/system/users/${id}`, payload),

  /**
   * 启用 / 停用。
   * 停用是**立即生效**的：后端必须同时让该账号已签发的 token 失效，
   * 只改数据库字段会让已登录的会话继续可用（这是个真实的越权风险）。
   */
  setUserEnabled: (id: number, enabled: boolean) =>
    api.post<void>(`/system/users/${id}/enabled`, { enabled }),

  /** 解除登录失败锁定 */
  unlockUser: (id: number) => api.post<void>(`/system/users/${id}/unlock`),

  /**
   * 重置密码。
   * 明文密码只在这一个请求里出现，**接口不得回显**；
   * 后端应写审计（谁在什么时候重置了谁），但不记录密码本身。
   */
  resetPassword: (id: number, password: string) =>
    api.post<void>(`/system/users/${id}/password`, { password }),

  /** 重置密码并强制下次登录修改（推荐用法） */
  resetPasswordAndForceChange: (id: number, password: string) =>
    api.post<void>(`/system/users/${id}/password`, { password, mustChange: true }),

  deleteUser: (id: number) => api.delete<void>(`/system/users/${id}`),

  /* ---------------------------- 角色与权限 ---------------------------- */

  listRoles: () => api.get<AdminRole[]>('/system/roles'),

  createRole: (payload: AdminRolePayload) => api.post<AdminRole>('/system/roles', payload),

  updateRole: (id: number, payload: AdminRolePayload) =>
    api.put<AdminRole>(`/system/roles/${id}`, payload),

  deleteRole: (id: number) => api.delete<void>(`/system/roles/${id}`),

  /* ---------------------------- 审计与登录日志 ---------------------------- */

  listAuditLogs: (query?: PageQuery) => api.get<PageResult<AuditLog>>('/system/audit-logs', query),

  /** 审计动作字典（供筛选下拉；也可前端从 permissions.ts 推导，两边都允许） */
  listAuditActions: () => api.get<AuditActionOption[]>('/system/audit-logs/actions'),

  /**
   * 审计按天聚合（近 14 天或 `from`/`to` 区间）。
   *
   * ⚠️ 必须由后端聚合：明细接口是**分页**的，
   * 前端拿明细自己「统计」只能算出当前一页，会被误读成全量。
   */
  getAuditDaily: (query?: Pick<PageQuery, 'from' | 'to'>) =>
    api.get<AuditDailyRow[]>('/system/audit-logs/daily', query),

  /** 审计按动作聚合（当前区间的动作分布） */
  getAuditByAction: (query?: Pick<PageQuery, 'from' | 'to'>) =>
    api.get<NameValueRow[]>('/system/audit-logs/by-action', query),

  listLoginLogs: (query?: PageQuery) =>
    api.get<PageResult<AdminLoginLog>>('/system/login-logs', query),

  /** 登录成功/失败按天聚合 */
  getLoginDaily: (query?: Pick<PageQuery, 'from' | 'to'>) =>
    api.get<LoginDailyRow[]>('/system/login-logs/daily', query),

  /** 登录失败按 IP 聚合（Top N） */
  getLoginFailByIp: (query?: Pick<PageQuery, 'from' | 'to'>) =>
    api.get<NameValueRow[]>('/system/login-logs/fail-by-ip', query),

  /**
   * 导出审计日志。
   *
   * **必须由后端出文件**：明细接口有分页与条数上限，
   * 前端把「当前页」导出成 CSV 会让人以为拿到了全量。
   * 后端要同时写一条 `action:system-audit:export` 的审计（导出即离场，需留痕）。
   */
  exportAuditLogs: (query?: PageQuery) =>
    api.post<{ url?: string; filename?: string; total: number }>('/system/audit-logs/export', query),

  /* ---------------------------- 密码策略 ---------------------------- */

  getPasswordPolicy: () => api.get<PasswordPolicy>('/system/password-policy'),

  savePasswordPolicy: (payload: PasswordPolicy) =>
    api.put<PasswordPolicy>('/system/password-policy', payload),
}

/* --------------------------- 登录鉴权（预留） --------------------------- */

export const authApi = {
  /**
   * 登录。
   * 后端返回 token，前端存 localStorage（见 `http.ts` 的 TOKEN_KEY），
   * 由 axios 请求拦截器统一加 `Authorization: Bearer`。
   *
   * 失败时后端返回 `code` ∈ `LOGIN_ERROR_CODES`（见 `domain.ts`）：
   * 账号不存在与密码错误**必须同码**（防账号枚举），停用/锁定各有独立码。
   */
  login: (payload: { username: string; password: string }) =>
    api.post<LoginResult>('/system/auth/login', payload),

  logout: () => api.post<void>('/system/auth/logout'),

  /** 当前登录人的资料与**权限键集合**（权限键见 src/config/permissions.ts） */
  profile: () =>
    api.get<{
      username: string
      displayName?: string
      roles: string[]
      permissions: string[]
      mustChangePassword?: boolean
    }>('/system/auth/profile'),

  /** 修改自己的密码（改密成功后后端应使旧 token 失效） */
  changePassword: (payload: { oldPassword: string; newPassword: string }) =>
    api.post<void>('/system/auth/password', payload),
}

/* ------------------------- 连接配置 / 兼容旧接口 ------------------------- */

/**
 * 保留原 systemApi 形状（连接配置页仍待开发）。
 * 审计接口已挪到 `adminApi`（路径由 /system/audit 改为 /system/audit-logs，
 * 避免和「连接配置」的 /system/connections 混在同一个 systemApi 里）。
 */
export const systemApi = {
  listConnections: () => api.get<unknown[]>('/system/connections'),
  saveConnection: (payload: unknown) => api.post<void>('/system/connections', payload),
  testConnection: (id: string) => api.post<{ ok: boolean; message: string }>(`/system/connections/${id}/test`),
}
