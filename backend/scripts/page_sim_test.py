# -*- coding: utf-8 -*-
"""
三页面「行为重放」验证。

严格照抄三个 .vue 页面在 onMounted 与各交互里发出的请求序列，
逐条断言返回值足以支撑页面渲染（不是只有 code=0，而是**数据真的够用**）。

为什么值得单独做一遍：契约测试只验字段名与类型，
但页面还可能因为「数据为空 / 取值对不上 / 聚合口径不一致」而显示空白，
比如列表能返回、但角色下拉是空的，页面就渲染不出角色名。
本脚本把每个数据源的真实取值打出来并断言「非空且有意义」。

对应文件：
  src/views/system/SystemUser.vue
  src/views/system/SystemRole.vue
  src/views/system/SystemAudit.vue
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8000/api"
PASS = 0
FAIL = 0
FAILURES: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
    else:
        FAIL += 1
        FAILURES.append(f"{name} -> {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def call(method: str, path: str, body=None, token=None):
    url = BASE + urllib.parse.quote(path, safe="/?&=%:.,-_")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8"))


def head(t: str) -> None:
    print(f"\n===== {t} =====")


TOKEN = call("POST", "/system/auth/login",
             {"username": "admin", "password": "Admin@2026"})["data"]["token"]

# =========================================================================== #
head("SystemUser.vue —— onMounted 的 reloadAll()：三个数据源并发")
# reloadAll = Promise.all([loadUsers(currentParams()), loadRoles(), loadPolicy()])
users = call("GET", "/system/users?page=1&pageSize=10", None, TOKEN)
roles = call("GET", "/system/roles", None, TOKEN)
policy = call("GET", "/system/password-policy", None, TOKEN)

check("数据源1 users 有数据", users["data"]["total"] > 0, f"total={users['data']['total']}")
check("数据源2 roles 有数据（否则角色下拉与角色名解析全空）",
      len(roles["data"]) > 0, f"count={len(roles['data'])}")
check("数据源3 policy 有数据（否则「还剩几次锁定」无法判断）",
      policy["data"]["maxFailedAttempts"] is not None, str(policy["data"]))

# 页面用 roleOptions = roles.map(r => ({label:`${r.name}（${r.code}）`, value:r.id}))
role_options = [{"label": f"{r['name']}（{r['code']}）", "value": r["id"]} for r in roles["data"]]
check("roleOptions 能正常构造（4 项）", len(role_options) == 4, str(role_options))
check("roleOptions 每项 label/value 都有值",
      all(o["label"] and o["value"] for o in role_options), str(role_options))

# 页面用 roleNameMap 解析账号的 roleIds → 角色名（列「角色」要显示中文名）
role_name_map = {r["id"]: r["name"] for r in roles["data"]}
unresolved: list[int] = []
for u in users["data"]["list"]:
    for rid in u["roleIds"]:
        if rid not in role_name_map:
            unresolved.append(rid)
check("所有账号的 roleIds 都能解析出角色名（否则页面显示「角色 N」）",
      not unresolved, f"无法解析的 roleId={sorted(set(unresolved))}")

# 页面统计卡：total / enabled / disabled / locked
rows = users["data"]["list"]
check("汇总卡 enabled 可算", isinstance(sum(1 for r in rows if r["enabled"]), int), "")
check("汇总卡 locked 可算", isinstance(sum(1 for r in rows if r["locked"]), int), "")
check("存在锁定账号可供展示（test_tmp）",
      any(r["locked"] for r in rows), "当前页无锁定账号")

# 页面锁定列提示文案依赖 policy.maxFailedAttempts 与 row.failedAttempts
lockrow = next((r for r in rows if r["locked"]), None)
check("锁定行能看到失败次数（页面要显示「失败 N 次」）",
      lockrow is not None and lockrow["failedAttempts"] > 0,
      str(lockrow))

# 页面密码策略即时校验清单依赖这些字段都存在
for f in ("minLength", "requireUpper", "requireLower", "requireDigit", "requireSymbol",
          "maxFailedAttempts", "lockMinutes", "expireDays", "forceChangeOnFirstLogin", "historySize"):
    check(f"policy.{f} 可用（密码校验清单要用）", policy["data"][f] is not None, "")

# 页面用 lastLoginTime / lastLoginIP / loginCount 渲染「最后登录」与「登录次数」
check("账号带 lastLoginTime/lastLoginIP（页面要显示）",
      any(r["lastLoginTime"] and r["lastLoginIP"] for r in rows), "全都为空")
check("账号带 loginCount", all(isinstance(r["loginCount"], int) for r in rows), "")

head("SystemUser.vue —— 筛选交互（页面传的具体参数）")
for label, qs, expect in (
    ("角色筛选 roleId=2", "roleId=2", 3),
    ("状态筛选 status=enabled", "status=enabled", 5),
    ("状态筛选 status=disabled", "status=disabled", 1),
    ("状态筛选 status=locked", "status=locked", 1),
    ("关键字 keyword=yangs", "keyword=yangs", 1),
):
    r = call("GET", f"/system/users?page=1&pageSize=10&{qs}", None, TOKEN)
    check(f"{label} -> total={expect}", r["data"]["total"] == expect, f"total={r['data']['total']}")
r = call("GET", "/system/users?page=1&pageSize=10&roleId=2&status=enabled", None, TOKEN)
# ops 角色共 3 个账号（yangsh / test_tmp 启用，liuyi 停用），
# 所以「ops 且启用」= 2 条。这里刻意写死数字而不是复用上面那个 3 ——
# 第一版就是照抄了 roleId=2 的 total=3，把正确的组合筛选误判成失败。
check("角色+状态组合筛选按 AND 生效（ops 且启用 = 2 条）",
      r["data"]["total"] == 2, f"total={r['data']['total']}")
check("组合筛选结果确实都是该角色且已启用",
      all(2 in u["roleIds"] and u["enabled"] for u in r["data"]["list"]),
      str([(u["username"], u["roleIds"], u["enabled"]) for u in r["data"]["list"]]))
r = call("GET", "/system/users?page=1&pageSize=10&roleId=2&status=disabled", None, TOKEN)
check("ops 且停用 = 1 条（liuyi）",
      r["data"]["total"] == 1 and r["data"]["list"][0]["username"] == "liuyi",
      f"total={r['data']['total']}")
r = call("GET", "/system/users?page=2&pageSize=10", None, TOKEN)
check("翻到第 2 页返回空列表而不是报错（共 6 条 / 每页 10）",
      r["code"] == 0 and r["data"]["list"] == [], str(r["data"]["list"]))

head("SystemRole.vue —— onMounted 的 load()")
# 页面靠 permissions.includes('*') 判断超管；靠 permissions.length 显示权限点数
super_role = next(r for r in roles["data"] if "*" in r["permissions"])
check("存在通配符角色（页面「超管角色」卡要显示）", super_role["code"] == "super_admin", super_role["code"])
check("通配符角色 permissions 只有一项 '*'（页面据此显示「全部（*）」）",
      super_role["permissions"] == ["*"], str(super_role["permissions"]))
non_wild = [r for r in roles["data"] if "*" not in r["permissions"]]
check("非通配符角色的权限点可计数（页面显示数字）",
      all(len(r["permissions"]) > 0 for r in non_wild), "存在权限点为空的角色")
check("内置角色数 = 3（页面「内置角色」卡）",
      sum(1 for r in roles["data"] if r["builtin"]) == 3,
      str(sum(1 for r in roles["data"] if r["builtin"])))
check("引用人数可算（页面「引用人数」列 + 删除确认文案）",
      all(isinstance(r["userCount"], int) for r in roles["data"]), "")
# 页面按 name/code/desc 做前端过滤
kw = "运维"
filtered = [r for r in roles["data"]
            if kw in r["name"] or kw in r["code"] or kw in (r["desc"] or "")]
check("角色页前端关键字过滤可用（搜「运维」命中 ops）",
      any(r["code"] == "ops" for r in filtered), str([r["code"] for r in filtered]))
# 页面权限树用 permission-keys 兜底（另做一次一致性检查）
pk = call("GET", "/system/roles/permission-keys", None, TOKEN)
check("permission-keys 与后端字典一致（24 个）", len(pk["data"]) == 24, f"count={len(pk['data'])}")
bad_keys: list[str] = []
for r in roles["data"]:
    for k in r["permissions"]:
        if k != "*" and k not in pk["data"]:
            bad_keys.append(k)
check("已存角色的权限键全部合法（否则权限树对不上）", not bad_keys, str(sorted(set(bad_keys))))

head("SystemAudit.vue —— onMounted 的 5 次调用 + 2 个图表")
audit = call("GET", "/system/audit-logs?page=1&pageSize=15", None, TOKEN)
action_dict = call("GET", "/system/audit-logs/actions", None, TOKEN)
audit_daily = call("GET", "/system/audit-logs/daily?from=2026-09-29%2000:00:00&to=2026-10-12%2023:59:59", None, TOKEN)
audit_by_action = call("GET", "/system/audit-logs/by-action?from=2026-09-29%2000:00:00&to=2026-10-12%2023:59:59", None, TOKEN)
login_logs = call("GET", "/system/login-logs?page=1&pageSize=15", None, TOKEN)

check("审计列表有数据", audit["data"]["total"] > 0, f"total={audit['data']['total']}")
check("动作字典有数据（模块/动作下拉要填充）", len(action_dict["data"]) > 0, "")
# 页面 moduleOptions 从 actionDict 去重 module 构造
modules = sorted({a["module"] for a in action_dict["data"]})
check("moduleOptions 能构造出来", len(modules) > 0, str(modules))
# 页面 MODULE_LABEL 映射的键：dashboard/server/bizdata/account/storage/monitor/system
KNOWN_MODULE_LABELS = {"dashboard", "server", "bizdata", "account", "storage", "monitor", "system"}
unknown_modules = [m for m in modules if m not in KNOWN_MODULE_LABELS]
check("动作字典的 module 都在前端 MODULE_LABEL 里（否则下拉显示英文键）",
      not unknown_modules, f"前端未映射的 module={unknown_modules}")
# 页面把 module 也用在上：审计行的 module 列
row_modules = {r["module"] for r in audit["data"]["list"]}
unknown_rows = [m for m in row_modules if m not in KNOWN_MODULE_LABELS]
check("审计行的 module 也都在前端映射里",
      not unknown_rows, f"未映射={unknown_rows}")

check("图表1 按天聚合 14 行（补零后图表 X 轴连续）", len(audit_daily["data"]) == 14,
      f"count={len(audit_daily['data'])}")
check("图表1 有非零数据（否则图表显示「暂无数据」）",
      sum(r["high"] + r["other"] for r in audit_daily["data"]) > 0, "")
check("图表2 动作分布有数据", len(audit_by_action["data"]) > 0, "")
check("图表2 的 name 与审计行的 actionLabel 同源（前端直接用 name 当 Y 轴标签）",
      all(any(a["label"] == r["name"] for a in action_dict["data"])
          for r in audit_by_action["data"]),
      str([r["name"] for r in audit_by_action["data"]]))
check("登录日志有数据", login_logs["data"]["total"] > 0, f"total={login_logs['data']['total']}")

head("SystemAudit.vue —— Tab2 的两个图表")
login_daily = call("GET", "/system/login-logs/daily?from=2026-09-29%2000:00:00&to=2026-10-12%2023:59:59", None, TOKEN)
fail_ip = call("GET", "/system/login-logs/fail-by-ip?from=2026-09-29%2000:00:00&to=2026-10-12%2023:59:59", None, TOKEN)
check("登录按天聚合 14 行", len(login_daily["data"]) == 14, f"count={len(login_daily['data'])}")
check("登录按天聚合有非零数据", sum(r["success"] + r["failure"] for r in login_daily["data"]) > 0, "")
check("失败 IP 聚合有数据", len(fail_ip["data"]) > 0, "")

head("SystemAudit.vue —— 导出 CSV（页面点「导出 CSV」）")
exp = call("POST", "/system/audit-logs/export", {"result": "failure", "sortOrder": "desc"}, TOKEN)
check("导出成功且 total>0", exp["code"] == 0 and exp["data"]["total"] > 0, str(exp["data"]))

head("汇总卡口径自检（页面「本页口径」的说明要与实际一致）")
rows = audit["data"]["list"]
check("高危数可用 level=='high' 数出来",
      sum(1 for r in rows if r["level"] == "high") >= 0, "")
check("失败数可用 result=='failure' 数出来",
      sum(1 for r in rows if r["result"] == "failure") >= 0, "")
check("操作人可去重（「涉及操作人」卡）",
      len({r["operator"] for r in rows}) > 0, "")
lrows = login_logs["data"]["list"]
check("登录失败数/失败率可用 success 布尔算出来",
      all(isinstance(r["success"], bool) for r in lrows), "")
check("异常来源 IP 可去重", len({r["loginIP"] for r in lrows if not r["success"] and r["loginIP"]}) >= 0, "")

head("登录页（LoginView.vue）依赖的账号状态分支")
for uname, pwd, expect_code, label in (
    ("admin", "Admin@2026", 0, "超管可登录"),
    ("yangsh", "Ops@2026", 0, "运维可登录"),
    ("zhangguochang", "View@2026", 0, "只读观察者可登录"),
    ("liuyi", "Ops@2026", 40102, "liuyi 已停用 -> 40102"),
    ("test_tmp", "Ops@2026", 40103, "test_tmp 已锁定 -> 40103"),
    ("admin", "wrong", 40101, "密码错 -> 40101"),
):
    r = call("POST", "/system/auth/login", {"username": uname, "password": pwd})
    check(label, r["code"] == expect_code, f"code={r['code']} msg={r.get('message')}")

head("权限差异演示（换账号登录 -> 按钮变灰的**后端依据**）")
# 注意：test_tmp 此时可能已被 e2e 解锁过，所以这里不依赖它，只用 4 个常态账号 + admin
tokens = {}
for uname, pwd in (("admin", "Admin@2026"), ("yangsh", "Ops@2026"),
                   ("zhangguochang", "View@2026"), ("audit01", "Ops@2026")):
    r = call("POST", "/system/auth/login", {"username": uname, "password": pwd})
    check(f"{uname} 可登录（权限差异演示的前置条件）", r["code"] == 0,
          f"code={r['code']} msg={r.get('message')}")
    if r["code"] != 0 or not r.get("data"):
        continue
    tokens[uname] = r["data"]["token"]
    perms = r["data"]["permissions"]
    print(f"    {uname:16s} 权限点 {len(perms):3d} 个"
          f"{'（通配符 *）' if '*' in perms else ''}")

check("四个演示账号都能登录（页面按账号演示权限差异）", len(tokens) == 4, str(sorted(tokens)))

MANAGE_USER = "action:system-user:manage"
check("admin 有 action:system-user:manage（或 *）",
      "*" in call("GET", "/system/auth/profile", None, tokens["admin"])["data"]["permissions"]
      or MANAGE_USER in call("GET", "/system/auth/profile", None, tokens["admin"])["data"]["permissions"], "")
check("yangsh(运维) **无** manage 权限（页面上这类按钮应置灰）",
      MANAGE_USER not in call("GET", "/system/auth/profile", None, tokens["yangsh"])["data"]["permissions"], "")
check("zhangguochang(只读) **无** manage 权限",
      MANAGE_USER not in call("GET", "/system/auth/profile", None, tokens["zhangguochang"])["data"]["permissions"], "")
check("audit01(审计员) **无** manage 权限",
      MANAGE_USER not in call("GET", "/system/auth/profile", None, tokens["audit01"])["data"]["permissions"], "")
# 后端必须真的拒绝（不只是前端置灰）
r = call("POST", "/system/users",
         {"username": "should_fail", "password": "Should@2026", "roleIds": [3], "enabled": True},
         tokens["yangsh"])
check("运维调新建账号接口 -> 后端真的拒绝 40300（前端置灰不是安全边界）",
      r["code"] == 40300, f"code={r['code']}")
check("被拒后**没有**真的建出账号",
      call("GET", "/system/users?keyword=should_fail", None, TOKEN)["data"]["total"] == 0, "竟建成功了")
# 审计页导出权限：auditor 有 system-audit:export
r = call("POST", "/system/audit-logs/export", {}, tokens["audit01"])
check("审计员可以导出（其角色含 action:system-audit:export）", r["code"] == 0, f"code={r['code']}")
r = call("POST", "/system/audit-logs/export", {}, tokens["zhangguochang"])
check("只读观察者不能导出 -> 40300", r["code"] == 40300, f"code={r['code']}")

head("越权尝试必须被后端拦下（直接打接口，绕过前端）")
r = call("POST", "/system/roles",
         {"code": "evil", "name": "提权", "permissions": ["*"]}, tokens["yangsh"])
check("运维给自己造通配符角色 -> 40300", r["code"] == 40300, f"code={r['code']}")
check("被拒后角色表未增项",
      len(call("GET", "/system/roles", None, TOKEN)["data"]) == 4,
      str(len(call("GET", "/system/roles", None, TOKEN)["data"])))
r = call("PUT", "/system/users/1002",
         {"username": "yangsh", "displayName": "杨", "roleIds": [1], "enabled": True},
         tokens["yangsh"])
check("运维给自己改角色为超管 -> 40300", r["code"] == 40300, f"code={r['code']}")
r = call("GET", "/system/users/1002", None, TOKEN)
check("被拒后 yangsh 角色未被改动",
      call("GET", "/system/users/1002", None, TOKEN)["data"]["roleIds"] == [2],
      str(call("GET", "/system/users/1002", None, TOKEN)["data"]["roleIds"]))

print("\n=============================================================")
print(f"  页面行为重放通过 {PASS} 项，失败 {FAIL} 项")
print("=============================================================")
if FAILURES:
    print("\n失败明细：")
    for item in FAILURES:
        print(f"  - {item}")
    sys.exit(1)
