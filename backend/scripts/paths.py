# -*- coding: utf-8 -*-
"""定位项目内的关键目录 —— 位置无关。

这几个验证脚本既可以放在 `.dsh/`（开发时临时用），也可以放在
`cloud-condition-admin/backend/scripts/`（交付给团队复跑）。
为了不让路径假设随位置变化而悄悄失效，这里统一「向上找标记文件」。

为什么不做成 `dirname(dirname(__file__)) + 'cloud-condition-admin/backend/sql'`：
那依赖脚本自己所在的层数，脚本一挪位置就解析到不存在的目录，
而且失败信息是「文件打不开」，很难联想到是路径假设的问题。
"""
from __future__ import annotations

import os


def _find_upward(start: str, marker: str) -> str | None:
    """从 start 逐级向上找，返回第一个存在 marker 的目录。"""
    current = os.path.abspath(start)
    while True:
        if os.path.exists(os.path.join(current, marker)):
            return current
        parent = os.path.dirname(current)
        if parent == current:  # 到根了
            return None
        current = parent


def project_root() -> str:
    """返回含 `cloud-condition-admin` 的目录。"""
    here = os.path.dirname(os.path.abspath(__file__))
    # 先看自己是不是已经在 cloud-condition-admin 里面
    root = _find_upward(here, "cloud-condition-admin")
    if root:
        return root
    # 也可能脚本被放在了 cloud-condition-admin 内部（scripts/ 目录）
    inner = _find_upward(here, os.path.join("backend", "sql", "01_schema_admin.sql"))
    if inner:
        # inner 是 backend 目录，再上一层就是 cloud-condition-admin
        return os.path.dirname(inner)
    return os.path.dirname(here)


def sql_dir() -> str:
    """backend/sql 目录。"""
    here = os.path.dirname(os.path.abspath(__file__))
    # 脚本在 backend/scripts/ 时，同级 ../sql 就是目标
    sibling = os.path.abspath(os.path.join(here, "..", "sql"))
    if os.path.isdir(sibling):
        return sibling
    return os.path.join(project_root(), "cloud-condition-admin", "backend", "sql")


def here_dir() -> str:
    """脚本自身所在目录（放临时产物用，如基线快照）。"""
    return os.path.dirname(os.path.abspath(__file__))


def python_exe() -> str:
    """优先用项目 venv 的 python（有 pymysql/bcrypt），否则退回当前解释器。"""
    import sys

    here = here_dir()
    for base in (project_root(), os.path.dirname(here), here):
        candidate = os.path.join(base, "venv", "Scripts", "python.exe")
        if os.path.isfile(candidate):
            return candidate
        candidate = os.path.join(base, "venv", "bin", "python3")
        if os.path.isfile(candidate):
            return candidate
    return sys.executable
