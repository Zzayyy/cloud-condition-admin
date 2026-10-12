# -*- coding: utf-8 -*-
"""
前端真实构建 + Vite 代理联调验证。

验证的不是「后端自己能不能跑」，而是**前端代码经由 Vite 代理打到后端**这条完整链路：
   前端 (127.0.0.1:5199) --/api 代理--> 后端 (127.0.0.1:8000/api)

为什么要单独测：前端 http.ts 会带 `Authorization: Bearer <token>`，
而后端 sa-token 配置的是 token-name=Authorization / token-prefix=Bearer。
这两处**任何一处对不上都会 401**，而且前端只会显示「登录状态已失效」，
不会告诉你是前缀问题。这里的断言直接读「经代理返回的 code」。
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

VITE = "http://127.0.0.1:5199"
BACKEND = "http://127.0.0.1:8000/api"

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


def call(base: str, method: str, path: str, body=None, token=None):
    """返回 (status, parsed)。

    parsed 对 JSON 响应是 dict；对 CSV 这类非 JSON 响应原样返回 bytes ——
    导出接口返回的就是带 BOM 的 CSV，硬套 json.loads 会炸
    （第一版就是这么崩的，而且崩在「下载其实成功了」之后，很容易误判成下载失败）。
    """
    url = base + urllib.parse.quote(path, safe="/?&=%:.,-_")
    headers = {"Content-Type": "application/json"}
    if token:
        # 与前端 src/api/http.ts 的请求拦截器完全一致
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            try:
                return r.status, json.loads(raw.decode("utf-8"))
            except Exception:
                return r.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8"))
        except Exception:
            return e.code, raw


print("===== 1. Vite 开发服务器可访问 =====")
try:
    with urllib.request.urlopen(VITE + "/", timeout=20) as r:
        html = r.read().decode("utf-8", "replace")
    check("Vite 首页返回 200", r.status == 200, f"status={r.status}")
    check("首页是前端应用（含 root 挂载点）", 'id="app"' in html or "/src/main.ts" in html,
          html[:200])
except Exception as e:  # noqa: BLE001
    check("Vite 首页返回 200", False, f"{type(e).__name__}: {e}")
    print("\nVite 未就绪，终止")
    sys.exit(1)

print("\n===== 2. /api 代理打通后端（关键：前缀与鉴权头） =====")
status, body = call(VITE, "POST", "/api/system/auth/login",
                    {"username": "admin", "password": "Admin@2026"})
check("经 Vite 代理登录成功 code=0", isinstance(body, dict) and body.get("code") == 0,
      f"status={status} body={str(body)[:200]}")
check("代理返回的是 JSON（不是 Vite 的 HTML 兜底页）", isinstance(body, dict),
      f"type={type(body).__name__} body={str(body)[:120]}")
token = body["data"]["token"] if isinstance(body, dict) and body.get("code") == 0 else None
check("拿到 token", bool(token), str(token))

print("\n===== 3. Bearer 前缀匹配（sa-token token-prefix 配置） =====")
if token:
    status, prof = call(VITE, "GET", "/api/system/auth/profile", None, token)
    check("带 Bearer 前缀可访问受保护接口（前缀配置正确）",
          isinstance(prof, dict) and prof.get("code") == 0,
          f"status={status} body={str(prof)[:200]}")

    # 反证：不带前缀应被拒绝 —— 若也能通过，说明 prefix 配置没生效
    url = VITE + "/api/system/auth/profile"
    req = urllib.request.Request(url, headers={"Authorization": token}, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = json.loads(r.read().decode("utf-8"))
        check("不带 Bearer 前缀被拒绝（证明 prefix 真的在生效）",
              raw.get("code") == 40100, f"竟然通过了：{raw}")
    except urllib.error.HTTPError as e:
        try:
            raw = json.loads(e.read().decode("utf-8"))
        except Exception:
            raw = {}
        check("不带 Bearer 前缀被拒绝（证明 prefix 真的在生效）",
              raw.get("code") == 40100, str(raw))

print("\n===== 4. 三个页面 onMounted 的请求全部经代理成功 =====")
if token:
    page_calls = [
        ("SystemUser: listUsers", "GET", "/api/system/users?page=1&pageSize=10"),
        ("SystemUser: listRoles", "GET", "/api/system/roles"),
        ("SystemUser: getPasswordPolicy", "GET", "/api/system/password-policy"),
        ("SystemRole: listRoles", "GET", "/api/system/roles"),
        ("SystemAudit: listAuditLogs", "GET", "/api/system/audit-logs?page=1&pageSize=15"),
        ("SystemAudit: listAuditActions", "GET", "/api/system/audit-logs/actions"),
        ("SystemAudit: getAuditDaily", "GET", "/api/system/audit-logs/daily"),
        ("SystemAudit: getAuditByAction", "GET", "/api/system/audit-logs/by-action"),
        ("SystemAudit: listLoginLogs", "GET", "/api/system/login-logs?page=1&pageSize=15"),
        ("SystemAudit: getLoginDaily", "GET", "/api/system/login-logs/daily"),
        ("SystemAudit: getLoginFailByIp", "GET", "/api/system/login-logs/fail-by-ip"),
    ]
    for label, method, path in page_calls:
        status, resp = call(VITE, method, path, None, token)
        ok = isinstance(resp, dict) and resp.get("code") == 0
        check(f"经代理 {label}", ok, f"status={status} body={str(resp)[:160]}")

print("\n===== 5. 写操作经代理也走通（前端按钮真的能用） =====")
if token:
    status, created = call(VITE, "POST", "/api/system/users",
                           {"username": "proxy_e2e_user", "displayName": "代理联调",
                            "password": "Proxy@2026", "roleIds": [3], "enabled": True,
                            "remark": "经 Vite 代理创建"}, token)
    check("经代理新建账号成功", isinstance(created, dict) and created.get("code") == 0,
          f"status={status} body={str(created)[:200]}")
    new_id = created["data"]["id"] if isinstance(created, dict) and created.get("code") == 0 else None

    if new_id:
        # 新账号真的能登录（经代理）
        status, lr = call(VITE, "POST", "/api/system/auth/login",
                          {"username": "proxy_e2e_user", "password": "Proxy@2026"})
        check("经代理用新账号登录成功（密码哈希比对正确）",
              isinstance(lr, dict) and lr.get("code") == 0, str(lr)[:200])

        status, dis = call(VITE, "POST", f"/api/system/users/{new_id}/enabled",
                           {"enabled": False}, token)
        check("经代理停用成功", isinstance(dis, dict) and dis.get("code") == 0, str(dis)[:160])

        status, dele = call(VITE, "DELETE", f"/api/system/users/{new_id}", None, token)
        check("经代理删除成功", isinstance(dele, dict) and dele.get("code") == 0, str(dele)[:160])

    # 导出（前端点击「导出 CSV」）
    status, exp = call(VITE, "POST", "/api/system/audit-logs/export", {"result": "failure"}, token)
    check("经代理导出审计成功", isinstance(exp, dict) and exp.get("code") == 0, str(exp)[:200])
    if isinstance(exp, dict) and exp.get("code") == 0 and exp["data"].get("url"):
        # 前端用的是 res.url 直接下载；这里验证该 url 经代理可下载
        dl_path = exp["data"]["url"]  # 形如 /api/system/audit-logs/export/download?file=xxx
        status, raw = call(VITE, "GET", dl_path, None, token)
        check("经代理下载导出文件成功", status == 200, f"status={status}")
        check("经代理下载到的确实是 CSV（含表头）",
              isinstance(raw, bytes) and b"id," in raw[:64], str(raw[:64] if isinstance(raw, bytes) else raw))

print("\n===== 6. 前端源码里的 mock 开关已关闭（生产/联调都不该走演示数据） =====")
import os
env_dev = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "cloud-condition-admin", "frontend", ".env.development")
with open(env_dev, encoding="utf-8", errors="replace") as fh:
    content = fh.read()
check(".env.development 里 VITE_USE_MOCK 仍为 true（本次联调是靠环境变量覆盖，未改仓库配置）",
      "VITE_USE_MOCK=true" in content,
      "配置文件被改动了 —— 联调结束前请确认这是有意为之")

print("\n=============================================================")
print(f"  前端代理联调通过 {PASS} 项，失败 {FAIL} 项")
print("=============================================================")
if FAILURES:
    print("\n失败明细：")
    for item in FAILURES:
        print(f"  - {item}")
    sys.exit(1)
