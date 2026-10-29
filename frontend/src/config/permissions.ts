import { flattenMenu, menuTree, type MenuNode } from './menu'

/**
 * 权限键契约 —— 全站唯一来源。
 *
 * 权限**不是另造一套字符串**，而是直接挂在已有的菜单树上：
 *  - 菜单权限：`menu:<菜单 name>`，name 就是 `src/config/menu.ts` 里的路由 name，
 *    所以「加一个菜单」= 权限树里自动多一个节点，不会两处对不上。
 *  - 操作权限：`action:<模块>:<动作>`，用于菜单里**看不见**的高危动作
 *    （删除键、执行只读 SQL、启停节点…）。这些动作若只靠菜单权限控制，
 *    任何能看到该页面的人都能点，等于没有控制。
 *
 * 超管用通配符 `*`，代表全部权限（菜单新增后自动覆盖，不用回填）。
 */

/** 通配符：拥有全部权限 */
export const PERMISSION_WILDCARD = '*'

/** 菜单权限键前缀 */
export const MENU_PREFIX = 'menu:'

/** 操作权限键前缀 */
export const ACTION_PREFIX = 'action:'

/** 菜单权限键：menu:<菜单 name> */
export function menuPermissionKey(name: string): string {
  return `${MENU_PREFIX}${name}`
}

/** 操作权限键：action:<模块>:<动作> */
export function actionPermissionKey(module: string, action: string): string {
  return `${ACTION_PREFIX}${module}:${action}`
}

/**
 * 危险操作权限点。
 *
 * 只列**真的会造成破坏或泄漏**的操作，不把「查看详情」也叫权限 ——
 * 权限点一多，管理员就会图省事全勾上，等于没有。
 *
 * `menu` 必须是 `src/config/menu.ts` 里真实存在的菜单 name（有单测式的自检，见下方 assert）。
 */
export interface ActionPermissionDef {
  /** 所在页面的菜单 name */
  menu: string
  /** 动作标识 */
  action: string
  label: string
  desc: string
  /** 是否属于「高压」操作：二次确认 + 写入审计 */
  dangerous: boolean
}

export const ACTION_PERMISSIONS: ActionPermissionDef[] = [
  {
    menu: 'conditional',
    action: 'write',
    label: '条件单状态变更（停止/运行/终止）',
    desc: '对应 setByStrategy 协议，会直接改变客户正在运行的条件单，并下发到策略平台。不含删除（删除不可逆，需求方明确不做）。',
    dangerous: true,
  },
  {
    menu: 'storage-redis',
    action: 'delete-key',
    label: '删除 Redis 键',
    desc: 'DEL 不可撤销。现场 137 的入口流有 400 万条，删错键会直接影响数据中台。',
    dangerous: true,
  },
  {
    menu: 'storage-mysql',
    action: 'readonly-sql',
    label: '执行只读 SQL',
    desc: '允许在查询窗口执行 SELECT。后端必须强制只读校验，权限点不能替代后端校验。',
    dangerous: false,
  },
  {
    menu: 'system-user',
    action: 'manage',
    label: '管理后台账号',
    desc: '新建 / 停用 / 重置密码。能改「谁能进后台」，属于最高一档权限。',
    dangerous: true,
  },
  {
    menu: 'system-role',
    action: 'manage',
    label: '管理角色与权限',
    desc: '改权限等于改所有人的可见范围，包含给自己提权的风险，必须写审计。',
    dangerous: true,
  },
  {
    menu: 'system-audit',
    action: 'export',
    label: '导出审计日志',
    desc: '审计日志含 IP 与操作人，导出即离场，需留痕。',
    dangerous: false,
  },
]

/* ------------------------------ 权限树（供界面渲染） ------------------------------ */

/** 权限树节点：父节点 = 一级菜单，子节点 = 菜单权限 + 该页面的操作权限 */
export interface PermissionTreeNode {
  key: string
  label: string
  /** 一级菜单分组节点还是权限点 */
  type: 'group' | 'menu' | 'action'
  children?: PermissionTreeNode[]
}

/**
 * 由菜单树推导权限树。
 *
 * 为什么从菜单树推导而不是手写：菜单一改（加页面/删页面），权限树自动跟上，
 * 不会出现「权限树里还留着一个已删页面的权限」这种对不上的情况。
 */
export function buildPermissionTree(nodes: MenuNode[] = menuTree): PermissionTreeNode[] {
  const out: PermissionTreeNode[] = []

  for (const node of nodes) {
    if (node.children?.length) {
      const children: PermissionTreeNode[] = []
      for (const leaf of flattenMenu([node])) {
        children.push({
          key: menuPermissionKey(leaf.name),
          label: leaf.meta.title,
          type: 'menu',
          children: actionsOf(leaf).map((a) => ({
            key: actionPermissionKey(leaf.name, a.action),
            label: a.label,
            type: 'action' as const,
          })),
        })
      }
      out.push({ key: node.name, label: node.meta.title, type: 'group', children })
    } else {
      const actions = actionsOf(node)
      out.push({
        key: menuPermissionKey(node.name),
        label: node.meta.title,
        type: 'menu',
        children: actions.map((a) => ({
          key: actionPermissionKey(node.name, a.action),
          label: a.label,
          type: 'action' as const,
        })),
      })
    }
  }

  return out
}

/** 某个菜单下挂的操作权限 */
export function actionsOf(node: MenuNode): ActionPermissionDef[] {
  return ACTION_PERMISSIONS.filter((a) => a.menu === node.name)
}

/** 全部权限键（不含通配符） */
export function allPermissionKeys(): string[] {
  const keys: string[] = []
  for (const node of flattenMenu()) keys.push(menuPermissionKey(node.name))
  for (const a of ACTION_PERMISSIONS) keys.push(actionPermissionKey(a.menu, a.action))
  return keys
}

/**
 * 判断一份权限集合是否覆盖目标键。
 *
 * `*` 覆盖一切；父级 `menu:x` **不**自动覆盖 `action:x:y`
 * —— 否则「能看到页面」就等于「能删键」，权限点就白加了。
 * 界面上靠勾选级联（cascade）帮管理员一并勾上，而不是靠运行时隐式放行。
 */
export function hasPermission(permissions: readonly string[] | undefined, key: string): boolean {
  if (!permissions?.length) return false
  if (permissions.includes(PERMISSION_WILDCARD)) return true
  return permissions.includes(key)
}

/** 通配符角色的权限集合（超管） */
export function wildcardPermissions(): string[] {
  return [PERMISSION_WILDCARD]
}

/* ------------------------------ 菜单可见性 ------------------------------ */

/**
 * 按权限裁剪菜单树。
 *
 * 判定**只落在叶子**上：`menu:<name>` 是页面的权限键，而 `server` / `bizdata`
 * 这类分组 name 在 `PermissionCatalog` 里**不是**权限键（后端只有 17 个叶子名），
 * 给分组判权会直接命中 `false`，导致整组静默消失。
 * 所以分组的去留由「它下面还有没有可见叶子」决定，自身不参与判定。
 *
 * 返回的是**新树**（只在有可见子项时复制分组），不改动 `menuTree` 原对象 ——
 * 权限树、路由表仍以全量菜单为准，菜单可见性只是渲染层的一次投影。
 */
export function filterMenuByPermissions(
  permissions: readonly string[] | undefined,
  nodes: MenuNode[] = menuTree,
): MenuNode[] {
  const out: MenuNode[] = []
  for (const node of nodes) {
    if (node.children?.length) {
      const children = filterMenuByPermissions(permissions, node.children)
      if (children.length) out.push({ ...node, children })
    } else if (hasPermission(permissions, menuPermissionKey(node.name))) {
      out.push(node)
    }
  }
  return out
}

/**
 * 第一个**有权访问**的叶子路径，用于「/」重定向、关闭全部页签后的落脚点。
 *
 * 不能直接用 `firstLeafPath()`：它是静态的，会把人送到一个没权限的页面，
 * 再被守卫弹回来，形成「点首页 → 弹回 → 再点首页」的死循环。
 *
 * @returns 没有任何可见菜单时返回 `undefined`，由调用方决定兜底（通常是个人信息页）
 */
export function firstAllowedPath(
  permissions: readonly string[] | undefined,
  nodes: MenuNode[] = menuTree,
): string | undefined {
  for (const node of nodes) {
    if (node.children?.length) {
      const inner = firstAllowedPath(permissions, node.children)
      if (inner) return inner
    } else if (hasPermission(permissions, menuPermissionKey(node.name))) {
      return node.path
    }
  }
  return undefined
}

/**
 * 收敛一份权限选择，维护 `action ⊆ menu` 这条不变量。
 *
 * <p>后端判定是「菜单 + 操作」AND（如 `{menu:system-audit, action:system-audit:export}`），
 * 缺菜单权限的 action 永远打不通；前端菜单裁剪与路由守卫也会先挡住页面。
 * 所以界面上允许出现「只有动作、没有页面」的组合毫无意义，必须在写回时收敛：
 *
 * <ul>
 *   <li><b>新勾上 action</b> → 自动补上它的 `menu`（否则是死权限）；</li>
 *   <li><b>取消 menu</b> → 连带撤销该页的 action（留下也点不到）。</li>
 * </ul>
 *
 * <p>方向靠「与 previous 的差量」判断，不能无条件补：否则用户刚取消页面访问，
 * 仍勾着的 action 会立刻把 menu 补回来，看起来像点了没反应。
 *
 * @param selected 界面当前的勾选集合（可能含合成键，会被过滤掉）
 * @param previous 上一次已保存/已生效的权限键，用于判断增删方向
 * @returns 过滤并排序后的真实权限键，以及被自动补/删的键（供界面提示）
 */
export function normalizePermissionSelection(
  selected: Iterable<string>,
  previous: readonly string[] = [],
): { keys: string[]; addedMenus: string[]; removedActions: string[] } {
  const allowed = new Set(allPermissionKeys())
  const set = new Set<string>()
  for (const key of selected) {
    if (allowed.has(key)) set.add(key)
  }

  const before = new Set(previous)
  const addedMenus: string[] = []
  const removedActions: string[] = []

  for (const key of [...set]) {
    if (before.has(key)) continue
    const def = ACTION_PERMISSIONS.find((a) => actionPermissionKey(a.menu, a.action) === key)
    if (!def) continue
    const menuKey = menuPermissionKey(def.menu)
    if (!set.has(menuKey)) {
      set.add(menuKey)
      addedMenus.push(menuKey)
    }
  }

  for (const key of before) {
    if (set.has(key) || !key.startsWith(MENU_PREFIX)) continue
    const name = key.slice(MENU_PREFIX.length)
    for (const def of ACTION_PERMISSIONS) {
      if (def.menu !== name) continue
      const actionKey = actionPermissionKey(def.menu, def.action)
      if (set.delete(actionKey)) removedActions.push(actionKey)
    }
  }

  // 按契约顺序输出：审计里比对前后差异时稳定可读
  const order = new Map(allPermissionKeys().map((key, index) => [key, index] as const))
  const keys = [...set].sort((a, b) => (order.get(a) ?? 0) - (order.get(b) ?? 0))
  return { keys, addedMenus, removedActions }
}

/**
 * 自检：ACTION_PERMISSIONS 里引用的菜单必须真实存在。
 * 写错菜单名会导致「某个操作权限永远勾不上」，而且不报错 —— 在这里直接炸出来。
 */
export function assertActionPermissions(): string[] {
  const problems: string[] = []
  const names = new Set(flattenMenu().map((n) => n.name))
  const seen = new Set<string>()
  for (const a of ACTION_PERMISSIONS) {
    if (!names.has(a.menu)) problems.push(`操作权限引用了不存在的菜单：${a.menu}:${a.action}`)
    const key = actionPermissionKey(a.menu, a.action)
    if (seen.has(key)) problems.push(`操作权限键重复：${key}`)
    seen.add(key)
  }
  return problems
}

const problems = assertActionPermissions()
if (problems.length) {
  console.error('[permissions] 权限点定义有误：\n' + problems.join('\n'))
}
