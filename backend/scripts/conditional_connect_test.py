# -*- coding: utf-8 -*-
"""条件单管理 —— 前后端联通简单测试（按需求：简单测即可，不做过度测试）。

⚠️ 本模块**只读 MySQL 三张表**，而现场这三张表都是 0 行。
⚠️ **本测试不向任何已有表写入数据** —— T_Conditional 是现场在用的业务表
   （策略平台的数据落点），临时插一条假条件单有被平台读到的风险，
   不值得为一次联通测试去动它。

所以分两部分验证：
  1. **运行时**：三个数据源都能正常响应，表为空时如实返回 0
     （关键：不能拿 Redis 等别处的数据充数）；
  2. **静态**：把后端 DTO 的字段名与前端 `domain.ts` 的 Conditional 接口逐字段比对。
     这一条比运行时插数据更可靠 —— 它不受"表里有没有数据"影响，
     而且正是能抓出"字段名写错但运行期不报错"这类静默 bug 的手段。
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


print("===== 0. 三张表当前行数（只读，不做任何写入） =====")
conn = pymysql.connect(**DB)
with conn, conn.cursor() as cur:
    for t in ("T_Conditional", "T_Conditional_his", "V_Conditional"):
        cur.execute(f"SELECT COUNT(*) FROM {t}")
        print(f"  {t} = {cur.fetchone()[0]} 行")

print("\n===== 1. 登录 =====")
login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
check("登录成功", login.get("code") == 0, str(login)[:120])
TOKEN = login["data"]["token"]

print("\n===== 2. 三个数据源：空表如实返回 0，不拿别处的数据充数 =====")
for src, table in (("running", "T_Conditional"), ("history", "T_Conditional_his"),
                   ("all", "V_Conditional")):
    res = call("GET", f"/strategy/conditionals?page=1&pageSize=20&source={src}", None, TOKEN)
    check(f"source={src} code=0", res.get("code") == 0, str(res)[:180])
    if res.get("code") != 0:
        continue
    d = res["data"]
    check(f"source={src} 返回 list/total/page/pageSize",
          {"list", "total", "page", "pageSize"} <= set(d), str(list(d)))
    check(f"source={src} 空表 -> total=0 且 list=[]（不填充其它来源的数据）",
          d["total"] == 0 and d["list"] == [], f"total={d['total']} list={len(d['list'])}")
    meta = d.get("meta") or {}
    check(f"source={src} meta 标出真实表名 {table}", meta.get("table") == table, str(meta))
    print(f"  -> {src}: total={d['total']}  表={meta.get('table')}  表行数={meta.get('tableRows')}")

print("\n===== 3. 静态核对：后端 DTO 字段 == 前端 Conditional 接口 =====")
dto_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                        "admin", "dto", "ConditionalDtos.java")
with open(dto_path, encoding="utf-8") as fh:
    dto = fh.read()
m = re.search(r"public record ConditionalView\((.*?)\)\s*\{", dto, re.DOTALL)
check("在 DTO 里找到 ConditionalView record", m is not None, "没匹配到")
if m:
    body = m.group(1)
    # 去掉注释行与空行，取每行最后一个标识符作为字段名
    dto_fields = []
    for line in body.split("\n"):
        line = line.strip()
        if not line or line.startswith(("/*", "*", "//")):
            continue
        line = re.sub(r"/\*.*?\*/", "", line)
        line = re.sub(r"//.*$", "", line)
        line = line.strip().rstrip(",")
        if not line:
            continue
        m2 = re.match(r"^[A-Za-z_][\w<>,\s\[\]\.]*?(\w+)$", line)
        if m2:
            dto_fields.append(m2.group(1))
    print(f"  DTO 字段({len(dto_fields)}): {dto_fields}")

    # 前端 domain.ts 的 Conditional 接口
    dom_path = os.path.join(FRONTEND, "src", "api", "domain.ts")
    with open(dom_path, encoding="utf-8") as fh:
        dom = fh.read()
    m3 = re.search(r"export interface Conditional \{(.*?)\n\}", dom, re.DOTALL)
    check("在前端 domain.ts 里找到 Conditional 接口", m3 is not None, "没匹配到")
    if m3:
        fe_fields = []
        for line in m3.group(1).split("\n"):
            line = re.sub(r"/\*.*?\*/", "", line).strip()
            if not line or line.startswith(("//", "*", "/*")):
                continue
            m4 = re.match(r"^(\w+)\s*[?:]", line)
            if m4:
                fe_fields.append(m4.group(1))
        print(f"  前端字段({len(fe_fields)}): {fe_fields}")

        missing = [f for f in fe_fields if f not in dto_fields]
        extra = [f for f in dto_fields if f not in fe_fields]
        check("后端字段覆盖前端全部字段（无缺失）", not missing, f"缺 {missing}")
        check("后端没有多出前端不认识的字段", not extra, f"多 {extra}")

    # 关键：确认已经不含 Redis 合并时期的多余字段
    check("已移除 dataSource 字段（不再混入 Redis 数据）",
          "dataSource" not in dto_fields, str(dto_fields))
    check("已移除 redisInstance 字段", "redisInstance" not in dto_fields, str(dto_fields))

print("\n===== 4. 代码里确认条件单不再读 Redis =====")
svc_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                        "admin", "service", "ConditionalService.java")
with open(svc_path, encoding="utf-8") as fh:
    svc = fh.read()
check("ConditionalService 未注入 RedisJsonGateway",
      "RedisJsonGateway" not in svc, "仍引用了 RedisJsonGateway")
check("ConditionalService 未出现 strategy- 键扫描调用",
      "scanJson" not in svc, "仍在扫描 Redis 键")
# 注释里会提到 Redis（解释为什么不用），所以只检查代码行
code_lines = [ln for ln in svc.split("\n")
              if not ln.strip().startswith(("*", "/*", "//"))]
code_only = "\n".join(code_lines)
check("非注释代码里不出现 Redis 读取", "redisGateway" not in code_only, "仍有 redisGateway 调用")

print("\n===== 5. 图表接口（空表应返回空，不用假数据充数） =====")
res = call("GET", "/strategy/conditionals/stats/daily", None, TOKEN)
check("按天趋势 code=0", res.get("code") == 0, str(res)[:150])
check("空表 -> 趋势返回空数组（图表显示「暂无数据」，不造假）",
      res.get("data") == [], str(res.get("data"))[:100])
res = call("GET", "/strategy/conditionals/stats/types", None, TOKEN)
check("类型分布 code=0", res.get("code") == 0, str(res)[:150])
check("空表 -> 类型分布返回空数组", res.get("data") == [], str(res.get("data"))[:100])

print("\n===== 6. 详情：不存在的 id 如实 404 =====")
res = call("GET", "/strategy/conditionals/123456789", None, TOKEN)
check("不存在的 id -> 40400（不返回空壳对象）", res.get("code") == 40400, f"code={res.get('code')}")

print("\n===== 7. 非法参数不静默通过 =====")
res = call("GET", "/strategy/conditionals?source=bogus", None, TOKEN)
check("非法 source -> 40000（不静默回落成默认值）", res.get("code") == 40000, f"code={res.get('code')}")

print("\n===== 8. 写操作现状（如实说明，不留能改库但改不对的接口） =====")
res = call("GET", "/strategy/conditionals/instructions", None, TOKEN)
check("说明接口 code=0", res.get("code") == 0, str(res)[:120])
if res.get("code") == 0:
    check("明确标注写操作未实现", res["data"].get("implemented") is False, str(res["data"]))

print("\n===== 9. 未登录必须被拦下 =====")
res = call("GET", "/strategy/conditionals")
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
