# -*- coding: utf-8 -*-
"""
前后端契约严格校验。

逐字段比对「后端实际返回」与「前端 src/api/domain.ts 里声明的接口」。
E2E 测的是行为，本脚本测的是**形状** —— 字段名写错、少字段、类型不对，
TypeScript 在运行期不会报错，页面只会静默显示空白/undefined，很难查。

用法：python contract_test.py
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


def keys_of(obj) -> set[str]:
    return set(obj.keys())


def expect_fields(where: str, obj: dict, required: set[str]) -> None:
    """断言 obj 恰好包含（或至少包含）required 里的字段，并报告缺失/多余。"""
    actual = keys_of(obj)
    missing = required - actual
    extra = actual - required
    if missing:
        check(f"{where} 字段完整", False, f"缺少 {sorted(missing)}；实际 {sorted(actual)}")
    elif extra:
        # 多余字段不算错误（前端会忽略），但列出来便于核对契约是否漂移
        check(f"{where} 字段完整（多出 {sorted(extra)}）", True)
    else:
        check(f"{where} 字段与契约逐一对齐", True)


def typed(where: str, obj: dict, spec: dict[str, tuple]) -> None:
    """逐字段校验 Python 类型（前端 TS 类型在运行期无人校验）。"""
    for field, allowed in spec.items():
        if field not in obj:
            check(f"{where}.{field} 存在", False, "字段缺失")
            continue
        value = obj[field]
        ok = value is None or isinstance(value, allowed)
        check(f"{where}.{field} 类型为 {[a.__name__ for a in allowed]}",
              ok, f"实际 {type(value).__name__}={value!r}")


# 登录拿 token
login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
TOKEN = login["data"]["token"]

print("===== LoginResult（前端 authApi.login） =====")
expect_fields("LoginResult", login["data"],
              {"token", "username", "displayName", "roles", "permissions", "mustChangePassword"})
typed("LoginResult", login["data"], {
    "token": (str,), "username": (str,), "displayName": (str,),
    "roles": (list,), "permissions": (list,), "mustChangePassword": (bool,),
})

print("\n===== ProfileResult（前端 authApi.profile） =====")
prof = call("GET", "/system/auth/profile", None, TOKEN)
expect_fields("ProfileResult", prof["data"],
              {"username", "displayName", "roles", "permissions", "mustChangePassword"})
typed("ProfileResult", prof["data"], {
    "username": (str,), "displayName": (str,),
    "roles": (list,), "permissions": (list,), "mustChangePassword": (bool,),
})

print("\n===== PageResult<AdminUser>（前端 adminApi.listUsers） =====")
users = call("GET", "/system/users?page=1&pageSize=10", None, TOKEN)
expect_fields("PageResult", users["data"], {"list", "total", "page", "pageSize"})
typed("PageResult", users["data"], {
    "list": (list,), "total": (int,), "page": (int,), "pageSize": (int,),
})
# AdminUser 契约（src/api/domain.ts）
ADMIN_USER_FIELDS = {
    "id", "username", "displayName", "roleIds", "enabled", "locked",
    "failedAttempts", "mustChangePassword", "lastLoginTime", "lastLoginIP",
    "loginCount", "createdAt", "updatedAt", "remark",
}
row = users["data"]["list"][0]
expect_fields("AdminUser", row, ADMIN_USER_FIELDS)
typed("AdminUser", row, {
    "id": (int,), "username": (str,), "displayName": (str,), "roleIds": (list,),
    "enabled": (bool,), "locked": (bool,), "failedAttempts": (int,),
    "mustChangePassword": (bool,), "lastLoginTime": (str,), "lastLoginIP": (str,),
    "loginCount": (int,), "createdAt": (str,), "updatedAt": (str,), "remark": (str,),
})
check("AdminUser.roleIds 元素为 int",
      all(isinstance(x, int) for x in row["roleIds"]), str(row["roleIds"]))

print("\n===== AdminRole[]（前端 adminApi.listRoles） =====")
roles = call("GET", "/system/roles", None, TOKEN)
check("返回数组（不分页，前端直接当数组用）", isinstance(roles["data"], list), type(roles["data"]).__name__)
ADMIN_ROLE_FIELDS = {"id", "code", "name", "desc", "builtin", "permissions", "userCount", "createdAt", "updatedAt"}
r0 = roles["data"][0]
expect_fields("AdminRole", r0, ADMIN_ROLE_FIELDS)
typed("AdminRole", r0, {
    "id": (int,), "code": (str,), "name": (str,), "desc": (str,), "builtin": (bool,),
    "permissions": (list,), "userCount": (int,), "createdAt": (str,), "updatedAt": (str,),
})
check("AdminRole.permissions 元素为 str",
      all(isinstance(x, str) for x in r0["permissions"]), str(r0["permissions"]))
check("desc 字段名正确（不是 description）", "desc" in r0 and "description" not in r0, str(sorted(r0)))

print("\n===== PageResult<AuditLog>（前端 adminApi.listAuditLogs） =====")
audit = call("GET", "/system/audit-logs?page=1&pageSize=15", None, TOKEN)
expect_fields("PageResult", audit["data"], {"list", "total", "page", "pageSize"})
AUDIT_LOG_FIELDS = {
    "id", "operator", "action", "actionLabel", "module", "level",
    "target", "result", "message", "ip", "costMs", "createdAt",
}
a0 = audit["data"]["list"][0]
expect_fields("AuditLog", a0, AUDIT_LOG_FIELDS)
typed("AuditLog", a0, {
    "id": (int,), "operator": (str,), "action": (str,), "actionLabel": (str,),
    "module": (str,), "level": (str,), "target": (str,), "result": (str,),
    "message": (str,), "ip": (str,), "costMs": (int,), "createdAt": (str,),
})
check("AuditLog.level ∈ {high,medium,low}",
      a0["level"] in ("high", "medium", "low"), str(a0["level"]))
check("AuditLog.result ∈ {success,failure}",
      a0["result"] in ("success", "failure"), str(a0["result"]))

print("\n===== AuditActionOption[]（前端 adminApi.listAuditActions） =====")
actions = call("GET", "/system/audit-logs/actions", None, TOKEN)
check("返回数组", isinstance(actions["data"], list), type(actions["data"]).__name__)
expect_fields("AuditActionOption", actions["data"][0], {"action", "label", "module", "level"})
typed("AuditActionOption", actions["data"][0], {
    "action": (str,), "label": (str,), "module": (str,), "level": (str,),
})

print("\n===== AuditDailyRow[]（前端 adminApi.getAuditDaily） =====")
daily = call("GET", "/system/audit-logs/daily", None, TOKEN)
check("返回数组", isinstance(daily["data"], list), type(daily["data"]).__name__)
expect_fields("AuditDailyRow", daily["data"][0], {"date", "high", "other"})
typed("AuditDailyRow", daily["data"][0], {"date": (str,), "high": (int,), "other": (int,)})

print("\n===== NameValueRow[]（前端 adminApi.getAuditByAction） =====")
byaction = call("GET", "/system/audit-logs/by-action", None, TOKEN)
check("返回数组", isinstance(byaction["data"], list), type(byaction["data"]).__name__)
expect_fields("NameValueRow", byaction["data"][0], {"name", "value"})
typed("NameValueRow", byaction["data"][0], {"name": (str,), "value": (int,)})

print("\n===== PageResult<AdminLoginLog>（前端 adminApi.listLoginLogs） =====")
logs = call("GET", "/system/login-logs?page=1&pageSize=15", None, TOKEN)
expect_fields("PageResult", logs["data"], {"list", "total", "page", "pageSize"})
LOGIN_LOG_FIELDS = {"id", "username", "loginTime", "loginIP", "loginOS", "success", "info"}
l0 = logs["data"]["list"][0]
expect_fields("AdminLoginLog", l0, LOGIN_LOG_FIELDS)
typed("AdminLoginLog", l0, {
    "id": (int,), "username": (str,), "loginTime": (str,), "loginIP": (str,),
    "loginOS": (str,), "success": (bool,), "info": (str,),
})
check("AdminLoginLog.success 是布尔（前端直接当布尔用）",
      isinstance(l0["success"], bool), type(l0["success"]).__name__)

print("\n===== LoginDailyRow[]（前端 adminApi.getLoginDaily） =====")
ldaily = call("GET", "/system/login-logs/daily", None, TOKEN)
check("返回数组", isinstance(ldaily["data"], list), type(ldaily["data"]).__name__)
expect_fields("LoginDailyRow", ldaily["data"][0], {"date", "success", "failure"})
typed("LoginDailyRow", ldaily["data"][0], {"date": (str,), "success": (int,), "failure": (int,)})

print("\n===== NameValueRow[]（前端 adminApi.getLoginFailByIp） =====")
failip = call("GET", "/system/login-logs/fail-by-ip", None, TOKEN)
check("返回数组", isinstance(failip["data"], list), type(failip["data"]).__name__)
expect_fields("NameValueRow", failip["data"][0], {"name", "value"})

print("\n===== PasswordPolicy（前端 adminApi.getPasswordPolicy） =====")
policy = call("GET", "/system/password-policy", None, TOKEN)
POLICY_FIELDS = {
    "minLength", "requireUpper", "requireLower", "requireDigit", "requireSymbol",
    "maxFailedAttempts", "lockMinutes", "expireDays", "forceChangeOnFirstLogin", "historySize",
}
expect_fields("PasswordPolicy", policy["data"], POLICY_FIELDS)
typed("PasswordPolicy", policy["data"], {
    "minLength": (int,), "requireUpper": (bool,), "requireLower": (bool,),
    "requireDigit": (bool,), "requireSymbol": (bool,), "maxFailedAttempts": (int,),
    "lockMinutes": (int,), "expireDays": (int,), "forceChangeOnFirstLogin": (bool,),
    "historySize": (int,),
})

print("\n===== exportAuditLogs 返回形状 =====")
exp = call("POST", "/system/audit-logs/export", {"result": "failure"}, TOKEN)
check("成功且 data 含 url/filename/total",
      exp["code"] == 0 and {"url", "filename", "total"} <= keys_of(exp["data"]), str(exp["data"]))
typed("ExportResult", exp["data"], {"url": (str,), "filename": (str,), "total": (int,)})

print("\n===== 统一响应信封（src/api/types.ts 的 ApiEnvelope） =====")
for name, resp in (("listUsers", users), ("listRoles", roles), ("listAuditLogs", audit),
                   ("getLoginDaily", ldaily), ("getPasswordPolicy", policy)):
    expect_fields(f"ApiEnvelope[{name}]", resp, {"code", "message", "data"})
    check(f"ApiEnvelope[{name}].code == 0", resp["code"] == 0, str(resp["code"]))
    check(f"ApiEnvelope[{name}].message 为 str", isinstance(resp["message"], str), type(resp["message"]).__name__)

print("\n===== 时间格式（前端按字符串比较区间，必须定宽） =====")
import re
pat = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
for label, value in (("AdminUser.createdAt", row["createdAt"]),
                     ("AdminUser.updatedAt", row["updatedAt"]),
                     ("AuditLog.createdAt", a0["createdAt"]),
                     ("AdminLoginLog.loginTime", l0["loginTime"]),
                     ("AdminRole.createdAt", r0["createdAt"])):
    check(f"{label} 形如 yyyy-MM-dd HH:mm:ss", bool(pat.match(value or "")), repr(value))
daypat = re.compile(r"^\d{4}-\d{2}-\d{2}$")
for label, value in (("AuditDailyRow.date", daily["data"][0]["date"]),
                     ("LoginDailyRow.date", ldaily["data"][0]["date"])):
    check(f"{label} 形如 yyyy-MM-dd", bool(daypat.match(value or "")), repr(value))

print("\n===== 前端会传但容易被忽略的参数（逐一确认真的生效） =====")
# 前端 listUsers 传 roleId（数字）与 status（字符串）
res = call("GET", "/system/users?roleId=2&pageSize=50", None, TOKEN)
check("roleId 生效（ops 角色 3 人）", res["data"]["total"] == 3, f"total={res['data']['total']}")
# 前端 listAuditLogs 传 sortBy=createdAt & sortOrder=desc
res = call("GET", "/system/audit-logs?sortBy=createdAt&sortOrder=desc&pageSize=5", None, TOKEN)
dates = [r["createdAt"] for r in res["data"]["list"]]
check("sortBy=createdAt&sortOrder=desc 生效（时间倒序）",
      dates == sorted(dates, reverse=True), str(dates))
res = call("GET", "/system/audit-logs?sortBy=createdAt&sortOrder=asc&pageSize=5", None, TOKEN)
dates = [r["createdAt"] for r in res["data"]["list"]]
check("sortOrder=asc 生效（时间正序）", dates == sorted(dates), str(dates))
# 前端 listLoginLogs 传 sortBy=loginTime
res = call("GET", "/system/login-logs?sortBy=loginTime&sortOrder=desc&pageSize=5", None, TOKEN)
dates = [r["loginTime"] for r in res["data"]["list"]]
check("登录日志按 loginTime 排序生效", dates == sorted(dates, reverse=True), str(dates))
# 前端 listUsers 传 sortBy=username
res = call("GET", "/system/users?sortBy=username&sortOrder=asc&pageSize=50", None, TOKEN)
names = [r["username"] for r in res["data"]["list"]]
check("用户按 username 排序生效", names == sorted(names), str(names))

print("\n===== 路径与前端 endpoints.ts 完全一致 =====")
for method, path in (("GET", "/system/users"), ("GET", "/system/roles"),
                     ("GET", "/system/audit-logs"), ("GET", "/system/audit-logs/actions"),
                     ("GET", "/system/audit-logs/daily"), ("GET", "/system/audit-logs/by-action"),
                     ("GET", "/system/login-logs"), ("GET", "/system/login-logs/daily"),
                     ("GET", "/system/login-logs/fail-by-ip"), ("GET", "/system/password-policy"),
                     ("GET", "/system/auth/profile")):
    r = call(method, path, None, TOKEN)
    check(f"{method} {path} 可达且 code=0", r.get("code") == 0, str(r.get("code")))

print("\n=============================================================")
print(f"  契约校验通过 {PASS} 项，失败 {FAIL} 项")
print("=============================================================")
if FAILURES:
    print("\n失败明细：")
    for item in FAILURES:
        print(f"  - {item}")
    sys.exit(1)
