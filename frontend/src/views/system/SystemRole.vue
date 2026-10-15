<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NIcon,
  NInput,
  NPopconfirm,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import {
  AddOutline,
  CreateOutline,
  KeyOutline,
  LockClosedOutline,
  PeopleOutline,
  RefreshOutline,
  SaveOutline,
  SearchOutline,
  ShieldCheckmarkOutline,
  StarOutline,
  TrashOutline,
} from '@vicons/ionicons5'

import PermissionTree from '@/components/PermissionTree.vue'
import QueryErrorAlert from '@/components/QueryErrorAlert.vue'
import StatCard from '@/components/StatCard.vue'
import { adminApi } from '@/api/endpoints'
import { mockAdminApi } from '@/api/mock/system'
import { withDelay } from '@/api/mock/_shared'
import type { AdminRole, AdminRolePayload } from '@/api/domain'
import {
  PERMISSION_WILDCARD,
  actionPermissionKey,
  allPermissionKeys,
} from '@/config/permissions'
import { useResource } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import { orDash, shortDateTime } from '@/utils/format'

/**
 * 角色与权限页 —— 系统管理里权限最大的一页（改权限 = 改所有人的可见范围）。
 *
 * 三条设计立场：
 *
 * 1. **权限键不是另造一套字符串**，就是 `src/config/permissions.ts` 里挂在菜单树上的
 *    `menu:<菜单name>` 与 `action:<模块>:<动作>`，超管用通配符 `*`。
 *    权限树由 `buildPermissionTree()` 从菜单树推导（在 PermissionTree.vue 内），
 *    所以「加一个菜单」权限树自动多一项，不会两处对不上。
 *
 * 2. **显示勾上 = 真的有权限**。`hasPermission` 不做父级隐式放行
 *    （`menu:x` 绝不自动覆盖 `action:x:y`），而 naive-ui 的 cascade 会把已勾选
 *    父节点展开成全部子孙 —— 两者叠加会造成静默提权。PermissionTree.vue 已把
 *    所有真实权限键下沉为叶子、合成父节点设为不可勾选，本页只做数据绑定。
 *
 * 3. **演示态不伪造成功**。写操作直调 `adminApi.*`（不套 `useResource`，那套是给
 *    读接口用的）。演示态下**不调用真实接口**，只更新本地列表并明确标注
 *    「仅更新本地列表」；真实态失败一律 `message.error(错误信息)`。
 *    后端 `backend/` 是空的，`/system/roles` 尚未实现 —— 绝不假装保存成功。
 */

const message = useMessage()
const auth = useAuthStore()

/** 系统管理模块主色：石板灰 */
const accent = '#5b6b8c'

/** 新建角色的 code 规则（任务契约）：小写字母开头，仅小写字母/数字/下划线，长度 2~32 */
const CODE_RE = /^[a-z][a-z0-9_]{1,31}$/

/** 是否可以管理角色（演示态放行；真实态严格按权限键判定） */
const canManage = computed(() => auth.can(actionPermissionKey('system-role', 'manage')))

/** 契约里的权限点总数（演示数据里的角色权限点数量都以此为分母） */
const totalPermissionKeys = allPermissionKeys().length

/* ------------------------------ 角色列表取数 ------------------------------ */

const { data, loading, error, usingMock, load } = useResource(
  () => adminApi.listRoles(),
  // 演示态返回副本：本页的写操作要就地改列表，不能污染 mock 模块里的原始数组
  { mock: () => withDelay(mockAdminApi.listRoles().map((role) => ({ ...role, permissions: [...role.permissions] }))) },
)

const roles = computed<AdminRole[]>(() => data.value ?? [])

/**
 * 写操作走哪条路 = `useResource` 给的 `usingMock`。
 *
 * ⚠️ **不要再在本页自己算一遍**（如 `data.value !== undefined ? isMock.value : USE_MOCK`）。
 * 本页原先就是这么写的，注释里还写着「列表还没回来时写按钮也点不到」——
 * 那个前提是**错的**：演示态下按钮本来就点得到（权限判定在演示态无条件放行），
 * 于是「首个请求返回前点写操作」会走进真实分支、去调还没实现的后端。
 * `usingMock` 已经把「数据未到手时退回构建期常量 `USE_MOCK`」这件事做在
 * `useResource` 里了，全站只有这一份实现 —— 各页自己再写一遍，
 * 就是同一个坑换个地方复发（README 第 5b 条）。
 */

const keyword = ref('')

const filteredRoles = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return roles.value
  return roles.value.filter(
    (role) =>
      role.name.toLowerCase().includes(kw) ||
      role.code.toLowerCase().includes(kw) ||
      (role.desc ?? '').toLowerCase().includes(kw),
  )
})

function isWildcardRole(role: AdminRole | null | undefined): boolean {
  return !!role && role.permissions.includes(PERMISSION_WILDCARD)
}

/* ------------------------------ 汇总 ------------------------------ */

const summary = computed(() => {
  const list = roles.value
  const wildcard = list.filter(isWildcardRole)
  // 「权限点总数」按契约口径统计：全部菜单权限 + 全部操作权限
  return {
    roleCount: list.length,
    builtinCount: list.filter((r) => r.builtin).length,
    permissionTotal: totalPermissionKeys,
    superAdmins: wildcard.map((r) => r.code),
  }
})

/* ------------------------------ 权限草稿（编辑器用） ------------------------------ */

/**
 * 权限编辑**只在「编辑角色」抽屉里做**。
 *
 * 此前页面右侧还常驻一块「权限分配」面板，与抽屉里的权限树功能重复
 * （两处都能改同一份 permissions），且把 7 列的角色表格挤到横向滚动。
 * 现已删除右侧面板：列表保持全宽，改权限一律走操作列的「编辑」抽屉。
 */

/* ------------------------------ 本地写入（演示态） ------------------------------ */

const saving = ref(false)

function errText(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

/** 'YYYY-MM-DD HH:mm:ss'（不引 mock 工具，真实态也用不到它） */
function nowText(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  )
}

/** 演示态的本地更新：只改本页列表，**不调用真实接口**，界面上会说清这一点 */
function applyLocalUpdate(id: number, payload: AdminRolePayload) {
  const now = nowText()
  data.value = (data.value ?? []).map((role) =>
    role.id === id
      ? {
          ...role,
          code: payload.code,
          name: payload.name,
          desc: payload.desc ?? null,
          permissions: [...payload.permissions],
          updatedAt: now,
        }
      : role,
  )
}

function applyLocalCreate(payload: AdminRolePayload): AdminRole {
  const list = data.value ?? []
  const now = nowText()
  const row: AdminRole = {
    id: list.reduce((max, role) => Math.max(max, role.id), 0) + 1,
    code: payload.code,
    name: payload.name,
    desc: payload.desc ?? null,
    builtin: false,
    permissions: [...payload.permissions],
    userCount: 0,
    createdAt: now,
    updatedAt: now,
  }
  data.value = [...list, row]
  return row
}

function applyLocalDelete(id: number) {
  data.value = (data.value ?? []).filter((role) => role.id !== id)
}

function reload() {
  return load()
}

onMounted(reload)

/* ------------------------------ 删除角色 ------------------------------ */

async function removeRole(role: AdminRole) {
  if (role.builtin) {
    message.warning('内置角色不可删除')
    return
  }
  if (!canManage.value) return

  saving.value = true
  try {
    if (usingMock.value) {
      applyLocalDelete(role.id)
      message.success('角色已删除（演示态：仅更新本地列表，未调用真实接口）')
    } else {
      await adminApi.deleteRole(role.id)
      message.success(`已删除角色「${role.name}」`)
      reload()
    }
  } catch (err) {
    message.error(`删除角色失败：${errText(err)}`)
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 新建 / 编辑表单 ------------------------------ */

const showForm = ref(false)
const editingId = ref<number | null>(null)
const editingBuiltin = ref(false)
const form = reactive({ code: '', name: '', desc: '' })
const formPermissions = ref<string[]>([])

/**
 * 正在编辑的角色是否为通配符角色。
 *
 * ⚠️ **不能用 `formPermissions.value.includes('*')` 判断。**
 * `openEdit` 对通配符角色会把草稿置空（树里只有真实键，`*` 不在树里），
 * 于是那个判断恒为 `false`，连锁造成两个错误：
 *   1. 编辑超管角色时不显示「这是通配符角色」的提示，而是渲染一棵空权限树；
 *   2. 提交时走 `[...formPermissions]` 分支 —— 若用户顺手勾了几项再保存，
 *      `*` 会被替换成那几个具体键，**超管被静默降权**（后端若也照此落库，
 *      等于后台再也进不去）。
 * 所以这里必须独立记录「被编辑的角色本身是不是通配符」，与草稿无关。
 */
const editingWildcard = ref(false)
const isCreating = computed(() => editingId.value === null)

function openCreate() {
  editingId.value = null
  editingBuiltin.value = false
  editingWildcard.value = false
  form.code = ''
  form.name = ''
  form.desc = ''
  formPermissions.value = []
  showForm.value = true
}

function openEdit(role: AdminRole) {
  editingId.value = role.id
  editingBuiltin.value = role.builtin
  editingWildcard.value = isWildcardRole(role)
  form.code = role.code
  form.name = role.name
  form.desc = role.desc ?? ''
  // 通配符角色不走树（树里只有真实键），草稿保持空，提交时原样保留 `*`
  formPermissions.value = isWildcardRole(role) ? [] : [...role.permissions]
  showForm.value = true
}

async function submitForm() {
  const code = form.code.trim()
  const name = form.name.trim()
  const creating = isCreating.value

  if (creating) {
    if (!CODE_RE.test(code)) {
      message.error('角色编码需以小写字母开头，只能含小写字母、数字与下划线，长度 2~32（例：ops_readonly）')
      return
    }
    if (roles.value.some((role) => role.code === code)) {
      message.error(`角色编码「${code}」已存在，请换一个`)
      return
    }
  }
  if (!name) {
    message.error('请填写角色名称')
    return
  }
  if (!editingWildcard.value && !formPermissions.value.length) {
    message.error('至少选择一个权限点：一个权限都没有的角色，登录后什么都看不到')
    return
  }

  const target = editingId.value === null ? null : roles.value.find((role) => role.id === editingId.value) ?? null

  const payload: AdminRolePayload = {
    code: creating ? code : target?.code ?? code,
    name,
    desc: form.desc.trim() || null,
    // 通配符角色的权限是 `*`，不由树决定，原样保留
    permissions: editingWildcard.value ? [PERMISSION_WILDCARD] : [...formPermissions.value],
  }

  saving.value = true
  try {
    if (usingMock.value) {
      if (creating) {
        applyLocalCreate(payload)
        message.success('角色已新建（演示态：仅更新本地列表，未调用真实接口）')
      } else if (target) {
        applyLocalUpdate(target.id, payload)
        message.success('角色已保存（演示态：仅更新本地列表，未调用真实接口）')
      }
    } else if (creating) {
      await adminApi.createRole(payload)
      message.success(`已新建角色「${payload.name}」`)
      reload()
    } else if (target) {
      await adminApi.updateRole(target.id, payload)
      message.success(`已保存角色「${target.name}」`)
      reload()
    }
    showForm.value = false
  } catch (err) {
    message.error(`${creating ? '新建' : '保存'}角色失败：${errText(err)}`)
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 表格 ------------------------------ */

const columns: DataTableColumns<AdminRole> = [
  {
    title: '角色',
    key: 'name',
    width: 168,
    fixed: 'left',
    render: (row) =>
      h('div', { class: 'role-name-cell' }, [
        h(
          NButton,
          // 角色名直接打开编辑抽屉（原先只是「选中」给右侧面板用，面板已删）
          { text: true, type: 'primary', size: 'small', onClick: () => openEdit(row) },
          { default: () => row.name },
        ),
        row.builtin
          ? h(
              NTooltip,
              { trigger: 'hover' },
              {
                trigger: () =>
                  h(
                    NTag,
                    { size: 'tiny', round: true, bordered: false, type: 'info' },
                    {
                      icon: () => h(NIcon, null, { default: () => h(LockClosedOutline) }),
                      default: () => '内置',
                    },
                  ),
                default: () => '内置角色：不可删除、code 与名称不可改',
              },
            )
          : null,
      ]),
  },
  {
    title: 'code',
    key: 'code',
    width: 138,
    render: (row) => h('span', { class: 'mono' }, row.code),
  },
  {
    title: '权限点',
    key: 'permissions',
    width: 132,
    render: (row) =>
      isWildcardRole(row)
        ? h(
            NTag,
            { size: 'small', round: true, bordered: false, type: 'warning' },
            { default: () => '全部（*）' },
          )
        : h(
            NTag,
            { size: 'small', round: true, bordered: false, type: 'info' },
            { default: () => `${row.permissions.length}` },
          ),
  },
  {
    title: '引用人数',
    key: 'userCount',
    width: 96,
    render: (row) =>
      row.userCount > 0
        ? h(
            NTooltip,
            { trigger: 'hover' },
            {
              trigger: () => h('span', { class: 'count-cell' }, `${row.userCount}`),
              default: () => `有 ${row.userCount} 个管理账号在用这个角色，删角色会影响他们`,
            },
          )
        : h('span', { class: 'text-muted' }, '0'),
  },
  {
    title: '说明',
    key: 'desc',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: (row) => orDash(row.desc),
  },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 140,
    render: (row) => shortDateTime(row.updatedAt),
  },
  {
    title: '操作',
    key: 'actions',
    width: 92,
    fixed: 'right',
    render: (row) => {
      const editButton = h(
        NButton,
        {
          size: 'tiny',
          quaternary: true,
          disabled: !canManage.value,
          onClick: () => openEdit(row),
        },
        { icon: () => h(NIcon, null, { default: () => h(CreateOutline) }) },
      )

      if (row.builtin) {
        return h('div', { class: 'row-actions' }, [
          editButton,
          h(
            NTooltip,
            { trigger: 'hover' },
            {
              trigger: () =>
                h(
                  NButton,
                  { size: 'tiny', quaternary: true, type: 'error', disabled: true },
                  { icon: () => h(NIcon, null, { default: () => h(TrashOutline) }) },
                ),
              default: () => '内置角色不可删除。删掉超管角色会让后台再也进不去 —— 现场没有别的恢复手段。',
            },
          ),
        ])
      }

      return h('div', { class: 'row-actions' }, [
        editButton,
        h(
          NPopconfirm,
          {
            positiveText: '删除',
            negativeText: '取消',
            onPositiveClick: () => removeRole(row),
          },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'error', disabled: !canManage.value },
                { icon: () => h(NIcon, null, { default: () => h(TrashOutline) }) },
              ),
            default: () =>
              row.userCount > 0
                ? `确认删除角色「${row.name}」？仍有 ${row.userCount} 个管理账号在用这个角色，删除后他们会失去权限。此操作不可撤销。`
                : `确认删除角色「${row.name}」？此操作不可撤销。`,
          },
        ),
      ])
    },
  },
]
</script>

<template>
  <div>
    <QueryErrorAlert v-if="error" :message="error" :loading="loading" @retry="reload" />

    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <StatCard
          label="角色数"
          :value="summary.roleCount"
          suffix="个"
          :icon="ShieldCheckmarkOutline"
          :color="accent"
          hint="内置 + 自定义"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="内置角色"
          :value="summary.builtinCount"
          suffix="个"
          :icon="LockClosedOutline"
          color="#dd7a15"
          hint="不可删除，code / 名称不可改"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="权限点总数"
          :value="summary.permissionTotal"
          suffix="个"
          :icon="KeyOutline"
          color="#3b6ff5"
          hint="菜单权限 + 操作权限（契约口径）"
        />
      </n-gi>
      <n-gi>
        <StatCard
          label="超管角色"
          :value="summary.superAdmins.length"
          suffix="个"
          :icon="StarOutline"
          color="#d6455e"
          :hint="summary.superAdmins.length ? `通配符 *：${summary.superAdmins.join('、')}` : '没有通配符角色，需要现场确认'"
        />
      </n-gi>
    </n-grid>

    <div class="role-layout">
      <!-- 左：角色列表 -->
      <n-card class="page-card role-list-card" size="small" :bordered="false">
        <template #header>
          <span class="section-title">
            <n-icon :component="PeopleOutline" :color="accent" :size="17" />
            角色列表
          </span>
        </template>

        <div class="toolbar">
          <n-input
            v-model:value="keyword"
            placeholder="搜索角色名 / code / 说明"
            clearable
            size="small"
            style="width: 190px"
          >
            <template #prefix>
              <n-icon :component="SearchOutline" />
            </template>
          </n-input>
          <n-button size="small" :loading="loading" @click="reload">
            <template #icon>
              <n-icon :component="RefreshOutline" />
            </template>
            刷新
          </n-button>
          <n-button size="small" type="primary" :disabled="!canManage" @click="openCreate">
            <template #icon>
              <n-icon :component="AddOutline" />
            </template>
            新建角色
          </n-button>
        </div>

        <n-data-table
          class="role-table"
          :columns="columns"
          :data="filteredRoles"
          :loading="loading"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: AdminRole) => row.id"
          :scroll-x="1000"
          :pagination="false"
        />

        <div class="table-foot">
          共 {{ filteredRoles.length }} 个角色<template v-if="keyword.trim()">（已按关键词过滤）</template>。
          点操作列的「编辑」即可调整它的基本信息与权限。
        </div>
      </n-card>
    </div>

    <!-- 新建 / 编辑角色 -->
    <AppDialog
      v-model:show="showForm"
      :title="isCreating ? '新建角色' : `编辑角色 · ${form.code}`"
      :width="640"
      :mask-closable="false"
      :close-on-esc="false"
    >
      <n-alert v-if="editingBuiltin" type="info" :bordered="false" size="small" style="margin-bottom: 14px">
        <template #header>这是内置角色</template>
        内置角色的 <code>code</code> 与名称<b>不可修改</b>（审计要靠 code 认人，改名会让审计记录对不上）。
        权限仍可调整，但请谨慎：<b>改权限等于改所有用这个角色的人的可见范围</b>。
      </n-alert>

      <n-form label-placement="top" size="small">
        <n-form-item label="角色编码 code">
          <n-input
            v-model:value="form.code"
            placeholder="例：ops_readonly"
            :disabled="!isCreating"
            :input-props="{ autocapitalize: 'off', autocomplete: 'off' }"
          />
        </n-form-item>
        <div class="field-hint">
          <template v-if="isCreating">
            小写字母开头，只能含小写字母、数字与下划线，长度 2~32。<b>创建后不可修改</b> ——
            审计记录靠 code 认人，改名会让历史记录对不上。
          </template>
          <template v-else>角色编码创建后不可修改。</template>
        </div>

        <n-form-item label="角色名称">
          <n-input
            v-model:value="form.name"
            placeholder="例：只读观察者"
            :disabled="editingBuiltin"
            maxlength="32"
            show-count
          />
        </n-form-item>
        <div v-if="editingBuiltin" class="field-hint">内置角色名称受保护，不可修改。</div>

        <n-form-item label="说明">
          <n-input
            v-model:value="form.desc"
            type="textarea"
            placeholder="这个角色是干什么的、适合给谁用"
            :autosize="{ minRows: 2, maxRows: 4 }"
            maxlength="200"
            show-count
          />
        </n-form-item>
      </n-form>

      <div class="form-section-title">
        <n-icon :component="KeyOutline" :color="accent" />
        权限（至少选一个）
      </div>

      <n-alert v-if="editingWildcard" type="warning" :bordered="false" size="small" style="margin-bottom: 10px">
        该角色是通配符 <code>*</code>，权限不由勾选决定；这里的勾选项不会写回角色。
      </n-alert>
      <PermissionTree
        v-else
        v-model:permissions="formPermissions"
        :disabled="editingWildcard"
        :color="accent"
      />

      <template #footer>
        <n-button size="small" :disabled="saving" @click="showForm = false">取消</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="submitForm">
          <template #icon>
            <n-icon :component="SaveOutline" />
          </template>
          {{ isCreating ? '创建' : '保存' }}
        </n-button>
      </template>
    </AppDialog>
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

/**
 * 角色列表占满整行。
 *
 * 原先这里是「左列表 + 右权限分配」的两栏布局（`flex: 3 1 680px` /
 * `flex: 2 1 470px`），但右侧面板与编辑抽屉功能重复，已删除。
 * 列表 7 列合计约 986px，独占整行后不再需要横向滚动。
 */
.role-layout {
  margin-top: 16px;
}

.role-list-card {
  min-width: 0;
}

.table-foot {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-muted);
}

.role-name-cell {
  display: flex;
  gap: 6px;
  align-items: center;
}

.count-cell {
  font-variant-numeric: tabular-nums;
}

.row-actions {
  display: flex;
  gap: 2px;
  align-items: center;
}

.field-hint {
  margin: -8px 0 14px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-muted);
}

.form-section-title {
  display: flex;
  gap: 7px;
  align-items: center;
  margin: 6px 0 10px;
  font-size: 13px;
  font-weight: 600;
}
</style>
