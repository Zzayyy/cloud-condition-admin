# 云条件单后台管理系统 · 后端

管理端后台服务的后端实现。**当前完成范围**：

| 模块 | 页面 | 状态 |
|---|---|---|
| 系统管理 | 管理用户与权限 / 角色与权限 / 操作审计 | ✅ |
| 登录鉴权 | 登录 / 登出 / 资料 / 改密 | ✅ |
| 账户与用户 | 用户账号 / 签署与股东号 / 登录日志 | ✅ |
| 业务数据 | 条件单管理 | ✅ 只读（写操作见下） |
| 业务数据 | 委托单管理 | ✅ 只读 |
| 业务数据 | 成交回报管理 | ✅ 只读 |
| 业务数据 | 策略日志管理 | ✅ 只读 |
| 数据存储 | Redis 管理 | ✅ 只读 + 删键（高危，需权限点） |
| 数据存储 | 数据库管理 | ✅ 只读（表清单 / 结构 / 分页读表 / 只读 SQL） |
| 服务器管理 | 委托服务器 | ✅ **只读**（节点清单 + 账号分配关系，**不做启停**） |

| 项 | 版本 |
|---|---|
| JDK | 21 |
| Spring Boot | 3.5.16 |
| MyBatis-Plus | 3.5.16（`mybatis-plus-spring-boot3-starter`） |
| Sa-Token | 1.44.0（`sa-token-spring-boot3-starter`） |
| Lettuce | 6.6.0（读 Redis `acc-*`，版本由 Boot BOM 管理） |
| MySQL | 8.0.45 @ `192.168.1.136:3306` / 库 `qianlong_cloud` |
| Redis | 8.6.2 @ `192.168.1.136:6379`、`192.168.1.137:6379` |

> ⚠️ **两个密码只差大小写和一个符号，极易看错**：
> MySQL 是 `Qianlong@2026`（小写 l，无 `&`），
> Redis 是 `QianLong@2026&`（**大写 L**，**末尾有 `&`**）。
> 实现时第一版就写成了小写 l + `&`，表现为「三个 Redis 实例全部连接失败」，
> 而接口**不报错**，只会让所有账号的 `hasRedis` 变成 false ——
> 很容易被误读成「这些账号在 Redis 里确实没有实时对象」。

---

## 快速开始

```bash
cd cloud-condition-admin/backend

# 1) 建表 + 灌测试数据（只创建 T_Admin* 新表，不动任何已有表）
#    依次执行 sql/01_schema_admin.sql、sql/02_data_admin.sql
mysql -h 192.168.1.136 -u qianlong_cloud -p qianlong_cloud < sql/01_schema_admin.sql
mysql -h 192.168.1.136 -u qianlong_cloud -p qianlong_cloud < sql/02_data_admin.sql

# 2) 启动（默认 8000 端口，上下文路径 /api）
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/cloud-condition-admin-backend-0.1.0.jar
```

启动后前端把 `.env.development` 的 `VITE_USE_MOCK` 改成 `false` 即可直连
（`VITE_API_TARGET` 默认已是 `http://127.0.0.1:8000`）。

> **端口/路径对齐**：前端 `VITE_API_BASE_URL=/api`，Vite 代理把 `/api` 转发到后端。
> 后端用 `server.servlet.context-path=/api`，所以 Controller 里只写 `/system/users`。

---

## SQL 文件

| 文件 | 作用 | 安全性 |
|---|---|---|
| `sql/01_schema_admin.sql` | 建 8 张 `T_Admin*` 表（`CREATE TABLE IF NOT EXISTS`） | 只建新表 |
| `sql/02_data_admin.sql` | 灌测试数据（角色 4 / 权限点 37 / 账号 6 / 审计 220 / 登录日志 180） | 只写 `T_Admin*`，可反复执行 |
| `sql/00_drop_admin.sql` | 回滚（**危险**） | 只删 `T_Admin*` |

> ⚠️ **不动别的表**：`qianlong_cloud` 库里原有的
> `T_Conditional` / `T_Conditional_his` / `V_Conditional` / `T_Order` / `T_Order_his` /
> `T_Trade` / `T_Trade_his` / `T_User` / `T_UserLog` / `T_StrategyLog`
> **一个字节都没有被读写**。这一点由两层证据保证：
> 脚本静态审计（语法层面只可能触及 `T_Admin*`）+ 执行后结构/行数比对。

### 建表前的自检（应返回空集）

```sql
SELECT TABLE_NAME FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='qianlong_cloud' AND TABLE_NAME LIKE 'T_Admin%';
```

### 建表后的核对

```sql
SELECT 'T_AdminRole' AS t, COUNT(*) FROM T_AdminRole
UNION ALL SELECT 'T_AdminRolePermission', COUNT(*) FROM T_AdminRolePermission
UNION ALL SELECT 'T_AdminUser',            COUNT(*) FROM T_AdminUser
UNION ALL SELECT 'T_AdminUserRole',        COUNT(*) FROM T_AdminUserRole
UNION ALL SELECT 'T_AdminPasswordPolicy',  COUNT(*) FROM T_AdminPasswordPolicy
UNION ALL SELECT 'T_AdminPasswordHistory', COUNT(*) FROM T_AdminPasswordHistory
UNION ALL SELECT 'T_AdminLoginLog',        COUNT(*) FROM T_AdminLoginLog
UNION ALL SELECT 'T_AdminAudit',           COUNT(*) FROM T_AdminAudit;
```

### 测试账号（明文仅用于联调，生产必须改掉）

| 账号 | 密码 | 角色 | 用来验证 |
|---|---|---|---|
| `admin` | `Admin@2026` | 超级管理员（`*`） | 全部权限 |
| `yangsh` | `Ops@2026` | 运维（19 个权限点） | 能启停；建账号按钮应置灰且**后端也会拒** |
| `zhangguochang` | `View@2026` | 只读观察者（14 个） | 写操作全点不动；首次登录需改密 |
| `audit01` | `Ops@2026` | 审计员（3 个） | 只能看审计、能导出 |
| `liuyi` | `Ops@2026` | — | **故意登不进去**：账号已停用（40102） |
| `test_tmp` | `Ops@2026` | — | **故意登不进去**：连续失败 5 次已锁定（40103） |

口令与前端 `src/api/mock/system.ts` 的演示口令刻意保持一致，便于对照排查。
库里只存 BCrypt 哈希（cost=10），**接口永不回显**。

### 密码策略（2026-10-12 已放宽）

| 项 | 取值 | 说明 |
|---|---|---|
| 最小长度 | **6** | 唯一保留的复杂度要求 |
| 大写 / 小写 / 数字 / 符号 | **均不要求** | 按需求方要求放宽，`123456`、`abcdef` 都能用 |
| 不得与登录名相同 | 固定生效 | 代码里的规则，不占策略表字段 |
| 连续失败锁定 | **保留**：5 次 / 30 分钟 | 防暴力破解，与密码复杂度无关 |
| 历史密码不重复 | **保留**：最近 5 次 | 同上 |

> ⚠️ 策略值**三处必须一致**，否则会出现「表里看着宽松、实际按严格校验」这类很难查的偏差：
> `01_schema_admin.sql` 的列 `DEFAULT`、`02_data_admin.sql` 的 `INSERT`、
> 以及 `PasswordPolicyService.defaultPolicy()`（表缺行时的兜底）。
> 前端演示态的口径在 `src/api/mock/system.ts` 的 `POLICY`，也要同步。

> 放宽复杂度**不等于**关掉防暴力破解。锁定与历史密码是独立维度，
> 已在 `e2e_test.py` 里分别断言，避免日后有人「顺手」把它们也一起放宽。

账号锁定后有两个解法：管理员点「解锁」，或者等 30 分钟自动解锁
（`lockTime` 到期后后端会在下次登录时自动放行）。

---

## 表设计

| 表 | 说明 |
|---|---|
| `T_AdminUser` | 后台管理账号。**与云单业务用户 `T_User` 无关** —— 那是客户的钱包账号 |
| `T_AdminRole` | 角色。`builtin=1` 不可删、code 与名称不可改 |
| `T_AdminRolePermission` | 角色持有的权限键 |
| `T_AdminUserRole` | 账号-角色多对多 |
| `T_AdminPasswordPolicy` | 密码策略 + 登录失败锁定策略（单行，`id=1`） |
| `T_AdminPasswordHistory` | 历史密码哈希（支撑 `historySize`） |
| `T_AdminLoginLog` | 管理端登录日志。**不是** `T_UserLog` |
| `T_AdminAudit` | 管理端操作审计（高危操作必写） |

列名统一小驼峰，与前端 `src/api/domain.ts` 的字段名一致，少一层容易写错的映射。

> ⚠️ **`mybatis-plus.global-config.db-config.table-underline: false` 必须保持关闭。**
> MyBatis-Plus 默认把驼峰字段名转下划线列名，而 `lastLoginIP` 会变成
> `last_login_i_p`（连续大写被逐字符拆开），生成一个不存在的列。
> 关掉后「字段名即列名」，与 DDL 严格一致。
> 同时 `map-underscore-to-camel-case: false` 也要关，否则查询结果映射不上。

### 账户与用户模块用到的**既有表**（只读，本项目不动其结构）

| 表 | 用途 | 现场实况 |
|---|---|---|
| `T_User` | 云单业务用户（客户账号） | **只有 1 行**（AUTO_INCREMENT 已到 160707，历史上写过多行） |
| `T_UserLog` | 客户登录/操作日志 | **191,009 行**，且**只有主键索引** |

> ⚠️ **这两张表一个字都不能改**（不加列、不加索引、不改注释）。
> 因此 `T_UserLog` 的查询不能靠加索引优化，只能靠查询策略：
> 默认走主键倒序分页（~1ms）；`Account LIKE` ~106ms、`GROUP BY Account` ~90ms
> 都在可接受范围（实测 19.1 万行）。若涨到千万级需与现场协商。

> **`T_UserLog` 有两个必须如实呈现、不要「修好」的现场事实**：
> 1. `LogIP` / `LogMAC` / `LogOS` **三列全部为 NULL**（191009 行无例外）。
>    所以页面「只看有设备信息的」筛选**必然查出 0 条** —— 那是真的没有数据，
>    不是查询写错了。后端如实返回 0，前端也已写明。
> 2. `Info` 实际只有 `user registed` / `user insert or updated` 两类取值，
>    不承载「失败原因」语义。

### Redis `acc-*`（账户与用户页的实时态）

键名形如 `acc-<账号>_<账号类型>_<渠道>`，类型是 **ReJSON-RL**，
所以必须用 `JSON.GET` 读（用 `GET` 会报 WRONGTYPE）。字段名照现场：
`useraccount` / `accounttype` / `maintype` / `onlineflag` / `syncflag` /
`strategyserverid` / `traderserver` / `regprotocal` / `shareHolders`。

**现场是「多实例 + 多 db」，acc-* 分散在其中**（实测 2026-10-12）：

| 实例 | acc-* 数量 |
|---|---|
| `192.168.1.136` db0 | 1 |
| `192.168.1.136` db1 | 12 |
| `192.168.1.137` db1 | 1 |

所以配置写成**列表**（`app.redis.instances`），而不是用
`spring.data.redis.*`（那只能表达一个连接）。
少读一个库的表现是「某些账号 `hasRedis=false`」，那不是「没有」而是「没查到」，
两者在运维判断上完全不同 —— 所以另外提供 `/account/redis-instances`
让人能确认「是账号真的没有实时对象」还是「我们没连上 Redis」。

> **SCAN 必须循环到游标结束**：`SCAN` 是增量游标，一次调用只返回一批，
> 且不保证返回全部。只调一次会漏键，而这种缺失很难察觉
> （页面只是少了一个实时态标记）。代码里做了循环 + 轮数上限兜底。
> 用 `SCAN` 而非 `KEYS`：现场 137 db0 有 400 万条流，`KEYS` 会把实例拖垮。

### Redis 配置中与委托服务器相关的一段（`app.trade.*`）

```yaml
app:
  redis:
    instances: [...]          # 业务要读的 (host, db) 组合（与上面同一份）
  trade:
    offline-after-sec: 30     # 心跳阈值。⚠️ 必须与前端 SERVER_OFFLINE_AFTER_SEC 一致
    registry-key: traderserver
    registry-instances: []    # 留空 = 读上面 instances 的全部；将来加机器不用改这里
    channels:
      # ⚠️ 现场实测带 _1 后缀（REDISSELECT=1），不带后缀的那一套并不存在
      online: tradeserver_online
      beat: tradeserver_beat
      offline: tradeserver_offline
      suffix: '_1'
```

> **`registry-instances` 留空是有意的**：将来现场加一台委托服务器，
> 只要它的 `(host, db)` 在 `app.redis.instances` 里，本模块自动能读到，
> 不需要回来改代码或改这一段配置。
>
> **接口把阈值回传在 `meta.offlineAfterSec`，页面会拿它与前端常量比对**，
> 不一致时直接在页面上告警 —— 两侧的「在线」判定必须同口径，
> 否则会出现「页面显示 1 台在线、汇总卡说 0 台」这种没法解释的偏差。

> **没有常驻 Pub/Sub 订阅**：在线状态由 `lastActiveTimeLong` 推导即可算准，
> 而订阅要在后台长期占一条连接。`channels` 目前仅用于展示与排查。
> 将来若要做「实时上下线告警」，再在这里加开关。

### 条件单模块用到的**既有表**（只读，本项目不动其结构）

| 对象 | 用途 | 现场实况 |
|---|---|---|
| `T_Conditional` | 运行中的条件单 | **0 行** |
| `T_Conditional_his` | 历史条件单 | **0 行** |
| `V_Conditional` | 前两张表的并集视图 | **0 行** |

> ### 条件单模块**只读这三张表**，表为空就显示为空
>
> 现场这三张表都是 **0 行**（2026-10-14 实测），所以条件单管理页现在是空的。
> **这是刻意的、也是正确的呈现。**
>
> 开发过程中曾把 Redis `strategy-*` 里的 60 多条条件单合并进来展示，
> **已全部移除**。原因：
>
> 1. **它让页面读数失去意义。** 菜单与页面标题写的是「条件单管理 / `T_Conditional`」，
>    用户看到 58 条会认为「这张表里有 58 条」。而事实是一条都没有 ——
>    运维据此判断「库里数据正常」，就永远不会去查为什么没落库。
> 2. **Redis 侧根本说不清是哪一份。** 三个实例/db 都有 `strategy-*`
>    （136 db0 有 3 条、136 db1 有 55 条、137 db1 有 4 条），
>    还有 4 条同一个 `id` 在两个实例上各存一份；合并时「取第一个」
>    由配置顺序决定，页面无法表达这件事，用户看到的数字既不是
>    某个实例的真实条数，也不是可靠的去重结果。
>
> **空表本身就是一个需要现场关注的信号**（见下方待确认事项）。
> 用别处的数据把空表填满，只会把这个信号掩盖掉。
>
> Redis 的 `strategy-*` 归「服务器管理」类页面去读更合适 ——
> 那里本来就在展示 Redis 的实时对象与流，不该混进
> 「业务数据 / 对应一张 MySQL 表」的查询页。

> ### ❓ 待现场确认（沿用前端 README 的遗留问题）
>
> MySQL 三张表均为空，而 Redis `strategy-*` 里有 60 多条真实条件单、
> 字段与表结构一一对应。需要确认：**这是设计如此（以 Redis 为准），
> 还是数据没落库？**
>
> 若是后者，说明策略平台写 Redis 之后没有回写 MySQL ——
> 那是个需要修的数据链路问题，条件单页会一直是空的。

> **Redis `strategy-*` 的命名注意**（若将来确实要读它）：
> `strategy-srv` 是**策略服务器节点清单**（ReJSON 数组），
> 前缀与条件单键相同但完全是另一种东西，必须排除。

#### 条件单接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/strategy/conditionals` | 列表（`source` 三源切换 + `type`/`mode`/`keyword` 筛选 + 分页） |
| GET | `/strategy/conditionals/{id}` | 详情（依次在运行中 / 历史 / 并集视图里找） |
| GET | `/strategy/conditionals/stats/daily` | 按天新增趋势（按 `AddTime` 分组） |
| GET | `/strategy/conditionals/stats/types` | 类型分布（全量口径，只统计主表+历史表，避免视图重复计数） |
| GET | `/strategy/conditionals/instructions` | **写操作现状说明**（见下） |

`source` 与真实对象的对应：

| `source` | 表/视图 |
|---|---|
| `running` | `T_Conditional` |
| `history` | `T_Conditional_his` |
| `all` | `V_Conditional` |

> ⚠️ **表名只能用 `${}` 拼接**（三张表列结构相同、只有表名不同，
> 而 MyBatis 的 `#{}` 只能出现在值的位置），所以这是注入面。
> 两层防护：调用方只能传 `ConditionalSource.table()`（枚举写死的三个常量），
> SQL 里再白名单校验一次，不匹配就落到 `WHERE 1=0`。

#### 为什么条件单**没有**启停 / 删除接口

前端的 `setConditional` / `removeConditional` 至今仍是
「提示后端接口尚未实现」—— 而这两个操作**不该做成简单的更新接口**：

它们要走 `set` / `remove` 协议**下发到策略平台**
（经 Redis Stream 到数据中心，再由数据中心转给策略服务器），
策略平台执行后才回执。**直接改 MySQL 不会让运行中的条件单真的启停**，
只会造成「库里显示已停止、实际还在跑」这种最危险的不一致。

所以本模块只提供读接口，并专门留了 `/instructions` 说明现状与原因
（做成接口而不是只写文档，是为了让「为什么这个按钮点了没用」在联调时一眼可见）。
将来补实现时应使用 `action:conditional:write` 权限点
（该权限点已在 `T_AdminRolePermission` 里存在且标记为 high 危险级）。

---

## 接口清单

上下文路径 `/api`，全部与前端 `src/api/endpoints.ts` 逐一对齐。

### 业务数据：委托单 / 成交 `tradeApi`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/trade/orders` | 委托单列表（`table` 主表/历史表 + `bsType`/`ocType`/`code`/`keyword` 筛选） |
| GET | `/trade/orders/{id}` | 委托单详情（先主表后历史表） |
| GET | `/trade/orders/{orderNo}/trades` | **某委托号**下的成交明细（委托 → 成交下钻） |
| GET | `/trade/orders/stats/bs` | 买卖方向分布（全量口径） |
| GET | `/trade/orders/stats/daily` | 按天下单量趋势 |
| GET | `/trade/trades` | 成交列表（成交回报页用） |

数据**只来自 MySQL** 的四张表：

| 表 | 用途 | 现场实况 |
|---|---|---|
| `T_Order` | 委托主表 | **0 行** |
| `T_Order_his` | 委托历史表 | **1 行**（测试数据，`OrderNo=1234567`） |
| `T_Trade` | 成交主表 | **0 行** |
| `T_Trade_his` | 成交历史表 | **1 行**（同一笔 `OrderNo=1234567`） |

所以「主表」标签页是空的、「历史表」有 1 条 —— **如实呈现，不拿别处的数据填充**
（条件单那边曾把 Redis 数据混进来，导致页面读数失去意义，见下节）。

> **路由注意**：`/trade/orders/stats/bs` 与 `/trade/orders/{id}` 段数相同，
> 但 Spring 的路径匹配**优先精确匹配**，`stats` 不会被当成 id 解析。
> 已实测确认（测试里专门有一条守着它）。

> **`table` 参数来自用户可点的 tab**（前端
> `table: tab === 'active' ? 'T_Order' : 'T_Order_his'`），所以它是不可信输入。
> 后端用 `TradeTable` 枚举严格白名单（非法值返回 40000，不静默回落），
> SQL 里再用 `<choose>` 二次校验 —— 与条件单同样的两层防护。

#### ⚠️ 两个列类型带来的口径问题（都已按实际类型处理）

1. **`OrderTime` / `TradeTime` 是 `date` 而不是 `datetime`**
   （现场 DDL 实测），所以**没有时分秒**。接口返回 `yyyy-MM-dd`，
   **不补 `00:00:00`** —— 补零会让页面显示「2026-06-04 00:00」，
   让人误以为真有那个精确时刻。
2. **`T_Trade.Volumn` 是 `varchar` 而不是数字**（现场值是 `'5'`），
   接口按字符串返回，**不擅自转数字** —— 前端契约里也是 `string | null`。

#### ⚠️ `OrderStatue` 的含义未知（沿用 README 的待确认项）

协议文档没定义取值，现场 `T_Order_his` 只有 1 条测试数据（值为 1）。
前端只展示 `StatueDis` 文本与原值、不做状态着色；
**后端同样不臆造映射** —— 猜一个映射出来会让别人以为那是确认过的口径。

### 服务器管理：委托服务器 `tradeApi`（**只读**）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/trade/servers` | 节点清单（跨实例全读 + 关键字/在线筛选 + 分页/排序） | 登录即可 |
| GET | `/trade/assignments` | 账号 → 委托服务器 的分配关系（`acc-*` 的 `traderserver` 字段） | 登录即可 |
| GET | `/trade/servers/{serverId}/accounts` | 某台节点承载的账号（详情抽屉下钻） | 登录即可 |

数据只来自 Redis，**两张表一个字都不写**。

#### 现场实况（2026-10-14 实测，全部只读探查）

注册表键 `traderserver` 在**三个库上各存一份，且内容完全不同**：

| 实例 | 条数 | 状况 |
|---|---|---|
| `192.168.1.136` db0 | 2 | 22 小时前（我们自己 `strategy_test` 插件的测试残留，`mac=…cloudcond-api` / `…Test`） |
| `192.168.1.136` db1 | 7 | **只有 `id=1 / ip=192.168.1.137 / mac=20474789c674Test` 是活的**（每 10 秒刷新），其余是 8/12、9/14 的僵尸记录 |
| `192.168.1.137` db1 | 4 | 全部 63 天前 |
| `192.168.1.137` db0 | 0 | 无此键（该库只有 1412 万条的 `ST-*` 流，**不要去扫**） |

> **委托服务器确实在跑**：`192.168.1.137` 上有 `./qltradeserverasy133`（属主 `qianlong`，
> `PID 3656140 → 3657013`，监听 8185 与 1234）；`192.168.1.136` 上也有一个同名进程。
> 实测订阅 `tradeserver_beat_1` 抓到实时心跳包
> `{"id":1,"unique_string":"WT-570-20474789c674Test-1","ip":"192.168.1.137","mac":"20474789c674Test"}`，
> 约 10 秒一次；`136 db1` 的 `WT-1-reply` 有 20140 条、最后一条是当天 14:35。
> 所以「现场起没起委托服务器」这件事，本页现在能直接回答。

#### ⚠️ 四个必须如实呈现、不要「修好」的现场特征

1. **`id` 会重复**：`136 db1` 的 7 条里有 **4 个 `id=0`**。
   所以行的唯一键是 **`(来源实例, 数组下标)`**，接口为此回传 `instance` 与 `arrayIndex`；
   前端表格的 `row-key` 也换成这一对 —— 否则会出现「点详情打开的是另一行」。
2. **`mac` 也会重复**：`id=1/mac=…Test`（活）与 `id=0/mac=…Test`（死）同时存在。
3. **`enable` 不能用来判在线**：`136 db0` 的两条与 `137 db1` 的四条都是
   `enable=true`，但最后活跃分别是 22 小时前与 63 天前。**在线只看 `lastActiveTimeLong`**
   （阈值 `app.trade.offline-after-sec`，现场 13 条里恰好只有 1 条在线）；
   `enable=false` 则一定不算在线。
4. **两处 `traderserver` 的字段名不一样**：注册表里是 `last-active-time`（带横线），
   而 `acc-*` 里内嵌的是 `lastactivetime`（**全小写、无横线**）。
   这是现场两处结构本身就不一致，两边都照原样读，**不做「统一」**。
   且 `acc-*` 里那个时间是**分配时刻**、不是节点心跳（现场普遍是几天到两个月前），
   所以**不能**拿它给分配列着色成「在线/离线」，否则会出现
   「节点表说这台在线、分配列说它离线」的自相矛盾。

> **为什么后端不按 `id` 去重**：现场这 4 条 `id=0` 是真实存在的记录
> （多次上线累积）。去重会让「这台机器上线过好几次、其中几条是陈旧残留」
> 这件事从页面上消失，而那恰是运维要看的。所以全部列出，由页面呈现重复。

#### ⚠️ 为什么**没有**启停接口（需求方已明确去掉该功能）

改 `enable` 需要先确定「改哪一台」，而现场可用的定位条件都不唯一
（`id` 重复、`mac` 也重复），后端又拿不到「这台机器是否属于本次运维范围」这一信息。
猜着写回很容易改到一台并不属于本次范围的机器上，而这是在往**生产 Redis** 写。
所以本模块只做只读呈现，启停由现场按自己的流程操作。

配套地，`action:server-trade:toggle` 这个权限点已从「可授予的权限」里撤掉，
但**保留在审计动作字典**里（`PermissionCatalog.AUDIT_ONLY_ACTIONS`）——
库里的历史审计行仍有 `Action='action:server-trade:toggle'`，
整个删掉会让那些旧记录在审计页显示成光秃秃的键名。

#### 节点列表的默认排序

**在线优先 + 心跳倒序**。不这么排的话，进页面第一眼全是几个月前的残留，
唯一活着的那台被挤到第 8 位之后 —— 而「现在到底有没有活的委托服务器」
正是这个页面要回答的第一个问题。用户显式点列排序时以用户选择为准。

#### 图表的全量口径

「各节点承载账号数」图必须按**全量**分配关系统计，不能只用当前页
（那会得出「第 1 页的分布」）。为此页面另外拉一次 `pageSize=200` 的全量请求，
超过 200 时在标题旁如实标注「已截断」。现场 `acc-*` 只有 14 个，远不到上限。

> ⚠️ 修掉了一个原有缺陷：这张图原先**无条件**调 `mockServerLoad()`，
> 于是真实态下也在用演示数据画图、而且不报错 —— 与 README 前面记的
> 「Redis 管理页类型分布图无条件调 mock」是同一类坑。

### 业务数据：策略日志 `strategyApi.listLogs`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/strategy/logs` | 列表（`keyword` / `status` / `fromIp` / 时间范围） |
| GET | `/strategy/logs/statuses` | **`Status` 列的真实去重取值**（见下） |
| GET | `/strategy/logs/stats/daily` | 按天日志量趋势 |
| GET | `/strategy/logs/stats/by-strategy` | 按条件单聚合（「按策略单查看生命周期」） |
| GET | `/strategy/logs/{strategy}/timeline` | 某条件单的完整轨迹（按时间升序） |

表：`T_StrategyLog`，**17430 行真实数据**（现场四张业务表里数据最多的）。

#### ⚠️ 三条现场事实（接口如实返回，不粉饰）

| 列 | 现场实况 | 后果 |
|---|---|---|
| `Status` | **17430 行全部 NULL** | 前端的状态下拉取自 `mock/strategy.ts` 的 `LOG_STATUS`，那 **7 个值是编的**，现场一个都不存在 |
| `FromIP` / `FromMac` | **全部 NULL** | 「按来源 IP 筛」必然 0 条 —— 不是查询写错了 |
| `OccurTIme` | `date` 类型（无时分秒），且存过**非法空串** | ⚠️ SQL 里**不能**写 `<> ''`，会报 **MySQL 1525** 让整条查询失败 |

> **关于 `Status`：为什么专门加 `/strategy/logs/statuses` 接口**
>
> 前端页面原本写的是
> `const statusOptions = mockStrategyApi.logStatusOptions.map(...)` ——
> **真实态也从 mock 取选项**。那样界面会变成「下拉里有 7 个选项，
> 但选任何一个都查出 0 条」，排查的人会去怀疑分页、SQL、权限，
> 而真正的原因是这一列根本没有数据。
>
> 现已改为：真实态调 `/strategy/logs/statuses` 拿真实去重值
> （当前是**空数组** → 下拉置灰并提示「状态列现场为空」），
> 只有演示态才回落 mock。`meta.statusColumnEmpty` / `meta.deviceColumnsEmpty`
> 也一并返回，供页面直接说明。
>
> 这两个 meta 字段是**真的去查一次**得出的，不是硬编码 `true` ——
> 现场补了数据之后页面会自动恢复正常显示，不需要改代码。

> **关于 `OccurTIme` 的 1525 报错**：这是实测踩到的 ——
> 探测脚本里写了 `WHERE OccurTIme <> ''`，直接抛
> `(1525, "Incorrect DATE value: ''")`，**整条 SQL 失败**而不是「筛掉几行」。
> 本模块对日期列只用 `IS NULL` 与范围比较。
>
> **性能**：表上只有主键索引且不能加（不改既有表结构）。
> 实测 17430 行下：主键倒序分页 ~1ms、按 `Strategy` 过滤 ~7ms、
> 按天聚合 ~6ms、`Info LIKE` ~1ms，都可接受。涨到百万级需与现场协商加索引。

### 鉴权 `authApi`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/system/auth/login` | 登录。失败按 code 分派（40101/40102/40103） |
| POST | `/system/auth/logout` | 登出（只注销当前 token） |
| GET | `/system/auth/profile` | 当前登录人资料 + 权限键集合 |
| POST | `/system/auth/password` | 修改自己的密码 |

### 管理用户 `adminApi`

| 方法 | 路径 | 权限点 |
|---|---|---|
| GET | `/system/users` | 登录即可（读） |
| GET | `/system/users/{id}` | 登录即可（读） |
| POST | `/system/users` | `action:system-user:manage` |
| PUT | `/system/users/{id}` | `action:system-user:manage` |
| POST | `/system/users/{id}/enabled` | `action:system-user:manage` |
| POST | `/system/users/{id}/unlock` | `action:system-user:manage` |
| POST | `/system/users/{id}/password` | `action:system-user:manage` |
| DELETE | `/system/users/{id}` | `action:system-user:manage` |

### 角色与权限

| 方法 | 路径 | 权限点 |
|---|---|---|
| GET | `/system/roles` | 登录即可（读） |
| GET | `/system/roles/{id}` | 登录即可（读） |
| GET | `/system/roles/permission-keys` | 登录即可（读，供排查键是否同步） |
| POST | `/system/roles` | `action:system-role:manage` |
| PUT | `/system/roles/{id}` | `action:system-role:manage` |
| DELETE | `/system/roles/{id}` | `action:system-role:manage` |

> 读接口刻意不要求 `manage`：只读观察者也要能拿到角色名，
> 否则账号列表的「角色」列只能显示「角色 3」这种没有信息量的内容。

### 审计与登录日志

| 方法 | 路径 | 权限点 |
|---|---|---|
| GET | `/system/audit-logs` | 登录即可 |
| GET | `/system/audit-logs/actions` | 登录即可 |
| GET | `/system/audit-logs/daily` | 登录即可 |
| GET | `/system/audit-logs/by-action` | 登录即可 |
| GET | `/system/login-logs` | 登录即可 |
| GET | `/system/login-logs/daily` | 登录即可 |
| GET | `/system/login-logs/fail-by-ip` | 登录即可 |
| POST | `/system/audit-logs/export` | `action:system-audit:export` |
| GET | `/system/audit-logs/export/download` | `action:system-audit:export` |

### 密码策略

| 方法 | 路径 | 权限点 |
|---|---|---|
| GET | `/system/password-policy` | 登录即可（页面要用它渲染提示） |
| PUT | `/system/password-policy` | `action:system-user:manage` |

### 账户与用户 `accountApi`

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/account/accounts` | 用户账号列表（MySQL `T_User` + Redis `acc-*` **合并后**返回） | 登录即可 |
| GET | `/account/accounts/{account}` | 单账号详情（两侧合并） | 登录即可 |
| GET | `/account/redis/{account}` | 该账号在 Redis 的实时态原始 JSON | 登录即可 |
| GET | `/account/redis-instances` | Redis 实例连接情况（**排查用**，非前端契约） | 登录即可 |
| GET | `/account/logs` | 登录日志明细（`T_UserLog`） | 登录即可 |
| GET | `/account/logs/stats` | 登录日志按账号聚合（登录频次） | 登录即可 |

**本模块只读，因此没有独立的操作权限点。** 权限点只列真正危险的动作 ——
避免管理员图省事全勾上而失去意义。将来若加「重置账号密码」这类写操作，
再引入独立权限点并写审计。

三个页面与接口的对应关系：
* **用户账号** → `listAccounts`（合并行）+ `getRedisAccount`（实时态抽屉）
* **签署与股东号** → `listAccounts`（复用，靠 `signed` 筛选；股东号已在行里）
* **登录日志** → `listLogs` + `listLogStats`

#### 合并口径（三条，都会影响页面读数）

1. **以两侧账号的并集为准**：只在 MySQL 有（Redis 对象已过期）、
   只在 Redis 有（库里还没落数据）**都要展示** —— 这两种差异本身就是要运维关注的信息。
2. **两边都有的字段以 Redis 为准**：Redis 是实时态，
   MySQL 那行可能很久没更新（现场 `T_User` 的 `LoginTime` 停在 2026-09-15，
   而 Redis 里那些账号是活的）。
3. **`hasRedis` 明确标出**「这个账号在 Redis 侧没有对象」，
   而不是把 Redis 字段静默填 null —— 否则「没有实时态」和
   「实时态是空值」在页面上长得一模一样，运维没法区分。

**为什么合并必须放后端**：两个源都要参与筛选（在线 / 已签署 / 渠道横跨两边），
拆成两个接口会让前端无法正确分页与筛选（要么分页各分一遍，
要么取回全量在浏览器里筛）。

> ⚠️ 本模块**不返回 `T_User.Passwd`**：出参 DTO 里根本没有这个字段
> （与 `T_AdminUser.passwordHash` 同样处理，用类型杜绝回显）。

#### 关于「全量读 `T_User`」

`listAccounts` 会 `SELECT * FROM T_User`（无分页）—— 因为合并是并集语义，
必须先拿到两侧全量才能算并集、再筛选、再分页；先分页再合并会出现
「第 2 页混进第 1 页的账号」这种无法解释的结果。

现场这张表**只有 1 行**，全量读没有代价。⚠️ 但它的
AUTO_INCREMENT 已到 160707，说明历史上写过多行；
**若将来真涨到几十万行，必须改成下推式**：先在 MySQL 按条件取候选、
Redis 侧按前缀筛，再归并，不能继续全量读到内存里。
`AccountService` 里已就地留了说明。

### 数据存储：MySQL 管理 `storageApi`

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/storage/mysql/tables` | 表/视图清单（含注释与行数） | 登录即可 |
| GET | `/storage/mysql/schema` | 单表结构（字段 / 索引 / DDL 原文） | 登录即可 |
| GET | `/storage/mysql/query` | **分页读一张表的数据** | `action:storage-mysql:readonly-sql` |
| POST | `/storage/mysql/query` | **执行一条只读 SQL** | `action:storage-mysql:readonly-sql` |

GET 与 POST 同路径，靠 HTTP 方法区分（前端就是这么定义的）。

> ## ⚠️ 这是全项目最高危的模块
>
> 现场的应用数据库账号是：
> ```
> GRANT ALL PRIVILEGES ON `qianlong_cloud`.* TO `qianlong_cloud`@`%`
> ```
> **一条没被拦住的 SQL 就能删表。**
>
> 而实测发现**数据库自己的只读事务挡不住 DDL**：
> ```
> START TRANSACTION READ ONLY;
>   INSERT / UPDATE / DELETE  -> errno 1792 被挡 ✅
>   CREATE TABLE              -> 成功了 ❌
>   DROP TABLE                -> 成功了 ❌
> ```
> 原因：DDL 会**隐式提交**，从而结束只读事务，之后就在普通会话里执行了。
> （实测在临时库对象上验证，已确认无残留：库内对象数始终为 18。）
>
> ### 所以用三层防护，且第 1 层是主要防线
>
> | 层 | 做什么 | 能挡住什么 |
> |---|---|---|
> | 1. **应用层白名单**（`ReadOnlySqlGuard`） | 只放行 `SELECT / WITH / SHOW / DESC / DESCRIBE / EXPLAIN`；拒绝多语句、可执行注释、危险关键字 | **主要防线** |
> | 2. 数据库只读事务 | `START TRANSACTION READ ONLY` | DML（挡不住 DDL） |
> | 3. 结果集上限 + 查询超时 | `maxRows=200`、`timeout=15s` | 拉全表 / `SLEEP` 拖死连接 |
>
> **黑名单覆盖**：DML/DDL、权限（`GRANT`/`REVOKE`）、文件（`INTO OUTFILE`/`LOAD_FILE`）、
> 锁（`FOR UPDATE`/`LOCK`）、会话与事务（`SET`/`CALL`/`PREPARE`/`COMMIT`）、
> 管理（`FLUSH`/`KILL`/`SHUTDOWN`）、拖慢实例（`BENCHMARK`/`SLEEP`）。
>
> ### 两个必须记住的实现坑（都真实踩到）
>
> **坑 1：剥掉字面量做校验，但执行必须用原文。**
> 为避免 `SELECT 'DROP TABLE x'` 误报，校验前会剥掉字符串字面量。
> 但**执行**必须用用户写的原文 —— 第一版返回了「剥掉字面量的版本」，后果是：
> ```
> SELECT 'HELLO' AS note                  -> 返回空字符串
> SELECT * FROM T_User WHERE Name='张国昌' -> 一行都查不到
> ```
> **所有字符串字面量被静默清空，且不报错。** 已改为「校验用剥过的文本、执行用原文」。
>
> **坑 2：可执行注释必须直接拒绝。**
> MySQL 的 `/*! ... */` 里的内容会被**真的执行**，而它长得像注释。
> 「剥注释校验 + 执行原文」会被它绕过（校验看到 `SELECT 1`，执行时跑了别的东西）。
> 所以含 `/*!` 的 SQL **直接拒绝**，而不是剥掉后执行。
>
> ### `SHOW CREATE TABLE` 要放行（它含 CREATE）
> 它是纯只读语句、也是 DBA 最常用的看结构方式。做法是**精确放行这一种形状**
> （`SHOW CREATE <TABLE|VIEW|DATABASE|...>`），而不是把 `CREATE` 从黑名单拿掉。
>
> ### 视图的 DDL 要取第 2 列
> `SHOW CREATE TABLE` 返回 2 列，而 `SHOW CREATE VIEW` 返回 **4 列**
> （视图名, DDL, character_set_client, collation_connection）。
> 取「最后一列」在视图上会拿到字符集 —— 页面显示 `utf8mb4`（已修：固定取第 2 列）。
>
> ### `binary` 列要可读化
> 现场 `T_User.RegProtocol` 是 `binary(1)`，值是 `'1'`。
> 不处理的话 Jackson 会序列化成 Base64，页面显示乱码；现已按可打印 ASCII 输出。

> ### 表清单的两个行数字段（`approxRows` vs `exactRows`）
> `information_schema.TABLES.TABLE_ROWS` **不准** ——
> 现场实测 `T_StrategyLog` 报 16566、实际 17430。
> 所以另给 `exactRows`（真实 `COUNT(*)`），且**只对小表算**
> （超过 10 万行不 COUNT，避免明显代价）。
> 两个字段并存是刻意的：页面要能看出「近似值不可信」。

> ### 库名白名单
> 只允许**当前连接的库**（`qianlong_cloud`）。实测该账号虽然读不到 `mysql` 库
> （errno 1142 无权限），但 `information_schema` / `performance_schema` 是可见的 ——
> 放开库名等于把这两个也暴露出去，没有必要。
> 请求别的库会**如实报错**（不静默改成当前库，那会让人以为查的是他指定的库）。

> ### 只读 SQL 与分页读表都要权限点 + 写审计
> 「能看页面」与「能跑任意 SELECT」是两件事：
> `ops` 角色有 `action:storage-mysql:readonly-sql`、`viewer` 没有。
> 每次执行**成功与失败都写审计**（谁、什么 SQL、返回多少行、耗时）。
> SQL 文本会记进审计，但**不记结果内容** —— 审计是「谁做了什么」，不是数据副本。
>
> ⚠️ 本模块**没有任何写接口**，也不提供「结果导出」——
> 导出会把整表数据带离现场，需要单独的权限设计与审计，不在本次范围。

> ### 💡 建议（需要现场 DBA 配合）
> 最彻底的做法是**给只读页面单独建一个只有 `SELECT` 权限的数据库账号**
> （甚至只授 `qianlong_cloud.*` 的 SELECT）。
> 本次**没有擅自改数据库账号** —— 那是现场要知情并决定的变更。
> 在换号之前，第 1 层白名单是不可放松的。

### 数据存储：Redis 管理 `storageApi`

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/storage/redis/instances` | 实例清单（按**主机**去重） | 登录即可 |
| GET | `/storage/redis/info` | 实例概览（INFO + 各 db 键数） | 登录即可 |
| GET | `/storage/redis/keys` | 扫描键（分页 + 关键字/类型/TTL 过滤） | 登录即可 |
| GET | `/storage/redis/key` | 单个键的内容（按类型返回不同结构） | 登录即可 |
| GET | `/storage/redis/channels` | Pub/Sub 频道（含订阅者数） | 登录即可 |
| DELETE | `/storage/redis/key` | **删除键（不可撤销）** | `action:storage-redis:delete-key` |

> **参数名注意**：接口参数是 `instanceId`，但**它的值就是主机地址**
> （如 `192.168.1.136`）—— 参数名保持与前端 `endpoints.ts` 一致，
> 语义却是「一台主机」。这是刻意的：管理页要浏览整台实例的所有 db，
> 而业务侧的 `app.redis.instances` 是「业务要读的 (host,db) 组合」，两者用途不同。
> 所以实例清单返回的是**去重后的主机**（136 只出现一次），
> 不是 136-db0 / 136-db1 各一条。

> ### ⚠️ 键数 ≠ 条目数（本页最容易误读的地方）
>
> 现场实测：
> ```
> 137 db0  DBSIZE = 117                        ← 只有 117 个键
> 但 DataHub_req_stream 的 XLEN = 14,129,288   ← 单个 stream 1412 万条条目
> ```
> 前端 README 里记的「457 万条」指的是**条目数**，不是键数。
> 所以接口把它们做成**不同字段**：`dbKeys[]` 是键数（DBSIZE 口径），
> 每个键的 `length` 才是条目数。页面上也分开显示 ——
> 混着看会得出完全错误的容量判断。
>
> 另外注意 `DataHub_req_stream` 已从 README 记的 407 万涨到 **1412 万**。

> ### ⚠️ 三条安全线
>
> 1. **只用 SCAN，绝不用 KEYS。** 现场 137 db0 有 1412 万条条目的 stream，
>    `KEYS *` 会把实例阻塞住、影响正在跑的生产流。
> 2. **服务端强制条数上限**（`SCAN_HARD_LIMIT = 2000`），接口里**没有**
>    「取全部」的选项。被截断时如实返回 `truncated=true` + `scanLimit`，
>    **不静默截断** —— 否则会被当成「这个 db 就这么多键」。
> 3. **删除键是高危**：单独要求权限点，成功与失败**都写审计**
>    （失败同样要留痕：有人试过删那个键）。返回值是**实际删掉的键数**，
>    键不存在就是 0，**不假装成功**。

> **连接按实例复用，请求内 SELECT 切换 db，切走必须切回**（`try/finally`）。
> 不切回去的 bug 表现为「随机查到别的 db 的键」，极难定位。

> **INFO 的 keyspace 段要精确匹配 `db\d+`**：新版本 Redis 还有
> `db0_distrib_strings_sizes` 这类**看起来也以 db 开头**的段，
> 直接 `startsWith("db")` 会在 `substring(2)` 时抛
> `NumberFormatException("0_distrib_strings_sizes")`。实测 137 的 INFO 里确实有。

#### 前端同时修掉的三处「真实态读 mock」

| 位置 | 原来的问题 | 现在 |
|---|---|---|
| 实例下拉 | 用 `mock/storage.ts` 硬编码的 `REDIS_INSTANCES`（只有 136/137 两项） | 调 `listRedisInstances()` 拿真实实例 |
| `switchInstance` | 用 `mockStorageApi.getRedisInstanceInfo(id).dbKeys` 复位 db → 真实态会落到不存在的 db | 从真实 `info` / 实例配置取 |
| 类型分布图 | **无条件**调 `mockStorageApi.typeDistribution()`，真实态也在用假数据画饼图且不报错 | 按当前页真实键列表统计（本页口径） |

### 数据存储：MySQL 管理（尚未实现）

`/storage/mysql/*`（表清单、表结构、只读 SQL）均未实现 ——
前端 `StorageMysql.vue` 目前走演示数据。

---

## 响应约定

所有响应 HTTP 200，业务结果放信封：

```json
{ "code": 0, "message": "ok", "data": { } }
```

**为什么业务错误也返回 200**：前端 `useResource` 把 HTTP 5xx 当作「后端不可达」，
会**降级到演示数据**。若把「密码错误」写成 500，前端就会拿假数据盖住真实故障 ——
这是管理真实交易系统的后台，绝不允许。所以 `code != 0` 表达业务失败
（前端抛 `BizError`，不降级），HTTP 状态码只留给网络层与网关。

错误码：

| code | 含义 |
|---|---|
| 0 | 成功 |
| 40000 | 参数或业务校验失败 |
| 40100 | 未登录 / token 失效 |
| 40101 | 账号或密码错误（**账号不存在与密码错误同码**） |
| 40102 | 账号已停用 |
| 40103 | 账号因连续失败被锁定 |
| 40300 | 没有权限 |
| 40400 | 资源不存在 |
| 40900 | 冲突（唯一键重复、内置对象受保护等） |
| 50000 | 服务端内部错误 |

时间一律 `yyyy-MM-dd HH:mm:ss`（定宽零填充）—— 前端按字符串比较时间范围，
格式不一致会**静默筛错**（前端 README 第 8 条）。

---

## 安全实现要点

README（前端）里点名要求后端兜住的事，逐条落地情况：

### 1. 停用 / 改权限 / 改密码要让已签发的 token 失效 ✅

由 `SessionKicker` 统一处理，四类操作都会踢会话：
停用、删除、改角色、改密码。**只改库字段等于没停** ——
库里的 `enabled` 已经是 0，但那个人的浏览器还拿着有效 token，刷一下照样能用。

### 2. 权限校验必须在后端做 ✅

`@SaCheckPermission` + `SaTokenPermissionProvider`，权限每次**查库**
（不塞进 token），所以改权限立即生效。

> ### ⚠️ 踩坑记录（本项目真实踩到，务必保留 `WebConfig` 里的拦截器注册）
>
> **`SaInterceptor` 不注册，所有 `@SaCheckLogin` / `@SaCheckPermission` 会被静默忽略。**
>
> Sa-Token 的鉴权注解需要 `SaInterceptor` 在请求进入 Controller 前读取执行。
> 而 `sa-token-spring-boot3-starter` 的自动配置（`AutoConfiguration.imports`）只注册了
> `SaBeanRegister` / `SaBeanInject` 等 Bean，**不会**自动注册这个拦截器。
>
> 漏注册时：**不报错、不打日志、接口照常返回 200**。实测表现是
> 只读观察者（viewer，没有任何 `action:system-user:manage`）能成功调用
> 「新建管理账号」「新建角色」，返回 `code=0`。
> 也就是说**任何能登录的人都能给自己提权**。
>
> 这类失效最难查：接口文档、单元测试、`code=0` 的响应都不会提示你。
> 所以 `AdminApplicationTests` 里专门有一条用例守着它
> （`未登录访问受保护接口必须被拒绝`），一旦失效立刻红灯。
>
> 另外注意：**只统一 `code` 不够，失败文案也必须逐字一致**。
> 早先版本在「密码错误」里拼了「还可尝试 N 次」，而「账号不存在」没有，
> 两者 message 不同 —— 那等于把登录接口变成账号枚举探针。
> 现已把剩余次数只写进日志，不回给调用方。

### 3. 改权限 / 重置密码 / 管理账号本身也要写审计 ✅

每个写操作都写审计，**成功与失败都写**（失败尤其要留痕：
「谁试过重置密码但失败了」必须查得到）。审计写入失败不影响业务，
但会打 error 日志，不静默吞掉。

### 4. 其他

* **密码只写不读**：出参 DTO 里根本没有密码字段（比「记得加 `@JsonIgnore`」可靠）。
* **重置密码要过策略校验**，且「不得与最近 N 次重复」真的生效：
  改密前先把当前密码写进历史表，所以「改回上一版」会被拦住。
* **删除/停用最后一个超管会被拒绝** —— 现场没有别的恢复手段。
* **内置 `admin` 账号不允许停用/删除**。
* **删除仍被账号引用的角色会被拒绝**（否则那些账号静默失去权限）。
* **导出文件路径穿越防护**：文件名白名单 + 规范化路径校验双重防护。
* **导出 CSV 带 UTF-8 BOM + `\r\n`**：否则 Excel 打开中文乱码。
* **排序字段白名单**：`sortBy` 来自查询串，未白名单直接报错而不是静默忽略。
* **时间参数严格解析**：传了但不合格式直接报错，不静默当成「没传」返回全量。
* **未配置任何 CORS**：前后端同源（开发走 Vite 代理、生产走 Nginx）。
  开 `allowedOrigins("*")` + `allowCredentials(true)` 等于给任意站点开带凭证的跨站调用。

---

## 目录结构

```
backend/
├── pom.xml
├── sql/
│   ├── 00_drop_admin.sql      # 回滚（只删 T_Admin*）
│   ├── 01_schema_admin.sql    # 建表
│   └── 02_data_admin.sql      # 测试数据
├── scripts/                   # 交付验证工具（非运行时依赖，见 scripts/README.md）
└── src/
    ├── main/
    │   ├── java/com/qianlong/cloud/admin/
    │   │   ├── AdminApplication.java
    │   │   ├── common/              # ApiResult / PageResult / 错误码 / 时间口径 / 全局异常
    │   │   ├── config/
    │   │   │   ├── WebConfig.java               # ★ Sa-Token 拦截器注册（关键）
    │   │   │   ├── SaTokenPermissionProvider.java
    │   │   │   ├── MybatisPlusConfig.java
    │   │   │   ├── JacksonConfig.java
    │   │   │   ├── RedisProperties.java         # app.redis.instances 实例清单
    │   │   │   └── TradeServerProperties.java   # app.trade.* 委托服务器（只读）
    │   │   ├── controller/          # HTTP 层（原 web 包）
    │   │   │   ├── AccountController.java       # 账户与用户
    │   │   │   └── ...（系统管理各 Controller）
    │   │   ├── dto/                 # 出入参（与前端 domain.ts 逐字段对齐）
    │   │   ├── entity/              # 表实体（T_Admin* 8 张 + T_User / T_UserLog）
    │   │   ├── mapper/              # MyBatis-Plus Mapper（含聚合 SQL）
    │   │   └── service/
    │   │       ├── RedisJsonGateway.java        # ★ 多实例连接 + SCAN + 按实例读同一个键
    │   │       ├── RedisAccountRepository.java  # ★ 多实例 acc-* 只读访问
    │   │       ├── AccountService.java          # ★ MySQL+Redis 合并
    │   │       ├── TraderServerRepository.java  # ★ 委托服务器注册表只读（跨实例全读）
    │   │       ├── TradeServerService.java      # ★ 委托服务器节点 + 分配关系
    │   │       └── AccountLogService.java
    │   └── resources/application.yml
    └── test/java/.../AdminApplicationTests.java   # 冒烟 + 守护鉴权不失效
```

### 包结构说明

按**技术分层**平铺，不再套 `module.system` 这一层：

| 包 | 职责 |
|---|---|
| `common` | 与业务无关的基础件：统一响应、错误码、时间口径、全局异常 |
| `config` | 框架装配：Sa-Token 拦截器、MyBatis-Plus 分页、Jackson 时间格式 |
| `controller` | HTTP 层：路由、参数校验、权限注解、审计埋点 |
| `dto` | 出入参。**与 `entity` 分开**的原因：`AdminUserEntity.passwordHash` 绝不能出现在响应里，用独立 DTO 从类型上杜绝（比「记得加 `@JsonIgnore`」可靠） |
| `entity` | 表结构映射 |
| `mapper` | 数据访问（含按天聚合等 SQL） |
| `service` | 业务逻辑 |

> **`AdminPrincipal` 放在 `dto` 而不是 `controller`**：它是 `service` 的返回值
> （当前登录人的身份快照），被 `AdminUserService` / `AuthService` 引用。
> 若放在 `controller`，依赖方向会变成 `service -> controller`（业务反过来依赖 HTTP 层），
> 是明确的分层倒置。放 `dto` 后方向正确：`service -> dto`，`controller -> service + dto`。

> 若将来要拆多模块（例如再实现「账户与用户」「数据存储」等页面），
> 建议到时候再引入 `module.<域>` 分包或 Maven 多模块 —— 现在只有系统管理一个域，
> 提前分层只会多一层没有信息量的路径。

---

## 已知限制 / 后续

* **本模块只实现系统管理三个页面**。其余页面（服务器管理、业务数据、账户与用户、
  数据存储）的接口尚未实现，`endpoints.ts` 里已有定义，可按同样结构继续加。
  （委托服务器页现已实现，见上；策略服务器 / 数据中台 / 消息总线三页仍未实现。）
* **Sa-Token 用默认内存 DAO**：会话在应用重启后失效（管理员需重新登录）。
  现场若要多实例部署，需要换成 Redis DAO 并引入 `sa-token-redis-jackson`。
* **删除账号不删审计**：这是刻意的 —— 审计要认人，
  「某人删了账号」这件事必须留得住。
* **导出文件落在服务端临时目录**，会被系统清理。生产建议改到固定目录并加定期清理任务。
* 密码策略目前是**全局单行**配置。若将来要按角色区分复杂度要求，需要给策略表加维度。
* **并发编辑**：两个管理员同时改同一角色时，后提交的会覆盖前者（last-write-wins）。
  当前未做乐观锁；若现场有多人同时运维，建议给 `T_AdminRole` 加 `version` 字段。
* **委托服务器不做启停**（需求方明确要求）：只呈现状态。
  `action:server-trade:toggle` 已从可授予权限中撤销，仅保留为历史审计标签。
* **委托服务器页的「承载账号数」按 `id` 匹配**：现场 `id` 会重复
  （`136 db1` 有 4 个 `id=0`），所以那几个同 id 的节点会显示承载同一批账号。
  这是数据本身如此，接口与页面都已注明；不去猜一个更细的匹配规则
  （那会把「本该看到的重复」藏起来）。
* **`meta.registryCopies` 只统计「有该键的实例数」**：若某实例上该键不存在
  （现场 `137 db0` 就是），它不计入份数，也不报错 —— 这符合预期，不是缺陷。

---

## 验证

```bash
# 单元/集成测试（需要数据库可连）
mvn test
```

测试覆盖：未登录拒绝、无效 token 拒绝、权限不足拒绝、
契约字段完整性、账号枚举防护、聚合补零、导出路径穿越。
