#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模块依赖方向校验（架构契约硬门）。

背景：
  AGENTS.md §2.2 规定了七个 Gradle 模块的依赖方向。此前仅写在文档里，
  没有任何机制强制——一旦让 :core 反向依赖 :app，编译能过，架构就烂了。

  本脚本把依赖方向从「文档约定」变成「可执行的硬门」：读各模块
  build.gradle.kts 里的 project(":X") 声明，对照白名单，超出即 FAIL（rc=1）。

检查项：
  1. 各模块声明的 project 依赖必须在白名单内（禁止反向依赖）；
  2. 纯 JVM 模块（tool-compiler）下不得 import android.*。

白名单来源：AGENTS.md §2.2 的依赖方向图。
改依赖方向时先改 AGENTS.md，再改本脚本 ALLOWED——两者不一致视为缺陷。

用法：
  python3 scripts/architecture_check.py             # 检查全部
  python3 scripts/architecture_check.py --verbose   # 附带打印各模块依赖
"""

import os
import re
import sys

# ────────────────── 依赖方向白名单 ──────────────────
# 模块名 → 允许依赖的模块集合（带冒号，与 settings.gradle.kts 一致）。
# 来源：AGENTS.md §2.2。修改时两边同步，不可只改一处。
ALLOWED = {
    "app":           {":data", ":core", ":llm", ":tool-compiler", ":mcp-bridge", ":litert", ":mnn"},
    "data":          {":core"},
    "core":          {":tool-compiler"},
    "llm":           {":core", ":tool-compiler"},
    "tool-compiler": set(),
    "mcp-bridge":    {":tool-compiler"},
    "litert":        {":core", ":llm"},
    # 本地推理后端与 LiteRT 同构：只向下依赖 :llm / :core，配置由 app 层注入。
    "mnn":           {":core", ":llm"},
}

# 纯 JVM 模块：不得 import Android SDK（这是它能桌面直接跑测试的前提）。
PURE_JVM_MODULES = {"tool-compiler"}

PROJECT_DEP_RE = re.compile(r'project\s*\(\s*"(:[^"]+)"')
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")


def repo_root():
    """定位仓库根（本脚本所在目录的上一级）。"""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_deps(module_dir):
    """读模块 build.gradle.kts 的 project 依赖集合；文件缺失返回 None。"""
    gradle_file = os.path.join(module_dir, "build.gradle.kts")
    if not os.path.isfile(gradle_file):
        return None
    with open(gradle_file, "r", encoding="utf-8") as f:
        text = f.read()
    # 先去注释，避免注释里举例的 project(":x") 被误判成真实依赖
    text = BLOCK_COMMENT_RE.sub("", text)
    text = LINE_COMMENT_RE.sub("", text)
    return set(PROJECT_DEP_RE.findall(text))


def check_android_import(module_dir, root):
    """扫 src/main 下 .kt，返回 import android.* 的命中 [(相对路径, 行号, 原文)]。"""
    hits = []
    src_root = os.path.join(module_dir, "src", "main")
    if not os.path.isdir(src_root):
        return hits
    for dirpath, _, filenames in os.walk(src_root):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            full = os.path.join(dirpath, name)
            with open(full, "r", encoding="utf-8") as f:
                for lineno, line in enumerate(f, 1):
                    if line.strip().startswith("import android."):
                        hits.append((os.path.relpath(full, root), lineno, line.strip()))
    return hits


def main():
    verbose = "--verbose" in sys.argv
    root = repo_root()
    errors = []
    checked = 0

    print("仓库根: " + root)

    for mod in sorted(ALLOWED.keys()):
        mod_dir = os.path.join(root, mod)
        if not os.path.isdir(mod_dir):
            errors.append("模块目录不存在: " + mod + "/")
            continue
        deps = read_deps(mod_dir)
        if deps is None:
            errors.append("缺少 build.gradle.kts: " + mod + "/")
            continue
        checked += 1
        allowed = ALLOWED[mod]
        illegal = deps - allowed
        if verbose:
            shown = ", ".join(sorted(deps)) if deps else "（无）"
            print("  :%-15s 依赖 %s" % (mod, shown))
        for d in sorted(illegal):
            allowed_shown = ", ".join(sorted(allowed)) if allowed else "（空）"
            errors.append(
                ":" + mod + " 声明了非法依赖 " + d
                + "（不在白名单 [" + allowed_shown + "] 内；"
                + "方向定义见 AGENTS.md §2.2）"
            )

    for mod in sorted(PURE_JVM_MODULES):
        mod_dir = os.path.join(root, mod)
        for path, lineno, line in check_android_import(mod_dir, root):
            errors.append(path + ":" + str(lineno) + " 纯 JVM 模块不得 import Android SDK: " + line)

    print("== 模块依赖方向校验（已检查 %d 个模块） ==" % checked)
    if errors:
        print("发现 %d 处违规：" % len(errors))
        for e in errors:
            print("  [FAIL] " + e)
        return 1
    print("  [PASS] 所有模块的依赖方向符合 AGENTS.md §2.2")
    return 0


if __name__ == "__main__":
    sys.exit(main())