# 云条件单后台管理系统

面向运维的后台，集中查看云条件单相关的**服务器节点、业务表数据、Redis / MySQL 存储**。
只读为主，唯一的业务写能力是**条件单状态变更**（停止 / 运行 / 终止）。

技术栈：Spring Boot 3.5 + MyBatis-Plus + Sa-Token ｜ Vue 3 + Vite + Naive UI

---

## 快速开始

```bash
# 1. 建表（只创建 T_Admin*，不碰业务表）
cd backend
mysql -h 192.168.1.136 -u qianlong_cloud -p qianlong_cloud < sql/01_schema_admin.sql
mysql -h 192.168.1.136 -u qianlong_cloud -p qianlong_cloud < sql/02_data_admin.sql

# 2. 起后端（8000，上下文 /api）
mvn spring-boot:run

# 3. 起前端（5173，已开 host，局域网可访问）
cd ../frontend && npm install && npm run dev
```

登录：`admin / Admin@2026`

> ⚠️ 两个密码只差大小写和符号，写错时**接口不报错**、只是所有账号读不到 Redis：
> MySQL `Qianlong@2026`（小写 l）｜Redis `QianLong@2026&`（大写 L，末尾 `&`）

---

## 数据源分工

不同页面的数据本来就在不同地方，不是"统一一下"能解决的。

| 页面 | 数据源 |
|---|---|
| 业务数据（条件单 / 委托单 / 成交 / 策略日志） | MySQL 表 |
| 服务器管理（委托 / 策略 / 数据中台 / 消息总线） | Redis 键 + Pub/Sub |
| 总览 | 两者聚合 |
| 账户与用户 | MySQL `T_User` + Redis `acc-*` 合并，每行标来源 |

两个容易踩的点：

- **条件单只读 MySQL**。Redis `strategy-*` 里也有，但那是策略平台实时态，双读会重复计数。
- **服务器节点按 `mac` 归并**。同一注册表键在多个实例各存一份（现场委托服务器 14 条只对应 4 台），不归并"节点总数"就是错的。

---

## 写操作的边界

| 能力 | 说明 |
|---|---|
| 条件单状态变更 | 走 `setByStrategy`，**先改本地 Redis + MySQL，再视情况通知策略平台** |
| 管理端账号 / 角色 / 密码 | 常规 CRUD，写审计 |
| 业务数据新增 / 删除 | **不提供**（需求方明确） |

安全闸默认全关：

```yaml
app.conditional:
  enabled: false                # 总开关
  allowed-server-ids: []        # 空 = 全部拒绝，不是"不限制"
  allow-live-platform: false    # 目标流上坐着真平台时拒发
```

> 现场条件单的 `strategyServerID` **全是 -1**（"尚未分配"），所以"发给哪台平台"的映射并不存在。
> 编号必须由运维显式给，拿不到就报错，**不猜、不默认成 0**（`0` 正是真平台所在的流）。

回执四档：`success` / `local_only` / `timeout` / `business_failed`。
**`timeout` 既不是成功也不是失败**，页面显示"结果未知"。

---

## 已知限制

| 项 | 现状 |
|---|---|
| 条件单状态变更 | 能下发但现场还开不了（编号全 -1，闸默认关）；协议来自实测样本，未在真平台端到端跑过 |
| 数据中台 137 | 显示"未知"是准确的：中台在 137，连的却是 136 的 Redis |
| Redis 管理 | 前端只读；后端有删键接口，页面不开放入口 |
| `X-Forwarded-For` | 无条件采信，直连后端可伪造登录 IP；生产用 nginx 反代时必须配 `X-Forwarded-For` |

---

## 测试

```bash
cd backend/scripts
python run_all_tests.py     # 全部联通测试（只读，不写业务表）
python conditional_connect_test.py   # 条件单守门（含安全闸断言）
python contract_test.py     # 前后端字段契约比对
```

---

## 文档

| 文档 | 内容 |
|---|---|
| `backend/README.md` | 表结构、接口、Redis 键结构、协议实测与踩坑 |
| `frontend/README.md` | 页面清单、演示数据机制、样式约定 |
| `backend/scripts/README.md` | 脚本用法 |

> 子文档更细，但**部分章节成文较早**（如"后端未动"、"删键入口"）。以代码与本文件为准。
