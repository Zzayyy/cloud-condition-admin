# 云条件单后台管理系统 · 前端

管理云条件单相关的**策略平台、委托服务器、数据中台、Redis 与 MySQL 数据**。

技术栈：Vue 3 + Vite + TypeScript + Naive UI + Vue Router + Pinia + Axios + ECharts。

> 当前阶段：**前端页面开发中，后端未动**。
> 已实现「总览」与「策略平台管理」3 个页面，其余为占位页。
> 因为没有后端，页面默认走**演示数据**（见下方「演示数据机制」），
> 界面上会明确标出「当前为演示数据」，不会跟真实数据混淆。

---

## 已实现的页面

| 页面 | 状态 | 说明 |
|---|---|---|
| 总览 | ✅ | 实测快照 + 协议清单 + 建设进度 |
| 策略平台管理 / 策略服务器 | ✅ | 节点列表、在线状态、负载分布图、详情抽屉、筛选排序分页 |
| 策略平台管理 / 条件单策略 | ✅ | 三数据源切换、筛选、类型/趋势图、明细抽屉（含原始报文 JSON）、启停/删除入口 |
| 策略平台管理 / 策略运行日志 | ✅ | 检索筛选、状态分布图、日志详情、条件单轨迹时间线 |
| 委托服务器管理 / 委托服务器 | ✅ | 节点表 + 账号分配关系双 Tab、负载分布图、节点详情（含承载账号下钻） |
| 委托服务器管理 / 委托单 | ✅ | 主表/历史表切换、买卖+开平+合约筛选、方向分布与趋势图、详情抽屉（成交明细 + OrderParam JSON） |
| 委托服务器管理 / 成交回报 | ✅ | 主表/历史表切换、合约成交量分布、详情抽屉（**成交 → 委托** 反向下钻） |
| 数据中台管理 / 中台节点 | ✅ | 实例清单、unique_id 水位、14 个 Pub/Sub 频道分组、无消费组与积压告警 |
| 数据中台管理 / 消息总线 | ✅ | 27 条 stream 跨 136/137、消费组 lag/pending、按 request_id 串联、报文原文查看 |
| 数据中台管理 / 协议报文 | ✅ | 9 个协议速查 + 字段/模板、MsgType 与 NtType 对照、XADD 信封、频道报文 |
| 账户与用户 / 用户账号 | ✅ | MySQL `T_User` 与 Redis `acc-*` 两侧合并、数据来源标记、Redis 实时态 JSON |
| 账户与用户 / 签署与股东号 | ✅ | 签署状态（`RegProtocol` 是 binary）、股东号按市场分组、acc_sign/acc_update 报文模板 |
| 账户与用户 / 登录日志 | ✅ | 明细 + 按账号聚合、设备信息筛选（**现场三列全 NULL，页面已如实说明**） |
| 数据存储 / Redis 管理 | ✅ | 实例 INFO、DB 树、SCAN 键浏览（类型/TTL/归属）、键内容按类型渲染、删键（二次确认） |
| 数据存储 / 数据库管理 | ✅ | 表清单（近似 vs 真实行数）、表结构（字段/注释/索引/DDL）、只读 SQL 查询 |
| 数据存储 / 数据维护 | ⏸ | **已与需求方确认暂缓**（占位页保留，页面上写明原因与已发现的疑点） |
| 系统管理 / 管理用户与权限 | ✅ | 后台管理账号 CRUD、角色分配、启停/解锁/重置密码、密码策略即时校验（**与 `T_User` 无关**） |
| 系统管理 / 角色与权限 | ✅ | 角色列表 + 权限树（**由菜单树推导**）、内置角色保护、危险操作权限点标记、改权限写审计 |
| 系统管理 / 操作审计 | ✅ | 高危操作审计（含动作键与权限键同源）+ 管理端登录日志（与 `T_UserLog` 分开）+ CSV 导出 |
| 系统管理 / 连接配置 | ⏳ | 占位页（后端待实现），列出数据来源与待办 |

上表 **20 行 = `flattenMenu()` 的 20 个叶子**（`总览` 本身就是叶子，所以叶子数比 19 个子节点多 1）。
其中 **18 个已实现**（`status: 'ready'`），**2 个占位**：`数据维护`（已与需求方确认暂缓）、`连接配置`（待开发）。
占位页不写 `view`，会自动落到 `PlaceholderView`。

> **已删除的菜单：「性能压测」「破坏测试」**（2026-10-14 与需求方确认）。
> 删除理由：那两项是研发发版前用命令行工具做的活
> （`datahub_test/`、`strategy_test/` 里已有完整的 PySide6 GUI），
> 与后台管理的日常运维无关；且**破坏测试能让数据中台崩溃**（现场已验证），
> 做成网页按钮会让高危操作变成「登录就能点」——
> 命令行工具里的护栏（检测到真平台消费者即拒绝发送、二次确认不记忆）
> 反而是更安全的做法。删除「性能压测」**不丢监控能力**：
> 积压 / lag / pending / 节点数 / 内存水位都已在消息总线、中台节点与本页覆盖。

---

## 演示数据机制

后端还没写，但页面要能看效果，所以有一套**显式**的演示数据机制。

### 怎么切换

`.env.development` 里的 `VITE_USE_MOCK`：

| 取值 | 行为 |
|---|---|
| `true`（开发默认） | 直接渲染 `src/api/mock/` 下的演示数据 |
| `false` | 走真实接口；**仅开发环境**下后端不可达时自动降级并提示 |

`.env.production` 里强制 `VITE_USE_MOCK=false`。

### 三条安全约束（重要）

这是管理真实交易系统的后台，宁可报错也不能给假数据：

1. **生产构建绝不自动降级** —— 自动降级只在 `import.meta.env.DEV` 下生效。
   后端挂掉时生产环境显示红色错误条，不会悄悄拿假数据顶上。
2. **业务错误不降级** —— 后端答了但返回 `code != 0`（`BizError`）时如实报错；
   只有「连不上」才考虑降级。
3. **误开开关会报警** —— 生产构建里若 `VITE_USE_MOCK=true`，控制台打 `console.error`。

4. **图表/下钻也必须守这条线** —— 任何从 `src/api/mock/` 直接读数据的地方
   （图表聚合、抽屉里的下钻列表），都必须用 `isMock` 判断：
   非演示态**返回空数组**让组件显示「暂无数据」，绝不能拿 mock 充数。
   图表尤其容易漏，因为 `BaseChart` 只看 `series.data` 的长度，
   `[{value:0},{value:0}]` 长度是 2，会画出一个没有意义的空环
   （已修：`BaseChart.hasData()` 现在要求至少一个非零值）。

演示数据在界面上有**明确的黄色横幅**标注，并写明原因，不会被误认为真实数据。
数据是按现场真实表/键结构造的（`strategy-srv`、`T_Conditional`、`T_StrategyLog`、
`traderserver`、`T_Order`、`T_Trade`），但**数值是编的**。
后端接通后，删掉 `src/api/mock/` 目录与调用处的 `mock` 参数即可。

---

## 快速开始

```bash
cd cloud-condition-admin/frontend
npm install
npm run dev          # http://localhost:5173
```

其他命令：

| 命令 | 说明 |
|---|---|
| `npm run dev` | 开发服务器（含 API 代理） |
| `npm run build` | 类型检查 + 生产构建，产物在 `dist/` |
| `npm run preview` | 预览生产构建 |
| `npm run typecheck` | 只跑 `vue-tsc` 类型检查 |

---

## 菜单是怎么定出来的

菜单不是拍脑袋想的，是先到现场把数据结构摸清楚再反推的。

### 第一步：摸清真实结构

用只读方式连接现场环境核对（**未做任何写操作**）：

* **MySQL** `192.168.1.136:3306` / 库 `qianlong_cloud`（MySQL 8.0.45），
  用户 `qianlong_cloud`。共 **9 张表 + 1 个视图**：

  | 表 | 说明 | 实测规模 |
  |---|---|---|
  | `T_Conditional` | 正在运行的条件单策略 | 0 行 |
  | `T_Conditional_his` | 历史条件单 | 0 行 |
  | `V_Conditional` | 视图，前两张表的并集 | — |
  | `T_Order` | 委托下单表 | 自增 2644 |
  | `T_Order_his` | 历史委托 | 1 行 |
  | `T_Trade` | 成交数据 | 自增 979 |
  | `T_Trade_his` | 历史成交 | 0 行 |
  | `T_User` | 用户账号 | 自增 160698 |
  | `T_UserLog` | 登录日志 | **19.05 万行** |
  | `T_StrategyLog` | 策略单日志 | **1.66 万行** |

* **Redis** 两个实例，均为 8.6.2：

  | 实例 | 内存 | 客户端 | 使用的 DB |
  |---|---|---|---|
  | `192.168.1.136` | 86.98 M | 12 | db0、db1 |
  | `192.168.1.137` | 1.47 G | 6 | db0、db1、db2 |

  键分为三类：

  * **Stream**：`DataHub_req_stream`（137 db0 已达 **407.3 万条**）、
    `DataHub_reply_stream`、`WT-<编号>` / `WT-<编号>-reply`（委托服务器方向）、
    `ST-<编号>` / `ST-<编号>-reply`（策略平台方向）。
    消费组固定为 `user_group`，consumer 名就是流名本身。
  * **ReJSON**：`traderserver`（委托服务器节点数组）、`strategy-srv`（策略服务器节点数组）、
    `acc-<账号>_<账号类型>_<渠道>`、`strategy-<账号>_<账号类型>_<渠道>`。
  * **String**：`unique_id`（已分配的唯一编号水位）。

  Pub/Sub 频道：`tradeserver_{online,beat,offline}`、`strategyserver_{online,beat,offline}`、
  `datahub_{online,beat,offline,select,oncall}`。
  ⚠️ 现场真中台订阅的是**带 `_1` 后缀**的那一套。

* 协议文档：`请求接口字段.txt`（9 个客户端协议）、`请求接口doc.txt`、
  `数据中台to策略平台.txt`（MsgType 4/8/11/17/18）。

### 第二步：按「管理对象」分菜单

菜单按**被管理的实体**划分，而不是按技术或页面类型：

```
总览
策略平台管理      ← 策略服务器、条件单策略、策略运行日志
委托服务器管理    ← 委托服务器、委托单、成交回报
数据中台管理      ← 中台节点、消息总线、协议报文
账户与用户        ← 用户账号、签署与股东号、登录日志
数据存储          ← Redis 管理、数据库管理、数据维护
系统管理          ← 连接配置、管理用户与权限、角色与权限、操作审计
```

共 **7 个一级菜单 / 20 个页面**（`总览` 自己就是一个页面，所以叶子数 = 20）。

> 「监控与统计」这个一级菜单已整体删除（2026-10-14）：其中
> 「性能压测」「破坏测试」是研发发版前的命令行活（`datahub_test/`、`strategy_test/` 里已有 GUI），
> 「实时指标」的 11 项指标逐项核对后**没有一项是别处看不到的**，
> 唯一独有的时间趋势已并入「消息总线」。详见下方「已删除的菜单」。

这样划分的原因：

1. **对应你说的四类管理对象**：「所有的策略平台、委托服务器、数据中台，还有 Redis 以及数据库」，
   分别落到「策略平台管理」「委托服务器管理」「数据中台管理」「数据存储」。
2. **每个菜单都有真实数据兜底**（见下表），不是空壳。
3. **账户与用户是隐藏的第五个维度**：`T_User` / `acc-*` 既不属于平台也不属于存储，
   但条件单必须挂在账号上，所以单独成组。
4. **系统管理收口「管后台的人」**：连接配置、管理用户与权限、角色与权限、操作审计。
   这一组管的是**后台自己**（谁能登录、能点哪些按钮、干了什么），
   与「账户与用户」那组管的**云单客户**（`T_User`）是两条完全不同的线，不要混。

### 第三步：把数据来源写进菜单

每个菜单项在 `src/config/menu.ts` 里都带 `refs`（数据来源）和 `todos`（待办功能），
占位页会直接把它们渲染出来。**所以打开任意一个页面，就能知道这个页面该连哪张表、哪个键。**

---

## 权限体系（系统管理）

三个页面（管理用户与权限 / 角色与权限 / 操作审计）共用一套权限键，
**权限不是另造一套字符串，而是挂在已有的菜单树上**（`src/config/permissions.ts`）：

| 类型 | 键格式 | 例子 | 说明 |
|---|---|---|---|
| 菜单权限 | `menu:<菜单 name>` | `menu:storage-redis` | `name` 就是 `src/config/menu.ts` 里的路由 name，所以「加一个菜单」权限树自动多一个节点，不会两处对不上 |
| 操作权限 | `action:<模块>:<动作>` | `action:storage-redis:delete-key` | 菜单里**看不见**的高危动作（删键、执行只读 SQL、启停节点） |
| 通配符 | `*` | — | 超管专用，代表全部权限，菜单新增后自动覆盖，不用回填 |

三条刻意的设计决定：

1. **父级菜单权限不隐式覆盖操作权限。** 勾了 `menu:storage-redis` 不等于自动拥有
   `action:storage-redis:delete-key` —— 否则「能看到页面」就等于「能删键」，
   操作权限点就白加了。界面上靠显式按钮帮管理员一并勾上，
   而不是靠运行时隐式放行（`hasPermission()` 只认精确键与 `*`）。
2. **权限树里所有父节点一律不可勾选（`checkboxDisabled`）。**
   这是堵一条**静默提权**路径：naive-ui 的 Tree（treemate）在 `cascade` 模式下，
   会给任何被勾选的**父节点**自动展开勾上它的**全部子孙**
   （`treemate/es/check.js` 的 `getExtendedCheckedKeySet`，第 166~196 行）。
   而权限树的父节点是**分组**（「数据存储」）和**页面**（「Redis 管理」）——
   它们的标签**不会**告诉用户「勾上我还会授出删除 Redis 键」。
   如果允许勾父节点，管理员只想给「页面访问」，实际却授了高危操作权限，
   而且此后随便勾一下别的项，这个被隐式点亮的键会被回写进角色 —— 权限悄悄放大。
   所以：**显示即授权**，父节点不可勾，批量授权走显式按钮（危险项二次确认）。
   另外，权限树在 emit 时用 `allPermissionKeys()` 做白名单过滤，
   作为纵深防御，即使父节点被程序化设为 checked 也不会写进角色。
3. **权限点只列真正危险的动作。** 连「查看详情」都算权限的话，
   管理员会图省事全勾上，等于没有控制。清单见 `ACTION_PERMISSIONS`。
4. **写错菜单名会直接报错。** `assertActionPermissions()` 在模块加载时自检
   「操作权限引用的菜单是否存在、键是否重复」——这类错误本来是**静默失效**
   （某个权限点永远勾不上），改成加载时就 `console.error` 打出来。
5. **`can('')` 不是有效守卫。** 演示态下 `authStore.can()` 无条件放行，
   传空键会返回 `true`。守卫一律要传真实的权限键，不要用空串/未定义值兜底。

> 权限键总数（可用 `allPermissionKeys()` 核对）：菜单叶子数 + `ACTION_PERMISSIONS` 条数。
> 当前是 **20 + 7 = 27** 个键（菜单叶子含 `总览` 自己 —— 它也是一个页面）。

### 关于鉴权状态（重要，别被界面骗了）

`src/stores/auth.ts` 管理当前会话。**它没有假装登录成功**：

* **演示态**（`VITE_USE_MOCK=true`）：给一个明确标注为「演示身份」的身份
  （顶栏会显示 `演示身份` 标签），权限为通配符，这样三个页面的按钮可见性逻辑
  能真实跑起来，而不是一片灰按钮看不出效果。
* **非演示态**：没有真实资料就是「未登录」，权限为空，
  **所有写操作一律不可用** —— 宁可点不动，也不能在没有鉴权的情况下放开高危入口。
* 后端 `backend/` 仍然是空的，`/system/auth/login` 没人实现，
  所以 README「已知待办」里那条「登录鉴权未接」**依然成立**，
  顶栏「退出登录」依旧不假装已登出。

---

## 视觉设计

界面不是「Naive UI 默认样子的堆砌」，有一套自己的设计令牌。

### 模块配色

7 个一级菜单各有主色，侧边栏图标、页面标题竖条、页签圆点、
总览指标卡、占位页强调色都跟着走，扫一眼就知道自己在哪个模块
（`监控与统计` 一级菜单已删，其红色仍被消息总线页的趋势卡沿用）：

| 模块 | 颜色 | 模块 | 颜色 |
|---|---|---|---|
| 总览 | 蓝 `#3b6ff5` | 账户与用户 | 玫红 `#d94f8a` |
| 策略平台管理 | 紫 `#7c5cf0` | 数据存储 | 绿 `#2f9e5f` |
| 委托服务器管理 | 青 `#0d9c9c` | 监控与统计 | 红 `#d6455e` |
| 数据中台管理 | 橙 `#dd7a15` | 系统管理 | 石板灰 `#5b6b8c` |

配色定义在 `src/theme/palette.ts`。数据来源卡片还会按**数据类型**再上一次色
（MySQL / Redis / Stream / Pub-Sub / JSON / 文档），和模块色是两套体系，
互不干扰。

### 亮暗双主题

* 亮/暗两套 CSS 变量在 `src/assets/styles/global.css`，
  `html.dark` 上整体切换；组件里一律引用变量，不写死颜色。
* 顶栏可切「跟随系统 / 浅色 / 深色」，选择记在 `localStorage`。
* 暗色不是简单反色：主色、语义色、阴影、装饰光晕都单独调过。
  > 例如指标卡右下的装饰光晕用 `--stat-glow` 控制，
  > 亮色 `0.07`、暗色 `0.045` —— 同一个透明度在暗色下会糊成一团脏影子。

### 样式约定

* 组件内的 `<style scoped>` 只写**布局**，颜色一律走 CSS 变量。
* 需要钻进 Naive UI 内部时用 `:deep()`，**不要用 `:global()`** ——
  它在 scoped 样式里会被编译成 `html.dark{...}` 这种裸选择器，
  污染全局（这个坑已经踩过一次）。
* 复用类放在 `global.css`：`.page-container`、`.page-card`、`.mono`、
  `.section-title`。

---

## 加一个菜单怎么做

**只需改一个文件**：`src/config/menu.ts`。侧边栏、面包屑、页面标题、路由表全部由它推导。
**权限树（`src/config/permissions.ts`）也从同一棵树推导**，
所以加菜单时权限页会自动多出对应节点，不需要再去权限那边补一份。

```ts
{
  name: 'monitor-alarm',          // 唯一，同时是路由 name 和菜单 key
  path: '/monitor/alarm',
  view: 'monitor/MonitorAlarm',   // 可选；对应 src/views/monitor/MonitorAlarm.vue
  meta: {
    title: '告警规则',
    icon: AlertCircleOutline,     // 来自 @vicons/ionicons5
    status: 'planned',            // planned 后端待实现 / placeholder 页面待开发 / ready 已完成
    desc: '一句话说明这个页面干什么',
    refs: [{ label: 'MySQL 表', value: 'T_Alarm' }],
    todos: ['规则增删改查', '告警渠道配置'],
  },
}
```

* **不写 `view`** → 自动落到通用占位页（能显示 refs/todos），适合先占位。
* **写了 `view`** → 去 `src/views/` 下建对应文件即可，路由会自动接上。
* `status: 'ready'` 后，顶栏的「页面待开发」标签会自动隐藏。
* 写完不用注册路由，`router/index.ts` 会自动读取。

> ⚠️ **踩坑记录（以下都是静默失效，构建/类型检查发现不了）**
>
> **1. `n-tab-pane` 不要用 `tab="..."` 属性。**
> naive-ui 2.45.x 的 `Tabs` 只从 `#tab` **插槽**读取标签，
> 写成 `tab="基本信息"` 会渲染成**空标签**（不报错）。必须写
> `<template #tab>基本信息</template>`。
>
> **2. `useResource` 的返回值不要在模板里用 `xxx.error` 这种写法。**
> 那样拿到的是 **Ref 对象（永远为真值）**，`v-if` 会无条件通过 ——
> 实测表现为「数据加载失败」和「当前为演示数据」两个横幅同时出现。
> 必须解构：`const { data, error, load } = useResource(...)`。
>
> **3. 不要给表格固定列写 `::after` / `::before`。**
> naive-ui 已用伪元素画「横向滚动时的固定列投影」：
> `fixed-left` 用 `::after`（`width:36px; right:-36px`，投影落在**相邻列**上），
> `fixed-right` 用 `::before`。我曾想加一条 1px 分界线，结果**覆盖掉了
> 那个 36px 投影的宽度**，只留一条残留灰条压在第 2 列上 —— 表现就是
> 「条件单策略 / 委托单 / 成交回报 第二列有灰影」。这三张表恰好都是
> 第一列 `fixed:'left'`，其余表只有 `fixed:'right'` 所以没事。
> **需要分界线请用 border，不要碰伪元素。**
>
> **4. 给 naive-ui 组件写覆盖样式时注意特异性。**
> 它的规则常是 `.n-data-table .n-data-table-th`（两个类，0-2-0）。
> 只写一个类会被静默盖掉（固定列背景第一次就是这么没生效的）。
> 排查办法：用 CDP 的 `CSS.getMatchedStylesForNode` 看真实级联，别猜。
>
> **5. 多个数据源不要各自判断 `isMock`。**
> `onMounted` 里并发发两个请求、第二个再去看 `isMock.value` 决定走 mock 还是接口，
> 是会错的 —— 那时第一个请求还没回来，`isMock` 仍是 `false`，
> 于是第二个请求去调真实接口、失败后返回空数组。
> 实测表现：节点表显示演示数据，但「Pub/Sub 频道」却是 **0 个**。
> 正确做法是每个数据源都走同一个 `useResource`，让降级逻辑自己保持一致。
>
> **5b. 判断「走演示分支还是真实分支」要用 `usingMock`，不要用 `isMock`。**
> `isMock` 的初值是 `false`，只有 `load()` 的 `await` **之后**才置位。
> 页面在 `onMounted` 里 `void reload()`（不 await）时，首个请求返回前
> （mock 的 `withDelay` 默认 260ms）`isMock.value` 仍是 `false` ——
> 此刻用户点写操作按钮，就会**误判为真实态**、去调还没实现的后端、然后弹一个「失败」。
> 实测：`load()` 后 await 前 `isMock===false`、`loading===true`、`apiCalls===0`。
> `useResource` 因此额外返回 `usingMock`：
> ```ts
> const usingMock = computed(() => (settled.value ? isMock.value : USE_MOCK))
> ```
> 数据未到手时退回**构建期常量** `USE_MOCK`（没有时序问题），
> 到手后以 `isMock` 为准（还能识别「自动降级到演示数据」的中间态）。
> **注意分工**：`usingMock` 用于**分支判定**（该调接口还是该改本地演示数据）；
> 而 `isMock` 继续用于**事实展示**（「当前为演示数据」横幅、演示密码按钮的显隐）——
> 横幅本来就该等数据到手后才显示，不要跟着改成 `usingMock`。
>
> **6. 模板里不要写 Markdown 语法。**
> `<div>**重点**</div>` 不会加粗，而是**把两个星号原样显示出来**。
> 要在 `.vue` 的 `<template>` 里加粗请用 `<b>` / `<strong>`；
> `**` 只对 Markdown 渲染器有效（注释里随便用）。
> 排查：`grep '\*\*[^*]+\*\*'` 只看 `<template>` 之后的部分。

> **7. 筛选参数的「类型」会静默吃掉筛选条件。**
> mock / 后端判定常常写成 `asString(q.x)`，这时页面传**布尔** `true`（来自开关或未转字符串的下拉）
> 会被判成空字符串 → **不过滤、返回全量、不报错**。实测踩到过两次：
> 登录日志「结果」筛选传 `'success'`（复用了审计侧的取值）导致**永远只显示失败的行**；
> 审计「结果」传布尔 `true` 导致 220 条全返回。
> 处理办法：布尔参数一律用 `_shared.ts` 的 `asBool()`（布尔 / `'true'` / `'1'` / `1` 都认，
> `undefined` 仍表示「未筛选」）；**同一概念在不同模块的取值要写清楚**，
> 比如审计 `result` 是 `'success'|'failure'`，登录 `success` 是布尔 —— 两者不能互相复用。
>
> **8. 时间范围筛选的字符串格式必须定宽且一致。**
> 演示态/后端若用字符串比较（`row.time < from`），传 ISO（`2026-10-01T00:00:00.000Z`）
> 或 `YYYY-M-D` 会**静默筛错**（实测 ISO 筛出 37 行、统一格式筛出 45 行，都不报错）。
> 一律用 `YYYY-MM-DD HH:mm:ss` 零填充，且**聚合接口与列表接口共用同一口径**
> （mock 里已抽成同一个 `filterByRange`，避免两处各写一遍各错一遍）。
>
> **9. 分页接口不能拿来「统计」。**
> 明细接口是分页的，前端硬凑只统计到当前一页，却会被当成全量读（运维场景里很危险）。
> 图表与导出必须走后端聚合/导出接口（`getAuditDaily`、`exportAuditLogs` 等），
> 不要用 `list*` 在前端算。

---

## 目录结构

```
frontend/
├── src/
│   ├── api/
│   │   ├── http.ts        # Axios 实例、拦截器、统一响应解包、错误类型
│   │   ├── types.ts       # ApiEnvelope / PageQuery / PageResult
│   │   ├── domain.ts      # 业务实体类型（按真实表结构整理）
│   │   ├── endpoints.ts   # 按模块拆分的接口定义（全部待后端实现）
│   │   └── mock/          # 各模块演示数据（后端接通后可整个删掉）
│   │       ├── strategy.ts
│   │       ├── trade.ts
│   │       ├── datahub.ts
│   │       ├── account.ts
│   │       ├── storage.ts
│   │       ├── monitor.ts
│   │       └── system.ts  # 后台管理账号 / 角色 / 审计 / 登录日志
│   ├── composables/
│   │   └── useResource.ts # ★ 取数：真实接口优先 + 演示数据兜底
│   ├── utils/
│   │   └── format.ts      # JSON 格式化、相对时间、空值处理
│   ├── theme/
│   │   └── palette.ts     # ★ 模块配色（一级菜单各自主色）
│   ├── config/
│   │   ├── menu.ts        # ★ 菜单唯一数据源（含数据来源与待办）
│   │   └── permissions.ts # ★ 权限键契约（权限树由 menu.ts 推导）
│   ├── layouts/
│   │   └── BasicLayout.vue# 侧边栏 + 顶栏 + 面包屑 + 多页签
│   ├── router/
│   │   └── index.ts       # 由 menu.ts 推导路由
│   ├── stores/
│   │   ├── app.ts         # 主题、侧边栏折叠
│   │   ├── auth.ts        # ★ 当前会话与权限判定（演示身份，不假装已登录）
│   │   └── tabs.ts        # 多页签
│   ├── components/        # StatCard / BaseChart / MockDataAlert / QueryErrorAlert / PermissionTree / PagePlaceholder
│   ├── views/
│   │   ├── dashboard/DashboardView.vue
│   │   ├── strategy/
│   │   │   ├── StrategyServer.vue   # 策略服务器
│   │   │   ├── StrategyList.vue     # 条件单策略
│   │   │   └── StrategyLog.vue      # 策略运行日志
│   │   ├── system/
│   │   │   ├── SystemUser.vue       # 管理用户与权限
│   │   │   ├── SystemRole.vue       # 角色与权限
│   │   │   └── SystemAudit.vue      # 操作审计
│   │   ├── PlaceholderView.vue      # 通用占位页
│   │   └── NotFoundView.vue
│   ├── assets/styles/global.css
│   ├── App.vue
│   └── main.ts
├── .env / .env.development / .env.production
└── vite.config.ts
```

---

## 前后端约定

后端目录 `cloud-condition-admin/backend/` 目前是空的。前端已按下面的约定写好，
后端照此实现即可直接对接。

### 1. 统一响应结构

所有接口返回 HTTP 200，业务结果放 body：

```json
{ "code": 0, "message": "ok", "data": {} }
```

* `code === 0` 视为成功，其余视为业务错误（前端抛 `BizError`）。
* 后端若采用别的结构，**只需改 `src/api/http.ts` 里的 `isApiEnvelope` 与 `unwrap`**。
* 分页统一用 `PageQuery` / `PageResult`（`src/api/types.ts`），
  即 `{ page, pageSize, keyword, sortBy, sortOrder }` → `{ list, total, page, pageSize }`。

### 2. 接口清单

接口路径已按资源定义在 `src/api/endpoints.ts`，主要有：

| 模块 | 接口 |
|---|---|
| 策略平台 | `/strategy/servers`、`/strategy/conditionals`、`/strategy/logs` |
| 委托服务器 | `/trade/servers`、`/trade/assignments`、`/trade/orders`、`/trade/trades` |
| 数据中台 | `/datahub/nodes`、`/datahub/streams`、`/datahub/trace` |
| 账户用户 | `/account/accounts`、`/account/redis/{account}`、`/account/logs` |
| 数据存储 | `/storage/redis/keys`、`/storage/redis/channels`、`/storage/mysql/query` |
| 系统管理（连接配置） | `/system/connections` |
| 系统管理（管理用户/权限/审计） | `/system/users`、`/system/roles`、`/system/audit-logs`、`/system/login-logs`、`/system/password-policy`、`/system/auth/*` |

> 路径只是**建议**，改动时同步改 `endpoints.ts` 即可。

### 2.1 管理端账号体系要新加的表（后端参考）

`T_User` 那条线是**云单客户**，后台管理账号必须另建表，别复用：

| 建议表 | 关键字段 | 说明 |
|---|---|---|
| `T_AdminUser` | `username`(唯一) / `displayName` / `enabled` / `locked` / `failedAttempts` / `mustChangePassword` / `lastLoginTime` / `lastLoginIP` / `loginCount` | 密码只存哈希，**接口永不回显** |
| `T_AdminRole` | `code` / `name` / `desc` / `builtin` | `builtin=true` 不可删（删超管角色 = 后台再也进不去） |
| `T_AdminRolePermission` | `roleId` / `permissionKey` | 键格式见「权限体系」一节 |
| `T_AdminAudit` | `operator` / `action` / `module` / `level` / `target` / `result` / `message` / `ip` / `costMs` | 高危操作必写 |
| `T_AdminLoginLog` | `username` / `loginTime` / `loginIP` / `loginOS` / `success` / `info` | **不同于** `T_UserLog` |

后端实现这组接口时，有三件事**必须**做到（前端已经按这个前提写，靠前端把关等于没有把关）：

1. **停用/改权限要让已签发的 token 失效**，只改数据库字段的话，已经登录的会话还能继续用，是个真实的越权口子。
2. **权限校验必须在后端做**。前端的 `authStore.can()` 只用来控制按钮可见性，任何人都能直接打接口，前端判断不是安全边界。
3. **改权限、重置密码、管理账号这三类操作本身也要写审计**（否则「谁给自己提了权」查不到）。

### 3. 开发环境代理

`.env.development` 里的 `VITE_API_TARGET`（默认 `http://127.0.0.1:8000`）
就是后端地址，`/api` 前缀会代理过去。后端起来后改这一处即可。

### 4. 后端实现时必须注意的安全点

这些是现场实测发现的，**接口设计时要带上限，否则会把生产环境拖垮**：

* `137 db0` 的 `DataHub_req_stream` 有 **407 万条**，
  列表类接口必须强制分页 + 条数上限，禁止全量 `XRANGE`。
* `T_UserLog` 19 万行、`T_StrategyLog` 1.6 万行，查询必须走分页与索引。
* Redis 键扫描用 `SCAN`，禁止 `KEYS *`。
* `/storage/mysql/query` 必须**强制只读**并限制结果集大小。
* 破坏测试写的是 `137 db2` 的 `DataHub_req_stream`（`db2` 与生产流隔离），
  不要写到 db0/db1。
* 高危操作（删键、删流、破坏测试、清理数据、改连接配置）需要二次确认 + 写审计。

---

## 已知待办

- [ ] 后端服务（`backend/` 为空，所有接口待实现）
- [ ] 登录鉴权：顶栏「退出登录」仍**不假装已登出**（真实态只清本地 token），未接真实鉴权；
      登录页也还没做 —— `authStore.login()` 已按契约写好，后端 `/system/auth/login` 一通就能接上
- [ ] 「策略平台管理」「委托服务器管理」共 6 个页面的写操作（启停/删除/启用开关）只弹提示，等接口
- [ ] **系统管理的三个页面只做了管理端自身**，还有两件必须由后端兜住的事（前端已按此前提写注释）：
      1. 停用账号 / 改权限后要让**已签发的 token 失效**，否则旧会话继续可用；
      2. **权限判定必须在后端做** —— `authStore.can()` 只管按钮可见性，
         能直接打接口的人不受它约束。
- [ ] 系统管理的写操作（建账号/改角色/重置密码）目前**演示态改本地、真实态如实报错**，
      接口落地后要补：乐观更新回滚、并发编辑冲突提示（两个管理员同时改同一角色）
- [ ] 剩余占位页逐个实现（`连接配置` 待开发；`数据维护` 已与需求方确认暂缓）
- [ ] 后端就绪后删掉 `src/api/mock/`，并把 `.env.development` 的 `VITE_USE_MOCK` 改为 `false`
- [ ] 各页汇总指标当前是「本页口径」，真实汇总应由后端单独出接口
- [ ] **`OrderStatue` 枚举未知**：协议文档没定义，现场 `T_Order_his` 只有 1 条测试数据，
      页面只展示后端返回的 `StatueDis` 文本与原值。**需要向现场确认取值含义**后再做状态着色/统计。
- [ ] 委托单/成交回报的「主表 vs 历史表」目前靠前端传 `table` 参数区分，
      真实实现建议后端按归档时间自动路由，或明确拆分接口
- [ ] `T_Order_his` / `T_Trade_his` / `T_Conditional` 现场都只有 0~1 行真实数据，
      接口联调时需要先造数据
- [ ] **`datahub_online` / `datahub_beat` 报文格式文档缺失**：文档只列了频道名，
      没给报文结构。协议报文页已如实标注，需向现场确认。
- [ ] **消息总线的数值目前是探查快照**：137 db0 入口流 407 万条、136 的 ST-0 有 595 pending
      都是实测值，接入后端后应改为实时查询（这些流很大，后端必须限流分页）
- [ ] 现场 137 上 **0 个 Pub/Sub 频道**，136 上 14 个频道**每个只有 1 个订阅者** ——
      这个差异是否正常，建议向现场确认
- [ ] **`T_User.MainType` 与列注释矛盾**：注释写「0 股票/1 股票期权/2 期货」，
      但实际值 = 6，且账号串是 `FAccount_AccountType_AccAtt`，说明它存的是**渠道 AccAtt**。
      本页按实际数据（AccAtt 语义）展示，**建议向现场确认该列真实含义**。
- [ ] **`T_UserLog` 的 `LogIP`/`LogMAC`/`LogOS` 三列全部为 NULL**（19.1 万行无例外），
      登录日志页查不到来源设备。如需设备信息，要么中台补写这三列，
      要么改用 `T_StrategyLog` 的 `FromIP`/`FromMac`（那边有值）。
- [ ] 用户账号页的「仅 Redis 有」目前依赖后端把 `T_User` 与 `acc-*` 合并后返回；
      现场 `T_User` 只有 1 行，合并口径需与现场对齐
- [ ] **数据存储 / 数据维护已确认暂缓**（需求方：暂时用不上）。恢复时优先做两件事：
      1. **MySQL ↔ Redis 一致性核对** —— 已实测到巨大差异：MySQL
         `T_Conditional` + `T_Conditional_his` = **0 行**，但 Redis `strategy-*` 键里有
         **59 条条件单**（136 db1 共 55 条：`strategy-914900043_7_6` 47 条、
         `strategy-300130000461_1_6` 6 条、`strategy-1000006_1_6` 2 条；137 db1 另有 4 条）。
         需要向现场确认这是设计如此（Redis 为准）还是数据没落库。
      2. 主表 → `_his` 历史表归档。
- [ ] **Redis `maxmemory=0` + `noeviction`**（两个实例都是）：内存无上限，
      写满会直接报错而不是淘汰旧键。137 的内存峰值曾达 **12.45G**（当前 1.47G），
      建议现场确认是否需要设上限与淘汰策略。
- [ ] **`information_schema.TABLE_ROWS` 不可信**：实测 `T_StrategyLog` 报 16566、
      真实 `COUNT` = 17430，`T_UserLog` 报 190500、真实 191002。
      后端若用该字段做分页总数会不一致，必须以 `COUNT(*)` 为准（大表需估算方案）。
- [ ] **趋势曲线在演示态下是生成的、不是真实历史**：
      现场没有时序库（Prometheus 之类），真实曲线需要后端接入指标存储后提供。
      「消息总线」页已用黄条明确标注。
- [ ] 趋势指标的**口径需与后端对齐**：目前指标定义（label/单位/阈值）写在
      `src/api/mock/monitor.ts` 里当展示元数据，真实接口只返回数值。
      后端落地时应把这三项指标与阈值作为前后端共同契约确认一次。
