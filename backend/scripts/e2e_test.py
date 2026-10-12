# -*- coding: utf-8 -*-
"""
系统管理模块接口端到端验证。

逐条覆盖前端页面会发的请求，既测成功路径也测错误分支。
用标准库 urllib，无需第三方依赖。

用法：python e2e_test.py
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


def section(title: str) -> None:
    print(f"\n===== {title} =====")


def call(method: str, path: str, body=None, token: str | None = None):
    """返回 (http_status, parsed_body_or_text)。业务错误也是 HTTP 200。

    路径里的非 ASCII（中文关键字等）要 percent-encode —— urllib 只接受 ASCII 的
    请求行，`?keyword=运维` 会直接抛 UnicodeEncodeError（第一版就是这么崩的）。
    """
    url = BASE + urllib.parse.quote(path, safe="/?&=%:.,-_")
    data = None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            raw = resp.read()
            try:
                return resp.status, json.loads(raw.decode("utf-8"))
            except Exception:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8"))
        except Exception:
            return e.code, raw.decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, f"{type(e).__name__}: {e}"


def code_of(res) -> int:
    return res[1].get("code") if isinstance(res[1], dict) else -999


def data_of(res):
    return res[1].get("data") if isinstance(res[1], dict) else None


def msg_of(res) -> str:
    return res[1].get("message", "") if isinstance(res[1], dict) else str(res[1])


# =========================================================================== #
section("1. 登录鉴权")

h1, b1 = call("POST", "/system/auth/login", {"username": "admin", "password": "wrong-password"})
check("密码错误 -> code 40101", code_of((h1, b1)) == 40101, f"code={code_of((h1,b1))} msg={msg_of((h1,b1))}")
h2, b2 = call("POST", "/system/auth/login", {"username": "no_such_user_xyz", "password": "whatever"})
check("账号不存在 -> 同为 40101（防账号枚举）", code_of((h2, b2)) == 40101, f"code={code_of((h2,b2))}")
check("两者 message 完全一致", msg_of((h1, b1)) == msg_of((h2, b2)), f"{msg_of((h1,b1))!r} vs {msg_of((h2,b2))!r}")
check("业务错误走 HTTP 200（5xx 会让前端降级到演示数据）", h1 == 200, f"http={h1}")

h3, b3 = call("POST", "/system/auth/login", {"username": "liuyi", "password": "Ops@2026"})
check("已停用账号 -> 40102", code_of((h3, b3)) == 40102, f"code={code_of((h3,b3))} msg={msg_of((h3,b3))}")
h4, b4 = call("POST", "/system/auth/login", {"username": "test_tmp", "password": "Ops@2026"})
check("已锁定账号 -> 40103", code_of((h4, b4)) == 40103, f"code={code_of((h4,b4))} msg={msg_of((h4,b4))}")

h5, b5 = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
check("超管登录成功", code_of((h5, b5)) == 0, f"code={code_of((h5,b5))} msg={msg_of((h5,b5))}")
admin_token = data_of((h5, b5))["token"]
check("返回 token", bool(admin_token), "")
check("超管 permissions == ['*']", data_of((h5, b5))["permissions"] == ["*"], str(data_of((h5, b5))["permissions"]))
check("超管 roles 含 super_admin", "super_admin" in data_of((h5, b5))["roles"], str(data_of((h5, b5))["roles"]))

h6, b6 = call("POST", "/system/auth/login", {"username": "zhangguochang", "password": "View@2026"})
viewer_token = data_of((h6, b6))["token"]
check("只读观察者登录成功", code_of((h6, b6)) == 0, f"code={code_of((h6,b6))}")
check("只读观察者 mustChangePassword=true", data_of((h6, b6))["mustChangePassword"] is True, "")
vperms = data_of((h6, b6))["permissions"]
check("只读观察者无通配符", "*" not in vperms, str(vperms))
check("只读观察者含 menu:dashboard", "menu:dashboard" in vperms, str(vperms))
check("只读观察者不含 action:system-user:manage", "action:system-user:manage" not in vperms, str(vperms))

h7, b7 = call("GET", "/system/auth/profile", None, admin_token)
check("profile 返回登录名", data_of((h7, b7))["username"] == "admin", str(data_of((h7, b7))))
h8, b8 = call("GET", "/system/auth/profile", None, "invalid-token-xxx")
check("无效 token -> 40100", code_of((h8, b8)) == 40100, f"code={code_of((h8,b8))}")
h9, b9 = call("GET", "/system/auth/profile")
check("未带 token -> 40100", code_of((h9, b9)) == 40100, f"code={code_of((h9,b9))}")

# =========================================================================== #
section("2. 管理用户：列表 / 筛选 / 分页")

res = call("GET", "/system/users?page=1&pageSize=10", None, admin_token)
check("用户列表 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
d = data_of(res)
check("total = 6", d["total"] == 6, f"total={d['total']}")
check("返回 6 行", len(d["list"]) == 6, f"count={len(d['list'])}")
admin_row = next(u for u in d["list"] if u["username"] == "admin")
check("admin roleIds 含 1", 1 in admin_row["roleIds"], str(admin_row["roleIds"]))
check("admin enabled=true", admin_row["enabled"] is True, str(admin_row["enabled"]))
# 精确比对字段名：不能用 `"password" in k` —— 那会命中合法的 mustChangePassword，
# 变成一条永远失败的断言（第一版就是这么写的，实测误报）。
forbidden = {"password", "passwordhash", "password_hash", "passwd", "pwd"}
check("响应不含 password / passwordHash / passwd 等任何密码字段",
      not (forbidden & {k.lower() for k in admin_row}),
      str(sorted(forbidden & {k.lower() for k in admin_row})))
locked_row = next(u for u in d["list"] if u["username"] == "test_tmp")
check("test_tmp locked=true 且 failedAttempts=5",
      locked_row["locked"] is True and locked_row["failedAttempts"] == 5,
      f"locked={locked_row['locked']} failed={locked_row['failedAttempts']}")
check("时间字段格式正确", len(admin_row["createdAt"]) == 19 and admin_row["createdAt"][4] == "-",
      str(admin_row["createdAt"]))

res = call("GET", "/system/users?status=disabled", None, admin_token)
check("status=disabled -> 1 个（liuyi）",
      data_of(res)["total"] == 1 and data_of(res)["list"][0]["username"] == "liuyi",
      f"total={data_of(res)['total']}")
res = call("GET", "/system/users?status=locked", None, admin_token)
check("status=locked -> 1 个", data_of(res)["total"] == 1, f"total={data_of(res)['total']}")
res = call("GET", "/system/users?roleId=3", None, admin_token)
check("roleId=3(viewer) -> 1 个",
      data_of(res)["total"] == 1 and data_of(res)["list"][0]["username"] == "zhangguochang",
      f"total={data_of(res)['total']}")
res = call("GET", "/system/users?keyword=yangs", None, admin_token)
check("keyword=yangs -> 1 个", data_of(res)["total"] == 1, f"total={data_of(res)['total']}")
res = call("GET", "/system/users?keyword=运维", None, admin_token)
check("keyword 搜备注也能命中", data_of(res)["total"] >= 1, f"total={data_of(res)['total']}")
res = call("GET", "/system/users?status=bogus", None, admin_token)
check("非法 status -> 40000（不静默返回全量）", code_of(res) == 40000, f"code={code_of(res)}")
res = call("GET", "/system/users?sortBy=evil%3Bdrop", None, admin_token)
check("非法排序字段 -> 40000（白名单）", code_of(res) == 40000, f"code={code_of(res)}")
res = call("GET", "/system/users?page=1&pageSize=2", None, admin_token)
check("pageSize=2 -> 返回 2 行且 total 仍为 6",
      len(data_of(res)["list"]) == 2 and data_of(res)["total"] == 6,
      f"count={len(data_of(res)['list'])} total={data_of(res)['total']}")

# =========================================================================== #
section("3. 权限校验：后端才是安全边界")

res = call("POST", "/system/users",
           {"username": "hacker", "password": "Hacker@2026", "roleIds": [3], "enabled": True}, viewer_token)
check("只读观察者建账号 -> 40300", code_of(res) == 40300, f"code={code_of(res)} msg={msg_of(res)}")
res = call("POST", "/system/roles",
           {"code": "hacker_role", "name": "H", "permissions": ["menu:dashboard"]}, viewer_token)
check("只读观察者建角色 -> 40300", code_of(res) == 40300, f"code={code_of(res)}")
res = call("GET", "/system/users", None, viewer_token)
check("只读观察者可以读用户列表（读不要求 manage）", code_of(res) == 0, f"code={code_of(res)}")

# =========================================================================== #
section("4. 角色与权限")

res = call("GET", "/system/roles", None, admin_token)
check("角色列表 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
roles = data_of(res)
check("角色数 = 4", len(roles) == 4, f"count={len(roles)}")
super_role = next(r for r in roles if r["code"] == "super_admin")
check("super_admin permissions == ['*']", super_role["permissions"] == ["*"], str(super_role["permissions"]))
check("super_admin builtin=true", super_role["builtin"] is True, "")
check("super_admin userCount = 1", super_role["userCount"] == 1, str(super_role["userCount"]))
ops_role = next(r for r in roles if r["code"] == "ops")
check("ops userCount = 3", ops_role["userCount"] == 3, str(ops_role["userCount"]))
check("ops 含 action:conditional:write", "action:conditional:write" in ops_role["permissions"], "")
check("ops 不含 action:system-user:manage", "action:system-user:manage" not in ops_role["permissions"], "")
auditor_role = next(r for r in roles if r["code"] == "auditor")
check("auditor builtin=false", auditor_role["builtin"] is False, "")
check("desc 已从 description 列映射", "通配符" in (super_role["desc"] or ""), str(super_role["desc"]))

res = call("GET", "/system/roles/permission-keys", None, admin_token)
check("权限键总数 = 24（17 菜单 + 7 操作）", len(data_of(res)) == 24, f"count={len(data_of(res))}")

res = call("POST", "/system/roles",
           {"code": "test_role", "name": "测试角色", "desc": "端到端测试用",
            "permissions": ["menu:dashboard", "action:system-audit:export"]}, admin_token)
check("新建角色 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
new_role_id = data_of(res)["id"]
check("接口造不出 builtin 角色", data_of(res)["builtin"] is False, "")
res = call("POST", "/system/roles", {"code": "test_role", "name": "重复", "permissions": ["menu:dashboard"]}, admin_token)
check("重复 code -> 40900", code_of(res) == 40900, f"code={code_of(res)}")
res = call("POST", "/system/roles", {"code": "BadCode", "name": "x", "permissions": ["menu:dashboard"]}, admin_token)
check("非法 code（大写）-> 40000", code_of(res) == 40000, f"code={code_of(res)}")
res = call("POST", "/system/roles", {"code": "ok_code", "name": "x", "permissions": ["not:a:real:key"]}, admin_token)
check("未知权限键 -> 40000（不静默丢弃）", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")
res = call("POST", "/system/roles", {"code": "ok_code2", "name": "x", "permissions": []}, admin_token)
check("空权限点 -> 40000", code_of(res) == 40000, f"code={code_of(res)}")

res = call("PUT", "/system/roles/1",
           {"code": "super_admin", "name": "改名超管", "permissions": ["*"]}, admin_token)
check("内置角色改名 -> 40900", code_of(res) == 40900, f"code={code_of(res)} msg={msg_of(res)}")
res = call("PUT", "/system/roles/1",
           {"code": "super_admin2", "name": "超级管理员", "permissions": ["*"]}, admin_token)
check("内置角色改 code -> 40900", code_of(res) == 40900, f"code={code_of(res)}")
res = call("DELETE", "/system/roles/1", None, admin_token)
check("删除内置角色 -> 40900", code_of(res) == 40900, f"code={code_of(res)} msg={msg_of(res)}")
res = call("DELETE", "/system/roles/2", None, admin_token)
check("删除仍被引用的角色 -> 40900", code_of(res) == 40900, f"code={code_of(res)} msg={msg_of(res)}")
res = call("PUT", "/system/roles/1",
           {"code": "super_admin", "name": "超级管理员", "permissions": ["menu:dashboard"]}, admin_token)
check("摘掉最后一个超管的 * -> 40900（否则后台进不去）",
      code_of(res) == 40900, f"code={code_of(res)} msg={msg_of(res)}")

res = call("PUT", f"/system/roles/{new_role_id}",
           {"code": "test_role", "name": "测试角色已改", "desc": "改过了", "permissions": ["menu:dashboard"]},
           admin_token)
check("更新自定义角色 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
check("权限点已更新为 1 个", len(data_of(res)["permissions"]) == 1, str(data_of(res)["permissions"]))
res = call("DELETE", f"/system/roles/{new_role_id}", None, admin_token)
check("删除无人引用的自定义角色 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")

# =========================================================================== #
section("5. 审计：列表 / 筛选 / 聚合")

res = call("GET", "/system/audit-logs?page=1&pageSize=15", None, admin_token)
check("审计列表 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
d = data_of(res)
check("审计 total >= 220", d["total"] >= 220, f"total={d['total']}")
check("审计行含 actionLabel", bool(d["list"][0]["actionLabel"]), "")
check("时间格式 yyyy-MM-dd HH:mm:ss",
      len(d["list"][0]["createdAt"]) == 19 and d["list"][0]["createdAt"][4] == "-",
      str(d["list"][0]["createdAt"]))

res = call("GET", "/system/audit-logs?level=high&pageSize=20", None, admin_token)
check("level=high 过滤生效", all(r["level"] == "high" for r in data_of(res)["list"]), "存在非 high 行")
res = call("GET", "/system/audit-logs?result=failure&pageSize=20", None, admin_token)
check("result=failure 过滤生效", all(r["result"] == "failure" for r in data_of(res)["list"]), "存在非 failure 行")
res = call("GET", "/system/audit-logs?action=action:storage-redis:delete-key&pageSize=10", None, admin_token)
check("action 过滤生效",
      all(r["action"] == "action:storage-redis:delete-key" for r in data_of(res)["list"]), "存在其他动作")
res = call("GET", "/system/audit-logs?module=storage&pageSize=10", None, admin_token)
check("module 过滤生效", all(r["module"] == "storage" for r in data_of(res)["list"]), "存在其他模块")
res = call("GET", "/system/audit-logs?operator=yangsh&pageSize=10", None, admin_token)
check("operator 过滤生效", all("yangsh" in r["operator"] for r in data_of(res)["list"]), "存在其他操作人")
res = call("GET", "/system/audit-logs?keyword=DataHub&pageSize=10", None, admin_token)
check("keyword 命中目标/动作", data_of(res)["total"] >= 1, f"total={data_of(res)['total']}")
res = call("GET", "/system/audit-logs?from=2020-01-01%2000:00:00&to=2020-01-02%2000:00:00", None, admin_token)
check("时间范围过滤（远古区间 -> 0 条）", data_of(res)["total"] == 0, f"total={data_of(res)['total']}")
res = call("GET", "/system/audit-logs?from=not-a-date", None, admin_token)
check("非法时间格式 -> 40000（不静默忽略条件返回全量）", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")
res = call("GET", "/system/audit-logs?sortBy=id", None, admin_token)
check("非法排序字段 -> 40000", code_of(res) == 40000, f"code={code_of(res)}")

res = call("GET", "/system/audit-logs/actions", None, admin_token)
check("动作字典 9 项（7 权限型 + 2 登记型）", len(data_of(res)) == 9, f"count={len(data_of(res))}")
check("含 system:login 登记型动作",
      any(a["action"] == "system:login" for a in data_of(res)), "")
check("动作字典带 module 与 level",
      all(a.get("module") and a.get("level") for a in data_of(res)), "")

res = call("GET", "/system/audit-logs/daily", None, admin_token)
daily = data_of(res)
check("审计按天聚合 14 行（默认近 14 天）", len(daily) == 14, f"count={len(daily)}")
check("日期为 yyyy-MM-dd", len(daily[0]["date"]) == 10 and daily[0]["date"][4] == "-", str(daily[0]["date"]))
check("含 high / other 两列", "high" in daily[0] and "other" in daily[0], str(daily[0]))
check("按天聚合有数据（补零不吞数据）", sum(r["high"] + r["other"] for r in daily) > 0,
      str(sum(r["high"] + r["other"] for r in daily)))
check("日期递增", [r["date"] for r in daily] == sorted(r["date"] for r in daily), "顺序错")

res = call("GET", "/system/audit-logs/daily?from=2020-01-01%2000:00:00&to=2020-01-05%2000:00:00", None, admin_token)
check("指定区间时按天补零（5 天 -> 5 行）", len(data_of(res)) == 5, f"count={len(data_of(res))}")

res = call("GET", "/system/audit-logs/by-action", None, admin_token)
byaction = data_of(res)
check("按动作聚合 0 < 行数 <= 8", 0 < len(byaction) <= 8, f"count={len(byaction)}")
check("按动作聚合按计数倒序",
      all(byaction[i]["value"] >= byaction[i + 1]["value"] for i in range(len(byaction) - 1)), "顺序错")

# =========================================================================== #
section("6. 管理端登录日志")

res = call("GET", "/system/login-logs?page=1&pageSize=15", None, admin_token)
check("登录日志 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
check("登录日志 total >= 180", data_of(res)["total"] >= 180, f"total={data_of(res)['total']}")
res = call("GET", "/system/login-logs?success=true&pageSize=20", None, admin_token)
check("success=true 过滤生效（前端传字符串）",
      all(r["success"] is True for r in data_of(res)["list"]), "存在失败行")
res = call("GET", "/system/login-logs?success=false&pageSize=20", None, admin_token)
check("success=false 过滤生效", all(r["success"] is False for r in data_of(res)["list"]), "存在成功行")
check("success=false 确实有数据（非恒 0 的静默失效）", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
res = call("GET", "/system/login-logs?username=yangsh&pageSize=20", None, admin_token)
check("username 过滤生效", all(r["username"] == "yangsh" for r in data_of(res)["list"]), "存在其他账号")
res = call("GET", "/system/login-logs?ip=192.168&pageSize=20", None, admin_token)
check("ip 过滤生效", all("192.168" in (r["loginIP"] or "") for r in data_of(res)["list"]), "存在其他 IP")
res = call("GET", "/system/login-logs?success=notabool", None, admin_token)
check("非法 success 取值 -> 40000（不静默返回全量）", code_of(res) == 40000, f"code={code_of(res)}")
res = call("GET", "/system/login-logs/daily", None, admin_token)
check("登录按天聚合 14 行", len(data_of(res)) == 14, f"count={len(data_of(res))}")
res = call("GET", "/system/login-logs/fail-by-ip", None, admin_token)
check("失败 IP 聚合 0 < 行数 <= 8 且有数据", 0 < len(data_of(res)) <= 8, f"count={len(data_of(res))}")

# =========================================================================== #
section("7. 密码策略")

res = call("GET", "/system/password-policy", None, admin_token)
p = data_of(res)
check("策略 code=0", code_of(res) == 0, f"code={code_of(res)}")
# 2026-10-12 按需求方要求放宽复杂度：只保留长度 ≥ 6，大小写/数字/符号一律不要求
check("minLength=6（已放宽）", p["minLength"] == 6, str(p["minLength"]))
check("requireUpper/Lower/Digit/Symbol 均为 false（已放宽）",
      all(p[k] is False for k in ("requireUpper", "requireLower", "requireDigit", "requireSymbol")), str(p))
# 放宽复杂度**不**等于关掉防暴力破解，这两项必须保留
check("maxFailedAttempts=5（保留，防暴力破解）", p["maxFailedAttempts"] == 5, str(p["maxFailedAttempts"]))
check("lockMinutes=30（保留）", p["lockMinutes"] == 30, str(p["lockMinutes"]))
check("historySize=5（保留）", p["historySize"] == 5, str(p["historySize"]))
check("expireDays=90", p["expireDays"] == 90, str(p["expireDays"]))
check("forceChangeOnFirstLogin=true", p["forceChangeOnFirstLogin"] is True, "")

# =========================================================================== #
section("8. 账号写操作")

# 放宽后：长度不足仍必须被拒（这是唯一保留的复杂度要求）
res = call("POST", "/system/users",
           {"username": "weakpwd_user", "password": "abc", "roleIds": [3], "enabled": True}, admin_token)
check("长度不足 6 位 -> 40000（长度要求仍生效）", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")
res = call("POST", "/system/users",
           {"username": "weakpwd1", "password": "12345", "roleIds": [3], "enabled": True}, admin_token)
check("恰好 5 位纯数字 -> 40000（边界）", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")

# 放宽后：纯英文/纯数字/无符号**应当被接受**（证明要求真的关掉了，不是被静默忽略）
res = call("POST", "/system/users",
           {"username": "plainpwd_user", "password": "alllowercase", "roleIds": [3], "enabled": True}, admin_token)
check("纯小写字母（12 位）-> 接受（已放宽）", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
plain_id = data_of(res)["id"] if code_of(res) == 0 else None
res = call("POST", "/system/users",
           {"username": "digits_user", "password": "123456", "roleIds": [3], "enabled": True}, admin_token)
check("纯数字 123456 -> 接受（已放宽）", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
digits_id = data_of(res)["id"] if code_of(res) == 0 else None
# 新密码能真的登录，证明存进去的是可用哈希而不是被截断/拒绝
if digits_id:
    res = call("POST", "/system/auth/login", {"username": "digits_user", "password": "123456"})
    check("纯数字密码能真的登录（策略放宽端到端生效）", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")

# 清理这两个用于验证放宽的账号
for _id in (plain_id, digits_id):
    if _id:
        call("DELETE", f"/system/users/{_id}", None, admin_token)

# 「不得与登录名相同」是代码里的固定规则，不受策略字段影响，仍须生效
res = call("POST", "/system/users",
           {"username": "sameaspwd", "password": "sameaspwd", "roleIds": [3], "enabled": True}, admin_token)
check("密码等于登录名 -> 40000（固定规则，未随复杂度一起放宽）",
      code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")

res = call("POST", "/system/users",
           {"username": "e2e_user", "displayName": "端到端测试", "password": "E2e@Test2026",
            "roleIds": [3], "enabled": True, "remark": "自动化测试创建"}, admin_token)
check("新建账号 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
new_user_id = data_of(res)["id"]
check("新建账号 roleIds=[3]", data_of(res)["roleIds"] == [3], str(data_of(res)["roleIds"]))
check("新建账号 mustChangePassword 跟随策略", data_of(res)["mustChangePassword"] is True, "")
check("新建响应不含密码字段",
      not ({k.lower() for k in data_of(res)} & {"password", "passwordhash", "passwd"}),
      str(list(data_of(res))))

res = call("POST", "/system/users",
           {"username": "e2e_user", "password": "E2e@Test2026", "roleIds": [3], "enabled": True}, admin_token)
check("重复登录名 -> 40900", code_of(res) == 40900, f"code={code_of(res)}")
res = call("POST", "/system/users",
           {"username": "bad name!", "password": "E2e@Test2026", "roleIds": [3], "enabled": True}, admin_token)
check("非法登录名 -> 40000", code_of(res) == 40000, f"code={code_of(res)}")
res = call("POST", "/system/users",
           {"username": "norole_user", "password": "E2e@Test2026", "roleIds": [], "enabled": True}, admin_token)
check("不分配角色 -> 40000", code_of(res) == 40000, f"code={code_of(res)}")
res = call("POST", "/system/users",
           {"username": "badrole_user", "password": "E2e@Test2026", "roleIds": [99999], "enabled": True}, admin_token)
check("角色不存在 -> 40000", code_of(res) == 40000, f"code={code_of(res)}")

res = call("POST", "/system/auth/login", {"username": "e2e_user", "password": "E2e@Test2026"})
check("新建账号可以真的登录", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
e2e_token = data_of(res)["token"]

res = call("PUT", f"/system/users/{new_user_id}",
           {"username": "e2e_user", "displayName": "改名了", "roleIds": [3, 4],
            "enabled": True, "remark": "更新过"}, admin_token)
check("编辑账号 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
check("角色已变为 2 个", len(data_of(res)["roleIds"]) == 2, str(data_of(res)["roleIds"]))
res = call("PUT", f"/system/users/{new_user_id}",
           {"username": "renamed_user", "displayName": "x", "roleIds": [3], "enabled": True}, admin_token)
check("改登录名 -> 40000（审计要认人）", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")

res = call("GET", "/system/users", None, e2e_token)
check("改角色后旧 token 已失效（避免界面权限与后端不一致）", code_of(res) == 40100, f"code={code_of(res)}")

res = call("POST", "/system/auth/login", {"username": "e2e_user", "password": "E2e@Test2026"})
e2e_token2 = data_of(res)["token"]
res = call("GET", "/system/users", None, e2e_token2)
check("停用前 token 可用", code_of(res) == 0, f"code={code_of(res)}")
res = call("POST", f"/system/users/{new_user_id}/enabled", {"enabled": False}, admin_token)
check("停用 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
res = call("GET", "/system/users", None, e2e_token2)
check("停用后旧 token 立即失效（只改库字段等于没停）", code_of(res) == 40100, f"code={code_of(res)}")
res = call("POST", "/system/auth/login", {"username": "e2e_user", "password": "E2e@Test2026"})
check("停用后无法登录 -> 40102", code_of(res) == 40102, f"code={code_of(res)}")

# 重新启用，后面的「重置密码」才有意义（停用状态下登录本就该被拒，
# 会把 40102 误当成「重置失败」——第一版测试就踩了这个顺序问题）
res = call("POST", f"/system/users/{new_user_id}/enabled", {"enabled": True}, admin_token)
check("重新启用 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")

res = call("POST", "/system/users/1001/enabled", {"enabled": False}, admin_token)
check("停用内置 admin -> 40900", code_of(res) == 40900, f"code={code_of(res)} msg={msg_of(res)}")
res = call("DELETE", "/system/users/1001", None, admin_token)
check("删除内置 admin -> 40900", code_of(res) == 40900, f"code={code_of(res)} msg={msg_of(res)}")

res = call("POST", "/system/users/1006/unlock", None, admin_token)
check("解锁 test_tmp code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
res = call("GET", "/system/users/1006", None, admin_token)
check("解锁后 locked=false 且 failedAttempts=0",
      data_of(res)["locked"] is False and data_of(res)["failedAttempts"] == 0, str(data_of(res)))
res = call("POST", "/system/auth/login", {"username": "test_tmp", "password": "Ops@2026"})
check("解锁后可以登录", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")

res = call("POST", f"/system/users/{new_user_id}/password",
           {"password": "Reset@2026x", "mustChange": True}, admin_token)
check("重置密码 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
res = call("POST", "/system/auth/login", {"username": "e2e_user", "password": "Reset@2026x"})
check("重置后新密码可登录", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
check("重置且强制改密 -> mustChangePassword=true", data_of(res)["mustChangePassword"] is True, "")
res = call("POST", "/system/auth/login", {"username": "e2e_user", "password": "E2e@Test2026"})
check("重置后旧密码不可登录", code_of(res) == 40101, f"code={code_of(res)}")
res = call("POST", f"/system/users/{new_user_id}/password", {"password": "Reset@2026x"}, admin_token)
check("重置为刚用过的密码 -> 40000（historySize 生效）", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")

res = call("POST", "/system/users",
           {"username": "lockme_user", "password": "Lock@2026x", "roleIds": [3], "enabled": True}, admin_token)
lock_id = data_of(res)["id"]
last_code = 0
for _ in range(5):
    lr = call("POST", "/system/auth/login", {"username": "lockme_user", "password": "definitely-wrong"})
    last_code = code_of(lr)
check("连续 5 次失败 -> 40103（达到阈值自动锁定）", last_code == 40103, f"code={last_code}")
res = call("POST", "/system/auth/login", {"username": "lockme_user", "password": "Lock@2026x"})
check("锁定后即使密码正确也拒登 -> 40103", code_of(res) == 40103, f"code={code_of(res)} msg={msg_of(res)}")
res = call("GET", f"/system/users/{lock_id}", None, admin_token)
check("锁定账号 locked=true 且 failedAttempts=5",
      data_of(res)["locked"] is True and data_of(res)["failedAttempts"] == 5, str(data_of(res)))

res = call("DELETE", f"/system/users/{new_user_id}", None, admin_token)
check("删除账号 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
res = call("GET", f"/system/users/{new_user_id}", None, admin_token)
check("删除后查不到 -> 40400", code_of(res) == 40400, f"code={code_of(res)}")
res = call("DELETE", f"/system/users/{lock_id}", None, admin_token)
check("清理锁定测试账号 code=0", code_of(res) == 0, f"code={code_of(res)}")

# =========================================================================== #
section("9. 审计导出")

res = call("POST", "/system/audit-logs/export", {"result": "failure"}, admin_token)
check("导出（有数据）code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
check("导出 total > 0", data_of(res)["total"] > 0, str(data_of(res)["total"]))
check("导出 url 是可下载的相对地址（不是 file://）",
      (data_of(res)["url"] or "").startswith("/api/"), str(data_of(res)["url"]))
check("导出 filename 以 .csv 结尾", (data_of(res)["filename"] or "").endswith(".csv"), str(data_of(res)["filename"]))

dl_path = data_of(res)["url"].replace("/api", "")
h, raw = call("GET", dl_path, None, admin_token)
if isinstance(raw, bytes):
    check("导出文件可下载（HTTP 200）", h == 200, f"http={h}")
    check("CSV 含 UTF-8 BOM（否则 Excel 中文乱码）",
          len(raw) > 3 and raw[0] == 0xEF and raw[1] == 0xBB and raw[2] == 0xBF,
          f"前3字节={list(raw[:3])}")
    check("CSV 大小合理（> 200 字节）", len(raw) > 200, f"len={len(raw)}")
    text = raw.decode("utf-8-sig")
    lines = [ln for ln in text.split("\r\n") if ln]
    check("CSV 行数 == 导出条数 + 表头", len(lines) == data_of(res)["total"] + 1,
          f"lines={len(lines)} total={data_of(res)['total']}")
    check("CSV 表头与前端约定一致", lines[0].startswith("id,时间,操作人,动作键"), lines[0][:60])
    check("CSV 未泄露密码", "Admin@2026" not in text and "Ops@2026" not in text, "含密码")
else:
    check("导出文件可下载（HTTP 200）", False, f"{h} {raw}")

res = call("POST", "/system/audit-logs/export",
           {"from": "2020-01-01 00:00:00", "to": "2020-01-02 00:00:00"}, admin_token)
check("导出（无数据）-> total=0 且不生成文件",
      code_of(res) == 0 and data_of(res)["total"] == 0 and not data_of(res)["url"], str(data_of(res)))
res = call("GET", "/system/audit-logs/export/download?file=..%2F..%2Fapplication.yml", None, admin_token)
check("下载路径穿越 -> 被拒（40000）", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")
res = call("GET", "/system/audit-logs/export/download?file=nonexistent-file-xyz.csv", None, admin_token)
check("下载不存在文件 -> 40400", code_of(res) == 40400, f"code={code_of(res)}")
res = call("POST", "/system/audit-logs/export", {}, viewer_token)
check("只读观察者导出 -> 40300", code_of(res) == 40300, f"code={code_of(res)}")

# =========================================================================== #
section("10. 修改自己的密码")

res = call("POST", "/system/auth/login", {"username": "audit01", "password": "Ops@2026"})
audit01_token = data_of(res)["token"]
res = call("POST", "/system/auth/password",
           {"oldPassword": "wrong-old", "newPassword": "NewPass@2026"}, audit01_token)
check("原密码错误 -> 40000", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")
res = call("POST", "/system/auth/password",
           {"oldPassword": "Ops@2026", "newPassword": "weak"}, audit01_token)
check("新密码不合策略 -> 40000", code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")
res = call("POST", "/system/auth/password",
           {"oldPassword": "Ops@2026", "newPassword": "NewPass@2026"}, audit01_token)
check("改密成功 code=0", code_of(res) == 0, f"code={code_of(res)} msg={msg_of(res)}")
res = call("POST", "/system/auth/password",
           {"oldPassword": "NewPass@2026", "newPassword": "Another@2026"}, audit01_token)
check("改密后旧 token 失效 -> 40100", code_of(res) == 40100, f"code={code_of(res)}")
res = call("POST", "/system/auth/login", {"username": "audit01", "password": "NewPass@2026"})
check("改密后用新密码可登录", code_of(res) == 0, f"code={code_of(res)}")
fresh_token = data_of(res)["token"]
res = call("POST", "/system/auth/password",
           {"oldPassword": "NewPass@2026", "newPassword": "Ops@2026"}, fresh_token)
# 改回「上一版密码」必须被 historySize 拦住 —— 这正是那条策略的意义，
# 若能改回去，说明历史密码表根本没起作用。
check("改回上一版密码 -> 40000（historySize 真的生效）",
      code_of(res) == 40000, f"code={code_of(res)} msg={msg_of(res)}")

# =========================================================================== #
section("11. 登出")

res = call("POST", "/system/auth/login", {"username": "yangsh", "password": "Ops@2026"})
yangsh_token = data_of(res)["token"]
res = call("GET", "/system/users", None, yangsh_token)
check("登出前 token 可用", code_of(res) == 0, f"code={code_of(res)}")
res = call("POST", "/system/auth/logout", None, yangsh_token)
check("登出 code=0", code_of(res) == 0, f"code={code_of(res)}")
res = call("GET", "/system/users", None, yangsh_token)
check("登出后 token 失效 -> 40100", code_of(res) == 40100, f"code={code_of(res)}")

# =========================================================================== #
section("12. 写操作是否留下审计")

res = call("GET", "/system/audit-logs?action=action:system-user:manage&pageSize=50", None, admin_token)
check("system-user:manage 审计存在", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
check("审计含「新建管理账号」记录",
      any("新建管理账号" in (r["message"] or "") for r in data_of(res)["list"]), "")
res = call("GET", "/system/audit-logs?action=action:system-role:manage&pageSize=50", None, admin_token)
check("system-role:manage 审计存在", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
res = call("GET", "/system/audit-logs?action=action:system-audit:export&pageSize=10", None, admin_token)
check("导出动作本身被审计（导出即离场，需留痕）", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
res = call("GET", "/system/audit-logs?result=failure&action=action:system-user:manage&pageSize=20", None, admin_token)
check("失败的写操作也被审计（失败必须留痕）", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
res = call("GET", "/system/audit-logs?action=system:login&pageSize=50", None, admin_token)
check("登录动作被审计（登记型动作）", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
allmsg = " ".join((r["message"] or "") + (r["target"] or "") for r in
                  call("GET", "/system/audit-logs?pageSize=200", None, admin_token)[1]["data"]["list"])
check("审计内容里不含任何明文密码",
      all(sec not in allmsg for sec in ("Admin@2026", "Ops@2026", "E2e@Test2026", "Reset@2026x")),
      "审计里出现了明文密码")

# =========================================================================== #
section("13. 登录日志是否记录")

res = call("GET", "/system/login-logs?username=admin&pageSize=5", None, admin_token)
check("admin 登录日志存在", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
res = call("GET", "/system/login-logs?success=false&username=no_such_user_xyz&pageSize=5", None, admin_token)
check("失败登录（不存在的账号）也被记录", data_of(res)["total"] > 0, f"total={data_of(res)['total']}")
res = call("GET", "/system/login-logs?success=false&pageSize=200", None, admin_token)
check("失败登录记录带失败原因",
      all(r["info"] for r in data_of(res)["list"]), "存在空 reason")
check("登录日志含 clientOS（User-Agent）",
      any(r["loginOS"] for r in data_of(res)["list"]), "全为空")

# =========================================================================== #
print("\n=============================================================")
print(f"  通过 {PASS} 项，失败 {FAIL} 项")
print("=============================================================")
if FAILURES:
    print("\n失败明细：")
    for item in FAILURES:
        print(f"  - {item}")
    sys.exit(1)
