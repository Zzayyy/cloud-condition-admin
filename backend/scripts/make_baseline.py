# -*- coding: utf-8 -*-
"""
生成/刷新既有业务表的结构基线快照。

⚠️ 只读：只查 information_schema 与 SHOW CREATE，不写任何数据。

用途：给 verify_untouched.py 提供比对基线。基线一旦生成就不应再变；
若某次核对报「结构不一致」，说明有人真的改了既有表，要去查清楚是谁改的。
"""
from __future__ import annotations

import json
import os

import pymysql

CONF = dict(host="192.168.1.136", port=3306, user="qianlong_cloud",
            password="Qianlong@2026", database="qianlong_cloud", charset="utf8mb4")

# 只对**既有业务对象**建基线；T_Admin* 是本项目新建的，不在此列
TABLES = ["T_Conditional", "T_Conditional_his", "T_Order", "T_Order_his",
          "T_Trade", "T_Trade_his", "T_User", "T_UserLog", "T_StrategyLog"]
VIEWS = ["V_Conditional"]

from paths import here_dir

OUT = os.path.join(here_dir(), "baseline_schema.json")


def main() -> None:
    conn = pymysql.connect(**CONF)
    with conn, conn.cursor() as cur:
        columns: dict[str, list] = {}
        cur.execute(
            "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, "
            "       COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT "
            "  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s "
            " ORDER BY TABLE_NAME, ORDINAL_POSITION", ("qianlong_cloud",))
        for row in cur.fetchall():
            if row[0] in TABLES:
                columns.setdefault(row[0], []).append(list(row[1:]))

        indexes: dict[str, list] = {}
        cur.execute(
            "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME "
            "  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=%s "
            " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX", ("qianlong_cloud",))
        for row in cur.fetchall():
            if row[0] in TABLES:
                indexes.setdefault(row[0], []).append(list(row[1:]))

        view_defs: dict[str, str] = {}
        for v in VIEWS:
            cur.execute(f"SHOW CREATE VIEW `{v}`")
            view_defs[v] = cur.fetchone()[1]

    payload = {"columns": columns, "indexes": indexes, "views": view_defs}
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    print(f"基线已写入 {OUT}")
    print(f"  表 {len(columns)} 张，列定义 {sum(len(v) for v in columns.values())} 条，"
          f"索引 {sum(len(v) for v in indexes.values())} 条，视图 {len(view_defs)} 个")


if __name__ == "__main__":
    main()
