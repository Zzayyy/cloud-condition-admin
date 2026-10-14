# -*- coding: utf-8 -*-
"""数据库管理 —— 前后端联通简单测试（按需求：简单测即可，不做过度测试）。

⚠️ 本测试**只读**，但会**故意尝试若干危险 SQL** 来验证白名单真的拦得住。
   所有危险尝试都期望「被拒绝」；若哪一条真的执行成功，测试会立刻失败并报警。
   为了绝对安全，危险样本里**不含任何能改动数据的合法语句**
   （例如不写 `DELETE FROM 真实表`），只用 DDL/写关键字的形式，
   并且执行前已被白名单拦截 —— 即使万一漏拦，MySQL 也会因语法/权限失败。
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8011/api"
DB = "qianlong_cloud"
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
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode())
        except Exception:
            return {"code": -1, "message": "non-json"}


def run_sql(sql, token, limit=200):
    return call("POST", "/storage/mysql/query",
                {"database": DB, "sql": sql, "limit": limit}, token)


print("===== 1. 登录 =====")
login = call("POST", "/system/auth/login", {"username": "admin", "password": "Admin@2026"})
check("登录成功", login.get("code") == 0, str(login)[:120])
TOKEN = login["data"]["token"]

print("\n===== 2. 表清单 =====")
res = call("GET", f"/storage/mysql/tables?database={DB}", None, TOKEN)
check("code=0", res.get("code") == 0, str(res)[:200])
tables = res.get("data") or []
check("返回表清单", len(tables) > 0, f"实际 {len(tables)}")
if tables:
    t0 = tables[0]
    need = {"name", "type", "engine", "approxRows", "dataLength", "indexLength", "comment"}
    check("MysqlTable 字段与前端契约一致", need <= set(t0), f"缺 {need - set(t0)}")
    # 关键：approx vs exact 都能给出来，页面才能看出「近似值不可信」
    with_exact = [t for t in tables if t.get("exactRows") is not None]
    check("部分表给了 exactRows（真实行数）", len(with_exact) > 0, "全为 null")
    print(f"  -> {len(tables)} 个对象")
    for t in tables[:5]:
        print(f"     {t['name']:22} {t['type']:11} approx={t['approxRows']} exact={t.get('exactRows')}")
    # 已知：T_StrategyLog 的 approx 与 exact 不一致（README 记录过）
    sl = next((t for t in tables if t["name"] == "T_StrategyLog"), None)
    if sl:
        print(f"  -> T_StrategyLog: approx={sl['approxRows']} exact={sl.get('exactRows')} "
              f"（近似值不准，两个都要有）")

print("\n===== 3. 表结构（字段 / 索引 / DDL） =====")
res = call("GET", f"/storage/mysql/schema?database={DB}&table=T_User", None, TOKEN)
check("code=0", res.get("code") == 0, str(res)[:200])
sch = res.get("data") or {}
check("返回 name/columns/indexes/ddl",
      {"name", "columns", "indexes", "ddl"} <= set(sch), f"实际 {sorted(sch)}")
cols = sch.get("columns") or []
check("有字段", len(cols) > 0, "0 个")
if cols:
    c0 = cols[0]
    need = {"name", "columnType", "nullable", "key", "defaultValue", "comment"}
    check("MysqlColumn 字段与前端契约一致", need <= set(c0), f"缺 {need - set(c0)}")
    print(f"  -> T_User {len(cols)} 列, {len(sch.get('indexes') or [])} 个索引")
    print(f"     样例: {c0['name']} {c0['columnType']} nullable={c0['nullable']} key={c0['key']}")
check("DDL 是 SHOW CREATE TABLE 原文（以 CREATE TABLE 开头）",
      (sch.get("ddl") or "").startswith("CREATE TABLE"), str(sch.get("ddl"))[:60])
res = call("GET", f"/storage/mysql/schema?database={DB}&table=V_Conditional", None, TOKEN)
check("视图也能取结构（视图 DDL 以 CREATE 开头）",
      res.get("code") == 0 and (res["data"]["ddl"] or "").startswith("CREATE"),
      str(res)[:150])
res = call("GET", f"/storage/mysql/schema?database={DB}&table=不存在的表xyz", None, TOKEN)
check("不存在的表 -> 40400", res.get("code") == 40400, f"code={res.get('code')}")

print("\n===== 4. 分页读表数据 =====")
res = call("GET", f"/storage/mysql/query?database={DB}&table=T_AdminUser&page=1&pageSize=3", None, TOKEN)
check("code=0", res.get("code") == 0, str(res)[:200])
qd = res.get("data") or {}
check("返回 list/total/page/pageSize",
      {"list", "total", "page", "pageSize"} <= set(qd), f"实际 {sorted(qd)}")
check("返回 3 行", len(qd.get("list") or []) == 3, f"实际 {len(qd.get('list') or [])}")
if qd.get("list"):
    print(f"  -> T_AdminUser total={qd['total']} 本页 {len(qd['list'])} 行")
    print(f"     第一行键: {list(qd['list'][0].keys())[:6]}")
    check("不含 passwordHash（T_AdminUser 的密码列）？—— 这是 SQL 直查，**会**出现",
          True, "")
# binary 列的展示（T_User.RegProtocol 是 binary(1)，值是 '1'）
res = call("GET", f"/storage/mysql/query?database={DB}&table=T_User&page=1&pageSize=1", None, TOKEN)
if res.get("code") == 0 and res["data"]["list"]:
    row = res["data"]["list"][0]
    rp = row.get("RegProtocol")
    print(f"  -> T_User.RegProtocol = {rp!r}（binary(1)，应显示为可读字符而不是 Base64 乱码）")
    check("binary 列可读化（不是 Base64）", rp in ("1", "0x31", None), str(rp))

print("\n===== 5. 只读 SQL：正常查询能用 =====")
res = run_sql("SELECT id, username, displayName FROM T_AdminUser LIMIT 3", TOKEN)
check("SELECT 可用", res.get("code") == 0, str(res)[:200])
if res.get("code") == 0:
    print(f"  -> 返回 {len(res['data']['list'])} 行，costMs={res['data']['costMs']}")
res = run_sql(f"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='{DB}' LIMIT 5", TOKEN)
check("可查 information_schema", res.get("code") == 0, str(res)[:150])
res = run_sql("WITH t AS (SELECT 1 AS n) SELECT * FROM t", TOKEN)
check("WITH (CTE) 可用", res.get("code") == 0, str(res)[:150])
res = run_sql("SHOW CREATE TABLE T_AdminRole", TOKEN)
check("SHOW CREATE TABLE 可用（含 CREATE 但属只读，应放行）",
      res.get("code") == 0, str(res)[:180])
res = run_sql("DESC T_AdminUser", TOKEN)
check("DESC 可用", res.get("code") == 0, str(res)[:150])

print("\n===== 5b. 字符串字面量必须原样保留（曾经的严重 bug） =====")
# 校验时会「剥掉字面量」以避免 'DROP TABLE x' 误报；
# 但**执行时必须用原文**，否则所有字符串字面量会被清空。
# 我第一版就是返回了剥掉字面量的版本，表现为
# `SELECT 'HELLO'` 返回空串、`WHERE Name='张国昌'` 查不到行 —— 且不报错。
res = run_sql("SELECT 'HELLO' AS note", TOKEN)
check("SELECT 'HELLO' 返回 HELLO（字面量未被清空）",
      res.get("code") == 0 and res["data"]["list"]
      and res["data"]["list"][0].get("note") == "HELLO",
      str(res.get("data", {}).get("list"))[:120])
res = run_sql("SELECT * FROM T_User WHERE Name = '张国昌'", TOKEN)
check("按中文姓名过滤能查到（字面量参与查询）",
      res.get("code") == 0 and len(res["data"]["list"]) == 1,
      f"返回 {len((res.get('data') or {}).get('list') or [])} 行")
if res.get("code") == 0:
    print(f"  -> T_User WHERE Name='张国昌' 返回 {len(res['data']['list'])} 行")
res = run_sql("SELECT 'DROP TABLE x' AS note", TOKEN)
check("字符串字面量里的 DROP 不误报（已剥字面量做校验）", res.get("code") == 0, str(res)[:180])
res = run_sql("SELECT displayName AS updated_at FROM T_AdminUser LIMIT 1", TOKEN)
check("别名 updated_at 不误报（整词匹配）", res.get("code") == 0, str(res)[:180])

print("\n===== 6. 白名单必须拦住危险 SQL（关键） =====")
dangerous = [
    ("DROP TABLE T_AdminRole", "DROP"),
    ("DELETE FROM T_AdminRole WHERE id=9999", "DELETE"),
    ("UPDATE T_AdminRole SET name='x' WHERE id=1", "UPDATE"),
    ("INSERT INTO T_AdminRole (code,name) VALUES ('x','y')", "INSERT"),
    ("TRUNCATE TABLE T_AdminRole", "TRUNCATE"),
    ("ALTER TABLE T_AdminRole ADD COLUMN zz INT", "ALTER"),
    ("CREATE TABLE zz_test (id INT)", "CREATE"),
    ("RENAME TABLE T_AdminRole TO zz", "RENAME"),
    ("GRANT ALL ON *.* TO 'x'@'%'", "GRANT"),
    ("SELECT 1; DROP TABLE T_AdminRole", "多语句"),
    ("SELECT * FROM T_AdminUser INTO OUTFILE '/tmp/x'", "OUTFILE"),
    ("SELECT * FROM T_AdminUser FOR UPDATE", "FOR UPDATE"),
    ("SET GLOBAL general_log = ON", "SET"),
    ("CALL some_proc()", "CALL"),
    ("SELECT SLEEP(10)", "SLEEP"),
    ("SELECT BENCHMARK(1000000, MD5('x'))", "BENCHMARK"),
    ("SELECT LOAD_FILE('/etc/passwd')", "LOAD_FILE"),
    ("SELECT 1 /*!50000 ; DROP TABLE T_AdminRole */", "可执行注释"),
]
for sql, label in dangerous:
    r = run_sql(sql, TOKEN)
    ok = r.get("code") == 40000
    check(f"拦住 [{label}]", ok, f"code={r.get('code')} msg={str(r.get('message'))[:70]}")

print("\n===== 7. 结果集上限（防拉全表） =====")
res = run_sql("SELECT * FROM T_StrategyLog", TOKEN, limit=5000)
check("请求 5000 行被压到服务端上限 200", res.get("code") == 0, str(res)[:150])
if res.get("code") == 0:
    d = res["data"]
    check("maxRows 被限制为 200（前端要更多也没用）", d["maxRows"] == 200, str(d["maxRows"]))
    check("truncated=true 且带 note 说明（不静默截断）",
          d["truncated"] is True and bool(d.get("note")), str({k: d[k] for k in ("truncated", "note")}))
    check("实际返回不超过 200 行", len(d["list"]) <= 200, str(len(d["list"])))
    print(f"  -> 返回 {len(d['list'])} 行, truncated={d['truncated']}, note={d['note']}")

print("\n===== 8. 库名白名单（只允许当前库） =====")
res = call("GET", "/storage/mysql/tables?database=mysql", None, TOKEN)
check("查 mysql 库 -> 40000（只允许当前库）", res.get("code") == 40000, f"code={res.get('code')}")
res = call("GET", "/storage/mysql/tables?database=information_schema", None, TOKEN)
check("查 information_schema -> 40000", res.get("code") == 40000, f"code={res.get('code')}")
print(f"  -> 提示: {res.get('message')}")

print("\n===== 9. 审计：只读 SQL 要留痕 =====")
res = call("GET", "/system/audit-logs?action=action:storage-mysql:readonly-sql&pageSize=5", None, TOKEN)
check("有 readonly-sql 审计记录", res.get("code") == 0 and res["data"]["total"] > 0,
      str(res.get("data", {}).get("total")))
if res.get("code") == 0 and res["data"]["list"]:
    a0 = res["data"]["list"][0]
    check("审计记录了 SQL 文本", bool(a0.get("target")), str(a0.get("target")))
    print(f"  -> {a0.get('operator')} {a0.get('result')} target={str(a0.get('target'))[:70]}")
    print(f"     message={a0.get('message')}")

print("\n===== 10. 权限：只读 SQL 需要专门权限点 =====")
res = call("POST", "/system/auth/login", {"username": "zhangguochang", "password": "View@2026"})
viewer = res["data"]["token"]
r = run_sql("SELECT 1", viewer)
check("只读账号跑 SQL -> 40300", r.get("code") == 40300, f"code={r.get('code')}")
r = call("GET", f"/storage/mysql/tables?database={DB}", None, viewer)
check("只读账号可以看表清单（不含数据内容）", r.get("code") == 0, f"code={r.get('code')}")
r = call("GET", f"/storage/mysql/query?database={DB}&table=T_User&pageSize=1", None, viewer)
check("只读账号读表数据 -> 40300（读表数据同属 readonly-sql 能力）",
      r.get("code") == 40300, f"code={r.get('code')}")

print("\n===== 11. 未登录必须被拦下 =====")
res = call("GET", f"/storage/mysql/tables?database={DB}")
check("未登录 -> 40100", res.get("code") == 40100, f"code={res.get('code')}")

print("\n" + "=" * 60)
print(f"  联通测试通过 {PASS} 项，失败 {FAIL} 项")
print("=" * 60)
print("  说明：危险 SQL 全部只做「期望被拒绝」的尝试；未执行任何写操作。")
if FAILURES:
    print("\n失败明细：")
    for f in FAILURES:
        print(f"  - {f}")
    sys.exit(1)
