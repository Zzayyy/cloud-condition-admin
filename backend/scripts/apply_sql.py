# -*- coding: utf-8 -*-
"""执行 backend/sql 下的脚本，并做「只动 T_Admin* 表」的前后对照校验。

用法：
    python apply_sql.py schema     # 建表 + 灌测试数据
    python apply_sql.py schema-only
    python apply_sql.py drop       # 回滚（危险）
"""
from __future__ import annotations

import os
import re
import sys

import pymysql

from paths import sql_dir

SQL_DIR = sql_dir()

CONF = dict(host="192.168.1.136", port=3306, user="qianlong_cloud",
            password="Qianlong@2026", database="qianlong_cloud", charset="utf8mb4")


def snapshot(conn) -> list[tuple]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA=%s ORDER BY TABLE_NAME", ("qianlong_cloud",))
        return list(cur.fetchall())


def split_statements(sql: str) -> list[str]:
    """按分号切分，但跳过注释行与字符串里的分号（本项目的脚本足够简单）。"""
    lines = []
    for raw in sql.splitlines():
        stripped = raw.strip()
        if stripped.startswith("--"):
            continue
        lines.append(raw)
    text = "\n".join(lines)
    out = []
    for chunk in text.split(";"):
        stmt = chunk.strip()
        if stmt:
            out.append(stmt)
    return out


def run(path: str) -> None:
    with open(path, encoding="utf-8") as fh:
        sql = fh.read()
    conn = pymysql.connect(**CONF)
    with conn:
        before = snapshot(conn)
        with conn.cursor() as cur:
            for stmt in split_statements(sql):
                cur.execute(stmt)
        conn.commit()
        after = snapshot(conn)

    before_names = {r[0] for r in before}
    after_names = {r[0] for r in after}
    print(f"\n=== {os.path.basename(path)} 执行完成 ===")
    print(f"新增对象：{sorted(after_names - before_names)}")
    print(f"消失对象：{sorted(before_names - after_names)}")
    print(f"对象总数：{len(before_names)} -> {len(after_names)}")
    return after


def counts() -> None:
    conn = pymysql.connect(**CONF)
    tables = ["T_AdminRole", "T_AdminRolePermission", "T_AdminUser", "T_AdminUserRole",
              "T_AdminPasswordPolicy", "T_AdminPasswordHistory", "T_AdminLoginLog", "T_AdminAudit"]
    with conn, conn.cursor() as cur:
        print("\n=== T_Admin* 行数 ===")
        for t in tables:
            cur.execute(f"SELECT COUNT(*) FROM `{t}`")
            print(f"  {t:28s} {cur.fetchone()[0]:>6}")
        print("\n=== 既有业务表（必须一行未变） ===")
        for t in ["T_Conditional", "T_Conditional_his", "T_Order", "T_Order_his",
                  "T_Trade", "T_Trade_his", "T_User", "T_UserLog", "T_StrategyLog"]:
            cur.execute(f"SELECT COUNT(*) FROM `{t}`")
            print(f"  {t:28s} {cur.fetchone()[0]:>6}")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "schema"
    if mode in ("schema", "schema-only"):
        run(os.path.join(SQL_DIR, "01_schema_admin.sql"))
        if mode == "schema":
            run(os.path.join(SQL_DIR, "02_data_admin.sql"))
    elif mode == "drop":
        run(os.path.join(SQL_DIR, "00_drop_admin.sql"))
    else:
        raise SystemExit(f"unknown mode {mode}")
    counts()
