<script setup lang="ts">
import { computed, h, ref, type VNodeChild } from 'vue'
import { NButton, NIcon, NTag, NTooltip, NTree, useDialog, type TreeOption } from 'naive-ui'
import {
  ChevronDownOutline,
  ChevronUpOutline,
  HelpCircleOutline,
  KeyOutline,
  LayersOutline,
  WarningOutline,
} from '@vicons/ionicons5'

import {
  ACTION_PERMISSIONS,
  actionPermissionKey,
  allPermissionKeys,
  buildPermissionTree,
  hasPermission,
  type PermissionTreeNode,
} from '@/config/permissions'

/**
 * 权限树 —— 角色页的核心控件（改权限 = 改所有人的可见范围）。
 *
 * 四条必须写清楚的设计约束：
 *
 * 1. **受控**。naive-ui 的 `n-tree` 只认 `checkedKeys` + `onUpdate:checkedKeys`
 *    这对受控写法；只给 `defaultCheckedKeys` 会让「本页全部操作权限」
 *    「换角色回填」这类程序化改选**静默不生效**（不报错）。
 *    这里把父组件传来的 `permissions` 当作唯一数据源，一切改选都 emit 回去。
 *
 * 2. **所有真实权限键都是叶子**。`permissions.ts` 写明 `menu:x` **不**覆盖
 *    `action:x:y`（`hasPermission` 不做隐式放行）；但 naive-ui / treemate 在
 *    cascade 模式下，给一个父键会把它的**全部子孙**算成已勾选
 *    （treemate `getExtendedCheckedKeySet`）。若照 `buildPermissionTree()`
 *    的三层结构直接渲染，一个只有 `menu:storage-redis` 的角色会被显示成
 *    已勾上「删除 Redis 键」，用户此后随便勾一下别的项，回传的 checkedKeys
 *    就带着那个被隐式点亮的 `action:` 键，**一保存等于静默提权**。
 *    因此显示层拆成：分组（合成键）→ 页面容器（合成键）→ 叶子
 *    （真实 `menu:` 键 + 各真实 `action:` 键）。真实键没有子节点，
 *    cascade 无从展开，**显示即授权**。
 *
 * 3. **合成父节点一律不可勾选**（`checkboxDisabled`）。父节点的标签
 *    （如「数据存储」「Redis 管理」）根本不会告诉用户「勾了我还会授出删除 Redis 键」。
 *    不可勾选 + 批量操作走显式按钮，才不会有隐式放大。
 *    `checkboxDisabled` 同时让 treemate 在展开父键时**停止下钻**
 *    （`getExtendedCheckedKeySet` 遇到 disabled 节点直接 STOP），
 *    是第二道防线。
 *
 * 4. **合成键绝不落库**。`@group:` / `@page:` 只存在于界面；任何 emit 出去的
 *    集合都要过 `allPermissionKeys()` 白名单 —— 这是纵深防御：
 *    即使父节点被程序化设成 checked，也写不进角色。
 */

/** 合成键前缀：仅用于界面分组 / 页面容器，不是权限键 */
const GROUP_PREFIX = '@group:'
const PAGE_PREFIX = '@page:'

const props = withDefaults(
  defineProps<{
    /** 当前已授权的**真实**权限键集合（不含合成键） */
    permissions: string[]
    /** 只读展示：所有改选与批量按钮失效 */
    disabled?: boolean
    /** 通配符角色（`['*']`）：整棵树不可勾选，改为显示通配符说明 */
    wildcard?: boolean
    /** 强调色（模块主色），默认系统管理石板灰 */
    color?: string
  }>(),
  { disabled: false, wildcard: false, color: '#5b6b8c' },
)

const emit = defineEmits<{ 'update:permissions': [keys: string[]] }>()

const dialog = useDialog()

/* ------------------------------ 契约派生数据 ------------------------------ */

const ALL_REAL_KEYS = allPermissionKeys()
const REAL_KEY_SET = new Set(ALL_REAL_KEYS)
/** 契约顺序：emit 出去时按它排序，审计比对前后差异时稳定可读 */
const KEY_ORDER = new Map(ALL_REAL_KEYS.map((key, index) => [key, index] as const))
const ACTION_DEF = new Map(
  ACTION_PERMISSIONS.map((def) => [actionPermissionKey(def.menu, def.action), def] as const),
)

/* ------------------------------ 界面选项构建 ------------------------------ */

interface PermTreeOption extends TreeOption {
  key: string
  label: string
  permType: 'group' | 'page' | 'menu' | 'action'
  dangerous?: boolean
  desc?: string
}

/** 合成键（分组 / 页面容器）→ 其下全部真实权限键 */
const batchKeys = new Map<string, string[]>()
/** 真实权限键 → 中文名（二次确认时列给人看） */
const keyLabels = new Map<string, string>()

function makeLeafOption(key: string, label: string, permType: 'menu' | 'action'): PermTreeOption {
  const def = permType === 'action' ? ACTION_DEF.get(key) : undefined
  keyLabels.set(key, label)
  return {
    key,
    label,
    permType,
    dangerous: def?.dangerous ?? false,
    desc: def?.desc,
  }
}

function collectRealKeys(options: PermTreeOption[]): string[] {
  const out: string[] = []
  for (const option of options) {
    if (option.children?.length) out.push(...collectRealKeys(option.children as PermTreeOption[]))
    else out.push(option.key)
  }
  return out
}

function toOptions(nodes: PermissionTreeNode[]): PermTreeOption[] {
  return nodes.map((node) => {
    if (node.type === 'group') {
      // 顶层分组：合成键 + 不可勾选
      const children = toOptions(node.children ?? [])
      const key = GROUP_PREFIX + node.key
      batchKeys.set(key, collectRealKeys(children))
      return { key, label: node.label, permType: 'group', checkboxDisabled: true, children }
    }

    const actionNodes = (node.children ?? []).filter((child) => child.type === 'action')
    if (actionNodes.length) {
      // 有操作权限的页面：容器不可勾选，真实键下沉为叶子
      const key = PAGE_PREFIX + node.key
      const children: PermTreeOption[] = [
        makeLeafOption(node.key, '页面访问（菜单权限）', 'menu'),
        ...actionNodes.map((action) => makeLeafOption(action.key, action.label, 'action')),
      ]
      batchKeys.set(key, collectRealKeys(children))
      return { key, label: node.label, permType: 'page', checkboxDisabled: true, children }
    }

    // 没有操作权限的页面：真实键本身就是叶子
    return makeLeafOption(node.key, node.label, 'menu')
  })
}

/** 权限树由菜单树推导（`buildPermissionTree()`）—— 菜单一改自动跟上 */
const treeData = toOptions(buildPermissionTree())

function expandableKeys(options: PermTreeOption[] = treeData): string[] {
  const out: string[] = []
  for (const option of options) {
    if (option.children?.length) {
      out.push(option.key)
      out.push(...expandableKeys(option.children as PermTreeOption[]))
    }
  }
  return out
}

/* ------------------------------ 受控状态 ------------------------------ */

/** 已勾选的真实权限键（受控：唯一数据源是父组件的 permissions） */
const checkedKeys = computed(() => props.permissions.filter((key) => REAL_KEY_SET.has(key)))

/** 展开状态也受控，保证「全部展开 / 全部收起」可靠生效 */
const expandedKeys = ref<string[]>(expandableKeys())

const locked = computed(() => props.disabled || props.wildcard)

const totalRealKeys = ALL_REAL_KEYS.length

const dangerousSelected = computed(() =>
  props.wildcard
    ? []
    : ACTION_PERMISSIONS.filter(
        (def) =>
          def.dangerous && hasPermission(props.permissions, actionPermissionKey(def.menu, def.action)),
      ),
)

/* ------------------------------ 写回 ------------------------------ */

function commit(keys: Iterable<string>) {
  const set = new Set<string>()
  for (const key of keys) {
    // 合成键与契约里不存在的键一律丢弃，绝不写进角色
    if (REAL_KEY_SET.has(key)) set.add(key)
  }
  const sorted = [...set].sort((a, b) => (KEY_ORDER.get(a) ?? 0) - (KEY_ORDER.get(b) ?? 0))
  emit('update:permissions', sorted)
}

function handleUpdateCheckedKeys(keys: Array<string | number>) {
  if (locked.value) return
  // meta（node / action）在这里不需要：集合语义由 commit 统一收敛
  commit(keys.map((key) => String(key)))
}

function handleUpdateExpandedKeys(keys: Array<string | number>) {
  expandedKeys.value = keys.map((key) => String(key))
}

function toggleExpandAll() {
  expandedKeys.value = expandedKeys.value.length ? [] : expandableKeys()
}

/* ------------------------------ 批量操作（显式按钮） ------------------------------ */

function keyLabel(key: string): string {
  return keyLabels.get(key) ?? key
}

/** 要授予高危权限时二次确认，并把具体键名列出来（不靠父节点勾选框） */
function confirmDangerous(keys: string[], title: string): Promise<boolean> {
  return new Promise((resolve) => {
    let settled = false
    const done = (value: boolean) => {
      if (settled) return
      settled = true
      resolve(value)
    }
    dialog.warning({
      title,
      content: () =>
        h('div', {}, [
          h('div', {}, '以下高危操作权限会被一并授予，请逐条确认：'),
          h(
            'ul',
            { style: 'margin:8px 0 0;padding-left:18px;font-size:12.5px;line-height:1.8' },
            keys.map((key) => h('li', { key }, keyLabel(key))),
          ),
          h(
            'div',
            { style: 'margin-top:10px;font-size:12.5px;color:var(--danger)' },
            '高危操作在后端仍必须二次确认并写审计，权限点不能替代后端校验。',
          ),
        ]),
      positiveText: '确认授予',
      negativeText: '取消',
      onPositiveClick: () => done(true),
      onNegativeClick: () => done(false),
      onClose: () => done(false),
      onMaskClick: () => done(false),
    })
  })
}

/** mode=all 全选给定范围；none 清空；invert 反选 */
async function applyKeys(keys: string[], mode: 'all' | 'none' | 'invert', title: string) {
  if (locked.value) return
  const set = new Set(checkedKeys.value)
  const added: string[] = []

  if (mode === 'all') {
    for (const key of keys) {
      if (!set.has(key)) {
        set.add(key)
        added.push(key)
      }
    }
  } else if (mode === 'none') {
    for (const key of keys) set.delete(key)
  } else {
    for (const key of keys) {
      if (set.has(key)) set.delete(key)
      else {
        set.add(key)
        added.push(key)
      }
    }
  }

  const dangerousAdded = added.filter((key) => ACTION_DEF.get(key)?.dangerous)
  if (dangerousAdded.length) {
    const ok = await confirmDangerous(dangerousAdded, title)
    if (!ok) return
  }
  commit(set)
}

function applyGroup(batchKey: string, mode: 'all' | 'none' | 'invert', label: string) {
  const keys = batchKeys.get(batchKey) ?? []
  const verb = mode === 'all' ? '全选' : mode === 'none' ? '清空' : '反选'
  void applyKeys(keys, mode, `${verb}「${label}」的权限`)
}

/** 页面容器：一键勾上该页的菜单权限 + 全部操作权限 */
function applyPage(batchKey: string, label: string) {
  const keys = batchKeys.get(batchKey) ?? []
  void applyKeys(keys, 'all', `勾上「${label}」的全部权限`)
}

function clearPage(batchKey: string, label: string) {
  const keys = batchKeys.get(batchKey) ?? []
  void applyKeys(keys, 'none', `清空「${label}」的权限`)
}

/* ------------------------------ 渲染 ------------------------------ */

function renderLabel(info: { option: TreeOption }): VNodeChild {
  const option = info.option as PermTreeOption

  if (option.permType === 'group') {
    return h('span', { class: 'perm-node perm-node--group' }, [
      h(NIcon, { component: LayersOutline, size: 15, color: props.color }),
      h('span', { class: 'perm-node-text' }, option.label),
      h(
        NTag,
        { size: 'tiny', round: true, bordered: false },
        { default: () => '不可整组勾选' },
      ),
    ])
  }

  if (option.permType === 'page') {
    return h('span', { class: 'perm-node perm-node--page' }, [
      h('span', { class: 'perm-node-text' }, option.label),
    ])
  }

  const parts: VNodeChild[] = [
    h(NIcon, { component: option.dangerous ? WarningOutline : KeyOutline, size: 13 }),
    h('span', { class: 'perm-node-text' }, option.label),
    h('code', { class: 'perm-key' }, option.key),
  ]
  if (option.dangerous) {
    parts.push(
      h(NTag, { size: 'tiny', round: true, bordered: false, type: 'error' }, { default: () => '高危' }),
    )
  }

  const node = h(
    'span',
    { class: ['perm-node', option.dangerous ? 'perm-node--danger' : 'perm-node--key'] },
    parts,
  )
  if (!option.desc) return node
  return h(NTooltip, { trigger: 'hover', delay: 200 }, { trigger: () => node, default: () => option.desc })
}

function renderSuffix(info: { option: TreeOption }): VNodeChild {
  const option = info.option as PermTreeOption

  const button = (label: string, onClick: () => void) =>
    h(
      NButton,
      {
        size: 'tiny',
        quaternary: true,
        disabled: locked.value,
        onClick: (event: MouseEvent) => {
          // 不要冒泡到 tree-node-content，避免触发行选中
          event.stopPropagation()
          onClick()
        },
      },
      { default: () => label },
    )

  if (option.permType === 'group') {
    return h('span', { class: 'perm-batch-actions' }, [
      button('全选本模块', () => applyGroup(option.key, 'all', option.label)),
      button('清空', () => applyGroup(option.key, 'none', option.label)),
      button('反选', () => applyGroup(option.key, 'invert', option.label)),
    ])
  }

  if (option.permType === 'page') {
    return h('span', { class: 'perm-batch-actions' }, [
      button('勾上本页全部权限', () => applyPage(option.key, option.label)),
      button('清空本页', () => clearPage(option.key, option.label)),
    ])
  }

  return null
}
</script>

<template>
  <div class="perm-tree">
    <div class="perm-tree-toolbar">
      <div class="perm-tree-summary">
        <n-tag size="small" round :bordered="false" :type="wildcard ? 'warning' : 'info'">
          已选 {{ checkedKeys.length }} / {{ totalRealKeys }} 个权限点
        </n-tag>
        <n-tag v-if="wildcard" size="small" round :bordered="false" type="warning">
          通配符 *（全部权限，不可编辑）
        </n-tag>
        <n-tag v-else-if="dangerousSelected.length" size="small" round :bordered="false" type="error">
          <template #icon>
            <n-icon :component="WarningOutline" />
          </template>
          含 {{ dangerousSelected.length }} 项高危操作
        </n-tag>
      </div>

      <div class="perm-tree-buttons">
        <n-button size="tiny" quaternary :disabled="locked" @click="applyKeys(ALL_REAL_KEYS, 'all', '全选所有权限')">
          全选
        </n-button>
        <n-button size="tiny" quaternary :disabled="locked" @click="applyKeys(ALL_REAL_KEYS, 'none', '全不选')">
          全不选
        </n-button>
        <n-button size="tiny" quaternary :disabled="locked" @click="applyKeys(ALL_REAL_KEYS, 'invert', '反选所有权限')">
          反选
        </n-button>
        <n-button size="tiny" quaternary @click="toggleExpandAll">
          <template #icon>
            <n-icon :component="expandedKeys.length ? ChevronUpOutline : ChevronDownOutline" />
          </template>
          {{ expandedKeys.length ? '全部收起' : '全部展开' }}
        </n-button>
        <n-tooltip trigger="hover" :delay="150" style="max-width: 420px">
          <template #trigger>
            <n-icon :component="HelpCircleOutline" :size="16" class="perm-help" />
          </template>
          <!--
            tooltip 内容是挂到 body 上的 portal，scoped 样式够不到它，
            而本项目的约定是禁用 :global()（见下方样式注释），
            所以这里用行内样式控制排版。
          -->
          <div style="display: grid; gap: 8px; font-size: 12.5px; line-height: 1.75">
            <div>
              只有<b>叶子上的勾选框</b>能改权限；分组行与页面行没有勾选框，
              批量改动用行尾的<b>显式按钮</b> —— 避免「勾一个名字，却授出看不见的深层权限」。
            </div>
            <div>
              勾<b>「页面访问（菜单权限）」</b>只给 <code>menu:&lt;页面&gt;</code>：
              能进页面，但页里的写操作仍点不动。
            </div>
            <div>
              后端<b>不做父级隐式放行</b>：只有 <code>menu:*</code> 不会自动获得
              <code>action:*</code>，所以这里<b>显示勾上 = 真的有权限</b>。
            </div>
            <div>
              标<b>高危</b>的是删除 / 下发协议 / 改账号与权限一类操作；
              批量勾选若会授出高危权限，会弹窗<b>逐条列出键名</b>并二次确认。
            </div>
          </div>
        </n-tooltip>
      </div>
    </div>

    <div class="perm-tree-scroll">
      <n-tree
        block-line
        cascade
        checkable
        check-strategy="all"
        :check-on-click="false"
        :selectable="false"
        :data="treeData"
        :checked-keys="checkedKeys"
        :expanded-keys="expandedKeys"
        :disabled="locked"
        :render-label="renderLabel"
        :render-suffix="renderSuffix"
        @update:checked-keys="handleUpdateCheckedKeys"
        @update:expanded-keys="handleUpdateExpandedKeys"
      />
    </div>
  </div>
</template>

<style scoped>
.perm-tree {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.perm-tree-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.perm-tree-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.perm-tree-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

/* 说明改成悬停图标：原先是一整块常驻 alert，太占纵向空间 */
.perm-help {
  color: var(--text-muted);
  cursor: help;
}

.perm-help:hover {
  color: var(--text-secondary);
}

.perm-tree-scroll {
  max-height: 520px;
  padding: 6px 2px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
}

/*
 * render-label / render-suffix 里的节点由本组件创建、但由 n-tree 渲染，
 * 拿不到 scoped 属性，因此用 :deep() 下钻（README 约定：禁用 :global()）。
 * 选择器都带两层类，避免被 naive-ui 的 `.n-tree .xxx`（0-2-0）静默盖掉。
 */
.perm-tree-scroll :deep(.perm-node) {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.perm-tree-scroll :deep(.perm-node--group) {
  font-weight: 600;
}

.perm-tree-scroll :deep(.perm-node--page) {
  font-weight: 600;
}

.perm-tree-scroll :deep(.perm-node--danger) {
  color: var(--danger);
}

.perm-tree-scroll :deep(.perm-node--key) {
  color: var(--text-muted);
}

.perm-tree-scroll :deep(.perm-node-text) {
  font-size: 13px;
  color: var(--text-primary);
}

.perm-tree-scroll :deep(.perm-key) {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
  color: var(--text-secondary);
}

.perm-tree-scroll :deep(.perm-batch-actions) {
  display: inline-flex;
  gap: 2px;
  align-items: center;
}
</style>
