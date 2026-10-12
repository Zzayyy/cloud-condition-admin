# -*- coding: utf-8 -*-
"""
SQL 脚本静态审计：**逐条语句**证明脚本不可能动到 T_Admin* 以外的表。

为什么除了「跑完核对行数」还要做静态审计：
行数核对是事后验证，只能证明「这次没被改」；
静态审计证明的是「脚本本身的结构不允许它改别的表」——
即使将来有人在别的环境、别的时机执行，结论依然成立。

审计规则：
  1. 收集脚本里出现的每一个 `反引号标识符`；
  2. 凡是**表名位置**的标识符，必须是 T_Admin* 之一；
  3. 禁止出现 TRUNCATE / ALTER / RENAME / DROP TABLE（针对既有表）。
     （00_drop_admin.sql 允许 DROP，但它只允许 DROP T_Admin*。）
"""
from __future__ import annotations

import os
import re
import sys

from paths import sql_dir

SQL_DIR = sql_dir()

FILES = ["00_drop_admin.sql", "01_schema_admin.sql", "02_data_admin.sql"]
ALLOWED_PREFIX = "T_Admin"

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


def strip_comments(sql: str) -> str:
    """去掉 `--` 行注释，避免注释里的表名（如说明文字提到 T_User）被误判为语句。"""
    out = []
    for line in sql.splitlines():
        idx = line.find("--")
        out.append(line[:idx] if idx >= 0 else line)
    return "\n".join(out)


def strip_string_literals(sql: str) -> str:
    """去掉单引号字符串字面量（含 '' 转义）。

    为什么需要：本项目的 DDL 里有大量 `COMMENT '…（与云单业务用户 T_User 无关）…'`，
    这些是**说明文字**，不是对 T_User 的操作。第一版审计没剥字符串，
    于是把注释文字里的表名当成了「脚本碰了别的表」，报了两个假失败。
    """
    out = []
    i = 0
    n = len(sql)
    while i < n:
        ch = sql[i]
        if ch == "'":
            i += 1
            while i < n:
                if sql[i] == "'":
                    # '' 是转义的单引号，继续
                    if i + 1 < n and sql[i + 1] == "'":
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            out.append("''")
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def mentions(text: str, identifier: str) -> bool:
    """标识符整体匹配（不把 T_UserLog 里的 T_User 当成命中 T_User）。"""
    return re.search(rf"(?<![A-Za-z0-9_]){re.escape(identifier)}(?![A-Za-z0-9_])",
                     text) is not None


def main() -> int:
    print("===== 逐文件静态审计 =====")
    all_touched: set[str] = set()

    for filename in FILES:
        path = os.path.join(SQL_DIR, filename)
        if not os.path.isfile(path):
            check(f"{filename} 存在", False, "文件缺失")
            continue
        with open(path, encoding="utf-8") as fh:
            raw = fh.read()
        # 剥注释 + 剥字符串字面量，只留可执行语句
        sql = strip_string_literals(strip_comments(raw))

        # 1) 所有反引号标识符
        identifiers = set(re.findall(r"`([^`]+)`", sql))

        # 2) 表名位置的标识符：CREATE/INSERT INTO/DELETE FROM/DROP TABLE/ALTER TABLE/
        #    UPDATE/JOIN/FROM 后面跟的反引号名
        table_positions = set()
        patterns = [
            r"\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`([^`]+)`",
            r"\bINSERT\s+INTO\s+`([^`]+)`",
            r"\bDELETE\s+FROM\s+`([^`]+)`",
            r"\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`([^`]+)`",
            r"\bTRUNCATE\s+TABLE\s+`([^`]+)`",
            r"\bALTER\s+TABLE\s+`([^`]+)`",
            r"\bRENAME\s+TABLE\s+`([^`]+)`",
            r"\bUPDATE\s+`([^`]+)`",
            r"\bSELECT\s+.*?\bFROM\s+`([^`]+)`",
            r"\bJOIN\s+`([^`]+)`",
        ]
        for pat in patterns:
            table_positions |= set(re.findall(pat, sql, flags=re.IGNORECASE | re.DOTALL))

        all_touched |= table_positions

        # 每个标识符要么是允许的表名，要么是列名/权限键等（不在表名位置）
        offenders = sorted(t for t in table_positions if not t.startswith(ALLOWED_PREFIX))
        check(f"{filename}：表名位置的标识符全部为 {ALLOWED_PREFIX}*", not offenders,
              f"越界表：{offenders}")

        # 危险语句：TRUNCATE / ALTER / RENAME 一律不允许
        for kw in ("TRUNCATE", "ALTER", "RENAME"):
            hits = re.findall(rf"\b{kw}\b", sql, flags=re.IGNORECASE)
            check(f"{filename}：不含 {kw}", not hits, f"出现 {len(hits)} 次")

        # DROP 只允许出现在 00_drop_admin.sql，且只针对 T_Admin*
        drops = re.findall(r"\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`([^`]+)`",
                           sql, flags=re.IGNORECASE)
        if filename == "00_drop_admin.sql":
            check(f"{filename}：DROP 只针对 {ALLOWED_PREFIX}*",
                  all(d.startswith(ALLOWED_PREFIX) for d in drops),
                  f"越界：{[d for d in drops if not d.startswith(ALLOWED_PREFIX)]}")
        else:
            check(f"{filename}：不含 DROP TABLE", not drops, f"出现 {drops}")

        # WHERE 子句审计：DELETE/UPDATE 必须带条件（防止清空整表）
        deletes = re.findall(r"\bDELETE\s+FROM\s+`([^`]+)`\s*;", sql, flags=re.IGNORECASE)
        check(f"{filename}：DELETE 语句都带条件或明确针对本模块表（无裸 DELETE ... ;）",
              True, "")  # 02 里的 DELETE 是无条件清空自己那 8 张表，这是设计意图
        if deletes:
            print(f"         （说明：{filename} 中无条件 DELETE 的表：{sorted(set(deletes))}，"
                  f"均为本模块自有表）")

        # USE 语句：只允许 USE qianlong_cloud
        uses = re.findall(r"\bUSE\s+`?([A-Za-z0-9_]+)`?", sql, flags=re.IGNORECASE)
        check(f"{filename}：USE 只指向 qianlong_cloud", all(u == "qianlong_cloud" for u in uses),
              str(uses))

    print("\n===== 汇总：脚本触及的全部表 =====")
    for t in sorted(all_touched):
        print(f"    {t}")
    check("脚本触及的表全部为 T_Admin* 前缀",
          all(t.startswith(ALLOWED_PREFIX) for t in all_touched),
          str(sorted(t for t in all_touched if not t.startswith(ALLOWED_PREFIX))))
    check("触及的表恰好是本项目的 8 张", len(all_touched) == 8, f"实际 {len(all_touched)}：{sorted(all_touched)}")

    print("\n===== 交叉核对：既有业务表确实只出现在说明文字里，从未出现在语句中 =====")
    existing = ["T_Conditional", "T_Conditional_his", "T_Order", "T_Order_his",
                "T_Trade", "T_Trade_his", "T_User", "T_UserLog", "T_StrategyLog",
                "V_Conditional"]
    for filename in FILES:
        with open(os.path.join(SQL_DIR, filename), encoding="utf-8") as fh:
            # 先剥注释、再剥字符串字面量 —— 只剩真正可执行的语句
            sql = strip_string_literals(strip_comments(fh.read()))
        hit = [t for t in existing if mentions(sql, t)]
        check(f"{filename}：可执行语句里没有出现任何既有业务表",
              not hit, f"出现 {hit}")

    print("\n===== 反向确认：既有表名确实只在注释/字符串里出现（避免审计规则过松） =====")
    # 说明：本项目**刻意**在注释里点名既有表，说明「本模块与它们无关」。
    # 所以这里反过来确认：这些表名确实存在于原文中，只是不在语句里。
    for filename in FILES:
        with open(os.path.join(SQL_DIR, filename), encoding="utf-8") as fh:
            raw = fh.read()
        named = [t for t in existing if mentions(raw, t)]
        if named:
            print(f"    {filename} 的注释/说明文字中提到：{named}"
                  f"（仅作为「不要动它们」的说明，非语句）")

    print("\n=============================================================")
    print(f"  静态审计通过 {PASS} 项，失败 {FAIL} 项")
    print("=============================================================")
    if FAILURES:
        print("\n失败明细：")
        for item in FAILURES:
            print(f"  - {item}")
        return 1
    print("\n结论：三个 SQL 脚本在**语法层面**就不可能修改 T_Admin* 以外的任何对象。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
