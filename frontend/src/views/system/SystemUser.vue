<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, type VNodeChild } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NIcon,
  NInput,
  NModal,
  NPopconfirm,
  NSelect,
  NSwitch,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
  type FormInst,
  type FormRules,
} from 'naive-ui'
import {
  BanOutline,
  CheckmarkCircleOutline,
  CloseCircleOutline,
  CloseOutline,
  CreateOutline,
  InformationCircleOutline,
  KeyOutline,
  LockClosedOutline,
  LockOpenOutline,
  PeopleOutline,
  PersonAddOutline,
  PlayOutline,
  RefreshOutline,
  SearchOutline,
  TrashOutline,
} from '@vicons/ionicons5'

import MockDataAlert from '@/components/MockDataAlert.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { adminApi } from '@/api/endpoints'
import { DEMO_ADMIN_PASSWORD, USERS, mockAdminApi } from '@/api/mock/system'
import { fmt, withDelay } from '@/api/mock/_shared'
import type { AdminUser, AdminUserPayload, PasswordCheckItem } from '@/api/domain'
import { useResource } from '@/composables/useResource'
import { actionPermissionKey } from '@/config/permissions'
import { useAuthStore } from '@/stores/auth'
import { orDash, shortDateTime, thousands } from '@/utils/format'

/**
 * 系统管理 / 管理用户与权限。
 *
 * 这一页管的是「**谁能登录后台**」，不是云单业务用户：
 *  - 本页账号 = 后台管理端自己的账号体系（建议表 `T_AdminUser`）；
 *  - 「账户与用户 / 用户账号」里的 `T_User` 是客户的钱包账号（会挂条件单）。
 * 两套东西混在一个列表里，权限就形同虚设了。
 *
 * 三条不能含糊的约束（页面上也写明了）：
 *  1. 演示态的账号与密码都是编的，现场真实密码绝不写进仓库；本页不回显任何已存在的密码。
 *  2. 停用必须同时让已签发的 token 失效 —— 只改库字段等于没停。后端未实现前前端无法验证这点。
 *  3. 真实态写操作失败一律 `message.error`，**不动本地列表**；演示态不调用真实接口，只改演示数据。
 */

const message = useMessage()
const authStore = useAuthStore()
const accent = '#5b6b8c'

/** 写操作权限点：演示态放行，真实态严格判定 */
const manageKey = actionPermissionKey('system-user', 'manage')
const canManage = computed(() => authStore.can(manageKey))

/* ------------------------------ 取数（三个数据源各自独立） ------------------------------ */

/**
 * 三个数据源各走一个 `useResource`，**不要在 onMounted 里用别处的 isMock 决定另一请求走 mock 还是接口**
 * （那时别的请求还没回来，isMock 仍是 false，会去调真实接口然后拿到空数组 —— 踩过一次）。
 */
type NoParams = undefined

const keyword = ref('')
const roleFilter = ref<number | null>(null)
const statusFilter = ref<string>('all')
const page = ref(1)
const pageSize = ref(10)

/**
 * ⚠️ 分支判定一律用 `usingMock`，**不要用 `isMock`**。
 *
 * `isMock` 初值是 `false`，只有 `load()` 的 `await` 之后才置位；而本页在 `onMounted`
 * 里 `void reloadAll()`（不 await），mock 又有 260ms 延迟 —— 首个请求返回前
 * `isMock.value` 仍是 `false`。此刻用户点写操作按钮就会**误判为真实态**、
 * 去调还没实现的后端、然后弹「失败」（verifier 实测复现）。
 *
 * `usingMock` 在数据未到手时退回构建期常量 `USE_MOCK`，避开这个时序窗口；
 * 数据到手后以 `isMock` 为准（还能识别「自动降级」中间态）。
 *
 * `isMock` 仅保留给**「当前展示的是不是演示数据」这类事实展示**（如 `MockDataAlert` 横幅）。
 */
const {
  data: usersData,
  loading,
  error,
  isMock,
  usingMock,
  mockReason,
  load: loadUsers,
} = useResource(
  (params: Record<string, unknown>) => adminApi.listUsers(params),
  { mock: (params: Record<string, unknown>) => withDelay(mockAdminApi.listUsers(params)) },
)

const {
  data: rolesData,
  loading: rolesLoading,
  error: rolesError,
  usingMock: rolesUsingMock,
  load: loadRoles,
} = useResource((_params: NoParams) => adminApi.listRoles(), {
  mock: (_params: NoParams) => withDelay(mockAdminApi.listRoles()),
})

const {
  data: policyData,
  loading: policyLoading,
  error: policyError,
  usingMock: policyUsingMock,
  load: loadPolicy,
} = useResource((_params: NoParams) => adminApi.getPasswordPolicy(), {
  mock: (_params: NoParams) => withDelay(mockAdminApi.getPasswordPolicy()),
})

function currentParams() {
  return {
    page: page.value,
    pageSize: pageSize.value,
    keyword: keyword.value.trim() || undefined,
    roleId: roleFilter.value ?? undefined,
    status: statusFilter.value === 'all' ? undefined : statusFilter.value,
  }
}

function reload() {
  return loadUsers(currentParams())
}

function reloadAll() {
  return Promise.all([loadUsers(currentParams()), loadRoles(undefined), loadPolicy(undefined)])
}

onMounted(() => {
  void reloadAll()
})

function handleSearch() {
  page.value = 1
  void reload()
}

function resetFilters() {
  keyword.value = ''
  roleFilter.value = null
  statusFilter.value = 'all'
  page.value = 1
  void reload()
}

const rows = computed<AdminUser[]>(() => usersData.value?.list ?? [])
const total = computed(() => usersData.value?.total ?? 0)
const policy = computed(() => policyData.value)

const roleOptions = computed(() =>
  (rolesData.value ?? []).map((r) => ({ label: `${r.name}（${r.code}）`, value: r.id })),
)

const statusOptions = [
  { label: '全部状态', value: 'all' },
  { label: '已启用', value: 'enabled' },
  { label: '已停用', value: 'disabled' },
  { label: '锁定中', value: 'locked' },
]

/**
 * 角色名解析：真实态用 `listRoles()` 的结果，演示态才允许回落到 mock 的 `roleNameOf`。
 * 都拿不到时如实显示 `角色 <id>`，不猜。
 */
const roleNameMap = computed(() => {
  const map = new Map<number, string>()
  for (const r of rolesData.value ?? []) map.set(r.id, r.name)
  return map
})

function roleNameOf(roleId: number): string {
  // 回落读的是 mock 的角色表，所以**只有确实处于演示态**（用户列表或角色源其一）才允许回落；
  // 真实态拿不到名字就如实显示「角色 N」，绝不拿演示角色名冒充真实角色（不交叉判断）。
  const allowMockFallback = usingMock.value || rolesUsingMock.value
  return roleNameMap.value.get(roleId) ?? (allowMockFallback ? mockAdminApi.roleNameOf(roleId) : `角色 ${roleId}`)
}

/** 用户列表是真实数据、但角色/策略降级到演示数据时，必须说出来（不能交叉判断） */
const partialMockNote = computed(() => {
  if (usingMock.value) return undefined
  const parts: string[] = []
  if (rolesUsingMock.value) parts.push('角色列表')
  if (policyUsingMock.value) parts.push('密码策略')
  if (!parts.length) return undefined
  return `${parts.join(' / ')}来自演示数据（真实接口不可达），而用户列表是真实数据 —— 两者不要交叉判断：角色名可能对不上，密码策略也不是现场策略。`
})

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = rows.value
  return {
    total: total.value,
    enabled: list.filter((r) => r.enabled).length,
    disabled: list.filter((r) => !r.enabled).length,
    locked: list.filter((r) => r.locked).length,
  }
})

const lockHintForStat = computed(() => {
  const p = policy.value
  const scope = `本页口径（当前页 ${rows.value.length} 个里的 locked = true）`
  if (!p) return `${scope}；密码策略未加载，阈值未知`
  return p.maxFailedAttempts > 0
    ? `${scope}；阈值：连续失败 ${p.maxFailedAttempts} 次 / 锁定 ${p.lockMinutes} 分钟`
    : `${scope}；未启用失败锁定（maxFailedAttempts = 0）`
})

/**
 * 锁定列的说明文案：失败次数与「再失败几次锁定」都来自 Policy.maxFailedAttempts。
 * 策略没加载出来时**不能**说成「未启用」—— 那是把一个未知事实说成了已知事实。
 */
function failedInfo(row: AdminUser): string {
  const p = policy.value
  if (!p) {
    return `密码策略未加载（${policyError.value ?? '后端接口尚未实现'}），无法判断「还差几次锁定」。该账号已连续失败 ${row.failedAttempts} 次${row.locked ? '，当前处于锁定状态' : ''}。`
  }
  if (p.maxFailedAttempts <= 0) return '未启用失败锁定策略（maxFailedAttempts = 0），登录失败不会自动锁定'
  if (row.locked) {
    return `已锁定：连续失败 ${row.failedAttempts} 次，达到阈值 ${p.maxFailedAttempts}，锁定 ${p.lockMinutes} 分钟，需管理员手动解锁`
  }
  const left = Math.max(p.maxFailedAttempts - row.failedAttempts, 0)
  return `已连续失败 ${row.failedAttempts} 次，再失败 ${left} 次将锁定（阈值 ${p.maxFailedAttempts}）`
}

const policySummary = computed(() => {
  const p = policy.value
  if (!p) return ''
  const parts = [`长度 ≥ ${p.minLength}`]
  if (p.requireUpper) parts.push('含大写字母')
  if (p.requireLower) parts.push('含小写字母')
  if (p.requireDigit) parts.push('含数字')
  if (p.requireSymbol) parts.push('含符号')
  parts.push('不得与登录名相同')
  return parts.join('、')
})

const policyExtra = computed(() => {
  const p = policy.value
  if (!p) return ''
  const facts: string[] = []
  facts.push(
    p.maxFailedAttempts > 0
      ? `连续失败 ${p.maxFailedAttempts} 次锁定 ${p.lockMinutes} 分钟`
      : '未启用登录失败锁定',
  )
  facts.push(p.expireDays > 0 ? `密码 ${p.expireDays} 天过期` : '密码永不过期')
  facts.push(p.forceChangeOnFirstLogin ? '首次登录强制改密' : '首次登录不强制改密')
  facts.push(p.historySize > 0 ? `禁止与最近 ${p.historySize} 次重复` : '不校验历史密码')
  return facts.join('；')
})

/* ------------------------------ 密码策略即时校验 ------------------------------ */

/**
 * 前端即时校验清单（长度 / 大小写 / 数字 / 符号 / 不等于用户名）。
 *
 * ⚠️ **这只是体验，不是安全边界**：策略可能过期，前端也能被绕过，
 * 真正的判定必须在后端。页面上每一处都写明了这一点。
 */
function passwordChecklist(pwd: string, username: string): PasswordCheckItem[] {
  const p = policy.value
  if (!p) return []
  const items: PasswordCheckItem[] = [
    { key: 'minLength', label: `长度不少于 ${p.minLength} 位`, passed: pwd.length >= p.minLength },
  ]
  if (p.requireUpper) items.push({ key: 'upper', label: '包含大写字母', passed: /[A-Z]/.test(pwd) })
  if (p.requireLower) items.push({ key: 'lower', label: '包含小写字母', passed: /[a-z]/.test(pwd) })
  if (p.requireDigit) items.push({ key: 'digit', label: '包含数字', passed: /\d/.test(pwd) })
  if (p.requireSymbol) {
    items.push({ key: 'symbol', label: '包含符号（非字母数字）', passed: /[^A-Za-z0-9]/.test(pwd) })
  }
  items.push({
    key: 'notUsername',
    label: '不等于登录名',
    passed: pwd.length > 0 && pwd.toLowerCase() !== username.trim().toLowerCase(),
  })
  return items
}

function errText(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

function tip(text: string, trigger: () => VNodeChild) {
  return h(NTooltip, { trigger: 'hover' }, { trigger, default: () => text })
}

/** 权限不足时统一拦一刀：按钮已置灰，这里再兜一次（真实态后端也必须拒绝） */
function guardManage(): boolean {
  if (canManage.value) return true
  message.warning(`没有 ${manageKey} 权限：演示态放行、真实态严格判定，此操作已被拦下`)
  return false
}

/* ------------------------------ 演示态本地写入 ------------------------------ */

/**
 * 演示态下的写入。
 *
 * 后端未实现，这里改的是 `mock/system.ts` 里的**内存演示数据**：
 * 不调用真实接口，也**不伪造接口成功** —— 成功提示里一律写明「未调用后端」。
 * 刷新浏览器即回到初始演示数据。**任何密码都不写进这里**（AdminUser 里根本没有密码字段）。
 */
function localPatch(id: number, patch: Partial<AdminUser>) {
  const row = USERS.find((u) => u.id === id)
  if (!row) return
  Object.assign(row, patch, { updatedAt: fmt(new Date()) })
}

function localCreate(payload: AdminUserPayload): AdminUser {
  const row: AdminUser = {
    id: USERS.reduce((max, u) => Math.max(max, u.id), 1000) + 1,
    username: payload.username,
    displayName: payload.displayName ?? null,
    roleIds: [...payload.roleIds],
    enabled: payload.enabled,
    locked: false,
    failedAttempts: 0,
    // 新建账号是否必须改密，按密码策略来（forceChangeOnFirstLogin），不拍脑袋
    mustChangePassword: policy.value?.forceChangeOnFirstLogin ?? true,
    lastLoginTime: null,
    lastLoginIP: null,
    loginCount: 0,
    createdAt: fmt(new Date()),
    updatedAt: fmt(new Date()),
    remark: payload.remark ?? null,
  }
  USERS.unshift(row)
  return row
}

/* ------------------------------ 启用 / 停用 / 解锁 / 删除 ------------------------------ */

async function toggleEnabled(row: AdminUser) {
  if (!guardManage()) return
  const next = !row.enabled

  // usingMock：数据未到手时也不会误判成真实态（见上方 usingMock 说明）
  if (usingMock.value) {
    localPatch(row.id, { enabled: next })
    await reload()
    message.success(`演示态：已${next ? '启用' : '停用'} ${row.username}（未调用后端）`)
    return
  }

  try {
    await adminApi.setUserEnabled(row.id, next)
    message.success(
      next
        ? `已启用 ${row.username}`
        : `已停用 ${row.username}。⚠️ 请确认后端同时让它已签发的 token 失效 —— 只改库字段等于没停。`,
    )
    await reload()
  } catch (err) {
    // 真实态失败：如实报错，本地列表不动
    message.error(`${next ? '启用' : '停用'}失败：${errText(err)}（本地列表未改动）`)
  }
}

async function unlockUser(row: AdminUser) {
  if (!guardManage()) return

  // usingMock：同上，避免窗口期误判
  if (usingMock.value) {
    localPatch(row.id, { locked: false, failedAttempts: 0 })
    await reload()
    message.success(`演示态：已解除 ${row.username} 的锁定（未调用后端）`)
    return
  }

  try {
    await adminApi.unlockUser(row.id)
    message.success(`已解除 ${row.username} 的登录失败锁定`)
    await reload()
  } catch (err) {
    message.error(`解锁失败：${errText(err)}（本地列表未改动）`)
  }
}

async function removeUser(row: AdminUser) {
  if (!guardManage()) return
  if (row.username === 'admin') {
    message.warning('内置账号 admin 不允许删除：删掉超管，后台就再也进不去了')
    return
  }

  // usingMock：同上，避免窗口期误判
  if (usingMock.value) {
    const idx = USERS.findIndex((u) => u.id === row.id)
    if (idx >= 0) USERS.splice(idx, 1)
    await reload()
    message.success(`演示态：已删除 ${row.username}（未调用后端，仅演示数据）`)
    return
  }

  try {
    await adminApi.deleteUser(row.id)
    message.success(`已删除管理账号 ${row.username}`)
    await reload()
  } catch (err) {
    message.error(`删除失败：${errText(err)}（本地列表未改动）`)
  }
}

/* ------------------------------ 表格 ------------------------------ */

const columns = computed<DataTableColumns<AdminUser>>(() => [
  {
    title: '登录名',
    key: 'username',
    width: 150,
    fixed: 'left',
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'primary', size: 'small', onClick: () => openDetail(row) },
        { default: () => h('span', { class: 'mono' }, row.username) },
      ),
  },
  {
    title: '显示名',
    key: 'displayName',
    width: 110,
    render: (row) => orDash(row.displayName),
  },
  {
    title: '角色',
    key: 'roleIds',
    width: 172,
    render: (row) => {
      if (!row.roleIds.length) {
        return tip('这个账号没有分配任何角色：即使能登录，也不会看到任何页面', () =>
          h(
            NTag,
            { size: 'small', round: true, bordered: false, type: 'warning' },
            { default: () => '未分配角色' },
          ),
        )
      }
      return h(
        'div',
        { class: 'role-cell' },
        row.roleIds.map((id) =>
          h(
            NTag,
            { size: 'small', round: true, bordered: false, type: 'info' },
            { default: () => roleNameOf(id) },
          ),
        ),
      )
    },
  },
  {
    title: '状态',
    key: 'enabled',
    width: 88,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.enabled ? 'success' : 'error' },
        { default: () => (row.enabled ? '启用' : '停用') },
      ),
  },
  {
    title: '锁定',
    key: 'locked',
    width: 162,
    render: (row) =>
      tip(failedInfo(row), () => {
        // 锁定行也要把失败次数显示出来（不能只写「已锁定」，那是丢信息）
        const failedTag = h('span', { class: 'text-muted' }, `失败 ${row.failedAttempts} 次`)
        if (row.locked) {
          return h('div', { class: 'lock-cell' }, [
            h(
              NTag,
              { size: 'small', round: true, bordered: false, type: 'error' },
              {
                icon: () => h(NIcon, null, { default: () => h(LockClosedOutline) }),
                default: () => '已锁定',
              },
            ),
            failedTag,
          ])
        }
        // 策略没加载出来时只说事实（失败次数），不编造「阈值」与「未启用」
        if (!policy.value) return failedTag
        const max = policy.value.maxFailedAttempts
        if (max <= 0) return h('span', { class: 'text-muted' }, '未启用')
        return h('span', { class: 'text-muted' }, `失败 ${row.failedAttempts}/${max}`)
      }),
  },
  {
    title: '需改密',
    key: 'mustChangePassword',
    width: 92,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          type: row.mustChangePassword ? 'warning' : 'default',
        },
        { default: () => (row.mustChangePassword ? '需改密' : '正常') },
      ),
  },
  {
    title: '最后登录',
    key: 'lastLoginTime',
    width: 190,
    render: (row) =>
      h('div', { class: 'login-cell' }, [
        h('span', {}, shortDateTime(row.lastLoginTime)),
        h('span', { class: 'mono text-muted' }, `IP ${orDash(row.lastLoginIP)}`),
      ]),
  },
  {
    title: '登录次数',
    key: 'loginCount',
    width: 96,
    align: 'right',
    render: (row) => h('span', { class: 'num' }, thousands(row.loginCount)),
  },
  {
    title: '操作',
    key: 'actions',
    width: 258,
    fixed: 'right',
    render: (row) =>
      h('div', { class: 'row-actions' }, [
        h(
          NButton,
          {
            size: 'tiny',
            quaternary: true,
            disabled: !canManage.value,
            onClick: () => openEdit(row),
          },
          {
            icon: () => h(NIcon, null, { default: () => h(CreateOutline) }),
            default: () => '编辑',
          },
        ),
        h(
          NPopconfirm,
          {
            onPositiveClick: () => toggleEnabled(row),
            positiveText: row.enabled ? '停用' : '启用',
            negativeText: '取消',
            positiveButtonProps: { type: row.enabled ? 'warning' : 'primary' },
          },
          {
            trigger: () =>
              h(
                NButton,
                {
                  size: 'tiny',
                  quaternary: true,
                  type: row.enabled ? 'warning' : 'primary',
                  disabled: !canManage.value,
                },
                {
                  icon: () =>
                    h(NIcon, null, { default: () => h(row.enabled ? BanOutline : PlayOutline) }),
                  default: () => (row.enabled ? '停用' : '启用'),
                },
              ),
            default: () =>
              row.enabled
                ? `确认停用 ${row.username}？该账号立即不能登录，且后端必须让它已签发的 token 失效 —— 只改库字段等于没停。`
                : `确认启用 ${row.username}？`,
          },
        ),
        tip(row.locked ? '解除登录失败锁定' : '该账号当前未被锁定', () =>
          h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              disabled: !canManage.value || !row.locked,
              onClick: () => unlockUser(row),
            },
            { icon: () => h(NIcon, null, { default: () => h(LockOpenOutline) }) },
          ),
        ),
        tip('重置密码（不回显旧密码）', () =>
          h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              disabled: !canManage.value,
              onClick: () => openReset(row),
            },
            { icon: () => h(NIcon, null, { default: () => h(KeyOutline) }) },
          ),
        ),
        renderDelete(row),
      ]),
  },
])

/**
 * 删除按钮单独抽出来：
 * 内置账号 `admin` 不允许删除（删掉超管就再也进不去后台了），
 * 置灰的 `<button>` 不触发鼠标事件，所以用 `<span>` 包一层让 tooltip 还能弹出来。
 */
function renderDelete(row: AdminUser) {
  const isBuiltinAdmin = row.username === 'admin'
  const btn = h(
    NButton,
    {
      size: 'tiny',
      quaternary: true,
      type: 'error',
      disabled: !canManage.value || isBuiltinAdmin,
    },
    { icon: () => h(NIcon, null, { default: () => h(TrashOutline) }) },
  )

  if (isBuiltinAdmin) {
    return tip('内置账号 admin 不允许删除 —— 删掉超管，后台就再也进不去了（现场没有别的恢复手段）', () =>
      h('span', { class: 'act-wrap' }, [btn]),
    )
  }

  if (!canManage.value) {
    return tip(`没有 ${manageKey} 权限`, () => h('span', { class: 'act-wrap' }, [btn]))
  }

  return h(
    NPopconfirm,
    {
      onPositiveClick: () => removeUser(row),
      positiveText: '删除',
      negativeText: '取消',
      positiveButtonProps: { type: 'error' },
    },
    {
      trigger: () => btn,
      default: () => `确认删除管理账号 ${row.username}？删除后无法登录；已产生的审计记录会保留（审计要认人）。`,
    },
  )
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const showDetail = ref(false)
const active = ref<AdminUser | null>(null)

function openDetail(row: AdminUser) {
  active.value = row
  showDetail.value = true
}

function editFromDetail(row: AdminUser) {
  showDetail.value = false
  openEdit(row)
}

/* ------------------------------ 新建 / 编辑抽屉 ------------------------------ */

const showForm = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const formRef = ref<FormInst | null>(null)

const formModel = reactive<{
  username: string
  displayName: string
  roleIds: number[]
  enabled: boolean
  remark: string
  password: string
}>({
  username: '',
  displayName: '',
  roleIds: [],
  enabled: true,
  remark: '',
  password: '',
})

/**
 * 校验规则。
 * `password` 只在**新建**时存在：契约里 `AdminUserPayload.password` 是「仅新建时必填」，
 * 编辑不改密码（改密走「重置密码」），所以编辑时连字段都不渲染。
 */
const userRules = computed<FormRules>(() => {
  const rules: FormRules = {
    username: [
      { required: true, message: '请输入登录名', trigger: ['input', 'blur'] },
      {
        pattern: /^[A-Za-z0-9_.-]{3,32}$/,
        message: '登录名 3-32 位，仅限字母、数字与 _ . -',
        trigger: ['input', 'blur'],
      },
    ],
    displayName: [{ required: true, message: '请输入显示名（审计要认人）', trigger: ['input', 'blur'] }],
    roleIds: [
      {
        required: true,
        validator: (_rule: unknown, value: unknown) => Array.isArray(value) && value.length > 0,
        message: '至少选择一个角色',
        trigger: ['change', 'blur'],
      },
    ],
  }

  if (editingId.value === null) {
    rules.password = [
      { required: true, message: '新建账号必须设置初始密码', trigger: ['input', 'blur'] },
      {
        validator: (_rule: unknown, value: unknown) =>
          typeof value !== 'string' ||
          !policy.value ||
          passwordChecklist(value, formModel.username).every((item) => item.passed),
        message: '初始密码不满足当前密码策略（前端仅作体验校验，后端仍会重新判定）',
        trigger: ['input', 'blur'],
      },
    ]
  }

  return rules
})

function openCreate() {
  if (!guardManage()) return
  editingId.value = null
  Object.assign(formModel, {
    username: '',
    displayName: '',
    roleIds: [],
    enabled: true,
    remark: '',
    password: '',
  })
  showForm.value = true
  if (!policyData.value && !policyLoading.value) void loadPolicy(undefined)
}

function openEdit(row: AdminUser) {
  if (!guardManage()) return
  editingId.value = row.id
  Object.assign(formModel, {
    username: row.username,
    displayName: row.displayName ?? '',
    roleIds: [...row.roleIds],
    enabled: row.enabled,
    remark: row.remark ?? '',
    password: '',
  })
  showForm.value = true
  if (!policyData.value && !policyLoading.value) void loadPolicy(undefined)
}

async function submitUser() {
  if (!guardManage()) return

  try {
    await formRef.value?.validate()
  } catch {
    // naive-ui 会把错误显示在对应字段上，这里不再重复提示
    return
  }

  const payload: AdminUserPayload = {
    username: formModel.username.trim(),
    displayName: formModel.displayName.trim() || null,
    roleIds: [...formModel.roleIds],
    enabled: formModel.enabled,
    remark: formModel.remark.trim() || null,
  }
  const id = editingId.value

  // usingMock：同上，避免窗口期把「保存」误判成真实态
  if (usingMock.value) {
    // 演示态：只改内存演示数据，不调用（也不假装调用）真实接口
    if (id === null) {
      const row = localCreate(payload)
      showForm.value = false
      await reload()
      message.success(
        `演示态：已新建 ${row.username}（未调用后端；初始密码只存在于输入框，不会写进演示数据）`,
      )
    } else {
      localPatch(id, {
        displayName: payload.displayName,
        roleIds: payload.roleIds,
        enabled: payload.enabled,
        remark: payload.remark,
      })
      showForm.value = false
      await reload()
      message.success(`演示态：已保存 ${payload.username} 的改动（未调用后端；登录名保持不变）`)
    }
    return
  }

  submitting.value = true
  try {
    if (id === null) {
      payload.password = formModel.password
      await adminApi.createUser(payload)
      message.success(`已新建管理账号 ${payload.username}`)
    } else {
      // 编辑不带 password：改密必须走「重置密码」（后端要单独写审计）
      await adminApi.updateUser(id, payload)
      message.success(`已保存 ${payload.username} 的改动`)
    }
    showForm.value = false
    await reload()
  } catch (err) {
    // 真实态失败：如实报错，本地列表不动
    message.error(`${id === null ? '新建' : '保存'}失败：${errText(err)}`)
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 重置密码 ------------------------------ */

const showReset = ref(false)
const resetTarget = ref<AdminUser | null>(null)
const resetSubmitting = ref(false)
const resetForm = reactive({ password: '', confirm: '', forceChange: true })

const resetChecklist = computed<PasswordCheckItem[]>(() =>
  policy.value ? passwordChecklist(resetForm.password, resetTarget.value?.username ?? '') : [],
)
const resetPolicyPassed = computed(() => resetChecklist.value.every((item) => item.passed))
const resetConfirmMatched = computed(
  () => resetForm.password.length > 0 && resetForm.password === resetForm.confirm,
)

function openReset(row: AdminUser) {
  if (!guardManage()) return
  resetTarget.value = row
  resetForm.password = ''
  resetForm.confirm = ''
  resetForm.forceChange = true
  showReset.value = true
  if (!policyData.value && !policyLoading.value) void loadPolicy(undefined)
}

function useDemoPassword() {
  resetForm.password = DEMO_ADMIN_PASSWORD
  resetForm.confirm = DEMO_ADMIN_PASSWORD
}

async function submitReset() {
  const target = resetTarget.value
  if (!target) return
  if (!guardManage()) return

  if (!resetForm.password) {
    message.warning('请输入新密码')
    return
  }
  if (!resetConfirmMatched.value) {
    message.warning('两次输入的密码不一致')
    return
  }
  if (policy.value && !resetPolicyPassed.value) {
    message.warning('新密码不满足当前密码策略（前端只是体验校验，真正的判定必须在后端）')
    return
  }

  // usingMock：同上，避免窗口期把「重置」误判成真实态
  if (usingMock.value) {
    // 演示态：没有后端可调，**不假装重置成功**，也不写入任何密码
    showReset.value = false
    if (resetForm.forceChange) {
      localPatch(target.id, { mustChangePassword: true })
      await reload()
      message.success(
        `演示态：未调用后端、未写入任何密码；仅把 ${target.username} 标记为「需改密」`,
      )
    } else {
      message.success(
        `演示态：未调用后端、未写入任何密码、未改动任何字段（${target.username} 的密码在演示数据里本就不存在）`,
      )
    }
    return
  }

  resetSubmitting.value = true
  try {
    if (resetForm.forceChange) {
      await adminApi.resetPasswordAndForceChange(target.id, resetForm.password)
    } else {
      await adminApi.resetPassword(target.id, resetForm.password)
    }
    showReset.value = false
    message.success(
      `已重置 ${target.username} 的密码${resetForm.forceChange ? '，下次登录需修改' : ''}`,
    )
    await reload()
  } catch (err) {
    message.error(`重置密码失败：${errText(err)}（本地列表未改动）`)
  } finally {
    resetSubmitting.value = false
  }
}
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />
    <QueryErrorAlert
      v-if="rolesError"
      :message="`角色列表加载失败：${rolesError}。角色筛选与角色名解析不可用 —— 这不等于「没有角色」。`"
      :loading="rolesLoading"
      @retry="loadRoles(undefined)"
    />
    <MockDataAlert v-if="isMock" :reason="mockReason" />

    <n-alert v-if="partialMockNote" class="page-card" type="warning" :bordered="false" size="small">
      <template #header>部分数据源已降级到演示数据</template>
      {{ partialMockNote }}
    </n-alert>

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="管理账号总数"
          :value="summary.total"
          suffix="个"
          :icon="PeopleOutline"
          :color="accent"
          hint="后端返回的 total（当前筛选条件下，含未展示的页）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="已启用"
          :value="summary.enabled"
          suffix="个"
          :icon="CheckmarkCircleOutline"
          color="#2f9e5f"
          :hint="`本页口径（当前页 ${rows.length} 个里的 enabled = true）`"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="已停用"
          :value="summary.disabled"
          suffix="个"
          :icon="BanOutline"
          color="#d6455e"
          :hint="`本页口径（当前页 ${rows.length} 个里的 enabled = false）`"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="锁定中"
          :value="summary.locked"
          suffix="个"
          :icon="LockClosedOutline"
          color="#dd7a15"
          :hint="lockHintForStat"
        />
      </n-gi>
    </n-grid>

    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="PeopleOutline" :color="accent" :size="17" />
          管理账号列表
        </span>
      </template>

      <div class="toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索登录名 / 显示名 / 备注"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>

        <n-select
          v-model:value="roleFilter"
          :options="roleOptions"
          :loading="rolesLoading"
          placeholder="全部角色"
          clearable
          style="width: 190px"
          @update:value="handleSearch"
        />

        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          style="width: 140px"
          @update:value="handleSearch"
        />

        <n-button size="small" @click="resetFilters">
          <template #icon>
            <n-icon :component="CloseOutline" />
          </template>
          重置
        </n-button>

        <n-button size="small" :loading="loading" @click="reload">
          <template #icon>
            <n-icon :component="RefreshOutline" />
          </template>
          刷新
        </n-button>

        <div class="spacer" />

        <n-button type="primary" size="small" :disabled="!canManage" @click="openCreate">
          <template #icon>
            <n-icon :component="PersonAddOutline" />
          </template>
          新建管理账号
        </n-button>
      </div>

      <n-data-table
        remote
        :columns="columns"
        :data="rows"
        :loading="loading"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: AdminUser) => String(row.id)"
        :scroll-x="1340"
        :pagination="{
          page: page,
          pageSize: pageSize,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50],
          onUpdatePage: (p: number) => { page = p; reload() },
          onUpdatePageSize: (s: number) => { pageSize = s; page = 1; reload() },
          prefix: ({ itemCount }: { itemCount: number }) => `共 ${itemCount} 个管理账号`,
        }"
      />
    </n-card>

    <!-- 账号详情 -->
    <n-drawer v-model:show="showDetail" :width="560" placement="right">
      <n-drawer-content :title="`管理账号详情 · ${active?.username ?? ''}`" closable>
        <template v-if="active">
          <n-descriptions :column="2" label-placement="top" size="small" bordered>
            <n-descriptions-item label="登录名">
              <span class="mono">{{ active.username }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="显示名">{{ orDash(active.displayName) }}</n-descriptions-item>
            <n-descriptions-item label="角色">
              <div v-if="active.roleIds.length" class="role-cell">
                <n-tag
                  v-for="id in active.roleIds"
                  :key="id"
                  size="small"
                  round
                  :bordered="false"
                  type="info"
                >
                  {{ roleNameOf(id) }}
                </n-tag>
              </div>
              <n-tag v-else size="small" round :bordered="false" type="warning">未分配角色</n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag size="small" round :bordered="false" :type="active.enabled ? 'success' : 'error'">
                {{ active.enabled ? '启用' : '停用' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="锁定">
              <n-tag size="small" round :bordered="false" :type="active.locked ? 'error' : 'default'">
                {{ active.locked ? '已锁定' : '未锁定' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="失败次数">
              {{ active.failedAttempts }}
            </n-descriptions-item>
            <n-descriptions-item label="需改密">
              <n-tag
                size="small"
                round
                :bordered="false"
                :type="active.mustChangePassword ? 'warning' : 'default'"
              >
                {{ active.mustChangePassword ? '需改密' : '正常' }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="最后登录时间">
              {{ shortDateTime(active.lastLoginTime) }}
            </n-descriptions-item>
            <n-descriptions-item label="最后登录 IP">
              <span class="mono">{{ orDash(active.lastLoginIP) }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="登录次数">{{ thousands(active.loginCount) }}</n-descriptions-item>
            <n-descriptions-item label="创建时间">{{ shortDateTime(active.createdAt) }}</n-descriptions-item>
            <n-descriptions-item label="更新时间">{{ shortDateTime(active.updatedAt) }}</n-descriptions-item>
            <n-descriptions-item label="备注" :span="2">{{ orDash(active.remark) }}</n-descriptions-item>
          </n-descriptions>

          <div class="detail-note">
            <n-icon :component="InformationCircleOutline" :color="accent" />
            <div>
              {{ failedInfo(active) }}
              <br />
              后端落地时的表：<code>T_AdminUser</code>（本页）、<code>T_AdminRole</code>（角色）、
              <code>T_AdminLoginLog</code>（管理端登录日志）。密码字段<b>只写不读</b>，
              接口不得回显明文或哈希。
            </div>
          </div>
        </template>

        <template #footer>
          <div class="drawer-footer">
            <n-button
              size="small"
              type="primary"
              :disabled="!canManage"
              @click="active && editFromDetail(active)"
            >
              <template #icon>
                <n-icon :component="CreateOutline" />
              </template>
              编辑
            </n-button>
            <n-button size="small" :disabled="!canManage" @click="active && openReset(active)">
              <template #icon>
                <n-icon :component="KeyOutline" />
              </template>
              重置密码
            </n-button>
            <div class="spacer" />
            <n-button size="small" @click="showDetail = false">关闭</n-button>
          </div>
        </template>
      </n-drawer-content>
    </n-drawer>

    <!-- 新建 / 编辑 -->
    <n-drawer v-model:show="showForm" :width="560" placement="right">
      <n-drawer-content
        :title="editingId === null ? '新建管理账号' : `编辑管理账号 · ${formModel.username}`"
        closable
      >
        <n-alert v-if="editingId !== null" type="info" :bordered="false" size="small" style="margin-bottom: 14px">
          登录名<b>不可修改</b>：改名会让审计认不出人（<code>T_AdminAudit.operator</code> 存的就是登录名）。
          编辑也<b>不会</b>改密码 —— 改密请用列表里的「重置密码」。
        </n-alert>

        <n-form
          ref="formRef"
          :model="formModel"
          :rules="userRules"
          label-placement="top"
          require-mark-placement="right-hanging"
        >
          <n-form-item label="登录名" path="username">
            <n-input
              v-model:value="formModel.username"
              :readonly="editingId !== null"
              placeholder="如 yangsh（3-32 位字母 / 数字 / _ . -）"
            />
          </n-form-item>

          <n-form-item label="显示名" path="displayName">
            <n-input v-model:value="formModel.displayName" placeholder="真实姓名或岗位，审计要认人" />
          </n-form-item>

          <n-form-item label="角色（可多选，至少一个）" path="roleIds">
            <n-select
              v-model:value="formModel.roleIds"
              :options="roleOptions"
              :loading="rolesLoading"
              multiple
              placeholder="选择角色"
            />
          </n-form-item>

          <n-form-item label="启用" path="enabled">
            <div class="switch-row">
              <n-switch v-model:value="formModel.enabled" />
              <span class="form-hint">
                停用后该账号不能登录；后端必须<b>同时让它已签发的 token 失效</b>，否则等于没停。
              </span>
            </div>
          </n-form-item>

          <n-form-item v-if="editingId === null" label="初始密码" path="password">
            <n-input
              v-model:value="formModel.password"
              type="password"
              show-password-on="click"
              placeholder="新建必须设置初始密码"
            />
          </n-form-item>

          <n-form-item label="备注">
            <n-input
              v-model:value="formModel.remark"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 4 }"
              placeholder="为什么建这个账号 / 什么时候该停用"
            />
          </n-form-item>
        </n-form>

        <div class="policy-line">
          <n-icon :component="InformationCircleOutline" :color="accent" />
          <div>
            <template v-if="policy">
              当前密码策略（来自 <code>getPasswordPolicy()</code>）：{{ policySummary }}。
              <br />
              {{ policyExtra }}。
              <br />
              <b>前端校验只是体验，真正的判定必须在后端</b> —— 前端规则可能过期，也不该成为安全边界。
            </template>
            <template v-else>
              密码策略未加载（{{ policyError || '后端接口尚未实现' }}）：本次只做必填与格式校验。
              在策略拿到之前，<b>无法判断</b>密码是否满足现场要求 ——
              请不要据此认为密码已经合规，<b>真正的判定必须在后端</b>。
            </template>
          </div>
        </div>

        <template #footer>
          <div class="drawer-footer">
            <div class="spacer" />
            <n-button size="small" @click="showForm = false">取消</n-button>
            <n-button
              size="small"
              type="primary"
              :loading="submitting"
              :disabled="!canManage"
              @click="submitUser"
            >
              {{ editingId === null ? '新建' : '保存' }}
            </n-button>
          </div>
        </template>
      </n-drawer-content>
    </n-drawer>

    <!-- 重置密码 -->
    <n-modal
      v-model:show="showReset"
      preset="card"
      style="width: 580px"
      :title="`重置密码 · ${resetTarget?.username ?? ''}`"
      :mask-closable="false"
    >
      <n-alert type="warning" :bordered="false" size="small" style="margin-bottom: 14px">
        新密码<b>只在这一个请求里出现</b>，接口不得回显；后端应写审计（谁在什么时候重置了谁），
        但<b>不记录密码本身</b>。本页<b>不回显任何已存在的密码</b>，也没有任何地方保存它。
        <span v-if="isMock">
          当前是演示态：点「确认重置」<b>不会调用后端、也不会真的改任何密码</b>。
        </span>
      </n-alert>

      <n-form label-placement="top">
        <n-form-item label="新密码">
          <n-input
            v-model:value="resetForm.password"
            type="password"
            show-password-on="click"
            placeholder="输入新密码"
          />
        </n-form-item>
        <n-form-item
          label="确认新密码"
          :validation-status="resetForm.confirm && !resetConfirmMatched ? 'error' : undefined"
          :feedback="
            resetForm.confirm && !resetConfirmMatched ? '两次输入的密码不一致' : undefined
          "
        >
          <n-input
            v-model:value="resetForm.confirm"
            type="password"
            show-password-on="click"
            placeholder="再输一次"
          />
        </n-form-item>
      </n-form>

      <div class="check-block">
        <div class="check-title">密码策略即时校验</div>
        <ul v-if="resetChecklist.length" class="check-list">
          <li
            v-for="item in resetChecklist"
            :key="item.key"
            :class="item.passed ? 'check-ok' : 'check-bad'"
          >
            <n-icon :component="item.passed ? CheckmarkCircleOutline : CloseCircleOutline" />
            <span>{{ item.label }}</span>
          </li>
        </ul>
        <div v-else class="check-empty">
          密码策略未加载（{{ policyError || '后端接口尚未实现' }}），本次只做「必填」与「两次一致」校验
          —— <b>无法判断</b>密码是否满足现场要求，请不要据此认为已经合规。
        </div>
        <div v-if="policyExtra" class="check-facts">{{ policyExtra }}</div>
        <div class="check-note">
          <b>前端校验只是体验，真正的判定必须在后端。</b>
        </div>
      </div>

      <div class="reset-actions">
        <template v-if="isMock">
          <n-button size="tiny" secondary @click="useDemoPassword">
            使用演示密码 <span class="mono">{{ DEMO_ADMIN_PASSWORD }}</span>
          </n-button>
          <span class="text-muted reset-hint">
            仅演示态使用：这是编的密码，真实环境请勿使用固定密码。
          </span>
        </template>
        <span v-else class="text-muted reset-hint">
          非演示态不提供固定的演示密码：请由管理员设定强密码并当面交付，重置后写审计。
        </span>
      </div>

      <div class="switch-row" style="margin-top: 14px">
        <n-switch v-model:value="resetForm.forceChange" />
        <span class="form-hint">
          同时强制该账号<b>下次登录修改</b>（调用 <code>resetPasswordAndForceChange</code>，推荐做法）
        </span>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <div class="spacer" />
          <n-button size="small" @click="showReset = false">取消</n-button>
          <n-button
            size="small"
            type="primary"
            :loading="resetSubmitting"
            :disabled="!canManage"
            @click="submitReset"
          >
            确认重置
          </n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}

.spacer {
  flex: 1;
}

.num {
  font-variant-numeric: tabular-nums;
}

.policy-line code,
.detail-note code,
.form-hint code,
.check-note code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.role-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.lock-cell {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
}

.login-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  font-size: 12.5px;
}

.row-actions {
  display: flex;
  gap: 2px;
  align-items: center;
}

/* 置灰的 <button> 不触发鼠标事件，tooltip 挂在外层 span 上 */
.act-wrap {
  display: inline-flex;
}

.drawer-footer {
  display: flex;
  gap: 8px;
  align-items: center;
}

.detail-note {
  display: flex;
  gap: 10px;
  margin-top: 16px;
  padding: 12px 14px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid v-bind(accent);
}

.policy-line {
  display: flex;
  gap: 9px;
  margin-top: 4px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--text-secondary);
  background: var(--bg-body);
  border-radius: var(--radius-sm);
  border-left: 3px solid v-bind(accent);
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.form-hint {
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}

.check-block {
  padding: 12px 14px;
  background: var(--bg-body);
  border-radius: var(--radius-sm);
}

.check-title {
  margin-bottom: 8px;
  font-size: 12.5px;
  font-weight: 600;
}

.check-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px 14px;
  font-size: 12.5px;
}

.check-list li {
  display: flex;
  align-items: center;
  gap: 6px;
}

.check-ok {
  color: var(--success);
}

.check-bad {
  color: var(--danger);
}

.check-empty {
  font-size: 12.5px;
  color: var(--text-muted);
}

.check-facts {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}

.check-note {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-secondary);
}

.reset-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.reset-hint {
  font-size: 12px;
}
</style>
