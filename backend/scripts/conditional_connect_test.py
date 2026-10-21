# -*- coding: utf-8 -*-
"""条件单管理 —— 前后端联通简单测试（按需求：简单测即可，不做过度测试）。

⚠️ 本测试**不向任何已有表写入数据**，也**不向生产 Redis 写报文**。
  T_Conditional 是现场在用的业务表（策略平台的数据落点），
  临时插一条假条件单有被平台读到的风险，不值得为一次联通测试去动它。

2026-10-21 起条件单模块有两条变化，本脚本同步改了断言：
  1. **数据源**：主数据源从 MySQL 三张表（现场 0 行）换成 Redis 的
     `strategy-*` 键（现场 56 条真实条件单）。`source` 的语义也相应从
     「查哪张表」改成「筛哪类状态」（running=Mode1 / history=Mode≠1或Flag2 / all）。
     所以「空表返回 0」那条断言没有了，换成「口径一致、不重不漏」。
  2. **写操作**：状态变更（停止/运行/终止）已实现，走 setByStrategy 协议。
     脚本**不会真的下发** —— 因为 `app.conditional.enabled` 默认 false，
     本脚本第 9 节专门断言这一点（未开启时必须被拒，且不产生任何写入）。

分两部分验证：
  1. **运行时**：三个数据源的响应与口径、自洽性（all = running + history）、
     图表与列表同源、写操作的安全闸；
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

# ---- 先把 application.yml 的 app.conditional 段读出来（后面多节都要用）----
app_path = os.path.join(BACKEND, "src", "main", "resources", "application.yml")
with open(app_path, encoding="utf-8") as fh:
    yml = fh.read()

# ⚠️ 必须**限定在 app.conditional 段内**再找键：
# 全文找 `enabled:` 会先命中 app.datahub.heartbeat.enabled: true，
# 于是断言把"心跳订阅开着"误读成"写操作开关开着"。
_m = re.search(r"^\s{2}conditional:\s*$", yml, re.MULTILINE)
check("application.yml 里有 app.conditional 段", _m is not None, "没找到 conditional: 段")
SECT = yml[_m.end():] if _m else ""
_next = re.search(r"^\s{2}\w+:\s*$", SECT, re.MULTILINE)
COND = SECT[:_next.start()] if _next else SECT


def yml_value(key, default=None):
    """只在 app.conditional 段内取键值（注释行不参与）"""
    for line in COND.split("\n"):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        mm = re.match(r"^%s:\s*(.*)$" % re.escape(key), s)
        if mm:
            return mm.group(1).strip()
    return default


yml_enabled = (yml_value("enabled", "") or "").lower()

print("\n===== 2. 三个数据源：按【状态】筛，且每行都标出来源 =====")
# 2026-10-21 起主数据源是 Redis 的 strategy-* 键（现场 56 条），MySQL 三张表是 0 行。
# source 的语义也从「查哪张表」改成「筛哪类状态」：
#   running → Mode=1；history → Mode≠1 或 Flag=2；all → 不筛
seen = {}
for src in ("running", "history", "all"):
    res = call("GET", f"/strategy/conditionals?page=1&pageSize=200&source={src}", None, TOKEN)
    check(f"source={src} code=0", res.get("code") == 0, str(res)[:180])
    if res.get("code") != 0:
        continue
    d = res["data"]
    check(f"source={src} 返回 list/total/page/pageSize",
          {"list", "total", "page", "pageSize"} <= set(d), str(list(d)))
    meta = d.get("meta") or {}
    check(f"source={src} meta 带来源构成（redisCount/dbOnlyCount）",
          "redisCount" in meta and "dbOnlyCount" in meta, str(meta))
    check(f"source={src} meta 带筛选口径说明 scope", bool(meta.get("scope")), str(meta))
    seen[src] = d["total"]
    rows = d["list"]
    # 口径核对：running 必须全是 Mode=1；history 必须都不是 Mode=1 或 Flag=2
    if src == "running":
        bad = [r for r in rows if r.get("Mode") != 1]
        check("source=running 只含 Mode=1 的单", not bad, f"{len(bad)} 条 Mode≠1")
    if src == "history":
        bad = [r for r in rows if r.get("Mode") == 1 and r.get("Flag") != 2]
        check("source=history 不含 Mode=1 的单（除非 Flag=2）", not bad, f"{len(bad)} 条")
    # 每行都要标来源，且 Redis 来源的行要带实例
    no_source = [r for r in rows if r.get("source") not in ("redis", "db")]
    check(f"source={src} 每行都标了 source", not no_source, f"{len(no_source)} 行缺 source")
    print(f"  -> {src}: total={d['total']}  实时态={meta.get('redisCount')} "
          f"仅归档={meta.get('dbOnlyCount')}  截断={meta.get('truncated')}")

if len(seen) == 3:
    check("all 的条数 = running + history（状态二分不重不漏）",
          seen["all"] == seen["running"] + seen["history"],
          f"all={seen['all']} running={seen['running']} history={seen['history']}")

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

print("\n===== 4. 代码里确认：读 Redis 只走 RedisConditionalRepository 这一条明确路径 =====")
svc_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                        "admin", "service", "ConditionalService.java")
with open(svc_path, encoding="utf-8") as fh:
    svc = fh.read()
# 守门思想的**要点**没变：Redis 的访问必须收敛到一个专门的类里，
# 不能在 service 里到处散着调 scanJson —— 否则以后没法回答"这个类到底读了什么"。
check("ConditionalService 通过 RedisConditionalRepository 读 Redis",
      "RedisConditionalRepository" in svc, "没有注入 RedisConditionalRepository")
code_lines = [ln for ln in svc.split("\n")
              if not ln.strip().startswith(("*", "/*", "//"))]
code_only = "\n".join(code_lines)
check("ConditionalService 不直接调 scanJson（访问收敛到 repository）",
      "scanJson" not in code_only, "service 里仍在散着扫 Redis 键")

print("\n===== 5. 图表接口：与列表同源，不用假数据充数 =====")
res = call("GET", "/strategy/conditionals/stats/daily", None, TOKEN)
check("按天趋势 code=0", res.get("code") == 0, str(res)[:150])
res_types = call("GET", "/strategy/conditionals/stats/types", None, TOKEN)
check("类型分布 code=0", res_types.get("code") == 0, str(res_types)[:150])
res_stats = call("GET", "/strategy/conditionals/stats/summary?source=all", None, TOKEN)
check("汇总 code=0", res_stats.get("code") == 0, str(res_stats)[:150])
if res.get("code") == 0 and res_types.get("code") == 0 and res_stats.get("code") == 0:
    daily = res.get("data") or []
    types = res_types.get("data") or []
    stats = res_stats.get("data") or {}
    all_total = seen.get("all", 0)
    check("类型分布条数合计 == 全部条件单条数（口径一致，不多算）",
          sum(t.get("count", 0) for t in types) == all_total,
          f"类型合计={sum(t.get('count', 0) for t in types)} 全部={all_total}")
    check("汇总 totalCount == 列表 total",
          stats.get("totalCount") == all_total,
          f"汇总={stats.get('totalCount')} 列表={all_total}")
    check("趋势与列表同源（有数据的日子不为空）",
          (len(daily) == 0) == (all_total == 0),
          f"趋势 {len(daily)} 天 / 全部 {all_total} 条")
    print(f"  -> 趋势 {len(daily)} 天；类型 {len(types)} 种；汇总 {stats}")

print("\n===== 6. 详情：不存在的 id 如实 404 =====")
res = call("GET", "/strategy/conditionals/123456789", None, TOKEN)
check("不存在的 id -> 40400（不返回空壳对象）", res.get("code") == 40400, f"code={res.get('code')}")

print("\n===== 7. 非法参数不静默通过 =====")
res = call("GET", "/strategy/conditionals?source=bogus", None, TOKEN)
check("非法 source -> 40000（不静默回落成默认值）", res.get("code") == 40000, f"code={res.get('code')}")

print("\n===== 8. 写操作：已实现状态变更，但不提供新增与删除 =====")
res = call("GET", "/strategy/conditionals/instructions", None, TOKEN)
check("说明接口 code=0", res.get("code") == 0, str(res)[:120])
if res.get("code") == 0:
    d = res["data"]
    check("标注状态变更已实现", d.get("implemented") is True, str(d))
    check("标注不支持新增（需求方明确不做新增）", d.get("createSupported") is False, str(d))
    check("标注所需权限点", d.get("requiredPermission") == "action:conditional:write", str(d))
    # 页面靠这个字段提前置灰按钮，而不是"点了才报未启用"
    check("回传当前是否可写（enabled）", "enabled" in d, str(d))
    check("enabled 与 application.yml 的配置一致",
          bool(d.get("enabled")) == (yml_enabled == "true"),
          f"接口={d.get('enabled')} yml={yml_enabled or '未配置'}")

print("\n===== 9. 写操作的安全闸 =====")
# ⚠️ 这里**不能**用一条真实的 mode 请求去试探：
#    万一当前是联调态（enabled=true），那个请求会真的改掉一条条件单。
#    所以按当前配置分流。
if yml_enabled == "true":
    print("  [skip] 当前 enabled=true（联调态），跳过「未开启时必须被拒」这条 ——")
    print("         因为发真请求会真的改数据。改回 false 后再跑本脚本即可覆盖到。")
else:
    res = call("POST", "/strategy/conditionals/20260902000054/state", {"mode": 0}, TOKEN)
    check("enabled=false 时状态变更被拒（不产生任何写入）",
          res.get("code") == 40000, f"code={res.get('code')} msg={res.get('message')}")
    print(f"  -> {res.get('message')}")

# 下面这两条**与 enabled 无关**，而且在任何写动作之前就被拒，所以永远安全：
res = call("POST", "/strategy/conditionals/20260902000054/state", {"mode": 3}, TOKEN)
check("mode=3 被拒（Mode=3 是策略平台写的终态，不接受人工设置）",
      res.get("code") == 40000, f"code={res.get('code')} msg={res.get('message')}")
print(f"  -> {res.get('message')}")

res = call("POST", "/strategy/conditionals/20260902000054/state", {"mode": 99}, TOKEN)
check("mode=99 被拒（取值只允许 0/1/2）",
      res.get("code") == 40000, f"code={res.get('code')} msg={res.get('message')}")

print("\n===== 9b. 静态守门：代码里的默认值必须是关 =====")
# ⚠️ 这里守的是**代码里的默认值**，不是 yml 里的当前值。
# 理由：yml 是运行时配置，联调时把它改成 true 是**正常的**（不然页面点不动）。
# 真正要保证的是「不写这一项时是关的」—— 也就是 Java 字段的初始值。
# 早先的版本断言 yml 的 enabled=false，结果把联调配置误报成缺陷。
props_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                          "admin", "config", "ConditionalWriteProperties.java")
with open(props_path, encoding="utf-8") as fh:
    props = fh.read()
props_code = "\n".join(
    ln for ln in props.split("\n")
    if not ln.strip().startswith(("*", "/*", "//")) and not ln.strip().startswith("*/")
)
check("代码默认值：enabled = false（不写配置就是关的）",
      bool(re.search(r"private\s+boolean\s+enabled\s*=\s*false\s*;", props_code)),
      "字段默认值不是 false")
check("代码默认值：allowLivePlatform = false",
      bool(re.search(r"private\s+boolean\s+allowLivePlatform\s*=\s*false\s*;", props_code)),
      "字段默认值不是 false")
check("代码默认值：allowedServerIds 为空列表（= 一律不下发）",
      bool(re.search(r"private\s+List<String>\s+allowedServerIds\s*=\s*new\s+ArrayList<>\(\)\s*;",
                     props_code)),
      "默认不是空列表")
# ⚠️ 这个开关曾经存在（把"改本地"当降级路径），但需求方口径是
# 「不管是否连上策略服务器，申请一到就改本地」—— 本地改是主路径，
# 不该再有一个开关去拦它。留着会让"明明该改却没改"。
check("local-only-fallback 已移除（本地改是主路径，不该有开关拦它）",
      "localOnlyFallback" not in props_code, "属性类里仍有 localOnlyFallback")

# 当前 yml 是联调配置还是安全基线 —— **只提示，不判定失败**
yml_enabled = (yml_value("enabled", "") or "").lower()
print("\n  [info] 当前 application.yml 的 app.conditional.enabled = %s"
      % (yml_enabled or "未配置（取代码默认 false）"))
if yml_enabled == "true":
    print("         → 这是**联调态**（页面可操作）。交付前请改回 false，")
    print("           或改用 application-local.yml 装 true、主配置留 false。")

print("\n===== 9c. 静态守门：Mapper 只允许改 Mode / ModifyTime =====")
mapper_path = os.path.join(BACKEND, "src", "main", "java", "com", "qianlong", "cloud",
                           "admin", "mapper", "ConditionalMapper.java")
with open(mapper_path, encoding="utf-8") as fh:
    mapper = fh.read()
# ⚠️ 注释里会点名「不提供 INSERT / DELETE」，不剥掉会把这些说明文字误判成真的写了
mapper_code = "\n".join(
    ln for ln in mapper.split("\n")
    if not ln.strip().startswith(("*", "/*", "//")) and not ln.strip().startswith("*/")
)
check("ConditionalMapper 没有 INSERT", "INSERT" not in mapper_code.upper(), "出现了 INSERT")
check("ConditionalMapper 没有 DELETE", "DELETE" not in mapper_code.upper(), "出现了 DELETE")
check("ConditionalMapper 有 updateMode（唯一的写）", "updateMode" in mapper_code, "没有 updateMode")
upd = re.findall(r"@Update", mapper_code)
check("ConditionalMapper 只有一个 @Update", len(upd) == 1, f"有 {len(upd)} 个")

print("\n===== 10. 未登录必须被拦下 =====")
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
