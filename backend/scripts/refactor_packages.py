# -*- coding: utf-8 -*-
"""
包结构重构：
    com.qianlong.cloud.admin.module.system.{dto,entity,mapper,service,web}
 -> com.qianlong.cloud.admin.{dto,entity,mapper,service,controller}

即：去掉 module.system 这一层，并把 web 改名为 controller。

另外把 AdminPrincipal 从 web 移到 dto：
它原本在 web 包里，却被 service 层的 AdminUserService / AuthService 引用，
形成 service -> web（控制器层）的反向依赖。它是 service 的返回值（一个数据载体），
放到 dto 后依赖方向才正确：service -> dto，controller -> service + dto。
"""
from __future__ import annotations

import os
import re
import shutil

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "src")
BASE = "com.qianlong.cloud.admin"
OLD = f"{BASE}.module.system"
SRC_DIR = os.path.join(ROOT, "main", "java", *BASE.split("."))
TEST_DIR = os.path.join(ROOT, "test", "java")

# 子包映射：旧名 -> 新名
SUB_MAP = {
    "dto": "dto",
    "entity": "entity",
    "mapper": "mapper",
    "service": "service",
    "web": "controller",
}

MOVED = []


def move_tree() -> None:
    old_root = os.path.join(SRC_DIR, "module", "system")
    for old_sub, new_sub in SUB_MAP.items():
        src = os.path.join(old_root, old_sub)
        dst = os.path.join(SRC_DIR, new_sub)
        if not os.path.isdir(src):
            print(f"  [skip] 不存在 {src}")
            continue
        if os.path.isdir(dst):
            # 逐文件移动，避免 shutil.move 把 dst 当成已存在目录而嵌套
            os.makedirs(dst, exist_ok=True)
            for name in os.listdir(src):
                shutil.move(os.path.join(src, name), os.path.join(dst, name))
        else:
            shutil.move(src, dst)
        MOVED.append((old_sub, new_sub))
        print(f"  {old_sub:10s} -> {new_sub}")

    # 清理空的 module 目录
    module_dir = os.path.join(SRC_DIR, "module")
    shutil.rmtree(module_dir, ignore_errors=True)
    print(f"  已删除空目录 {os.path.relpath(module_dir, SRC_DIR)}")


def move_principal() -> None:
    """AdminPrincipal: controller -> dto"""
    src = os.path.join(SRC_DIR, "controller", "AdminPrincipal.java")
    dst = os.path.join(SRC_DIR, "dto", "AdminPrincipal.java")
    if os.path.isfile(src):
        shutil.move(src, dst)
        print("  AdminPrincipal.java: controller -> dto（修正 service 反向依赖）")


def fix_package_declarations() -> None:
    """
    按**文件实际所在目录**重写 package 声明。

    为什么单独做这一步：上面 rewrite() 是按「旧包名 -> 新包名」做文本替换的，
    对**跟着目录一起搬走**的文件（AdminPrincipal 从 web 搬到 dto）就失灵了 ——
    它的 import 行被替换成了 dto，但它自己那行 `package ...web;` 没被动，
    于是文件在 dto 目录里却声明 controller 包，编译直接失败。
    以「实际路径」为准来算包名，就不依赖任何模式匹配，也不可能对不上。
    """
    fixed = []
    for path in all_java():
        with open(path, encoding="utf-8") as fh:
            text = fh.read()

        # 从文件路径反推正确的包名：src/main/java/<pkg path>/X.java -> <pkg path>
        norm = path.replace("\\", "/")
        for marker in ("/src/main/java/", "/src/test/java/"):
            idx = norm.find(marker)
            if idx >= 0:
                rel = norm[idx + len(marker):]
                break
        else:
            continue
        correct_pkg = os.path.dirname(rel).replace("/", ".")

        m = re.search(r"^package\s+([\w.]+);", text, flags=re.MULTILINE)
        if not m:
            print(f"  [warn] {os.path.basename(path)} 没有 package 声明")
            continue
        if m.group(1) != correct_pkg:
            text = text[:m.start()] + f"package {correct_pkg};" + text[m.end():]
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text)
            fixed.append(f"{os.path.basename(path)}: {m.group(1)} -> {correct_pkg}")
    if fixed:
        print(f"  修正了 {len(fixed)} 个 package 声明：")
        for f in fixed:
            print(f"    {f}")
    else:
        print("  所有 package 声明与文件位置一致 ✓")


def all_java() -> list[str]:
    out = []
    for base in (SRC_DIR, TEST_DIR):
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                if f.endswith(".java"):
                    out.append(os.path.join(dirpath, f))
    return out


def rewrite() -> None:
    # 先处理 AdminPrincipal 的特例（它从 web/controller 挪到了 dto），
    # 必须在通用替换之前做，否则会被改成 controller.AdminPrincipal
    principal_old = f"{OLD}.web.AdminPrincipal"
    principal_new = f"{BASE}.dto.AdminPrincipal"

    changed = 0
    for path in all_java():
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        original = text

        # 1) AdminPrincipal 特例（它从 web/controller 挪到了 dto）
        text = text.replace(f"import {principal_old};", f"import {principal_new};")

        # 2) 子包感知的前缀替换 —— 必须**先**做，且覆盖全文（import、全限定名、字符串）。
        #
        #    为什么不能只替换 `module.system.` -> `admin.`：
        #    那样会把 `...module.system.web.Foo` 变成 `...admin.web.Foo`，
        #    而 web 已经改名 controller，于是留下一个不存在的 admin.web 包。
        #    第一版就是这么错的：`WebConfig` 的
        #    `import ...module.system.web.CurrentAdminUserArgumentResolver;`
        #    变成 `import ...admin.web.CurrentAdminUserArgumentResolver;`，编译失败。
        #
        #    逐个「旧子包. -> 新子包.」映射就不会有这个问题；
        #    放全文替换是因为同类引用还出现在全限定名（不只是 import 行）里。
        for old_sub, new_sub in SUB_MAP.items():
            text = text.replace(f"{OLD}.{old_sub}.", f"{BASE}.{new_sub}.")

        # 3) 兜底：其余 `module.system.` 前缀（例如指向并未改名的类）
        text = text.replace(f"{OLD}.", f"{BASE}.")

        # 4) package 声明（子包感知替换已覆盖大部分，这里保证行首形态也正确）
        for old_sub, new_sub in SUB_MAP.items():
            text = re.sub(
                rf"^package\s+{re.escape(OLD)}\.{old_sub};",
                f"package {BASE}.{new_sub};",
                text, flags=re.MULTILINE)

        # 5) import 行：子包感知替换已处理，这里只兜底 static import 的前缀
        text = re.sub(
            rf"^import\s+static\s+{re.escape(BASE)}\.module\.system\.",
            f"import static {BASE}.", text, flags=re.MULTILINE)

        # 6) @MapperScan 里的包名（字符串字面量，已被第 2/3 步覆盖，这里显式保证）
        text = text.replace(f'"{OLD}.mapper"', f'"{BASE}.mapper"')

        if text != original:
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text)
            changed += 1
    print(f"  改写了 {changed} 个文件")


def add_missing_principal_import() -> None:
    """
    controller 包里的文件原本与 AdminPrincipal 同包，不需要 import；
    它挪到 dto 后，这些文件需要补上 import。
    """
    controller_dir = os.path.join(SRC_DIR, "controller")
    added = []
    for name in sorted(os.listdir(controller_dir)):
        if not name.endswith(".java") or name == "AdminPrincipal.java":
            continue
        path = os.path.join(controller_dir, name)
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if not re.search(r"\bAdminPrincipal\b", text):
            continue
        if re.search(r"^import\s+[\w.]*\.AdminPrincipal;", text, flags=re.MULTILINE):
            continue

        imp = f"import {BASE}.dto.AdminPrincipal;"
        lines = text.split("\n")
        # 插到 com.qianlong 组的第一条 import 之前，保持分组可读
        idx = next((i for i, ln in enumerate(lines)
                    if ln.startswith(f"import {BASE}.")), None)
        if idx is None:
            idx = next((i for i, ln in enumerate(lines)
                        if ln.startswith("import ")), None)
        if idx is None:
            print(f"  [warn] {name} 找不到 import 位置")
            continue
        lines.insert(idx, imp)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines))
        added.append(name)
    print(f"  为 {len(added)} 个 controller 文件补了 AdminPrincipal 的 import：{added}")


def verify() -> list[str]:
    """
    确认没有残留的旧包名，**以及**没有指向不存在的新包名。

    第二条同样重要：第一版只查了 `module.system` 残留，于是
    `admin.web.CurrentAdminUserArgumentResolver`（web 已改名 controller）
    顺利通过了校验，直到编译才暴露。所以这里直接核对「引用的每个
    com.qianlong.cloud.admin.* 包是否真实存在」。
    """
    # 真实存在的包（以目录为准）
    existing_pkgs = set()
    for base in (SRC_DIR, TEST_DIR):
        for dirpath, _dirs, _files in os.walk(base):
            rel = os.path.relpath(dirpath, base)
            if rel == ".":
                continue
            existing_pkgs.add(rel.replace(os.sep, "."))

    problems = []
    for path in all_java():
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if "module.system" in text or "module/system" in text:
            problems.append(f"{os.path.basename(path)}: 仍有 module.system 残留")
            continue
        # 检查每个引用的子包是否存在
        for m in re.finditer(rf"{re.escape(BASE)}\.([a-z]+)\b", text):
            sub = m.group(1)
            if sub not in existing_pkgs:
                problems.append(
                    f"{os.path.basename(path)}: 引用了不存在的包 {BASE}.{sub}")
                break
    return problems


def main() -> int:
    print("=== 1) 移动目录 ===")
    move_tree()
    move_principal()

    print("\n=== 2) 改写 package / import / @MapperScan ===")
    rewrite()

    print("\n=== 3) 补 AdminPrincipal 的 import ===")
    add_missing_principal_import()

    print("\n=== 3b) 按文件实际位置校正 package 声明 ===")
    fix_package_declarations()

    print("\n=== 4) 校验残留 ===")
    problems = verify()
    if problems:
        for p in problems:
            print(f"  [FAIL] 仍有 module.system 残留: {p}")
        return 1
    print("  无残留 ✓")

    print("\n=== 新结构 ===")
    for name in sorted(os.listdir(SRC_DIR)):
        full = os.path.join(SRC_DIR, name)
        if os.path.isdir(full):
            n = len([f for f in os.listdir(full) if f.endswith(".java")])
            print(f"  {name:12s} {n} 个文件")
        elif name.endswith(".java"):
            print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
