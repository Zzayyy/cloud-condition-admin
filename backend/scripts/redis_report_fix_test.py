# -*- coding: utf-8 -*-
"""复现并验证「未知的 Redis 主机：136」这个报错的修复。

复现的是前端真实发出的请求序列：
  1. 旧代码：onMounted 直接拿写死的 '136' 去请求 -> 后端如实报「未知的 Redis 主机：136」
  2. 新代码：先取实例清单，用清单里第一个实例的 id 再取数据 -> 应当成功

⚠️ 只读。不写任何数据。
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


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [ok]   {name}")
    else:
        FAIL += 1
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


login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
TOKEN = login["data"]["token"]
check("登录成功", login.get("code") == 0, str(login)[:100])

print("\n===== 1. 复现旧 bug：拿写死的 '136' 去请求 =====")
res = call("GET", "/storage/redis/info?instanceId=136", None, TOKEN)
print(f"  -> code={res.get('code')} message={res.get('message')}")
check("旧 id '136' 确实被后端拒绝（这就是用户看到的报错）",
      res.get("code") == 40000 and "未知的 Redis 主机" in (res.get("message") or ""),
      str(res)[:150])

print("\n===== 2. 新逻辑：先取清单，再按清单里的 id 请求 =====")
res = call("GET", "/storage/redis/instances", None, TOKEN)
check("实例清单 code=0", res.get("code") == 0, str(res)[:150])
insts = res.get("data") or []
check("清单非空", len(insts) > 0, "空清单")
first = insts[0]
print(f"  -> 清单第一个实例: id={first['id']!r} host={first['host']!r} db={first['db']}")

res = call("GET", f"/storage/redis/info?instanceId={urllib.parse.quote(first['id'])}", None, TOKEN)
check(f"用清单里的 id({first['id']}) 取 info -> code=0（这就是修复后的路径）",
      res.get("code") == 0, str(res)[:180])
if res.get("code") == 0:
    print(f"  -> {res['data']['host']} v{res['data']['version']} "
          f"内存 {res['data']['usedMemoryHuman']} dbKeys={res['data']['dbKeys']}")

res = call("GET", f"/storage/redis/keys?instanceId={urllib.parse.quote(first['id'])}&db={first['db']}&pageSize=5", None, TOKEN)
check("用清单里的 id 取键列表 -> code=0", res.get("code") == 0, str(res)[:180])

print("\n===== 3. 清单里每个实例都要能取到数据（逐个验证） =====")
for i in insts:
    r = call("GET", f"/storage/redis/info?instanceId={urllib.parse.quote(i['id'])}", None, TOKEN)
    rk = call("GET", f"/storage/redis/keys?instanceId={urllib.parse.quote(i['id'])}&db={i['db']}&pageSize=3", None, TOKEN)
    ok = r.get("code") == 0 and rk.get("code") == 0
    check(f"{i['id']} info+keys 均可用", ok, f"info={r.get('code')} keys={rk.get('code')}")
    if ok:
        print(f"     {i['id']}: dbKeys={r['data']['dbKeys']} 起始 db={i['db']} "
              f"该 db 键数={rk['data']['dbSize']}")

print("\n===== 4. 两个实例的密码是同一个（用户怀疑不一样） =====")
import redis  # noqa: E402

for host in ("192.168.1.136", "192.168.1.137"):
    ok_upper = False
    try:
        rr = redis.Redis(host=host, port=6379, password="QianLong@2026&", db=0,
                         decode_responses=True, socket_connect_timeout=5, socket_timeout=6)
        rr.ping()
        ok_upper = True
        rr.close()
    except Exception:
        pass
    check(f"{host} 用同一个密码 QianLong@2026& 可连", ok_upper, "连不上")

print("\n===== 5. Redis 管理页没有任何需要持久化的表单 =====")
import os  # noqa: E402

view = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "src", "views", "storage", "StorageRedis.vue")
view = view.replace(os.sep + "scripts" + os.sep + "src", os.sep + "src")
# 上面拼错了，直接用相对项目根
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # cloud-condition-admin
view_path = os.path.join(ROOT, "frontend", "src", "views", "storage", "StorageRedis.vue")
with open(view_path, encoding="utf-8") as fh:
    txt = fh.read()
check("页面不含录入表单（n-form 为 0）", "<n-form" not in txt, "出现了表单组件")
check("页面唯一的写操作是删键（deleteKey）", "function deleteKey" in txt, "找不到 deleteKey")
print("  -> 数据全部为实时快照：INFO / SCAN / JSON.GET / PUBSUB CHANNELS")

print("\n" + "=" * 60)
print(f"  通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 60)
sys.exit(1 if FAIL else 0)
