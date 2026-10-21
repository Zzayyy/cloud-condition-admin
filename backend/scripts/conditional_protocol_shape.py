# -*- coding: utf-8 -*-
"""条件单写操作 —— 协议形态统计（**只读**）。

回答一个关键问题：**管理端该往 `ST-<编号>` 发什么形状的报文。**

已探明的事实（见 conditional_write_probe.py 的输出）：
  * `DataHub_req_stream` = 客户端 → 数据中台 的入口流，条目带
    `request_id / server_id / server_type / reply_req_stream / reply_reply_stream / task`；
    报文**不带 MsgType**，靠 task 的顶层键名区分协议。
  * `ST-<编号>` = 数据中台 → 策略平台 的下发流，条目只有 `request_id / task`；
    报文**带 MsgType**（4/8/11/17/18）。
  * 已知 `setByStrategy` 出现在 `DataHub_req_stream` 里且**不带 MsgType**。

本脚本统计两件事：
  1. `ST-*` 流里 task 的**协议键 + MsgType 组合分布**（决定管理端该发哪种形状）
  2. `DataHub_req_stream` 里 task 的**协议键 + server_type 组合分布**

只读：只用 SCAN / XREVRANGE / XINFO。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter

try:
    import redis
except ImportError:  # pragma: no cover
    print("需要 redis-py：pip install redis")
    sys.exit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.dirname(HERE)
APP_YML = os.path.join(BACKEND, "src", "main", "resources", "application.yml")

sys.path.insert(0, HERE)
from conditional_write_probe import load_instances, connect, scan_keys  # noqa: E402


def tally_stream(r, key: str, count: int) -> None:
    try:
        rows = r.xrevrange(key, count=count)
    except Exception as e:
        print("    %s 读取失败：%s" % (key, e))
        return
    proto = Counter()
    shapes = Counter()
    msgtypes = Counter()
    samples: dict[str, str] = {}
    bad = 0
    for _eid, fields in rows:
        task = fields.get("task", "") if isinstance(fields, dict) else ""
        if not task:
            continue
        try:
            obj = json.loads(task)
        except Exception:
            bad += 1
            continue
        if not isinstance(obj, dict):
            bad += 1
            continue
        mt = obj.get("MsgType")
        msgtypes[str(mt)] += 1
        for k in obj.keys():
            proto[k] += 1
            shapes["%s + MsgType=%s" % (k, mt)] += 1
            if k not in samples:
                samples[k] = task
    print("    %s：扫了 %d 条，解析失败 %d 条" % (key, len(rows), bad))
    if not proto:
        print("      （没有可解析的 task）")
        return
    print("      协议顶层键分布：%s" % dict(proto.most_common()))
    print("      MsgType 分布：%s" % dict(msgtypes.most_common()))
    print("      键 + MsgType 组合：")
    for combo, n in shapes.most_common():
        print("        %-40s %d 条" % (combo, n))
    for k, task in samples.items():
        print("      ── %s 的样本（截断 700 字）──" % k)
        print("        %s" % task[:700])


def main() -> int:
    ap = argparse.ArgumentParser(description="条件单写操作 · 协议形态统计（只读）")
    ap.add_argument("--host", default=None)
    ap.add_argument("--db", type=int, default=None)
    ap.add_argument("--count", type=int, default=300, help="每条流扫尾部多少条")
    ap.add_argument("--yml", default=APP_YML)
    a = ap.parse_args()

    insts = load_instances(a.yml)
    if a.host:
        insts = [i for i in insts if i["host"] == a.host]
    if a.db is not None:
        insts = [i for i in insts if int(i.get("db", 0)) == a.db]
    if not insts:
        print("没有匹配的实例")
        return 2

    for inst in insts:
        label = "%s db%s" % (inst["host"], inst.get("db", 0))
        try:
            r = connect(inst)
            r.ping()
        except Exception as e:
            print("!! %s 连不上：%s" % (label, e))
            continue

        print()
        print("=" * 78)
        print("实例 %s" % label)
        print("=" * 78)

        print("① ST-* 下发流（中台 → 策略平台）")
        for k in [x for x in scan_keys(r, "ST-*", limit=100) if not x.endswith("-reply")]:
            tally_stream(r, k, a.count)

        print()
        print("② DataHub_req_stream（客户端 → 中台）")
        tally_stream(r, "DataHub_req_stream", a.count)

        print()
        print("③ DataHub_reply_stream（回执）")
        tally_stream(r, "DataHub_reply_stream", a.count)

    print()
    print("=" * 78)
    print("统计结束（本脚本没有做任何写操作）")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
