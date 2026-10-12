# -*- coding: utf-8 -*-
"""
一键跑完整验证：每个测试套件前都重灌测试数据。

为什么每个套件都要重灌：这些测试会真的改数据（解锁账号、改密码、写审计、建账号）。
不重灌时第二个套件的失败往往源自第一个套件的副作用，属于**测试污染**而非产品缺陷，
会把人误导到错误的方向上排查（本次开发已经真实踩到：page_sim 报「锁定账号缺失」，
实际是 e2e 刚刚把 test_tmp 解锁了）。

用法：python run_all_tests.py
"""
from __future__ import annotations

import os
import subprocess
import sys

from paths import here_dir, python_exe

HERE = here_dir()
PY = python_exe()
ENV = {**os.environ, "PYTHONIOENCODING": "utf-8"}

SUITES = [
    ("接口端到端（行为 + 错误分支）", "e2e_test.py"),
    ("前后端契约（字段名与类型）", "contract_test.py"),
    ("三页面行为重放（数据够不够用）", "page_sim_test.py"),
]


def run(args: list[str]) -> int:
    return subprocess.call(args, env=ENV, cwd=HERE)


def main() -> int:
    results: list[tuple[str, int]] = []
    for index, (title, script) in enumerate(SUITES, start=1):
        print("\n" + "#" * 78)
        print(f"# [{index}/{len(SUITES)}] {title}")
        print("#" * 78)
        print("-- 重灌测试数据 --")
        rc = run([PY, os.path.join(HERE, "apply_sql.py"), "schema"])
        if rc != 0:
            print(f"重灌失败（exit {rc}），终止")
            return rc
        print(f"-- 执行 {script} --")
        rc = run([PY, os.path.join(HERE, script)])
        results.append((title, rc))

    print("\n" + "=" * 78)
    print("  总结")
    print("=" * 78)
    failed = 0
    for title, rc in results:
        status = "通过" if rc == 0 else f"失败 (exit {rc})"
        print(f"  {status:16s} {title}")
        if rc != 0:
            failed += 1
    print("=" * 78)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
