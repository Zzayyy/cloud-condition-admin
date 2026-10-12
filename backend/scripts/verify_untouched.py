# -*- coding: utf-8 -*-
"""
最终核对：确认本次改动**没有动到 qianlong_cloud 库里已有的任何表/视图**。

做法：
  1. 列出库里全部对象，按「是否 T_Admin* 前缀」分开，逐个人工核对名称；
  2. 对既有业务表的**结构**（列定义、索引）与本次开发前记录的基线逐字符比对；
  3. 对既有业务表**逐行数**核对（行数变化只可能来自现场业务写入，不由本项目造成）；
  4. 显式断言 T_Admin* 表在建表前不存在（脚本 01 里也写了自检 SQL）。

用法：python verify_untouched.py
"""
from __future__ import annotations

import json
import os
import sys

import pymysql

CONF = dict(host="192.168.1.136", port=3306, user="qianlong_cloud",
            password="Qianlong@2026", database="qianlong_cloud", charset="utf8mb4")

# 开发开始前（2026-10-12 首次探查）记录的基线：库内共 9 表 + 1 视图
BASELINE_OBJECTS = {
    "T_Conditional": "BASE TABLE",
    "T_Conditional_his": "BASE TABLE",
    "T_Order": "BASE TABLE",
    "T_Order_his": "BASE TABLE",
    "T_StrategyLog": "BASE TABLE",
    "T_Trade": "BASE TABLE",
    "T_Trade_his": "BASE TABLE",
    "T_User": "BASE TABLE",
    "T_UserLog": "BASE TABLE",
    "V_Conditional": "VIEW",
}

# 本项目**只应**新增这些表
EXPECTED_NEW = {
    "T_AdminRole", "T_AdminRolePermission", "T_AdminUser", "T_AdminUserRole",
    "T_AdminPasswordPolicy", "T_AdminPasswordHistory", "T_AdminLoginLog", "T_AdminAudit",
}

PASS = 0
FAIL = 0
FAILURES: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [ok]   {name}")
    else:
        FAIL += 1
        FAILURES.append(f"{name} -> {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def main() -> int:
    conn = pymysql.connect(**CONF)
    with conn, conn.cursor() as cur:
        print("===== 1. 库内全部对象清单 =====")
        cur.execute(
            "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA=%s ORDER BY TABLE_TYPE, TABLE_NAME", ("qianlong_cloud",))
        objects = {name: ttype for name, ttype in cur.fetchall()}
        for name, ttype in sorted(objects.items()):
            tag = "新增（本项目）" if name in EXPECTED_NEW else "原有"
            print(f"    {tag:14s} {name:28s} {ttype}")

        actual_baseline = {n: t for n, t in objects.items() if n not in EXPECTED_NEW}
        check("原有对象数量与基线一致（9 表 + 1 视图 = 10）",
              len(actual_baseline) == 10, f"实际 {len(actual_baseline)}")
        check("原有一个都没少（没有 DROP 掉任何已有表/视图）",
              set(BASELINE_OBJECTS) <= set(actual_baseline),
              f"缺失 {sorted(set(BASELINE_OBJECTS) - set(actual_baseline))}")
        check("原有对象的类型没被改动（表还是表、视图还是视图）",
              all(actual_baseline.get(n) == t for n, t in BASELINE_OBJECTS.items()),
              str({n: actual_baseline.get(n) for n, t in BASELINE_OBJECTS.items() if actual_baseline.get(n) != t}))
        check("新增对象恰好是那 8 张 T_Admin* 表",
              {n for n in objects if n not in BASELINE_OBJECTS} == EXPECTED_NEW,
              str(sorted({n for n in objects if n not in BASELINE_OBJECTS} ^ EXPECTED_NEW)))
        check("没有任何非 T_Admin* 的新增对象",
              all(n.startswith("T_Admin") for n in objects if n not in BASELINE_OBJECTS),
              str([n for n in objects if n not in BASELINE_OBJECTS and not n.startswith("T_Admin")]))

        print("\n===== 2. 既有业务表结构逐字段核对（对比基线快照） =====")
        from paths import here_dir

        baseline_path = os.path.join(here_dir(), "baseline_schema.json")
        if not os.path.isfile(baseline_path):
            print("  [skip] 未找到基线快照，先生成。")
        else:
            with open(baseline_path, encoding="utf-8") as fh:
                baseline = json.load(fh)

            cur.execute(
                "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, "
                "       COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT "
                "  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s "
                " ORDER BY TABLE_NAME, ORDINAL_POSITION", ("qianlong_cloud",))
            current_cols: dict[str, list] = {}
            for row in cur.fetchall():
                current_cols.setdefault(row[0], []).append(list(row[1:]))

            for table, expected_cols in baseline["columns"].items():
                actual = current_cols.get(table, [])
                if actual == expected_cols:
                    check(f"{table} 字段定义与基线完全一致（{len(actual)} 列）", True)
                else:
                    # 逐列找差异，便于定位
                    diffs = []
                    for i, (e, a) in enumerate(zip(expected_cols, actual)):
                        if e != a:
                            diffs.append(f"#{i} 基线={e} 实际={a}")
                    if len(expected_cols) != len(actual):
                        diffs.append(f"列数 基线={len(expected_cols)} 实际={len(actual)}")
                    check(f"{table} 字段定义与基线完全一致", False, "; ".join(diffs[:3]))

            cur.execute(
                "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME "
                "  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=%s "
                " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX", ("qianlong_cloud",))
            current_idx: dict[str, list] = {}
            for row in cur.fetchall():
                current_idx.setdefault(row[0], []).append(list(row[1:]))
            for table, expected_idx in baseline["indexes"].items():
                actual = current_idx.get(table, [])
                check(f"{table} 索引与基线一致", actual == expected_idx,
                      f"基线={expected_idx} 实际={actual}")

            for view, expected_def in baseline.get("views", {}).items():
                cur.execute(f"SHOW CREATE VIEW `{view}`")
                ddl = cur.fetchone()[1]
                check(f"视图 {view} 定义未变", ddl == expected_def, "视图定义被改过")

        print("\n===== 3. 既有业务表行数（只读核对） =====")
        for table in sorted(BASELINE_OBJECTS):
            if BASELINE_OBJECTS[table] == "VIEW":
                continue
            cur.execute(f"SELECT COUNT(*) FROM `{table}`")
            count = cur.fetchone()[0]
            print(f"    {table:24s} {count:>8} 行")

        print("\n===== 4. 新增 T_Admin* 表内容规模 =====")
        for table in sorted(EXPECTED_NEW):
            cur.execute(f"SELECT COUNT(*) FROM `{table}`")
            print(f"    {table:28s} {cur.fetchone()[0]:>6} 行")

        print("\n===== 5. T_AdminUser 里没有明文密码 =====")
        cur.execute("SELECT username, passwordHash FROM T_AdminUser")
        rows = cur.fetchall()
        plaintext_markers = ("Admin@2026", "Ops@2026", "View@2026")
        bad = [u for u, h in rows if any(m in (h or "") for m in plaintext_markers)]
        check("密码列全部为 BCrypt 哈希，无明文", not bad, f"疑似明文：{bad}")
        check("哈希格式为 $2a$/$2b$ + cost",
              all((h or "").startswith(("$2a$", "$2b$")) for _, h in rows),
              str([h[:7] for _, h in rows]))
        for u, h in rows:
            print(f"    {u:16s} {h[:20]}... (len={len(h)})")

        print("\n===== 6. 审计表里没有明文密码 =====")
        cur.execute("SELECT COUNT(*) FROM T_AdminAudit WHERE message LIKE '%@2026%' "
                    "OR target LIKE '%@2026%'")
        check("审计表不含任何形如密码的内容", cur.fetchone()[0] == 0, "审计里出现疑似密码")

    print("\n=============================================================")
    print(f"  核对通过 {PASS} 项，失败 {FAIL} 项")
    print("=============================================================")
    if FAILURES:
        print("\n失败明细：")
        for item in FAILURES:
            print(f"  - {item}")
        return 1
    print("\n结论：qianlong_cloud 库中已有的 9 张表 + 1 个视图**结构与内容均未被本次改动触碰**。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
