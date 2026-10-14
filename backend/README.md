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
    │   │   │   └── RedisProperties.java         # app.redis.instances 实例清单
    │   │   ├── controller/          # HTTP 层（原 web 包）
    │   │   │   ├── AccountController.java       # 账户与用户
    │   │   │   └── ...（系统管理各 Controller）
    │   │   ├── dto/                 # 出入参（与前端 domain.ts 逐字段对齐）
    │   │   ├── entity/              # 表实体（T_Admin* 8 张 + T_User / T_UserLog）
    │   │   ├── mapper/              # MyBatis-Plus Mapper（含聚合 SQL）
    │   │   └── service/
    │   │       ├── RedisAccountRepository.java  # ★ 多实例 acc-* 只读访问
    │   │       ├── AccountService.java          # ★ MySQL+Redis 合并
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
* **Sa-Token 用默认内存 DAO**：会话在应用重启后失效（管理员需重新登录）。
  现场若要多实例部署，需要换成 Redis DAO 并引入 `sa-token-redis-jackson`。
* **删除账号不删审计**：这是刻意的 —— 审计要认人，
  「某人删了账号」这件事必须留得住。
* **导出文件落在服务端临时目录**，会被系统清理。生产建议改到固定目录并加定期清理任务。
* 密码策略目前是**全局单行**配置。若将来要按角色区分复杂度要求，需要给策略表加维度。
* **并发编辑**：两个管理员同时改同一角色时，后提交的会覆盖前者（last-write-wins）。
  当前未做乐观锁；若现场有多人同时运维，建议给 `T_AdminRole` 加 `version` 字段。

---

## 验证

```bash
# 单元/集成测试（需要数据库可连）
mvn test
```

测试覆盖：未登录拒绝、无效 token 拒绝、权限不足拒绝、
契约字段完整性、账号枚举防护、聚合补零、导出路径穿越。
