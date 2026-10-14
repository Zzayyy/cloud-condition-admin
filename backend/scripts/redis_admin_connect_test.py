# -*- coding: utf-8 -*-
"""Redis 管理 —— 前后端联通简单测试（按需求：简单测即可，不做过度测试）。

⚠️ 本测试**只读**，唯一的写操作是「删除一个自己临时创建的测试键」，
   并且用完即删、不碰现场任何已有键。

重点验证三条安全线与最容易误读的地方：
  1. 实例清单来自配置（不是 mock 硬编码的 136/137）；
  2. 键数 ≠ 条目数（137 db0 只有 117 键，但某个 stream 有 1400 万条）；
  3. SCAN 有硬上限且如实返回 truncated；不用 KEYS。
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

import redis

BASE = "http://127.0.0.1:8011/api"
REDIS_PWD = "QianLong@2026&"
HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.dirname(HERE)
FRONTEND = os.path.join(os.path.dirname(BACKEND), "frontend")

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
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = r.read()
            try:
                return json.loads(raw.decode())
            except Exception:
                return {"code": -1, "raw": raw[:200]}
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def fe_fields(path, iface):
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(rf"export interface {iface} \{{(.*?)\n\}}", src, re.DOTALL)
    if not m:
        return []
    out = []
    for line in m.group(1).split("\n"):
        line = re.sub(r"/\*.*?\*/", "", line).strip()
        if not line or line.startswith(("//", "*", "/*")):
            continue
        m2 = re.match(r"^(\w+)\s*[?:]", line)
        if m2:
            out.append(m2.group(1))
    return out


def dto_fields(path, record):
    """从 Java record 里抽字段名。

    ⚠️ 必须先把泛型里的逗号「保护」起来：像
    `List<DbKeyCount> dbKeys` 或 `Map<String, String> fields` 这种声明，
    泛型内部的逗号会被误当成字段分隔符，把 `String` 之类切出来当字段名。
    第一版就是因此把 RedisInstanceView / RedisPubSubChannelView 的字段数算错。
    """
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(rf"public record {record}\((.*?)\)\s*\{{", src, re.DOTALL)
    if not m:
        return []
    body = m.group(1)
    # 去掉块注释与行注释
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)
    body = re.sub(r"//[^\n]*", "", body)
    # 把尖括号里的逗号换成占位符，避免被当成分隔符
    out_chars = []
    depth = 0
    for ch in body:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth = max(0, depth - 1)
        if ch == "," and depth > 0:
            out_chars.append("\u0000")
        else:
            out_chars.append(ch)
    body = "".join(out_chars)

    names = []
    for part in body.split(","):
        part = part.replace("\u0000", ",").strip()
        if not part:
            continue
        # 取最后一个标识符作为字段名（形如 `List<DbKeyCount> dbKeys` -> dbKeys）
        m2 = re.match(r"^[A-Za-z_][\w<>,\s\[\]\.\u0000]*?(\w+)$", part)
        if m2:
            names.append(m2.group(1))
    return names


print("===== 1. 登录 =====")
login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
check("登录成功", login.get("code") == 0, str(login)[:120])
TOKEN = login["data"]["token"]

print("\n===== 2. 实例清单（按主机去重，来自配置而非 mock） =====")
res = call("GET", "/storage/redis/instances", None, TOKEN)
check("code=0", res.get("code") == 0, str(res)[:180])
insts = res.get("data") or []
check("返回实例清单", len(insts) > 0, f"实际 {len(insts)}")
need = {"id", "name", "host", "port", "db"}
if insts:
    check("RedisInstance 字段与前端契约一致", need <= set(insts[0]), f"缺 {need - set(insts[0])}")
    print(f"  -> {[(i['id'], i['host'], i['db']) for i in insts]}")
check("实例清单是「按主机去重」（136 只出现一次，不是 db0/db1 各一条）",
      len([i for i in insts if i["host"] == "192.168.1.136"]) == 1,
      str([(i["host"], i["db"]) for i in insts]))
check("包含 137（它的 db0 有 1400 万条条目的 stream）",
      any(i["host"] == "192.168.1.137" for i in insts),
      str([i["host"] for i in insts]))

print("\n===== 3. 实例概览（INFO） =====")
res = call("GET", "/storage/redis/info?instanceId=192.168.1.136", None, TOKEN)
check("136 info code=0", res.get("code") == 0, str(res)[:200])
d = res.get("data") or {}
need = {"host", "port", "version", "mode", "os", "uptimeInSeconds", "connectedClients",
        "usedMemory", "usedMemoryHuman", "usedMemoryPeakHuman", "maxmemory",
        "maxmemoryPolicy", "totalCommandsProcessed", "keyspaceHits", "keyspaceMisses", "dbKeys"}
check("RedisInstanceInfo 字段与前端契约一致", need <= set(d), f"缺 {need - set(d)}")
check("version 是 8.x", str(d.get("version", "")).startswith("8."), str(d.get("version")))
check("maxmemory=0（现场无上限，页面要能看出来）", d.get("maxmemory") == 0, str(d.get("maxmemory")))
check("dbKeys 是「键数」口径（不是条目数）",
      all("db" in x and "keys" in x for x in (d.get("dbKeys") or [])), str(d.get("dbKeys")))
print(f"  -> v{d.get('version')} {d.get('mode')} 内存 {d.get('usedMemoryHuman')} "
      f"(峰值 {d.get('usedMemoryPeakHuman')}) maxmemory={d.get('maxmemory')} "
      f"{d.get('maxmemoryPolicy')}")
print(f"  -> dbKeys(db 键数) = {d.get('dbKeys')}")

print("\n===== 4. 关键：键数 ≠ 条目数（本页最容易误读的地方） =====")
res = call("GET", "/storage/redis/info?instanceId=192.168.1.137", None, TOKEN)
info137 = res.get("data") or {}
db0keys = next((x["keys"] for x in (info137.get("dbKeys") or []) if x["db"] == 0), None)
r = redis.Redis(host="192.168.1.137", port=6379, password=REDIS_PWD, db=0,
                decode_responses=True, socket_timeout=15)
xlen = r.xlen("DataHub_req_stream")
print(f"  -> 137 db0: dbKeys(键数)={db0keys}  但 DataHub_req_stream 的 XLEN={xlen:,}")
check("137 db0 的 dbKeys 能取到（证明管理页能访问该 db，尽管它不在业务配置里）",
      db0keys is not None, f"dbKeys={info137.get('dbKeys')}")
check("键数与条目数是两个数量级（证明接口没把两者混淆）",
      db0keys is not None and xlen > db0keys * 1000,
      f"keys={db0keys} xlen={xlen}")

print("\n===== 5. 扫描键（SCAN + 硬上限 + 如实截断） =====")
res = call("GET", "/storage/redis/keys?instanceId=192.168.1.136&db=1&page=1&pageSize=15", None, TOKEN)
check("scan code=0", res.get("code") == 0, str(res)[:200])
s = res.get("data") or {}
check("返回 list/total/page/pageSize", {"list", "total", "page", "pageSize"} <= set(s), str(list(s)))
check("有键", len(s.get("list") or []) > 0, "0 个")
if s.get("list"):
    k0 = s["list"][0]
    need = {"key", "type", "pttl", "domain"}
    check("RedisKeyInfo 字段与前端契约一致", need <= set(k0), f"缺 {need - set(k0)}")
    check("type 是真实类型（不是 unknown）", k0["type"] not in (None, "", "unknown"), str(k0["type"]))
    check("pttl 是数字（-1 永不过期 / -2 不存在）", isinstance(k0["pttl"], int), str(k0["pttl"]))
    print(f"  -> {len(s['list'])} 个：{[ (x['key'], x['type']) for x in s['list'][:3] ]}")
check("返回 dbSize（该 db 真实键数）", isinstance(s.get("dbSize"), int), str(s.get("dbSize")))
check("返回 truncated 标志（不静默截断）", "truncated" in s, str(sorted(s)))
print(f"  -> total={s.get('total')} dbSize={s.get('dbSize')} truncated={s.get('truncated')} "
      f"scanLimit={s.get('scanLimit')}")

print("\n===== 6. 筛选（type / keyword / hasTtl） =====")
res = call("GET", "/storage/redis/keys?instanceId=192.168.1.136&db=1&type=ReJSON-RL&pageSize=50", None, TOKEN)
check("按 type 筛选 code=0", res.get("code") == 0, str(res)[:150])
if res.get("code") == 0:
    bad = [x for x in res["data"]["list"] if x["type"] != "ReJSON-RL"]
    check("type 筛选生效（返回的 type 都对）", not bad, f"{len(bad)} 个不符")
    print(f"  -> ReJSON-RL: {res['data']['total']} 个")
res = call("GET", "/storage/redis/keys?instanceId=192.168.1.136&db=1&keyword=acc-&pageSize=50", None, TOKEN)
check("keyword 筛选生效",
      res.get("code") == 0 and all("acc-" in x["key"] for x in res["data"]["list"]),
      str(res)[:120])
print(f"  -> keyword=acc-: {res['data']['total']} 个")
res = call("GET", "/storage/redis/keys?instanceId=192.168.1.136&db=1&hasTtl=false&pageSize=50", None, TOKEN)
check("hasTtl=false 筛选生效（全部 pttl<0）",
      res.get("code") == 0 and all(x["pttl"] < 0 for x in res["data"]["list"]), str(res)[:120])

print("\n===== 7. 键详情（按类型返回不同结构） =====")
res = call("GET", "/storage/redis/keys?instanceId=192.168.1.136&db=1&type=ReJSON-RL&pageSize=50", None, TOKEN)
json_key = res["data"]["list"][0]["key"] if res["data"]["list"] else None
if json_key:
    res = call("GET", f"/storage/redis/key?instanceId=192.168.1.136&db=1&key={urllib.parse.quote(json_key)}", None, TOKEN)
    check("ReJSON 键详情 code=0", res.get("code") == 0, str(res)[:200])
    kd = res.get("data") or {}
    need = {"key", "type", "pttl", "length", "value", "json", "entries", "members",
            "memoryUsage", "encoding"}
    check("RedisKeyDetail 字段与前端契约一致", need <= set(kd), f"缺 {need - set(kd)}")
    check("ReJSON 返回 json 字段（不是 value）", bool(kd.get("json")), f"json={str(kd.get('json'))[:60]}")
    check("json 是可解析的 JSON", (lambda: (json.loads(kd["json"]), True)[1])() if kd.get("json") else False, "解析失败")
    print(f"  -> key={kd.get('key')} type={kd.get('type')} encoding={kd.get('encoding')} "
          f"memoryUsage={kd.get('memoryUsage')}")
    print(f"     json 前 80 字: {str(kd.get('json'))[:80]}")

# stream 详情：验证只取少量条目（现场该 stream 有 1400 万条）
res = call("GET", "/storage/redis/keys?instanceId=192.168.1.137&db=0&type=stream&pageSize=50", None, TOKEN)
stream_key = None
for x in (res.get("data") or {}).get("list") or []:
    if x["key"] == "DataHub_req_stream":
        stream_key = x["key"]
        break
if stream_key:
    res = call("GET", f"/storage/redis/key?instanceId=192.168.1.137&db=0&key={urllib.parse.quote(stream_key)}", None, TOKEN)
    check("stream 详情 code=0", res.get("code") == 0, str(res)[:200])
    kd = res.get("data") or {}
    check("stream 的 length 是真实条目数(百万级)",
          isinstance(kd.get("length"), int) and kd["length"] > 1_000_000, str(kd.get("length")))
    check("**只返回少量** entries（不把 1400 万条拉回来）",
          isinstance(kd.get("entries"), list) and len(kd["entries"]) <= 20,
          f"entries={len(kd.get('entries') or [])}")
    print(f"  -> DataHub_req_stream length={kd.get('length'):,} "
          f"entries 返回 {len(kd.get('entries') or [])} 条")

res = call("GET", "/storage/redis/key?instanceId=192.168.1.136&db=1&key=绝对不存在的键xyz", None, TOKEN)
check("不存在的键 -> 40400（不返回空壳）", res.get("code") == 40400, f"code={res.get('code')}")
res = call("GET", "/storage/redis/key?instanceId=192.168.1.136&db=99&key=x", None, TOKEN)
check("非法 db -> 40000", res.get("code") == 40000, f"code={res.get('code')}")
res = call("GET", "/storage/redis/info?instanceId=10.0.0.99", None, TOKEN)
check("未知实例 -> 40000（不静默返回空）", res.get("code") == 40000, f"code={res.get('code')}")

print("\n===== 8. Pub/Sub 频道 =====")
res = call("GET", "/storage/redis/channels?instanceId=192.168.1.136", None, TOKEN)
check("136-0 channels code=0", res.get("code") == 0, str(res)[:180])
ch = res.get("data") or []
check("频道带订阅者数（判断『有没有人在听』）",
      all("subscribers" in x for x in ch), str(ch[:2]))
print(f"  -> 136 有 {len(ch)} 个频道：{[(x['name'], x['subscribers']) for x in ch[:4]]}")
res = call("GET", "/storage/redis/channels?instanceId=192.168.1.137", None, TOKEN)
check("137-0 channels code=0", res.get("code") == 0, str(res)[:150])
print(f"  -> 137 有 {len(res.get('data') or [])} 个频道（现场为 0，如实返回）")

print("\n===== 9. 删除键（高危：权限 + 审计 + 真实结果） =====")
# 自建一个临时键来删，不碰现场任何已有键
tmp = redis.Redis(host="192.168.1.136", port=6379, password=REDIS_PWD, db=0,
                  decode_responses=True, socket_timeout=10)
TEST_KEY = "__dsh_test_delete_me__"
tmp.set(TEST_KEY, "temp")
check("测试键已创建", tmp.exists(TEST_KEY) == 1, "创建失败")
try:
    res = call("DELETE", f"/storage/redis/key?instanceId=192.168.1.136&db=0&key={TEST_KEY}", None, TOKEN)
    check("删除 code=0", res.get("code") == 0, str(res)[:200])
    check("返回实际删除键数=1（真实结果，不是假装成功）", res.get("data") == 1, str(res.get("data")))
    check("键确实被删掉了", tmp.exists(TEST_KEY) == 0, "键还在")
    # 再删一次：应如实返回 0，而不是假装成功
    res = call("DELETE", f"/storage/redis/key?instanceId=192.168.1.136&db=0&key={TEST_KEY}", None, TOKEN)
    check("重复删除 -> 返回 0（如实告知键不存在）", res.get("code") == 0 and res.get("data") == 0,
          str(res.get("data")))
    # 审计：删除动作必须留痕
    res = call("GET", "/system/audit-logs?action=action:storage-redis:delete-key&pageSize=5", None, TOKEN)
    check("删除动作已写审计", res.get("code") == 0 and res["data"]["total"] > 0,
          str(res.get("data", {}).get("total")))
    if res.get("code") == 0 and res["data"]["list"]:
        a0 = res["data"]["list"][0]
        check("审计里记录了目标键名", TEST_KEY in (a0.get("target") or ""), str(a0.get("target")))
        print(f"  -> 审计: {a0.get('operator')} {a0.get('action')} target={a0.get('target')} "
              f"result={a0.get('result')}")
finally:
    tmp.delete(TEST_KEY)
    tmp.close()

print("\n===== 10. 权限：删除键需要专门权限点 =====")
res = call("POST", "/system/auth/login", {"username": "zhangguochang", "password": "View@2026"})
viewer = res["data"]["token"]
res = call("DELETE", f"/storage/redis/key?instanceId=192.168.1.136&db=0&key={TEST_KEY}", None, viewer)
check("只读账号删除键 -> 40300（前端置灰不是安全边界）", res.get("code") == 40300, f"code={res.get('code')}")
res = call("GET", "/storage/redis/keys?instanceId=192.168.1.136&db=1", None, viewer)
check("只读账号可以看键列表（读不要求删除权限）", res.get("code") == 0, f"code={res.get('code')}")

print("\n===== 11. 静态核对：后端 DTO == 前端接口 =====")
dto_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                        "admin", "dto", "RedisAdminDtos.java")
dom_path = os.path.join(FRONTEND, "src", "api", "domain.ts")
for rec, iface in (("RedisInstanceView", "RedisInstance"),
                   ("RedisInstanceInfoView", "RedisInstanceInfo"),
                   ("RedisKeyInfoView", "RedisKeyInfo"),
                   ("RedisKeyDetailView", "RedisKeyDetail"),
                   ("RedisPubSubChannelView", "RedisPubSubChannel")):
    d = dto_fields(dto_path, rec)
    f = fe_fields(dom_path, iface)
    missing = [x for x in f if x not in d]
    extra = [x for x in d if x not in f]
    check(f"{rec} 覆盖 {iface} 全部字段", not missing, f"缺 {missing}")
    if rec == "RedisKeyDetailView":
        # 详情额外带 instance / db：这是**有意**的，前端会忽略多余字段，
        # 但排查时要能知道「这条详情是从哪个实例/db 读到的」。
        # 所以只允许这两个已知的多余字段，出现别的仍然报错。
        unexpected = [x for x in extra if x not in ("instance", "db")]
        check(f"{rec} 只允许 instance/db 这两个额外字段", not unexpected, f"多 {unexpected}")
    else:
        check(f"{rec} 没有多出 {iface} 不认识的字段", not extra, f"多 {extra}")
    print(f"  {rec}({len(d)}) vs {iface}({len(f)})")

print("\n===== 12. 前端不再从 mock 取实例与类型分布 =====")
view_path = os.path.join(FRONTEND, "src", "views", "storage", "StorageRedis.vue")
with open(view_path, encoding="utf-8") as fh:
    view = fh.read()
check("实例清单调用了 listRedisInstances", "listRedisInstances" in view, "仍用 mock 的 REDIS_INSTANCES")


def strip_comments(src: str) -> str:
    """去掉块注释与行注释。

    ⚠️ 必须处理**块注释**：`switchInstance` 的改动说明写在 `/* ... */` 里、
    引用了旧的 `mockStorageApi.getRedisInstanceInfo(...)` 写法。
    只按「行首是否 /*」过滤是不够的 —— 注释块中间的行不以 /* 开头，
    会被当成代码。第一版就是这样误报了一条。
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


code_only = strip_comments(view)
check("非注释代码里不再调用 mockStorageApi.typeDistribution",
      "mockStorageApi.typeDistribution" not in code_only, "仍在用 mock 画类型分布图")

# ⚠️ 这里必须**只看 switchInstance 函数体**，不能全文搜
# `mockStorageApi.getRedisInstanceInfo` —— useResource 的 `mock:` 回调
# 里有一处是**合法**的（演示态的兜底数据），全文搜必然误报。
# 第一版就是这么误报的。
m_switch = re.search(r"function switchInstance\([^)]*\)\s*\{(.*?)\n\}", code_only, re.DOTALL)
check("找到 switchInstance 函数体", m_switch is not None, "正则没匹配到")
if m_switch:
    body = m_switch.group(1)
    check("switchInstance 不再用 mock 取 db（真实态不读 mock）",
          "mockStorageApi" not in body, f"函数体里仍有 mock：{body.strip()[:120]}")
    # ⚠️ 这里断言的是**用真实配置里的 db**（instancesData），
    # 而**不是** infoData —— infoData 是**上一个实例**的 info，
    # 用它复位会把上一个实例的 db 带到新实例上（那是修掉的第二个 bug）。
    check("switchInstance 用真实实例配置取起始 db",
          "instancesData" in body, f"函数体：{body.strip()[:150]}")
    check("switchInstance **不**读 infoData（那是上一个实例的 info，会带错 db）",
          "infoData" not in body, f"函数体读了 infoData：{body.strip()[:150]}")
# 反证：mock 回调应当**只**出现在 useResource 的 mock 分支里（演示态用），
# 不能出现在任何真实态路径上。这里只确认它没有被整段删掉导致演示态失效。
check("演示态的 mock 回退仍然保留（只用于演示分支）",
      "mock:" in code_only, "mock 分支被误删，演示态会失效")

print("\n===== 13. 未登录必须被拦下 =====")
res = call("GET", "/storage/redis/instances")
check("未登录 -> 40100", res.get("code") == 40100, f"code={res.get('code')}")

print("\n" + "=" * 60)
print(f"  联通测试通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 60)
print("  说明：唯一的写操作是删除自建的临时键（已清理），未碰现场任何已有键。")
if FAILURES:
    print("\n失败明细：")
    for f in FAILURES:
        print(f"  - {f}")
    sys.exit(1)
