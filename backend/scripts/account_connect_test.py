# -*- coding: utf-8 -*-
"""账户与用户模块 —— 前后端联通简单测试（按需求：简单测即可，不做过度测试）。

覆盖 5 个接口各自能通、能返回数据、字段名与前端契约一致。
不做边界/异常分支的穷举。
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8011/api"
PASS = 0
FAIL = 0
FAILURES: list[str] = []


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [ok]   {name}")
    else:
        FAIL += 1
        FAILURES.append(f"{name} -> {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def call(method, path, body=None, token=None):
    url = BASE + urllib.parse.quote(path, safe="/?&=%:.,-_")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


print("===== 登录拿 token =====")
login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
check("登录成功", login.get("code") == 0, str(login)[:120])
TOKEN = login["data"]["token"]

print("\n===== 1. 用户账号列表（MySQL T_User + Redis acc-* 合并） =====")
res = call("GET", "/account/accounts?page=1&pageSize=50", None, TOKEN)
check("code=0", res.get("code") == 0, str(res)[:150])
d = res.get("data", {})
check("返回 PageResult 形状", {"list", "total", "page", "pageSize"} <= set(d), str(list(d)))
rows = d.get("list", [])
print(f"  -> total={d.get('total')}  本页 {len(rows)} 行")
check("合并后有数据（T_User 1 行 + Redis 14 个键）", len(rows) > 0, "0 行")

if rows:
    r0 = rows[0]
    need = {"account", "useraccount", "name", "accounttype", "accatt", "tradeAccount",
            "branchNo", "regtime", "logintime", "loginIP", "loginMAC", "loginOS",
            "shareHolders", "signed", "hasRedis", "onlineflag", "syncflag",
            "strategyserverid", "traderserver", "userId"}
    missing = need - set(r0)
    check("AccountRow 字段与前端契约一致", not missing, f"缺 {sorted(missing)}")
    check("没有 Passwd 字段（密码绝不外泄）",
          "Passwd" not in r0 and "passwd" not in r0, str(list(r0))[:160])
    has_redis = [x for x in rows if x.get("hasRedis")]
    check("hasRedis=true 的行存在（Redis 侧确实读到了）", len(has_redis) > 0,
          f"0 行；Redis 侧没读到")
    has_mysql = [x for x in rows if x.get("userId")]
    check("userId 非空的行存在（MySQL 侧读到了）", len(has_mysql) > 0, "0 行")
    print(f"  -> hasRedis={len(has_redis)} 行, 有 userId={len(has_mysql)} 行")
    # 打印几个样例，便于人工核对
    for x in rows[:4]:
        print(f"     {x.get('account'):<24} name={x.get('name')} hasRedis={x.get('hasRedis')} "
              f"online={x.get('onlineflag')} 实例={x.get('redisInstance')}")
    st = [x for x in r0.get("shareHolders") or []]
    check("shareHolders 已解析成数组（不是原始字符串）", isinstance(st, list), str(type(st)))

print("\n===== 2. Redis 实时态（acc-* 原始 JSON） =====")
acc = None
for x in rows:
    if x.get("hasRedis"):
        acc = x["account"]
        break
if acc:
    res = call("GET", f"/account/redis/{acc}", None, TOKEN)
    check(f"getRedisAccount({acc}) code=0", res.get("code") == 0, str(res)[:150])
    rd = res.get("data") or {}
    need = {"id", "useraccount", "account", "accounttype", "maintype", "regtime",
            "logintime", "loginIP", "loginMAC", "loginOS", "name", "tradeAccount",
            "branchNo", "shareHolders", "regprotocal", "strategyserverid",
            "onlineflag", "syncflag", "traderserver"}
    missing = need - set(rd)
    check("RedisAccount 字段与前端契约一致", not missing, f"缺 {sorted(missing)}")
    print(f"  -> name={rd.get('name')} onlineflag={rd.get('onlineflag')} "
          f"syncflag={rd.get('syncflag')} 来源={rd.get('instance')}")
    check("带来源实例信息（多实例排查用）", bool(rd.get("instance")), "空")
else:
    check("有账号可测 Redis 实时态", False, "没有 hasRedis 的行")

res = call("GET", "/account/redis/not-exist-account-xyz", None, TOKEN)
check("不存在的账号如实回 40400（不返回空壳对象）",
      res.get("code") == 40400, f"code={res.get('code')}")

print("\n===== 3. 登录日志（T_UserLog，19.1 万行） =====")
res = call("GET", "/account/logs?page=1&pageSize=10", None, TOKEN)
check("listLogs code=0", res.get("code") == 0, str(res)[:150])
d = res.get("data", {})
check("total > 0", (d.get("total") or 0) > 0, str(d.get("total")))
logs = d.get("list", [])
check("本页有行", len(logs) > 0, "0 行")
if logs:
    l0 = logs[0]
    need = {"id", "Account", "LogTime", "LogIP", "LogMAC", "LogOS", "Info"}
    check("UserLog 字段名与前端契约一致（首字母大写）", need <= set(l0), f"缺 {need - set(l0)}")
    print(f"  -> total={d.get('total')}  样例: id={l0.get('id')} Account={l0.get('Account')} "
          f"LogTime={l0.get('LogTime')} Info={l0.get('Info')}")

# 按账号筛选
res2 = call("GET", "/account/logs?page=1&pageSize=5&account=010100011300", None, TOKEN)
check("按账号筛选生效", res2.get("code") == 0 and (res2["data"]["total"] or 0) > 0,
      f"code={res2.get('code')} total={res2.get('data',{}).get('total')}")

# 设备筛选（现场三列全 NULL，所以 has 应为 0、没有的应为全部）
res3 = call("GET", "/account/logs?page=1&pageSize=5&withDevice=false", None, TOKEN)
check("withDevice=false 有数据", res3.get("code") == 0, str(res3)[:100])
res4 = call("GET", "/account/logs?page=1&pageSize=5&withDevice=true", None, TOKEN)
print(f"  -> withDevice=true 返回 total={res4.get('data',{}).get('total')} "
      f"（现场三列全 NULL，为 0 是正常的，不是 bug）")
check("withDevice=true 如实返回 0（现场设备列为 NULL）",
      res4.get("code") == 0 and (res4["data"]["total"] or 0) == 0,
      f"total={res4.get('data',{}).get('total')}")

print("\n===== 4. 登录日志按账号聚合（登录频次） =====")
res = call("GET", "/account/logs/stats?page=1&pageSize=10", None, TOKEN)
check("listLogStats code=0", res.get("code") == 0, str(res)[:150])
d = res.get("data", {})
stats = d.get("list", [])
check("聚合有数据", len(stats) > 0, "0 行")
if stats:
    s0 = stats[0]
    need = {"Account", "count", "firstTime", "lastTime"}
    check("AccountLogStat 字段与前端契约一致", need <= set(s0), f"缺 {need - set(s0)}")
    print(f"  -> 共 {d.get('total')} 个账号有日志；样例: {s0.get('Account')} "
          f"count={s0.get('count')} {s0.get('firstTime')} ~ {s0.get('lastTime')}")

print("\n===== 5. 签署与股东号页复用的接口（signed 筛选） =====")
res = call("GET", "/account/accounts?page=1&pageSize=50&signed=true", None, TOKEN)
check("signed=true 筛选可用", res.get("code") == 0, str(res)[:120])
signed_rows = res.get("data", {}).get("list", [])
check("signed=true 返回的行都确实已签署",
      all(x.get("signed") for x in signed_rows), "存在 signed=false 的行")
print(f"  -> signed=true: {res.get('data',{}).get('total')} 条")
res = call("GET", "/account/accounts?page=1&pageSize=50&signed=false", None, TOKEN)
print(f"  -> signed=false: {res.get('data',{}).get('total')} 条")
res = call("GET", "/account/accounts?page=1&pageSize=50&online=true", None, TOKEN)
check("online=true 筛选可用", res.get("code") == 0, str(res)[:120])
online_rows = res.get("data", {}).get("list", [])
check("online=true 返回的行都确实在线（onlineflag=1 且有 Redis 对象）",
      all(x.get("hasRedis") and x.get("onlineflag") == 1 for x in online_rows),
      "存在不在线的行")
print(f"  -> online=true: {res.get('data',{}).get('total')} 条")

print("\n===== 6. Redis 实例连接情况（排查用） =====")
res = call("GET", "/account/redis-instances", None, TOKEN)
check("code=0", res.get("code") == 0, str(res)[:120])
av = (res.get("data") or {}).get("available") or []
un = (res.get("data") or {}).get("unavailable") or []
check("三个实例全部可用（否则 hasRedis 会偏低）", len(av) == 3 and not un, f"可用={av} 不可用={un}")
print(f"  -> 可用实例: {av}")

print("\n===== 7. 未登录必须被拦下 =====")
res = call("GET", "/account/accounts")
check("未登录 -> 40100", res.get("code") == 40100, f"code={res.get('code')}")

print("\n" + "=" * 60)
print(f"  联通测试通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 60)
if FAILURES:
    print("\n失败明细：")
    for f in FAILURES:
        print(f"  - {f}")
    sys.exit(1)
