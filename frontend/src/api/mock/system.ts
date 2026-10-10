/**
 * 系统管理 —— 后台管理端账号体系的演示数据（Mock）。
 *
 * ⚠️ 三条不能含糊的事：
 *
 * 1. **这里不是 `T_User`**。`T_User` / `T_UserLog` 是云单业务用户（客户）的表，
 *    见 `mock/account.ts`。本文件是「谁能登录后台」的账号体系，
 *    两套东西混在一起会让权限形同虚设。
 *
 * 2. **演示账号是编的，演示密码也是编的**（`Demo#2026`）。
 *    现场真实密码绝不写进仓库 —— README 里已就此事定过规矩。
 *    界面上会明确标注这是演示凭据。
 *
 * 3. **审计日志照现场真发生过的高危动作造**：删 Redis 键、读只读 SQL、
 *    启停条件单、改连接配置，都对应 README「后端实现时必须注意的安全点」里
 *    点名要求写审计的那几项。用户登录日志（19 万行、设备三列全 NULL）不在这里。
 */
import type {
  AdminLoginLog,
  AdminRole,
  AdminUser,
  AuditActionOption,
  AuditDailyRow,
  AuditLevel,
  AuditLog,
  LoginDailyRow,
  LoginResult,
  NameValueRow,
  PasswordPolicy,
} from '../domain'
import { LOGIN_ERROR_CODES } from '../domain'
import type { PageQuery, PageResult } from '../types'
import { BizError } from '../http'
import { PERMISSION_WILDCARD, actionPermissionKey, menuPermissionKey } from '../../config/permissions'
import { asBool, asNumber, asString, fmt, intBetween, paginate, pick, seededRandom, type Rand } from './_shared'

const rand: Rand = seededRandom(20261015)

/** 演示用明文密码。**仅演示态使用**，真实环境由后端下发/重置。 */
export const DEMO_ADMIN_PASSWORD = 'Admin@2026'

/**
 * 演示账号的密码表（**写死几个够用就行**）。
 *
 * 三个可登录账号，覆盖三档权限 —— 换个账号登录就能看出按钮变灰：
 *   admin          → 超级管理员（通配符 *，全部权限）
 *   yangsh         → 运维（能启停，不能删键/改账号/读任意 SQL）
 *   zhangguochang  → 只读观察者（能进页面，写操作全点不动）
 *
 * 另外两个**故意登不进去**，用来验证登录页的错误分支（光跑成功路径不算数）：
 *   liuyi     → 账号已停用
 *   test_tmp  → 账号已锁定
 *
 * 密码是**编的**，只存在于前端演示数据里；现场真实密码绝不写进仓库。
 */
const DEMO_PASSWORDS: Record<string, string> = {
  admin: 'Admin@2026',
  yangsh: 'Ops@2026',
  zhangguochang: 'View@2026',
  liuyi: 'Ops@2026',
  test_tmp: 'Ops@2026',
}

/** 登录页上直接展示的三个可登录账号（含权限说明），避免用户去翻代码 */
export const DEMO_LOGIN_HINTS: Array<{
  username: string
  password: string
  label: string
  desc: string
}> = [
  { username: 'admin', password: 'Admin@2026', label: '超级管理员', desc: '全部权限' },
  { username: 'yangsh', password: 'Ops@2026', label: '运维', desc: '可启停，不能删除' },
  { username: 'zhangguochang', password: 'View@2026', label: '只读观察者', desc: '写得动才怪' },
]

/* ------------------------------ 内置角色 ------------------------------ */

/**
 * 内置角色：
 *  - `super_admin` 权限为 `*`（通配符），菜单新增后自动覆盖，不用回填；
 *  - `ops` 是日常运维，给到「能看能重启，但不能删东西」这一档；
 *  - `viewer` 只看不写。
 *
 * `builtin: true` 的角色在界面上禁止删除与改 code —— 删掉超管角色
 * 会让后台再也进不去（现场没有别的恢复手段）。
 */
const BUILTIN_ROLES: Array<Pick<AdminRole, 'code' | 'name' | 'desc' | 'builtin' | 'permissions'>> = [
  {
    code: 'super_admin',
    name: '超级管理员',
    desc: '拥有全部权限（通配符 *）。菜单新增后自动覆盖，无需回填权限点。',
    builtin: true,
    permissions: ['*'],
  },
  {
    code: 'ops',
    name: '运维',
    desc: '日常运维：可查看全部业务页面、启停节点与条件单，但不能删除 Redis 键、不能读任意 SQL、不能改后台账号。',
    builtin: true,
    permissions: [
      menuPermissionKey('dashboard'),
      menuPermissionKey('strategy-server'),
      menuPermissionKey('strategy-list'),
      menuPermissionKey('strategy-log'),
      menuPermissionKey('trade-server'),
      menuPermissionKey('trade-order'),
      menuPermissionKey('trade-deal'),
      menuPermissionKey('datahub-node'),
      menuPermissionKey('datahub-stream'),
      menuPermissionKey('datahub-protocol'),
      menuPermissionKey('account-user'),
      menuPermissionKey('account-sign'),
      menuPermissionKey('account-log'),
      menuPermissionKey('storage-redis'),
      menuPermissionKey('storage-mysql'),
      menuPermissionKey('system-audit'),
      actionPermissionKey('strategy-list', 'write'),
      actionPermissionKey('trade-server', 'toggle'),
      actionPermissionKey('storage-mysql', 'readonly-sql'),
      actionPermissionKey('system-audit', 'export'),
    ],
  },
  {
    code: 'viewer',
    name: '只读观察者',
    desc: '只能看。适合把界面投到大屏或给外部同事核对数据，任何写操作都会被后端拒绝。',
    builtin: true,
    permissions: [
      menuPermissionKey('dashboard'),
      menuPermissionKey('strategy-server'),
      menuPermissionKey('strategy-list'),
      menuPermissionKey('strategy-log'),
      menuPermissionKey('trade-server'),
      menuPermissionKey('trade-order'),
      menuPermissionKey('trade-deal'),
      menuPermissionKey('datahub-node'),
      menuPermissionKey('datahub-stream'),
      menuPermissionKey('datahub-protocol'),
      menuPermissionKey('account-user'),
      menuPermissionKey('account-sign'),
      menuPermissionKey('account-log'),
      menuPermissionKey('storage-redis'),
      menuPermissionKey('storage-mysql'),
    ],
  },
]

function buildRoles(): AdminRole[] {
  const list: AdminRole[] = BUILTIN_ROLES.map((r, i) => ({
    id: i + 1,
    code: r.code,
    name: r.name,
    desc: r.desc,
    builtin: r.builtin,
    permissions: [...r.permissions],
    userCount: 0,
    createdAt: fmt(new Date(Date.now() - (400 - i * 30) * 86400_000)),
    updatedAt: fmt(new Date(Date.now() - (60 - i * 10) * 86400_000)),
  }))

  // 一个非内置的自定义角色，用来证明「自定义角色可删可改」这条交互是通的
  list.push({
    id: 4,
    code: 'auditor',
    name: '审计员',
    desc: '自定义角色：只能看审计与登录日志，不能碰业务页面。',
    builtin: false,
    permissions: [
      menuPermissionKey('dashboard'),
      menuPermissionKey('system-audit'),
      actionPermissionKey('system-audit', 'export'),
    ],
    userCount: 0,
    createdAt: fmt(new Date(Date.now() - 45 * 86400_000)),
    updatedAt: fmt(new Date(Date.now() - 12 * 86400_000)),
  })

  return list
}

const ROLES = buildRoles()

/* ------------------------------ 管理用户 ------------------------------ */

const USER_SEEDS: Array<{
  username: string
  displayName: string
  roleCodes: string[]
  enabled: boolean
  locked: boolean
  failedAttempts: number
  mustChangePassword: boolean
  remark: string
}> = [
  {
    username: 'admin',
    displayName: '系统管理员',
    roleCodes: ['super_admin'],
    enabled: true,
    locked: false,
    failedAttempts: 0,
    mustChangePassword: false,
    remark: '内置账号，现场唯一的超管。密码策略已开启，改密后请同步告知运维。',
  },
  {
    username: 'yangsh',
    displayName: '杨',
    roleCodes: ['ops'],
    enabled: true,
    locked: false,
    failedAttempts: 0,
    mustChangePassword: false,
    remark: '现场运维同学（136 上的部署目录 /home/yangsh/so_test 即此人）。',
  },
  {
    username: 'zhangguochang',
    displayName: '张国昌',
    roleCodes: ['viewer'],
    enabled: true,
    locked: false,
    failedAttempts: 0,
    mustChangePassword: true,
    remark: '首次登录需改密。账号名与云单账号 300130000461 同属一个客户，但**不是一回事**（那是 T_User，这是后台账号）。',
  },
  {
    username: 'audit01',
    displayName: '合规审计',
    roleCodes: ['auditor'],
    enabled: true,
    locked: false,
    failedAttempts: 0,
    mustChangePassword: false,
    remark: '',
  },
  {
    username: 'liuyi',
    displayName: '刘工',
    roleCodes: ['ops'],
    enabled: false,
    locked: false,
    failedAttempts: 0,
    mustChangePassword: false,
    remark: '已离职/交接中，账号先停用，不删除（审计要认人）。',
  },
  {
    username: 'test_tmp',
    displayName: '临时测试',
    roleCodes: ['ops'],
    enabled: true,
    locked: true,
    failedAttempts: 5,
    mustChangePassword: false,
    remark: '连续 5 次密码错误被锁定，需管理员手动解锁。',
  },
]

function buildUsers(): AdminUser[] {
  const byCode = new Map(ROLES.map((r) => [r.code, r.id]))
  const users: AdminUser[] = USER_SEEDS.map((seed, i) => {
    const failed = seed.locked ? 5 : seed.failedAttempts
    return {
      id: 1001 + i,
      username: seed.username,
      displayName: seed.displayName,
      roleIds: seed.roleCodes.map((c) => byCode.get(c)).filter((v): v is number => v !== undefined),
      enabled: seed.enabled,
      locked: seed.locked,
      failedAttempts: failed,
      mustChangePassword: seed.mustChangePassword,
      lastLoginTime:
        seed.username === 'test_tmp'
          ? null
          : fmt(new Date(Date.now() - intBetween(rand, 120, 86400 * 9) * 1000)),
      lastLoginIP: seed.username === 'test_tmp' ? null : pick(rand, ['192.168.1.136', '10.0.0.35', '10.0.0.21', '127.0.0.1']),
      loginCount: seed.username === 'test_tmp' ? 0 : intBetween(rand, 3, 260),
      createdAt: fmt(new Date(Date.now() - (500 - i * 40) * 86400_000)),
      updatedAt: fmt(new Date(Date.now() - (30 - (i % 6) * 4) * 86400_000)),
      remark: seed.remark || null,
    }
  })

  // 回填每个角色的引用人数（界面上要能看到「删掉这个角色会影响几个人」）
  for (const role of ROLES) {
    role.userCount = users.filter((u) => u.roleIds.includes(role.id)).length
  }
  return users
}

const USERS = buildUsers()

/* ------------------------------ 登录日志 ------------------------------ */

const LOGIN_FAIL_REASONS = ['密码错误', '账号不存在', '账号已停用', '账号已锁定']

function buildLoginLogs(): AdminLoginLog[] {
  const rows: AdminLoginLog[] = []
  // 造 180 条足够展示分页与「失败 IP 聚合」
  for (let i = 0; i < 180; i += 1) {
    const user = pick(rand, USER_SEEDS)
    const success = rand() > 0.28
    const seconds = intBetween(rand, 60, 86400 * 30)
    const risky = rand() > 0.86
    rows.push({
      id: 90000 - i,
      username: user.username,
      loginTime: fmt(new Date(Date.now() - seconds * 1000)),
      loginIP: risky ? pick(rand, ['203.0.113.44', '198.51.100.7']) : pick(rand, ['192.168.1.136', '10.0.0.35', '10.0.0.21']),
      loginOS: pick(rand, ['Windows 11 / Chrome 141', 'Windows 10 / Edge 141', 'macOS 15 / Safari 18', 'CentOS 7 / curl']),
      success,
      info: success ? '登录成功' : pick(rand, LOGIN_FAIL_REASONS),
    })
  }
  return rows.sort((a, b) => b.id - a.id)
}

const LOGIN_LOGS = buildLoginLogs()

/* ------------------------------ 审计日志 ------------------------------ */

/** 审计动作字典：键与 permissions.ts 的权限键同源，界面筛选与权限树能对上 */
export const AUDIT_ACTIONS: AuditActionOption[] = [
  { action: actionPermissionKey('storage-redis', 'delete-key'), label: '删除 Redis 键', module: 'storage', level: 'high' },
  { action: actionPermissionKey('strategy-list', 'write'), label: '启停 / 删除条件单', module: 'strategy', level: 'high' },
  { action: actionPermissionKey('trade-server', 'toggle'), label: '启停委托服务器', module: 'trade', level: 'high' },
  { action: actionPermissionKey('storage-mysql', 'readonly-sql'), label: '执行只读 SQL', module: 'storage', level: 'medium' },
  { action: actionPermissionKey('system-user', 'manage'), label: '管理后台账号', module: 'system', level: 'high' },
  { action: actionPermissionKey('system-role', 'manage'), label: '管理角色与权限', module: 'system', level: 'high' },
  { action: actionPermissionKey('system-audit', 'export'), label: '导出审计日志', module: 'system', level: 'low' },
  { action: 'system:connection', label: '修改连接配置', module: 'system', level: 'high' },
  { action: 'system:login', label: '管理端登录', module: 'system', level: 'low' },
]

/** 高危动作的典型目标（照现场真实键/表名，便于一眼看出这是哪类操作） */
const AUDIT_TARGETS: Record<string, string[]> = {
  [actionPermissionKey('storage-redis', 'delete-key')]: [
    'a',
    'strategy_server_lock_1001',
    'WT-1-reply',
    'DataHub_destroy_stream',
  ],
  [actionPermissionKey('strategy-list', 'write')]: ['条件单 300130000461', '条件单 914900043', '条件单 1000006'],
  [actionPermissionKey('trade-server', 'toggle')]: ['traderserver id=1 mac=2cea7fd9d5c0', 'traderserver id=3 mac=20474789c674'],
  [actionPermissionKey('storage-mysql', 'readonly-sql')]: [
    'SELECT * FROM T_UserLog LIMIT 100',
    'SELECT COUNT(*) FROM T_StrategyLog',
    'SELECT * FROM V_Conditional LIMIT 50',
  ],
  [actionPermissionKey('system-user', 'manage')]: ['test_tmp', 'liuyi', 'audit01'],
  [actionPermissionKey('system-role', 'manage')]: ['viewer', 'auditor'],
  [actionPermissionKey('system-audit', 'export')]: ['2026-08 审计导出'],
  'system:connection': ['REDISHOST / REDISSELECT', 'DBHOST / DBUSER'],
  'system:login': ['admin', 'yangsh'],
}

const AUDIT_MESSAGES: Record<string, string[]> = {
  [actionPermissionKey('storage-redis', 'delete-key')]: [
    '删除成功（DEL a = 1）',
    '键不存在，未产生写入',
    '拒绝：该键属于 DataHub_* 流，后端要求二次确认',
  ],
  [actionPermissionKey('strategy-list', 'write')]: [
    '已下发 remove 协议，策略平台已回执',
    '已下发 set 协议（停止）',
    '超时未收到策略平台回执',
  ],
  [actionPermissionKey('trade-server', 'toggle')]: ['enable 已写回 Redis JSON', '目标节点 id 重复，按 mac 定位成功'],
  [actionPermissionKey('storage-mysql', 'readonly-sql')]: [
    '返回 100 行（已截断到上限）',
    '语句含 DELETE，被只读校验拒绝',
    '执行耗时 128ms',
  ],
  [actionPermissionKey('system-user', 'manage')]: ['新建账号并分配 viewer', '停用账号，旧 token 已失效', '重置密码并强制下次改密'],
  [actionPermissionKey('system-role', 'manage')]: ['新增权限点 3 个', '拒绝：内置角色不可删', '权限已保存，涉及 2 个账号'],
  [actionPermissionKey('system-audit', 'export')]: ['导出 1280 条，格式 CSV'],
  'system:connection': ['配置已保存（密码不回显）', '连通性测试通过'],
  'system:login': ['登录成功', '密码错误（第 2 次）', '账号已停用，拒绝登录'],
}

function levelOf(action: string): AuditLevel {
  return AUDIT_ACTIONS.find((a) => a.action === action)?.level ?? 'medium'
}

function buildAuditLogs(): AuditLog[] {
  const rows: AuditLog[] = []
  const doers = ['admin', 'yangsh', 'audit01']

  for (let i = 0; i < 220; i += 1) {
    const act = pick(rand, AUDIT_ACTIONS)
    const targets = AUDIT_TARGETS[act.action] ?? ['']
    const messages = AUDIT_MESSAGES[act.action] ?? ['执行成功']
    const result: AuditLog['result'] = rand() > 0.14 ? 'success' : 'failure'
    const seconds = intBetween(rand, 60, 86400 * 45)

    rows.push({
      id: 70000 - i,
      operator: pick(rand, doers),
      action: act.action,
      actionLabel: act.label,
      module: act.module,
      level: levelOf(act.action),
      target: pick(rand, targets) || null,
      result,
      message: result === 'success' ? pick(rand, messages) : pick(rand, messages.filter((m) => /拒绝|超时|错误|不存在|失败/.test(m)).concat(['内部错误'])),
      ip: pick(rand, ['192.168.1.136', '10.0.0.35', '10.0.0.21']),
      costMs: intBetween(rand, 3, 2400),
      createdAt: fmt(new Date(Date.now() - seconds * 1000)),
    })
  }

  return rows.sort((a, b) => b.id - a.id)
}

const AUDIT_LOGS = buildAuditLogs()

/* ------------------------------ 密码策略 ------------------------------ */

let POLICY: PasswordPolicy = {
  minLength: 10,
  requireUpper: true,
  requireLower: true,
  requireDigit: true,
  requireSymbol: true,
  maxFailedAttempts: 5,
  lockMinutes: 30,
  expireDays: 90,
  forceChangeOnFirstLogin: true,
  historySize: 5,
}

/* ----------------------------- 对外接口 ----------------------------- */

let nextUserId = 1100
let nextRoleId = 10

export const mockAdminApi = {
  /* ---------------------------- 管理用户 ---------------------------- */

  listUsers(query: PageQuery = {}): PageResult<AdminUser> {
    return paginate(
      USERS,
      query,
      (row, kw) =>
        row.username.toLowerCase().includes(kw.toLowerCase()) ||
        (row.displayName ?? '').includes(kw) ||
        (row.remark ?? '').includes(kw),
      { username: (r) => r.username, lastLoginTime: (r) => String(r.lastLoginTime ?? ''), createdAt: (r) => String(r.createdAt ?? '') },
      [
        (row, q) => {
          const roleId = asNumber(q.roleId)
          return roleId === undefined ? true : row.roleIds.includes(roleId)
        },
        (row, q) => {
          const s = asString(q.status)
          if (!s) return true
          if (s === 'enabled') return row.enabled
          if (s === 'disabled') return !row.enabled
          if (s === 'locked') return row.locked
          return true
        },
      ],
    )
  },
  /** 演示态不需要后端：直接返回成功，让页面走到「已提交」分支 */
  createUser(payload: { username: string }): AdminUser {
    const id = nextUserId++
    const row: AdminUser = {
      id,
      username: payload.username,
      displayName: null,
      roleIds: [],
      enabled: true,
      locked: false,
      failedAttempts: 0,
      mustChangePassword: true,
      lastLoginTime: null,
      lastLoginIP: null,
      loginCount: 0,
      createdAt: fmt(new Date()),
      updatedAt: fmt(new Date()),
      remark: null,
    }
    USERS.unshift(row)
    return row
  },

  /** 角色下拉 / 用户列表里的角色名解析都用得到 */
  listRoles(): AdminRole[] {
    return ROLES
  },

  /* ------------------------------ 登录鉴权 ------------------------------ */

  /**
   * 演示态登录校验。
   *
   * ⚠️ 这是**演示替身**，不是安全实现：密码是明文比较、token 是假的。
   * 它存在的意义是让登录页的**每条分支**都能被真正走一遍
   * （成功 / 密码错 / 账号停用 / 已锁定），而不是只能看到成功路径。
   *
   * 与真实后端的三点约定（前端按 `code` 分派，不匹配 message）：
   *  1. 账号不存在与密码错误**返回同一个码** `BAD_CREDENTIALS`（防账号枚举）；
   *  2. 停用 / 锁定**先于**密码校验返回，否则管理员会以为是密码错了；
   *  3. 失败要累计 `failedAttempts`，达到策略阈值自动置 `locked`。
   */
  login(username: string, password: string): LoginResult {
    const name = username.trim()
    const user = USERS.find((u) => u.username === name)

    // 1) 账号不存在 → 与密码错误同码（不泄露账号是否存在）
    if (!user) {
      throw new BizError('账号或密码错误', LOGIN_ERROR_CODES.BAD_CREDENTIALS)
    }

    // 2) 已锁定 → 先于密码校验，避免管理员误以为「密码错了」
    if (user.locked) {
      throw new BizError(
        `账号已被锁定（连续失败 ${user.failedAttempts} 次），请联系管理员解锁`,
        LOGIN_ERROR_CODES.LOCKED,
      )
    }

    // 3) 已停用
    if (!user.enabled) {
      throw new BizError('账号已停用，请联系管理员', LOGIN_ERROR_CODES.DISABLED)
    }

    // 4) 密码
    if (DEMO_PASSWORDS[name] !== password) {
      user.failedAttempts += 1
      const max = POLICY.maxFailedAttempts
      if (max > 0 && user.failedAttempts >= max) {
        user.locked = true
        throw new BizError(
          `账号或密码错误。连续失败 ${user.failedAttempts} 次，账号已被锁定`,
          LOGIN_ERROR_CODES.LOCKED,
        )
      }
      const left = max > 0 ? `，还可尝试 ${Math.max(0, max - user.failedAttempts)} 次` : ''
      throw new BizError(`账号或密码错误${left}`, LOGIN_ERROR_CODES.BAD_CREDENTIALS)
    }

    // 5) 成功：累计登录次数并刷新审计用的最后登录信息
    user.failedAttempts = 0
    user.loginCount += 1
    user.lastLoginTime = fmt(new Date())
    user.lastLoginIP = '127.0.0.1'

    return {
      token: `demo-token-${user.username}-${Date.now()}`,
      username: user.username,
      displayName: user.displayName ?? user.username,
      roles: user.roleIds.map((id) => ROLES.find((r) => r.id === id)?.code ?? String(id)),
      // 超管是通配符，其余取其角色的权限键并集
      permissions: user.roleIds.includes(1)
        ? [PERMISSION_WILDCARD]
        : [
            ...new Set(
              user.roleIds.flatMap(
                (id) => ROLES.find((r) => r.id === id)?.permissions ?? [],
              ),
            ),
          ],
      mustChangePassword: user.mustChangePassword,
    }
  },

  roleNameOf(roleId: number): string {
    return ROLES.find((r) => r.id === roleId)?.name ?? `角色 ${roleId}`
  },

  /* ---------------------------- 审计与登录日志 ---------------------------- */

  listAuditLogs(query: PageQuery = {}): PageResult<AuditLog> {
    return paginate(
      AUDIT_LOGS,
      query,
      (row, kw) =>
        (row.target ?? '').includes(kw) ||
        (row.operator ?? '').includes(kw) ||
        row.actionLabel.includes(kw) ||
        row.action.includes(kw),
      { createdAt: (r) => String(r.createdAt ?? ''), level: (r) => r.level, operator: (r) => r.operator },
      [
        (row, q) => {
          const level = asString(q.level)
          return level ? row.level === level : true
        },
        (row, q) => {
          const module = asString(q.module)
          return module ? row.module === module : true
        },
        (row, q) => {
          const action = asString(q.action)
          return action ? row.action === action : true
        },
        (row, q) => {
          const operator = asString(q.operator)
          return operator ? (row.operator ?? '').includes(operator) : true
        },
        (row, q) => {
          const result = asString(q.result)
          return result ? row.result === result : true
        },
        (row, q) => {
          /**
           * 时间范围。与 `listLoginLogs` 共用 `inRange`，
           * 保证「列表筛选」与「按天聚合」在演示态下是同一个区间。
           *
           * 之前这里写的是内联字符串比较、登录日志那份则**整项漏写**，
           * 于是「设了时间范围、表格条数却不变」且**不报错** —— 典型的静默失效。
           */
          return inRange(row.createdAt, q)
        },
      ],
    )
  },

  listAuditActions(): AuditActionOption[] {
    return AUDIT_ACTIONS
  },

  /**
   * 审计按天聚合。
   *
   * ⚠️ 与列表接口共用同一套 `from`/`to` 字符串口径（`YYYY-MM-DD HH:mm:ss`，
   * 零填充定宽），这样演示态下「列表筛选」与「图表聚合」看到的是同一个区间。
   * 真实态由后端聚合（明细接口是分页的，前端硬凑只能统计当前页）。
   */
  getAuditDaily(query: { from?: string; to?: string } = {}): AuditDailyRow[] {
    const from = asString(query.from)
    const to = asString(query.to)
    const days = defaultDayRange()
    const out: AuditDailyRow[] = []
    for (const day of days) {
      if (from && day < from.slice(0, 10)) continue
      if (to && day > to.slice(0, 10)) continue
      const sameDay = AUDIT_LOGS.filter((l) => (l.createdAt ?? '').startsWith(day))
      out.push({
        date: day,
        high: sameDay.filter((l) => l.level === 'high').length,
        other: sameDay.filter((l) => l.level !== 'high').length,
      })
    }
    return out
  },

  getAuditByAction(query: { from?: string; to?: string } = {}): NameValueRow[] {
    const rows = filterByRange(AUDIT_LOGS, query, (l) => l.createdAt)
    const map = new Map<string, number>()
    for (const l of rows) map.set(l.actionLabel, (map.get(l.actionLabel) ?? 0) + 1)
    return [...map.entries()].map(([name, value]) => ({ name, value })).sort((a, b) => b.value - a.value)
  },

  getLoginDaily(query: { from?: string; to?: string } = {}): LoginDailyRow[] {
    const from = asString(query.from)
    const to = asString(query.to)
    const out: LoginDailyRow[] = []
    for (const day of defaultDayRange()) {
      if (from && day < from.slice(0, 10)) continue
      if (to && day > to.slice(0, 10)) continue
      const sameDay = LOGIN_LOGS.filter((l) => (l.loginTime ?? '').startsWith(day))
      out.push({
        date: day,
        success: sameDay.filter((l) => l.success).length,
        failure: sameDay.filter((l) => !l.success).length,
      })
    }
    return out
  },

  getLoginFailByIp(query: { from?: string; to?: string } = {}): NameValueRow[] {
    const map = new Map<string, number>()
    for (const l of filterByRange(LOGIN_LOGS, query, (x) => x.loginTime)) {
      if (l.success) continue
      const key = l.loginIP ?? '未知'
      map.set(key, (map.get(key) ?? 0) + 1)
    }
    return [...map.entries()].map(([name, value]) => ({ name, value })).sort((a, b) => b.value - a.value)
  },

  /** 演示态导出：返回一个可直接下载的 CSV 文本（真实态必须由后端出文件） */
  exportAuditLogs(query: PageQuery = {}): { filename: string; content: string; total: number } {
    const res = this.listAuditLogs({ ...query, page: 1, pageSize: 100000 })
    const header = ['时间', '级别', '操作人', '动作', '模块', '目标', '结果', '耗时(ms)', 'IP', '说明']
    const lines = [header.join(',')]
    for (const r of res.list) {
      lines.push(
        [
          r.createdAt,
          r.level,
          r.operator,
          r.actionLabel,
          r.module,
          r.target,
          r.result,
          r.costMs,
          r.ip,
          r.message,
        ]
          .map((v) => `"${String(v ?? '').replace(/"/g, '""')}"`)
          .join(','),
      )
    }
    return {
      filename: `audit_${(query.from as string) || 'all'}.csv`,
      content: `\uFEFF${lines.join('\r\n')}`,
      total: res.total,
    }
  },

  listLoginLogs(query: PageQuery = {}): PageResult<AdminLoginLog> {
    return paginate(
      LOGIN_LOGS,
      query,
      (row, kw) =>
        row.username.toLowerCase().includes(kw.toLowerCase()) ||
        (row.loginIP ?? '').includes(kw) ||
        (row.loginOS ?? '').includes(kw),
      { loginTime: (r) => String(r.loginTime ?? ''), username: (r) => r.username },
      [
        (row, q) => {
          const username = asString(q.username)
          return username ? row.username.includes(username) : true
        },
        (row, q) => {
          /**
           * ⚠️ 这里必须用 `asBool` 而不是 `asString(q.success) === 'true'`。
           *
           * 「结果」这个筛选在审计侧取值是 `'success'`/`'failure'`，
           * 在登录侧却是布尔。页面若把审计那套取值复用过来，
           * 会**静默**变成「永远只显示失败的行」（`'success' === 'true'` 恒为 false），
           * 不报错、下拉也不报错。`asBool` 同时认 `'true'`/`'1'`/`true`/`1`，
           * 从根上消掉这个坑；`undefined` 仍区分「未筛选」。
           */
          const s = asBool(q.success)
          if (s === undefined) return true
          return row.success === s
        },
        (row, q) => {
          const ip = asString(q.ip)
          return ip ? (row.loginIP ?? '').includes(ip) : true
        },
        (row, q) => {
          /**
           * 时间范围。与 `listAuditLogs` 共用 `inRange`（同一套语义）。
           *
           * 之前**整项漏写**：用户设了时间范围、表格条数却不变，
           * 而且没有任何报错 —— 典型的静默失效。
           */
          return inRange(row.loginTime, q)
        },
      ],
    )
  },

  /* ---------------------------- 密码策略 ---------------------------- */

  getPasswordPolicy(): PasswordPolicy {
    return { ...POLICY }
  },

  savePasswordPolicy(payload: PasswordPolicy): PasswordPolicy {
    POLICY = { ...payload }
    return { ...POLICY }
  },
}

/* ------------------------------ 图表数据 ------------------------------ */

/** 最近 14 个自然日（含今天），`YYYY-MM-DD` */
function defaultDayRange(): string[] {
  const out: string[] = []
  for (let i = 13; i >= 0; i -= 1) out.push(fmt(new Date(Date.now() - i * 86400_000)).slice(0, 10))
  return out
}

/**
 * 按 `from`/`to` 区间筛行。
 *
 * 字符串比较要求**格式完全一致**（定宽零填充的 `YYYY-MM-DD HH:mm:ss`）。
 * 传 ISO（`2026-10-01T00:00:00Z`）或 `YYYY-M-D` 会静默筛错 ——
 * 所以聚合与列表用同一个 `filterByRange`，避免两处各写一遍各错一遍。
 */
function filterByRange<T>(
  rows: T[],
  query: { from?: string; to?: string },
  pickTime: (row: T) => string | null,
): T[] {
  const from = asString(query.from)
  const to = asString(query.to)
  if (!from && !to) return rows
  return rows.filter((row) => {
    const t = pickTime(row) ?? ''
    if (from && t < from) return false
    if (to && t > to) return false
    return true
  })
}

/**
 * 单行的时间范围判定（`paginate` 的 filter 形态）。
 *
 * 与 `filterByRange` 同一套语义，供不方便先整体 filter 的地方复用；
 * 两处都只认「定宽零填充的 `YYYY-MM-DD HH:mm:ss`」。
 */
function inRange(time: string | null | undefined, query: { from?: string; to?: string }): boolean {
  const from = asString(query.from)
  const to = asString(query.to)
  const t = time ?? ''
  if (from && t < from) return false
  if (to && t > to) return false
  return true
}

/**
 * 审计日志按天聚合（近 14 天），供趋势图。
 *
 * 这些是给页面直接用的便捷函数，**与 `mockAdminApi.getAuditDaily` 同源** ——
 * 后者是「真实接口的名字」，前者是演示态的快捷入口，两者必须给出一致结果，
 * 否则页面切到真实接口后图表会突然变样。所以这里直接转发，不重复实现。
 */
export function mockDailyAudit(): AuditDailyRow[] {
  return mockAdminApi.getAuditDaily()
}

/** 审计日志按动作聚合（转发 `getAuditByAction`，保证与真实接口口径一致） */
export function mockAuditByAction(): NameValueRow[] {
  return mockAdminApi.getAuditByAction()
}

/** 登录失败按 IP 聚合（转发 `getLoginFailByIp`）—— 「异常登录来源」就看这个 */
export function mockLoginFailByIp(): NameValueRow[] {
  return mockAdminApi.getLoginFailByIp()
}

/** 近 14 天管理端登录成功/失败（转发 `getLoginDaily`） */
export function mockDailyLogin(): LoginDailyRow[] {
  return mockAdminApi.getLoginDaily()
}

export { AUDIT_LOGS, LOGIN_LOGS, ROLES, USERS }
