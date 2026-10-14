import type { Component } from 'vue'
import {
  AlbumsOutline,
  AnalyticsOutline,
  CashOutline,
  ClipboardOutline,
  CloudOutline,
  CreateOutline,
  DocumentTextOutline,
  GitNetworkOutline,
  GridOutline,
  LayersOutline,
  ListOutline,
  PeopleOutline,
  PersonAddOutline,
  PersonOutline,
  PulseOutline,
  ServerOutline,
  SettingsOutline,
  ShieldCheckmarkOutline,
  SwapHorizontalOutline,
  TimeOutline,
} from '@vicons/ionicons5'

/**
 * 菜单元数据 —— 全站导航的唯一数据源。
 *
 * 侧边栏、面包屑、页面标题、路由表全部由这棵树推导：
 * 「加一个菜单」= 在这里加一个节点（见 README）。
 */

/** 该页面背后的真实数据来源，直接写在菜单里，避免以后靠猜。 */
export interface MenuRefItem {
  /** 标签，如 "MySQL 表" / "Redis Stream" / "Pub/Sub 频道" */
  label: string
  /** 实际名称 */
  value: string
}

export interface MenuMeta {
  /** 菜单标题 */
  title: string
  /** 图标组件（仅一级/二级菜单需要） */
  icon?: Component
  /** 该页面背后的数据来源 */
  refs?: MenuRefItem[]
  /** 该页面的后端接口是否已就绪 */
  status?: 'ready' | 'planned' | 'placeholder'
  /** 页面说明，用于占位页与页头 */
  desc?: string
  /** 后续要做的功能点，页面上直接列出来当 TODO 清单 */
  todos?: string[]
}

export interface MenuNode {
  /** 唯一的路由 name，同时作为菜单 key */
  name: string
  /** 路由 path（叶子为完整路径，父节点为其前缀） */
  path: string
  meta: MenuMeta
  /**
   * 页面组件相对 `src/views` 的路径（不含 .vue）。
   * 省略则使用通用占位页，因此可以先上菜单、后补页面。
   */
  view?: string
  children?: MenuNode[]
}

/**
 * 菜单树按**数据形态**分组，不按原先的子系统分组。
 *
 * 改动原因：原先按子系统分（策略平台 / 委托服务器 / 数据中台），
 * 但每个子系统里都混着两类性质完全不同的页面：
 *   - **节点类**：只读 Redis JSON 键 + 订阅 Pub/Sub 频道，不碰 MySQL；
 *   - **业务表类**：直接查 MySQL 表（T_Conditional / T_Order / T_Trade / T_StrategyLog）。
 * 运维找页面时的心智是「我要看某张表」或「我要看哪台服务器」，
 * 而不是「这张表属于哪个子系统」，所以把表页面独立出来、节点类收进「服务器管理」。
 *
 * ⚠️ **改菜单 name 会连带改权限键。** `menu:<name>` 由 name 推导（见 `permissions.ts`），
 * 所以 `ACTION_PERMISSIONS.menu`、角色的权限数组（`api/mock/system.ts`）
 * 以及审计动作键必须同步改，否则相关角色会**静默**看不到页面
 * —— `hasPermission` 是精确匹配，键对不上不会报错，只在权限自检里提示。
 */
export const menuTree: MenuNode[] = [
  {
    name: 'dashboard',
    path: '/dashboard',
    view: 'dashboard/DashboardView',
    meta: {
      title: '总览',
      icon: GridOutline,
      // 总览已实现（DashboardView 有完整内容），此前误标 placeholder →
      // 顶栏会在总览页挂「页面待开发」标签，与 README 的 ✅ 自相矛盾。
      status: 'ready',
      desc: '系统整体运行态势：在线节点、条件单规模、消息吞吐与异常汇总。',
    },
  },
  {
    name: 'server',
    path: '/server',
    meta: {
      title: '服务器管理',
      icon: ServerOutline,
      desc: '三类服务器节点（委托 / 策略 / 数据中台）的注册、在线状态与承载关系，外加消息总线运行状态。',
    },
    children: [
      {
        name: 'server-trade',
        path: '/server/trade',
        view: 'trade/TradeServer',
        meta: {
          title: '委托服务器',
          icon: SwapHorizontalOutline,
          status: 'ready',
          desc: '委托服务器节点清单与在线状态，以及账号到委托服务器的分配关系。**只读，不提供启停。**',
          refs: [
            {
              label: 'Pub/Sub 频道',
              value:
                'tradeserver_online_1 / _beat_1 / _offline_1（现场实测带 _1 后缀，不带后缀的那一套并不存在）',
            },
            {
              label: 'Redis JSON 键',
              value:
                'traderserver（数组：id / ip / mac / enable / last-active-time / lastActiveTimeLong）' +
                '；跨 136 db0、136 db1、137 db1 各存一份且内容不同',
            },
            { label: '协议说明', value: '请求接口doc.txt 第 1~3 条' },
          ],
          todos: [
            '节点列表：来源实例、ID、IP、MAC、启用状态、最后活跃时间（已完成）',
            '账号分配关系（acc-* 键里的 traderserver 字段）（已完成）',
            '心跳超时告警（页面按 30 秒阈值着色，未做主动告警推送）',
          ],
        },
      },
      {
        name: 'server-datahub',
        path: '/server/datahub',
        view: 'datahub/DatahubNode',
        meta: {
          title: '数据中台',
          icon: GitNetworkOutline,
          status: 'ready',
          desc: '数据中台实例的上线与心跳状态。',
          refs: [
            { label: 'Pub/Sub 频道', value: 'datahub_online / _beat / _offline / _select / _oncall' },
            { label: 'Redis JSON 键', value: 'unique_id（已分配的最大唯一编号）' },
            { label: '协议说明', value: '请求接口doc.txt 第 7~9 条' },
          ],
          todos: ['中台实例列表与心跳', 'unique_id 分配水位查看', '向在线节点下发心跳（datahub → WT/ST 频道）'],
        },
      },
      {
        name: 'server-strategy',
        path: '/server/strategy',
        view: 'strategy/StrategyServer',
        meta: {
          title: '策略服务器',
          icon: LayersOutline,
          status: 'ready',
          desc: '策略平台集群的节点清单、上线/心跳/下线状态与承载的条件单用户数。',
          refs: [
            { label: 'Pub/Sub 频道', value: 'strategyserver_online / _beat / _offline（现场另有 _1 后缀一套）' },
            { label: 'Redis JSON 键', value: 'strategy-srv（节点数组：id / ip / mac / usecount）' },
            { label: '协议说明', value: '请求接口doc.txt 第 4~6 条' },
          ],
          todos: ['节点列表：IP、MAC、分配 ID、usecount、最后活跃时间', '在线/离线状态实时刷新', '节点详情抽屉：承载的条件单与账号', '离线告警'],
        },
      },
      {
        name: 'server-stream',
        path: '/server/stream',
        view: 'datahub/DatahubStream',
        meta: {
          title: '消息总线',
          icon: AnalyticsOutline,
          status: 'ready',
          desc: 'Redis Stream 的积压、消费组与报文流量监控 —— 节点之间的数据都从这里过，所以与服务器同组。',
          refs: [
            { label: '入口流', value: 'DataHub_req_stream（委托服务器 → 数据中台）' },
            { label: '出口流', value: 'DataHub_reply_stream' },
            { label: '下发流', value: 'ST-<编号> / ST-<编号>-reply（中台 → 策略平台）' },
            { label: '回包流', value: 'WT-<编号> / WT-<编号>-reply（中台 → 委托服务器）' },
            { label: '消费组', value: '固定 user_group，consumer 名 = 流名本身' },
          ],
          todos: ['各流的 XLEN / 消费组 lag / pending 实时看板', '积压告警阈值', '报文原样查看与 JSON 格式化', '按 request_id 串联一次请求的收发'],
        },
      },
    ],
  },
  {
    /**
     * 业务数据：四个直接对应 MySQL 表的查询页面。
     *
     * 为什么不保持顶级平铺：它们平铺后侧边栏会出现 4 个「某某管理」同类项
     * 挤在中间，与其余「分组 + 子页面」的结构不一致。收进一个分组后
     * 侧边栏回到 6 个顶级项，且与「服务器管理 / 账户与用户」的层级对称。
     *
     * ⚠️ 四个叶子的 `name` **刻意保持不变**（conditional / order / deal /
     * strategy-log）：`menu:<name>` 是权限键（见 permissions.ts），
     * 改名会让 ops / viewer 角色的权限静默失效。这里只改 path 与层级。
     */
    name: 'bizdata',
    path: '/bizdata',
    meta: {
      title: '业务数据',
      icon: AlbumsOutline,
      desc: '四类业务表数据的查询与下钻：条件单、委托单、成交回报与策略日志，均直接对应 MySQL 表。',
    },
    children: [
      {
        name: 'conditional',
        path: '/bizdata/conditional',
        view: 'strategy/StrategyList',
        meta: {
          title: '条件单管理',
          icon: ListOutline,
          status: 'ready',
          desc: '全量云条件单的查询、明细查看与人工干预（启停/删除）。',
          refs: [
            { label: 'MySQL 表', value: 'T_Conditional（运行中） / T_Conditional_his（历史） / V_Conditional（两者并集视图）' },
            { label: 'Redis JSON 键', value: 'strategy-<账号>_<账号类型>_<渠道>，内含 Strategy 数组' },
            { label: '关键字段', value: 'Status 触发状态、Flag 有效标志、Mode 云单状态、Type 条件单类型' },
          ],
          todos: ['条件单列表：按账号/类型/状态/时间筛选', 'Strategy 原始 JSON 格式化查看', '启停 / 删除（走 set、remove 协议）', '历史表 T_Conditional_his 查询'],
        },
      },
      {
        name: 'order',
        path: '/bizdata/order',
        view: 'trade/TradeOrder',
        meta: {
          title: '委托单管理',
          icon: ClipboardOutline,
          status: 'ready',
          desc: '条件单触发后产生的委托记录。',
          refs: [
            { label: 'MySQL 表', value: 'T_Order（委托下单表） / T_Order_his（历史）' },
            { label: '关键字段', value: 'StrategyID 策略编号、Code 合约、BSType 买卖、OCType 开平、OrderStatue 委托状态、OrderNo 后台委托号' },
            { label: 'Redis Stream', value: 'DataHub_req_stream 中的 createTrade 报文' },
          ],
          todos: ['委托列表：按策略编号/账号/合约/状态/时间筛选', '委托状态链路展示', '委托参数 OrderParam(JSON) 查看'],
        },
      },
      {
        name: 'deal',
        path: '/bizdata/deal',
        view: 'trade/TradeDeal',
        meta: {
          title: '成交回报管理',
          icon: CashOutline,
          status: 'ready',
          desc: '委托对应的成交明细。',
          refs: [
            { label: 'MySQL 表', value: 'T_Trade（成交数据） / T_Trade_his（历史）' },
            { label: '关键字段', value: 'StrategyID、OrderNo 委托号、TradeNO 成交号、Code 合约、Price 成交价、TradeTime' },
          ],
          todos: ['成交列表与委托单关联下钻', '按策略单统计成交均价与总量', '成交回报时延统计'],
        },
      },
      {
        name: 'strategy-log',
        path: '/bizdata/strategy-log',
        view: 'strategy/StrategyLog',
        meta: {
          title: '策略日志管理',
          icon: DocumentTextOutline,
          status: 'ready',
          desc: '条件单的运行与触发轨迹。',
          refs: [
            { label: 'MySQL 表', value: 'T_StrategyLog（策略单日志）' },
            { label: '关键字段', value: 'Strategy 策略编号、Status 状态、Info、OccurTIme、FromIP / FromMac / FromAccount' },
          ],
          todos: ['日志检索：按策略编号、状态、时间、来源 IP', '按策略单聚合查看生命周期', '触发设备（IP/MAC）溯源'],
        },
      },
    ],
  },
  {
    name: 'account',
    path: '/account',
    meta: { title: '账户与用户', icon: PeopleOutline, desc: '云条件单账号、用户资料、签署状态与股东号。' },
    children: [
      {
        name: 'account-user',
        path: '/account/user',
        view: 'account/AccountUser',
        meta: {
          title: '用户账号',
          icon: PersonOutline,
          status: 'ready',
          desc: '云条件单用户资料与账号维度信息。',
          refs: [
            { label: 'MySQL 表', value: 'T_User（账号、AccType、MainType、Passwd、TradeAccount、ShareHolders、BranchNo）' },
            { label: 'Redis JSON 键', value: 'acc-<账号>_<账号类型>_<渠道>（onlineflag、syncflag、strategyserverid、traderserver）' },
          ],
          todos: ['账号列表：按账号/类型/渠道/在线状态筛选', '账号详情：Redis acc-* 与 MySQL T_User 对照', '在线标志与同步标志展示', '密码重置（谨慎）'],
        },
      },
      {
        name: 'account-sign',
        path: '/account/sign',
        view: 'account/AccountSign',
        meta: {
          title: '签署与股东号',
          icon: CreateOutline,
          status: 'ready',
          desc: '云条件单协议签署状态与股东号（市场）配置。',
          refs: [
            { label: '相关协议', value: 'acc_sign 账号签署 / acc_query 账号查询 / acc_update 更新 / logout 登出' },
            { label: '关键字段', value: 'RegProtocol 签署的协议、Shareholders[]（SAccount 股东号 + ExchangeNum 市场）' },
            { label: '市场取值', value: 'ExchangeNum：1 上海、2 深圳' },
          ],
          todos: ['签署状态一览（已签署/未签署）', '股东号与市场维护', '协议签署/更新报文下发', '登出操作'],
        },
      },
      {
        name: 'account-log',
        path: '/account/log',
        view: 'account/AccountLog',
        meta: {
          title: '登录日志',
          icon: TimeOutline,
          status: 'ready',
          desc: '用户登录/登出轨迹与来源设备。',
          refs: [{ label: 'MySQL 表', value: 'T_UserLog（Account、LogTime、LogIP、LogMAC、LogOS、Info）' }],
          todos: ['登录日志检索', '按 IP/MAC 聚合异常登录', '时间范围与账号过滤'],
        },
      },
    ],
  },
  {
    name: 'storage',
    path: '/storage',
    meta: { title: '数据存储', icon: CloudOutline, desc: 'Redis 与 MySQL 的实例、键空间与表结构管理。' },
    children: [
      {
        name: 'storage-redis',
        path: '/storage/redis',
        view: 'storage/StorageRedis',
        meta: {
          title: 'Redis 管理',
          icon: PulseOutline,
          status: 'ready',
          desc: '多实例、多 DB 的 Redis 键浏览与 Stream / 订阅发布监控。',
          refs: [
            { label: '实例', value: '192.168.1.136（86.98M，12 客户端） / 192.168.1.137（1.47G，6 客户端），端口 6379' },
            { label: 'DB 分布', value: '136 用 db0、db1；137 用 db0、db1、db2（破坏测试流在 db2）' },
            { label: '键类型', value: 'stream（总线与各类流）、ReJSON-RL（业务对象）、string（unique_id）' },
            { label: '注意', value: '137 的 db0 中 DataHub_req_stream 已达 400 万条，查询需限流' },
          ],
          todos: ['实例/DB 树形浏览与键搜索', '键详情：Stream 条目、JSON 值、TTL', '消费组与 pending 管理', 'Pub/Sub 频道在线订阅数监控', '高危操作（删除键/流）二次确认'],
        },
      },
      {
        name: 'storage-mysql',
        path: '/storage/mysql',
        view: 'storage/StorageMysql',
        meta: {
          title: '数据库管理',
          icon: ServerOutline,
          status: 'ready',
          desc: 'qianlong_cloud 库的表结构浏览与只读 SQL 查询。',
          refs: [
            { label: '连接', value: '192.168.1.136:3306 / qianlong_cloud / 用户 qianlong_cloud（MySQL 8.0.45）' },
            { label: '表清单', value: 'T_Conditional、T_Conditional_his、T_Order、T_Order_his、T_Trade、T_Trade_his、T_User、T_UserLog、T_StrategyLog + 视图 V_Conditional' },
            { label: '注意', value: 'T_UserLog 19 万行、T_StrategyLog 1.6 万行，查询必须分页' },
          ],
          todos: ['表/视图列表与结构（字段、类型、注释）', '通用分页查询与条件过滤', 'SQL 只读查询窗口（默认禁止写）', '导出查询结果'],
        },
      },
    ],
  },
  {
    name: 'system',
    path: '/system',
    meta: { title: '系统管理', icon: SettingsOutline, desc: '管理端账号、角色权限与操作审计。' },
    children: [
      {
        name: 'system-user',
        path: '/system/user',
        view: 'system/SystemUser',
        meta: {
          title: '管理用户与权限',
          icon: PersonAddOutline,
          status: 'ready',
          desc: '后台管理账号：登录名、角色分配、启停、锁定解除与密码重置。',
          refs: [
            { label: '说明', value: '与 T_User（云单业务用户）无关，是后台管理端自己的账号体系' },
            { label: '建议表', value: 'T_AdminUser（username / displayName / roleIds / enabled / locked / failedAttempts / mustChangePassword / lastLoginTime / lastLoginIP）' },
            { label: '关联', value: 'T_AdminRole（角色）、T_AdminLoginLog（管理端登录日志）' },
            { label: '密码', value: '只写不读：接口不得回显明文或哈希；重置后写审计但不记录密码' },
          ],
          todos: ['管理用户 CRUD 与登录', '角色与菜单/按钮权限', '密码策略与登录失败锁定'],
        },
      },
      {
        name: 'system-role',
        path: '/system/role',
        view: 'system/SystemRole',
        meta: {
          title: '角色与权限',
          icon: ShieldCheckmarkOutline,
          status: 'ready',
          desc: '角色定义与菜单/操作权限分配。权限树直接由菜单树推导，不会和页面脱节。',
          refs: [
            { label: '权限键规则', value: '菜单权限 menu:<菜单name>；操作权限 action:<模块>:<动作>；超管用通配符 *' },
            { label: '权限点定义', value: 'src/config/permissions.ts（删除 Redis 键、执行只读 SQL、启停条件单等）' },
            { label: '建议表', value: 'T_AdminRole（code / name / builtin）、T_AdminRolePermission（roleId / permissionKey）' },
            { label: '注意', value: '内置角色不可删；删除超管角色会导致后台再也进不去' },
          ],
          todos: ['角色 CRUD（内置角色只读）', '按菜单分组勾选权限点', '改权限写审计（含操作人与前后差异）'],
        },
      },
      {
        name: 'system-audit',
        path: '/system/audit',
        view: 'system/SystemAudit',
        meta: {
          title: '操作审计',
          icon: DocumentTextOutline,
          status: 'ready',
          desc: '高危操作（删除、清理、下发协议、改权限）的执行人、目标与结果，含管理端登录日志。',
          refs: [
            { label: '需审计的操作', value: 'Redis 键/流删除、MySQL 写操作、破坏测试发送、连接配置变更' },
            { label: '建议表', value: 'T_AdminAudit（operator / action / target / result / ip / costMs）、T_AdminLoginLog' },
            { label: '动作来源', value: '审计动作键与 permissions.ts 的权限键同源，筛选与权限树能对上' },
            { label: '区别', value: '不是 T_UserLog —— 那是云单客户的登录日志（19 万行），见「账户与用户 / 登录日志」' },
          ],
          todos: ['审计日志列表与检索', '操作前后快照对比', '导出审计报表'],
        },
      },
    ],
  },
]

/** 深度优先展开出所有叶子节点（即真正对应页面的路由）。 */
export function flattenMenu(nodes: MenuNode[] = menuTree): MenuNode[] {
  const out: MenuNode[] = []
  for (const node of nodes) {
    if (node.children?.length) {
      out.push(...flattenMenu(node.children))
    } else {
      out.push(node)
    }
  }
  return out
}

/** 按 name 查找菜单节点。 */
export function findMenuByName(name: string, nodes: MenuNode[] = menuTree): MenuNode | undefined {
  for (const node of nodes) {
    if (node.name === name) return node
    if (node.children?.length) {
      const hit = findMenuByName(name, node.children)
      if (hit) return hit
    }
  }
  return undefined
}

/** 求某路径对应的菜单祖先链，用于面包屑。 */
export function findMenuChainByPath(path: string, nodes: MenuNode[] = menuTree): MenuNode[] {
  for (const node of nodes) {
    if (node.path === path) return [node]
    if (node.children?.length) {
      const child = findMenuChainByPath(path, node.children)
      if (child.length) return [node, ...child]
    }
  }
  return []
}

/** 第一个可跳转的叶子路径，用于「/」重定向。 */
export function firstLeafPath(nodes: MenuNode[] = menuTree): string {
  for (const node of nodes) {
    if (node.children?.length) {
      const inner = firstLeafPath(node.children)
      if (inner) return inner
    } else if (node.path) {
      return node.path
    }
  }
  return '/dashboard'
}
