# -*- coding: utf-8 -*-
"""条件单写操作 —— **只读**地基探查（不写、不删、不改任何东西）。

⚠️ 本脚本**只做读操作**：PING / SCAN / JSON.GET / XINFO / XREVRANGE。
   没有 XADD、没有 SET、没有 DEL、没有 JSON.SET。

它要回答的是「写操作落地前必须先知道、而仓库里查不到」的四件事：

  1. **策略服务器编号从哪来** —— 查 `acc-*` 里的 `strategyserverid`（现场已确认
     它就是「中台分配的编号」），顺便对照 `strategy-srv` 的 `id`（已知现场全是 0）。
  2. **`strategy-*` 条件单键的顶层结构** —— 数组字段名到底是 `Strategy` 还是
     `strategy`？仓库里两种说法都有（menu.ts 说 Strategy、StrategyList.vue 说
     strategy），而 `JSON.SET` 路径写错会**静默写到别的字段**。
  3. **真实回执长什么样** —— `DataHub_reply_stream` 尾部若干条的真实格式
     （回执解析必须按真实格式写，不能照猜）。
  4. **真实请求报文长什么样** —— `DataHub_req_stream` 尾部若干条的 `task`
     原文（`create` 的字段名大小写以实测为准，不以文档为准）。

另外顺带看一眼 `ST-*` 下发流：哪些编号上有消费者（consumer 名 = 流名本身
= 真平台；带 `-wN` = 本仓库的 mock）。

用法：
    python conditional_write_probe.py
    python conditional_write_probe.py --host 192.168.1.136 --db 1
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Any

try:
    import redis
except ImportError:  # pragma: no cover
    print("需要 redis-py：pip install redis")
    sys.exit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.dirname(HERE)
APP_YML = os.path.join(BACKEND, "src", "main", "resources", "application.yml")

# ---------------------------------------------------------------- 读配置

def load_instances(path: str) -> list[dict[str, Any]]:
    """从 application.yml 的 app.redis.instances 读实例清单。

    刻意不写死实例列表 —— 与「实例清单来自配置」这条原则一致，
    现场加机器不用改这个脚本。
    """
    if not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()

    out: list[dict[str, Any]] = []
    in_instances = False
    cur: dict[str, Any] = {}
    for raw in lines:
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        stripped = line.strip()

        if indent == 4 and stripped == "instances:":
            in_instances = True
            continue
        if in_instances and indent <= 4 and stripped.endswith(":"):
            in_instances = False
        if not in_instances:
            continue

        if stripped.startswith("- "):
            if cur:
                out.append(cur)
            cur = {}
            stripped = stripped[2:].strip()
        if ":" not in stripped:
            continue
        k, v = stripped.split(":", 1)
        v = v.strip().strip("'\"")
        if k == "id":
            cur["id"] = v
        elif k == "host":
            cur["host"] = v
        elif k == "port":
            cur["port"] = int(v or 6379)
        elif k == "db":
            cur["db"] = int(v or 0)
        elif k == "password":
            cur["password"] = v
    if cur:
        out.append(cur)
    return [i for i in out if i.get("host")]


# ---------------------------------------------------------------- 小工具

def jdump(v: Any, limit: int = 400) -> str:
    try:
        s = json.dumps(v, ensure_ascii=False, default=str)
    except Exception:
        s = repr(v)
    return s if len(s) <= limit else s[:limit] + f"…(共{len(s)}字)"


def section(title: str) -> None:
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def connect(inst: dict[str, Any], timeout: float = 5.0):
    return redis.Redis(
        host=inst["host"],
        port=inst.get("port", 6379),
        password=inst.get("password") or None,
        db=inst.get("db", 0),
        socket_timeout=timeout,
        socket_connect_timeout=timeout,
        decode_responses=True,
    )


def scan_keys(r, pattern: str, limit: int = 200) -> list[str]:
    """SCAN 扫描（绝不用 KEYS）。返回最多 limit 个键。"""
    out: list[str] = []
    for k in r.scan_iter(match=pattern, count=200):
        out.append(k if isinstance(k, str) else k.decode())
        if len(out) >= limit:
            break
    return out


def json_get(r, key: str):
    """读 ReJSON 键。⚠️ 必须用 JSON.GET，用 GET 会 WRONGTYPE。"""
    try:
        raw = r.execute_command("JSON.GET", key)
    except Exception:
        return None
    if not raw:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return raw


def stream_tail(r, key: str, count: int = 5) -> list[tuple[str, dict[str, str]]]:
    """取流尾部若干条。⚠️ 用 XREVRANGE 且限定 count，不全量拉。"""
    try:
        rows = r.xrevrange(key, count=count)
    except Exception:
        return []
    out = []
    for entry_id, fields in rows:
        out.append((entry_id, fields if isinstance(fields, dict) else {}))
    return out


# ---------------------------------------------------------------- 各段探查

def probe_acc(r, label: str) -> dict[str, Any]:
    """① acc-* → strategyserverid（策略服务器编号的来源）。"""
    keys = scan_keys(r, "acc-*")
    print(f"  实例 {label}：acc-* 共 {len(keys)} 个")
    rows = []
    for k in keys:
        obj = json_get(r, k)
        if not isinstance(obj, dict):
            continue
        rows.append({
            "key": k,
            "useraccount": obj.get("useraccount"),
            "accounttype": obj.get("accounttype"),
            "maintype": obj.get("maintype"),
            "strategyserverid": obj.get("strategyserverid"),
            "traderserver": obj.get("traderserver"),
            "onlineflag": obj.get("onlineflag"),
        })
    if not rows:
        print("    （没有读到 acc-* 的 JSON）")
        return {}
    print("  %-34s %-12s %-10s %-16s %-14s" % ("key", "useraccount", "accounttype",
                                               "strategyserverid", "traderserver"))
    for x in rows:
        print("  %-34s %-12s %-10s %-16s %-14s" % (
            x["key"], x["useraccount"], x["accounttype"],
            x["strategyserverid"], x["traderserver"]))

    ids = [x["strategyserverid"] for x in rows if x["strategyserverid"] is not None]
    print(f"  → strategyserverid 取值集合：{sorted(set(map(str, ids)))}")
    if ids:
        print("  → 唯一值个数：%d（>1 说明不同账号分到不同策略平台）" % len(set(map(str, ids))))
    return {"rows": rows, "ids": ids}


def probe_strategy_srv(r, label: str) -> None:
    """①b strategy-srv 注册表里的 id（现场已知全是 0，核对一下）。"""
    obj = json_get(r, "strategy-srv")
    print(f"  实例 {label}：strategy-srv = {jdump(obj, 300)}")
    if isinstance(obj, dict):
        arr = obj.get("strategy-srv") or []
        for i, it in enumerate(arr):
            if isinstance(it, dict):
                print("    [%d] id=%r ip=%r mac=%r usecount=%r enable=%r lastActiveTimeLong=%r" % (
                    i, it.get("id"), it.get("ip"), it.get("mac"),
                    it.get("usecount"), it.get("enable"), it.get("lastActiveTimeLong")))


def probe_strategy_keys(r, label: str) -> None:
    """② strategy-* 条件单键：顶层键名 / 数组长度 / 元素字段。"""
    keys = [k for k in scan_keys(r, "strategy-*", limit=500) if k != "strategy-srv"]
    print(f"  实例 {label}：strategy-*（排除 strategy-srv）共 {len(keys)} 个")
    srv_ids: set[str] = set()
    modes: dict[str, int] = {}
    flags: dict[str, int] = {}
    statuses: dict[str, int] = {}
    total = 0
    for k in keys[:8]:
        obj = json_get(r, k)
        if not isinstance(obj, dict):
            print("    %s → 非对象：%s" % (k, jdump(obj, 120)))
            continue
        top_keys = list(obj.keys())
        print("    %s" % k)
        print("      顶层键：%s" % top_keys)
        for tk in top_keys:
            v = obj.get(tk)
            if isinstance(v, list):
                print("      数组字段 %r 长度=%d" % (tk, len(v)))
                total += len(v)
                for it in v:
                    if not isinstance(it, dict):
                        continue
                    # ★ 元素自带的策略服务器编号 —— 比 acc-*.strategyserverid 可靠
                    srv_ids.add(str(it.get("strategyServerID")))
                    for field, acc in (("mode", modes), ("flag", flags),
                                       ("status", statuses)):
                        acc[str(it.get(field))] = acc.get(str(it.get(field)), 0) + 1
                if v and isinstance(v[0], dict):
                    print("        元素字段：%s" % list(v[0].keys()))
                    sample = v[0]
                    for field in ("id", "Account", "UserAccount", "Status", "Flag",
                                  "Mode", "Type", "Name", "AddTime", "ModifyTime",
                                  "strategyServerID", "syncflag", "procflag"):
                        if field in sample:
                            val = sample[field]
                            if isinstance(val, str) and len(val) > 120:
                                val = val[:120] + "…"
                            print("        %-18s = %r" % (field, val))
                    if "Strategy" in sample or "strategy" in sample:
                        raw = sample.get("Strategy") or sample.get("strategy")
                        print("        原始内容前 200 字：%r" % (str(raw)[:200],))
            else:
                print("      非数组字段 %r = %s" % (tk, jdump(v, 120)))
    if total:
        print("  → 条件单元素合计 %d 条" % total)
        print("  → 元素里的 strategyServerID 取值集合：%s" % sorted(srv_ids))
        print("  → mode 分布：%s" % dict(sorted(modes.items())))
        print("  → flag 分布：%s" % dict(sorted(flags.items())))
        print("  → status 分布：%s" % dict(sorted(statuses.items())))


def probe_streams(r, label: str) -> None:
    """④ ST-* 下发流：谁在上面消费（真平台 vs mock）+ 尾部报文原文。"""
    keys = [k for k in scan_keys(r, "ST-*", limit=200) if not k.endswith("-reply")]
    print(f"  实例 {label}：ST-* 下发流（排除 -reply）共 {len(keys)} 个：{keys[:20]}")
    for k in keys[:20]:
        try:
            groups = r.xinfo_groups(k)
        except Exception:
            continue
        for g in groups:
            gname = g.get("name")
            cons = []
            try:
                cons = [c.get("name") for c in r.xinfo_consumers(k, gname)]
            except Exception:
                pass
            print("    %-12s group=%-12s 消费者=%s（=流名本身=真平台；带 -wN = 本仓库 mock）"
                  % (k, gname, cons))

        # ★ 流里的报文才是「中台 → 策略平台」方向的真实原文，比 req_stream 更准
        rows = stream_tail(r, k, count=8)
        if not rows:
            continue
        print("    ── %s 尾部 %d 条报文原文 ──" % (k, len(rows)))
        for entry_id, fields in rows:
            task = fields.get("task", "")
            print("      #%s  request_id=%s" % (entry_id, fields.get("request_id")))
            try:
                obj = json.loads(task)
                if isinstance(obj, dict):
                    print("        顶层键：%s" % list(obj.keys()))
                    for kk, vv in obj.items():
                        if isinstance(vv, dict):
                            print("          %s → %s" % (kk, jdump(vv, 600)))
                        else:
                            print("          %s → %r" % (kk, vv))
                else:
                    print("        task 非对象：%s" % jdump(task, 300))
            except Exception:
                print("        task 原文：%s" % jdump(task, 400))


def probe_reply(r, label: str) -> None:
    """③ 真实回执格式。"""
    for key in ("DataHub_reply_stream",):
        rows = stream_tail(r, key, count=5)
        print(f"  实例 {label}：{key} 尾部 {len(rows)} 条")
        for entry_id, fields in rows:
            print("    #%s" % entry_id)
            for k, v in fields.items():
                print("      %-14s = %s" % (k, jdump(v, 320)))


def probe_request(r, label: str) -> None:
    """④ 真实请求报文（task 原文）—— create 的字段名大小写以这里为准。"""
    for key in ("DataHub_req_stream",):
        rows = stream_tail(r, key, count=3)
        print(f"  实例 {label}：{key} 尾部 {len(rows)} 条")
        for entry_id, fields in rows:
            print("    #%s" % entry_id)
            for k, v in fields.items():
                if k == "task":
                    print("      %-14s = %s" % (k, jdump(v, 900)))
                else:
                    print("      %-14s = %s" % (k, jdump(v, 200)))
            # 把 task 里的顶层键与子对象键列出来，好比对大小写
            try:
                obj = json.loads(fields.get("task", ""))
                if isinstance(obj, dict):
                    print("      → task 顶层键：%s" % list(obj.keys()))
                    for k, v in obj.items():
                        if isinstance(v, dict):
                            print("        %s 的子键：%s" % (k, list(v.keys())[:20]))
            except Exception:
                pass


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description="条件单写操作 · 只读地基探查")
    ap.add_argument("--host", default=None, help="只看某一台")
    ap.add_argument("--db", type=int, default=None, help="只看某一个 db")
    ap.add_argument("--yml", default=APP_YML, help="application.yml 路径")
    a = ap.parse_args()

    insts = load_instances(a.yml)
    if not insts:
        print("读不到 %s 里的 app.redis.instances" % a.yml)
        return 2
    if a.host:
        insts = [i for i in insts if i["host"] == a.host]
    if a.db is not None:
        insts = [i for i in insts if int(i.get("db", 0)) == a.db]
    if not insts:
        print("没有匹配的实例")
        return 2

    print("探查实例：%s" % [(i["host"], i.get("db", 0)) for i in insts])
    print("（本脚本只读：PING / SCAN / JSON.GET / XINFO / XREVRANGE）")

    for inst in insts:
        label = "%s db%s" % (inst["host"], inst.get("db", 0))
        try:
            r = connect(inst)
            r.ping()
        except Exception as e:
            print()
            print("!! 实例 %s 连不上：%s" % (label, e))
            continue

        section("实例 %s —— 连接正常" % label)
        r = connect(inst)

        section("① acc-* → strategyserverid（策略服务器编号的来源）")
        try:
            probe_acc(r, label)
        except Exception as e:
            print("  异常：%s" % e)

        section("①b strategy-srv 注册表（对照：它的 id 现场全是 0）")
        try:
            probe_strategy_srv(r, label)
        except Exception as e:
            print("  异常：%s" % e)

        section("② strategy-* 条件单键（顶层数组字段名到底是 Strategy 还是 strategy）")
        try:
            probe_strategy_keys(r, label)
        except Exception as e:
            print("  异常：%s" % e)

        section("③ 真实回执格式（DataHub_reply_stream 尾部）")
        try:
            probe_reply(r, label)
        except Exception as e:
            print("  异常：%s" % e)

        section("④ 真实请求报文（DataHub_req_stream 尾部 · task 原文）")
        try:
            probe_request(r, label)
        except Exception as e:
            print("  异常：%s" % e)

        section("⑤ ST-* 下发流与消费者（真平台 / mock 的区分）")
        try:
            probe_streams(r, label)
        except Exception as e:
            print("  异常：%s" % e)

    print()
    print("=" * 78)
    print("探查结束（本脚本没有做任何写操作）")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
