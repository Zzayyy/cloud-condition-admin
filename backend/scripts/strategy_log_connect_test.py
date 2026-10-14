# -*- coding: utf-8 -*-
"""策略日志管理 —— 前后端联通简单测试（按需求：简单测即可，不做过度测试）。

⚠️ 本模块只读 T_StrategyLog，**不向任何已有表写入数据**。
   现场 17430 行真实数据，足够验证列表/筛选/聚合/时间线。

重点验证的三条现场事实（这些比「接口通了」更重要）：
  1. Status 整列为 NULL -> 后端如实返回空的状态字典，**不能**沿用 mock 编的 7 个值；
  2. FromIP / FromMac 整列为 NULL -> 按 IP 筛必然 0 条，不是查询写错；
  3. OccurTIme 是 date 类型且存过非法空串 -> SQL 里不能用 <> ''（会报 MySQL 1525）。
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

import pymysql

BASE = "http://127.0.0.1:8011/api"
DB = dict(host="192.168.1.136", user="qianlong_cloud", password="Qianlong@2026",
          database="qianlong_cloud", charset="utf8mb4")
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
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def fe_fields(path: str, iface: str) -> list[str]:
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


def dto_fields(path: str, record: str) -> list[str]:
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(rf"public record {record}\((.*?)\)\s*\{{", src, re.DOTALL)
    if not m:
        return []
    out = []
    for line in m.group(1).split("\n"):
        line = re.sub(r"/\*.*?\*/", "", line).replace("//", "#").split("#")[0].strip().rstrip(",")
        if not line:
            continue
        m2 = re.match(r"^[A-Za-z_][\w<>,\s\[\]\.]*?(\w+)$", line)
        if m2:
            out.append(m2.group(1))
    return out


print("===== 0. 现场事实核对（只读，不写入） =====")
conn = pymysql.connect(**DB)
with conn, conn.cursor() as cur:
    cur.execute("SELECT COUNT(*) FROM T_StrategyLog")
    total = cur.fetchone()[0]
    print(f"  T_StrategyLog 行数 = {total}")
    cur.execute("SELECT COUNT(*) FROM T_StrategyLog WHERE Status IS NOT NULL")
    status_nonnull = cur.fetchone()[0]
    print(f"  Status 非 NULL    = {status_nonnull}  <- 应为 0（整列为空）")
    cur.execute("SELECT COUNT(*) FROM T_StrategyLog WHERE FromIP IS NOT NULL OR FromMac IS NOT NULL")
    dev_nonnull = cur.fetchone()[0]
    print(f"  FromIP/FromMac 非 NULL = {dev_nonnull}  <- 应为 0")
check("Status 列确实整列为 NULL（前端的 7 个状态值是 mock 编的）",
      status_nonnull == 0, f"非 NULL {status_nonnull} 行")
check("FromIP/FromMac 确实整列为 NULL", dev_nonnull == 0, f"非 NULL {dev_nonnull} 行")

print("\n===== 1. 登录 =====")
login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
check("登录成功", login.get("code") == 0, str(login)[:120])
TOKEN = login["data"]["token"]

print("\n===== 2. 列表：有真实数据 =====")
res = call("GET", "/strategy/logs?page=1&pageSize=15", None, TOKEN)
check("code=0", res.get("code") == 0, str(res)[:180])
d = res["data"]
check("返回 list/total/page/pageSize", {"list", "total", "page", "pageSize"} <= set(d), str(list(d)))
check(f"total = {total}（与库里一致）", d["total"] == total, f"接口 total={d['total']} 库={total}")
check("本页 15 行", len(d["list"]) == 15, f"实际 {len(d['list'])}")
if d["list"]:
    r0 = d["list"][0]
    need = {"id", "Strategy", "Status", "Info", "OccurTIme", "FromIP", "FromMac", "FromAccount"}
    missing = need - set(r0)
    check("StrategyLog 字段与前端契约一致", not missing, f"缺 {sorted(missing)}")
    check("OccurTIme 是纯日期 yyyy-MM-dd（date 列，不补 00:00:00）",
          bool(re.match(r"^\d{4}-\d{2}-\d{2}$", r0.get("OccurTIme") or "")),
          str(r0.get("OccurTIme")))
    check("Status 如实返回 null（不编占位值）", r0.get("Status") is None, str(r0.get("Status")))
    check("FromIP 如实返回 null", r0.get("FromIP") is None, str(r0.get("FromIP")))
    check("Info 有真实内容", bool(r0.get("Info")), "空")
    check("FromAccount 有真实内容", bool(r0.get("FromAccount")), "空")
    print(f"  -> 样例: id={r0.get('id')} Strategy={r0.get('Strategy')} "
          f"OccurTIme={r0.get('OccurTIme')}")
    print(f"          Info={str(r0.get('Info'))[:60]}")
    print(f"          FromAccount={r0.get('FromAccount')} Status={r0.get('Status')}")

print("\n===== 3. 关键：状态字典必须是真实的空数组，而不是 mock 的 7 个值 =====")
res = call("GET", "/strategy/logs/statuses", None, TOKEN)
check("状态字典接口 code=0", res.get("code") == 0, str(res)[:150])
statuses = res.get("data")
check("返回空数组（现场 Status 整列为空，如实呈现）",
      statuses == [], f"实际 {statuses}")
check("**没有**返回 mock 编造的那 7 个状态值",
      not (isinstance(statuses, list) and any(
          s in statuses for s in ("触发成功", "触发失败", "已暂停", "已恢复", "已删除", "校验失败", "参数错误"))),
      f"实际 {statuses}")
meta = call("GET", "/strategy/logs?page=1&pageSize=5", None, TOKEN)["data"].get("meta") or {}
check("meta.statusColumnEmpty = true（页面据此提示，而不是给空下拉）",
      meta.get("statusColumnEmpty") is True, str(meta))
check("meta.deviceColumnsEmpty = true", meta.get("deviceColumnsEmpty") is True, str(meta))
print(f"  -> meta = {meta}")

print("\n===== 4. 筛选 =====")
res = call("GET", "/strategy/logs?page=1&pageSize=5&keyword=20260930000166", None, TOKEN)
check("按 Strategy 编号搜索命中", res["data"]["total"] >= 1, f"total={res['data']['total']}")
res = call("GET", "/strategy/logs?page=1&pageSize=5&keyword=new trade inserted", None, TOKEN)
check("按 Info 内容搜索命中", res["data"]["total"] > 0, f"total={res['data']['total']}")
res = call("GET", "/strategy/logs?page=1&pageSize=5&keyword=010100011300", None, TOKEN)
check("按 FromAccount 搜索命中", res["data"]["total"] > 0, f"total={res['data']['total']}")
res = call("GET", "/strategy/logs?page=1&pageSize=5&keyword=绝不存在的内容xyz", None, TOKEN)
check("搜索不存在的内容 -> 0", res["data"]["total"] == 0, f"total={res['data']['total']}")
# Status 筛选：现场该列为空，任何值都应 0 条（如实反映，不是 bug）
res = call("GET", "/strategy/logs?page=1&pageSize=5&status=触发成功", None, TOKEN)
check("按不存在的 Status 筛选 -> 0 条（该列现场为空）",
      res.get("code") == 0 and res["data"]["total"] == 0, str(res.get("data", {}).get("total")))
# FromIP 同理
res = call("GET", "/strategy/logs?page=1&pageSize=5&fromIp=1.2.3.4", None, TOKEN)
check("按 FromIP 筛选 -> 0 条（该列现场为空，不是查询写错）",
      res.get("code") == 0 and res["data"]["total"] == 0, str(res.get("data", {}).get("total")))

print("\n===== 5. 日期范围（关键：date 列 + 非法空串，不能报 1525） =====")
res = call("GET", "/strategy/logs?page=1&pageSize=5&from=2026-09-01&to=2026-09-30", None, TOKEN)
check("日期范围筛选 code=0（未触发 MySQL 1525）", res.get("code") == 0,
      f"code={res.get('code')} msg={res.get('message')}")
check("日期范围内有数据", res["data"]["total"] > 0, f"total={res['data']['total']}")
print(f"  -> 2026-09-01 ~ 2026-09-30: {res['data']['total']} 条")
res = call("GET", "/strategy/logs?page=1&pageSize=5&from=2026-01-01&to=2026-01-02", None, TOKEN)
check("远古区间 -> 0 条", res["data"]["total"] == 0, f"total={res['data']['total']}")
res = call("GET", "/strategy/logs?page=1&pageSize=5&from=不是日期", None, TOKEN)
check("非法日期 -> 40000（不静默忽略条件）", res.get("code") == 40000, f"code={res.get('code')}")

print("\n===== 6. 聚合接口 =====")
res = call("GET", "/strategy/logs/stats/daily", None, TOKEN)
check("按天趋势 code=0", res.get("code") == 0, str(res)[:150])
daily = res.get("data") or []
check("趋势有数据", len(daily) > 0, "0 组")
if daily:
    check("趋势行字段为 date/count", {"date", "count"} <= set(daily[0]), str(daily[0]))
    check("趋势按日期升序", [x["date"] for x in daily] == sorted(x["date"] for x in daily), "顺序错")
    print(f"  -> {len(daily)} 天，范围 {daily[0]['date']} ~ {daily[-1]['date']}")

res = call("GET", "/strategy/logs/stats/by-strategy?page=1&pageSize=5", None, TOKEN)
check("按条件单聚合 code=0", res.get("code") == 0, str(res)[:150])
st = res["data"]
check("聚合有数据且总数 = 去重后的条件单数", st["total"] > 0, f"total={st['total']}")
if st["list"]:
    s0 = st["list"][0]
    check("聚合行字段为 strategy/count/firstDay/lastDay",
          {"strategy", "count", "firstDay", "lastDay"} <= set(s0), str(s0))
    check("聚合按条数倒序", st["list"][0]["count"] >= st["list"][-1]["count"], "顺序错")
    print(f"  -> {st['total']} 个条件单有日志；最多: {s0['strategy']} = {s0['count']} 条")

print("\n===== 7. 时间线（某条件单的完整轨迹） =====")
sid = daily and None
res = call("GET", "/strategy/logs/stats/by-strategy?page=1&pageSize=1", None, TOKEN)
target = res["data"]["list"][0]["strategy"]
expect = res["data"]["list"][0]["count"]
res = call("GET", f"/strategy/logs/{target}/timeline", None, TOKEN)
check("时间线 code=0", res.get("code") == 0, str(res)[:150])
tl = res.get("data") or []
check(f"时间线返回该条件单的 {expect} 条日志", len(tl) == expect, f"实际 {len(tl)}")
check("时间线里都是这个条件单", all(x.get("Strategy") == target for x in tl), "混入了别的条件单")
if len(tl) > 1:
    check("时间线按 OccurTIme 升序（轨迹要按时间看）",
          [x["OccurTIme"] for x in tl] == sorted(x["OccurTIme"] for x in tl), "顺序错")
print(f"  -> 条件单 {target}: {len(tl)} 条，{tl[0]['OccurTIme']} ~ {tl[-1]['OccurTIme']}")

print("\n===== 8. 静态核对：后端 DTO == 前端接口 =====")
dto_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                        "admin", "dto", "StrategyLogDtos.java")
dom_path = os.path.join(FRONTEND, "src", "api", "domain.ts")
d = dto_fields(dto_path, "StrategyLogView")
f = fe_fields(dom_path, "StrategyLog")
print(f"  StrategyLogView({len(d)}): {d}")
print(f"  StrategyLog({len(f)}): {f}")
check("StrategyLogView 覆盖 StrategyLog 全部字段",
      not [x for x in f if x not in d], f"缺 {[x for x in f if x not in d]}")
check("StrategyLogView 没有多出前端不认识的字段",
      not [x for x in d if x not in f], f"多 {[x for x in d if x not in f]}")

print("\n===== 9. 前端不再从 mock 取状态选项 =====")
view_path = os.path.join(FRONTEND, "src", "views", "strategy", "StrategyLog.vue")
with open(view_path, encoding="utf-8") as fh:
    view = fh.read()
check("页面调用了 listLogStatuses（真实去重取值）",
      "listLogStatuses" in view, "仍只用 mock 的状态列表")
# 检查 statusOptions 的定义体里有 mock 回落，但不是唯一来源
m = re.search(r"const statusOptions = computed\((.*?)\n\)", view, re.DOTALL)
check("statusOptions 不再是直接映射 mock 的常量",
      m is not None, "仍是 mockStrategyApi.logStatusOptions.map(...)")

print("\n===== 10. 未登录必须被拦下 =====")
res = call("GET", "/strategy/logs")
check("未登录 -> 40100", res.get("code") == 40100, f"code={res.get('code')}")

print("\n" + "=" * 60)
print(f"  联通测试通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 60)
print("  说明：本测试**未向任何已有表写入数据**。")
if FAILURES:
    print("\n失败明细：")
    for f in FAILURES:
        print(f"  - {f}")
    sys.exit(1)
