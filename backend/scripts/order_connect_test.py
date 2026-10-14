# -*- coding: utf-8 -*-
"""委托单管理 —— 前后端联通简单测试（按需求：简单测即可，不做过度测试）。

⚠️ 本模块**只读 MySQL 四张表**，且**不向任何已有表写入数据**。
   现场 T_Order / T_Trade 都是 0 行，T_Order_his / T_Trade_his 各 1 行真实测试数据
   （OrderNo=1234567），足够验证列表与「委托 → 成交」下钻。

分两部分：
  1. 运行时：接口能通、主表空/历史表有那条真实数据、下钻能关联上；
  2. 静态：后端 DTO 字段与前端 domain.ts 的 OrderRecord / TradeRecord 逐字段比对
     （比插数据更可靠，也是能抓出「字段名写错但运行期不报错」的手段）。
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


def dto_fields(path: str, record: str) -> list[str]:
    """从 Java record 里抽出字段名（去掉注释行）。"""
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(rf"public record {record}\((.*?)\)\s*\{{", src, re.DOTALL)
    if not m:
        return []
    out = []
    for line in m.group(1).split("\n"):
        line = re.sub(r"/\*.*?\*/", "", line)
        line = re.sub(r"//.*$", "", line).strip().rstrip(",")
        if not line:
            continue
        m2 = re.match(r"^[A-Za-z_][\w<>,\s\[\]\.]*?(\w+)$", line)
        if m2:
            out.append(m2.group(1))
    return out


def fe_fields(path: str, iface: str) -> list[str]:
    """从前端 interface 里抽字段名。"""
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


print("===== 0. 四张表当前行数（只读） =====")
conn = pymysql.connect(**DB)
with conn, conn.cursor() as cur:
    for t in ("T_Order", "T_Order_his", "T_Trade", "T_Trade_his"):
        cur.execute(f"SELECT COUNT(*) FROM {t}")
        print(f"  {t:16} = {cur.fetchone()[0]} 行")

print("\n===== 1. 登录 =====")
login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
check("登录成功", login.get("code") == 0, str(login)[:120])
TOKEN = login["data"]["token"]

print("\n===== 2. 委托单：主表（空）与历史表（1 条真实数据） =====")
res = call("GET", "/trade/orders?page=1&pageSize=10&table=T_Order", None, TOKEN)
check("主表 code=0", res.get("code") == 0, str(res)[:180])
d = res["data"]
check("主表空 -> total=0 且 list=[]（不填充其它来源）",
      d["total"] == 0 and d["list"] == [], f"total={d['total']}")
check("meta 标出表名 T_Order", (d.get("meta") or {}).get("table") == "T_Order", str(d.get("meta")))
print(f"  -> 主表: total={d['total']}  表={(d.get('meta') or {}).get('table')}")

res = call("GET", "/trade/orders?page=1&pageSize=10&table=T_Order_his", None, TOKEN)
check("历史表 code=0", res.get("code") == 0, str(res)[:180])
d = res["data"]
check("历史表有 1 条真实数据", d["total"] == 1 and len(d["list"]) == 1,
      f"total={d['total']} list={len(d['list'])}")
if d["list"]:
    r0 = d["list"][0]
    need = {"ID", "StrategyID", "UserAccount", "Account", "Code", "Market", "OrderPrice",
            "OrderVolumn", "BSType", "OCType", "OrderTime", "OrderParam", "OrderStatue",
            "OrderNo", "CodeName", "StatueDis", "Reason"}
    missing = need - set(r0)
    check("OrderRecord 字段与前端契约一致", not missing, f"缺 {sorted(missing)}")
    check("OrderNo=1234567（与成交能关联上的那条）", r0.get("OrderNo") == "1234567",
          str(r0.get("OrderNo")))
    check("OrderTime 是纯日期 yyyy-MM-dd（date 列，不补 00:00:00）",
          bool(re.match(r"^\d{4}-\d{2}-\d{2}$", r0.get("OrderTime") or "")),
          str(r0.get("OrderTime")))
    print(f"  -> 样例: ID={r0.get('ID')} Code={r0.get('Code')} OrderNo={r0.get('OrderNo')} "
          f"BSType={r0.get('BSType')} OrderTime={r0.get('OrderTime')}")

print("\n===== 3. 筛选（bsType / ocType / code / keyword） =====")
res = call("GET", "/trade/orders?page=1&pageSize=10&table=T_Order_his&bsType=1", None, TOKEN)
check("bsType=1 命中", res["data"]["total"] == 1, f"total={res['data']['total']}")
res = call("GET", "/trade/orders?page=1&pageSize=10&table=T_Order_his&bsType=2", None, TOKEN)
check("bsType=2 不命中（筛选确实生效）", res["data"]["total"] == 0, f"total={res['data']['total']}")
res = call("GET", "/trade/orders?page=1&pageSize=10&table=T_Order_his&code=300059", None, TOKEN)
check("code=300059 命中", res["data"]["total"] == 1, f"total={res['data']['total']}")
res = call("GET", "/trade/orders?page=1&pageSize=10&table=T_Order_his&keyword=12345679", None, TOKEN)
check("keyword 命中", res["data"]["total"] == 1, f"total={res['data']['total']}")
res = call("GET", "/trade/orders?page=1&pageSize=10&table=T_Order_his&keyword=不存在的东西", None, TOKEN)
check("keyword 不命中 -> 0", res["data"]["total"] == 0, f"total={res['data']['total']}")

print("\n===== 4. 委托 → 成交 下钻（两张成交表都查） =====")
res = call("GET", "/trade/orders/1234567/trades", None, TOKEN)
check("下钻 code=0", res.get("code") == 0, str(res)[:180])
trades = res.get("data") or []
check("查到成交（T_Trade_his 里那条，说明历史表也查了）", len(trades) == 1,
      f"查到 {len(trades)} 条")
if trades:
    t0 = trades[0]
    need = {"id", "StrategyID", "OrderNo", "TradeNO", "Account", "Market", "Code",
            "Volumn", "Price", "TradeTime", "UserAccount"}
    missing = need - set(t0)
    check("TradeRecord 字段与前端契约一致", not missing, f"缺 {sorted(missing)}")
    check("Volumn 是字符串（库里 varchar，不擅自转数字）",
          isinstance(t0.get("Volumn"), str), f"type={type(t0.get('Volumn')).__name__}")
    check("TradeTime 是纯日期",
          bool(re.match(r"^\d{4}-\d{2}-\d{2}$", t0.get("TradeTime") or "")),
          str(t0.get("TradeTime")))
    check("OrderNo 与委托一致", t0.get("OrderNo") == "1234567", str(t0.get("OrderNo")))
    print(f"  -> 成交: TradeNO={t0.get('TradeNO')} Code={t0.get('Code')} "
          f"Volumn={t0.get('Volumn')!r} Price={t0.get('Price')} TradeTime={t0.get('TradeTime')}")
res = call("GET", "/trade/orders/不存在的委托号/trades", None, TOKEN)
check("不存在的委托号 -> 空数组（不是报错）", res.get("code") == 0 and res.get("data") == [],
      str(res)[:120])

print("\n===== 5. 成交列表 =====")
res = call("GET", "/trade/trades?page=1&pageSize=10&table=T_Trade", None, TOKEN)
check("成交主表 code=0", res.get("code") == 0, str(res)[:150])
check("成交主表为空如实返回 0", res["data"]["total"] == 0, f"total={res['data']['total']}")
res = call("GET", "/trade/trades?page=1&pageSize=10&table=T_Trade_his", None, TOKEN)
check("成交历史表有 1 条", res["data"]["total"] == 1, f"total={res['data']['total']}")

print("\n===== 6. 图表接口 =====")
res = call("GET", "/trade/orders/stats/bs?table=T_Order_his", None, TOKEN)
check("买卖分布 code=0（路由未被 /orders/{id} 抢走）", res.get("code") == 0, str(res)[:150])
bs = res.get("data") or []
check("买卖分布有数据且带中文标签",
      len(bs) == 1 and bs[0]["name"] == "买入" and bs[0]["count"] == 1, str(bs))
res = call("GET", "/trade/orders/stats/daily?table=T_Order_his", None, TOKEN)
check("按天趋势 code=0", res.get("code") == 0, str(res)[:150])
check("趋势含那天（date 列按天分组）", len(res.get("data") or []) == 1, str(res.get("data")))
print(f"  -> 买卖分布: {[(x['name'], x['count']) for x in bs]}  趋势: {res.get('data')}")

print("\n===== 7. 详情与错误分支 =====")
res = call("GET", "/trade/orders/1495", None, TOKEN)
check("按 id 查详情 code=0", res.get("code") == 0, str(res)[:150])
if res.get("code") == 0:
    check("详情 ID 正确", res["data"].get("ID") == 1495, str(res["data"].get("ID")))
res = call("GET", "/trade/orders/999999999", None, TOKEN)
check("不存在的 id -> 40400", res.get("code") == 40400, f"code={res.get('code')}")
res = call("GET", "/trade/orders?table=DROP_TABLE_x", None, TOKEN)
check("非法表名 -> 40000（不静默回落，且不执行拼接内容）",
      res.get("code") == 40000, f"code={res.get('code')}")
res = call("GET", "/trade/trades?table=bogus", None, TOKEN)
check("成交非法表名 -> 40000", res.get("code") == 40000, f"code={res.get('code')}")

print("\n===== 8. 静态核对：后端 DTO == 前端接口 =====")
dto_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                        "admin", "dto", "TradeDtos.java")
dom_path = os.path.join(FRONTEND, "src", "api", "domain.ts")

for rec, iface in (("OrderView", "OrderRecord"), ("TradeView", "TradeRecord")):
    d = dto_fields(dto_path, rec)
    f = fe_fields(dom_path, iface)
    print(f"  {rec}({len(d)}): {d}")
    print(f"  {iface}({len(f)}): {f}")
    check(f"{rec} 覆盖 {iface} 全部字段（无缺失）",
          not [x for x in f if x not in d], f"缺 {[x for x in f if x not in d]}")
    check(f"{rec} 没有多出 {iface} 不认识的字段",
          not [x for x in d if x not in f], f"多 {[x for x in d if x not in f]}")

print("\n===== 9. 未登录必须被拦下 =====")
res = call("GET", "/trade/orders")
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
