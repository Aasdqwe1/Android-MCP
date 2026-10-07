#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin 静态自检脚本（AAPT2 不可用环境下的替代验证）。

背景：本项目的构建/调试常在 Android 设备 PRoot 环境中进行，AAPT2 只有 x86_64
二进制，:app 模块的资源编译无法在本机完成。本脚本提供三层轻量验证：

1. 括号配平基线对照（默认模式）
   - 对 git 工作区改动的 .kt 文件，分别计算「当前版本」与「HEAD 版本」的
     花括号配平状态（跳过注释/字符串/字符字面量的词法扫描）；
   - 未闭合数与 HEAD 一致 → PASS（本次改动未引入新的失衡）；
     数量变化 → FAIL 并给出首个失衡行。
   - 已知局限：对 Kotlin 字符串模板（$var/${...}）的极端嵌套可能误报，
     因此采用「基线对照」而非绝对判断——误报在改动前后数量相同，不会误伤。

2. 注释/字面量闭合检查（默认模式，嵌套感知）
   - Kotlin 块注释会**嵌套**：KDoc 里出现 `/*` 必须再补一个 `*/` 才闭合，
     否则整个文件尾部被当成注释吞掉，编译器报 Unclosed comment 加一串假的
     Unresolved reference / Missing '}'——错误行号与列号都可能指向别处，
     极易误判成「写盘竞态」而盲重跑，一次就是 3 分钟编译白跑。
     ktlint / detekt 抓不到这类词法错误，故单列一层。
   - 扫描到文件末尾仍未闭合 → FAIL（硬门，rc=1）；
     中间出现的 `/*` 嵌套 → WARN 仅作提示（成对 `/* */` 是合法写法）。
   - 来由：ToolsFragment.kt 的 KDoc 写过 `assets/presets/*.json`，
     连续两轮编译才定位。闭合检查本身 0.7 秒扫完全仓 132 个 .kt；整条 `--all --ci` 约 16 秒，其中 93% 花在 check_balance 逐个
     `git show` 取 HEAD 基线（PRoot 下 subprocess 慢）——仍比一次
     3 分钟编译省一个数量级。

3. --audit 坏味道审计
   - 扫描一组已知踩坑模式（正则），命中即报告文件:行号；
   - 模式列表随项目经验持续追加（每条注明来由）。

用法：
  python3 scripts/kotlin_sanity_check.py                # 对照 git 工作区改动
  python3 scripts/kotlin_sanity_check.py a.kt b.kt      # 显式指定文件
  python3 scripts/kotlin_sanity_check.py --audit        # 仅坏味道审计
  python3 scripts/kotlin_sanity_check.py --audit --all  # 审计全仓（默认仅改动文件）
"""

import os
import re
import subprocess
import sys

# ────────────────────────── 坏味道模式库 ──────────────────────────
# 每条：(名称, 正则, 说明/来由)。新增模式时请附注踩坑场景。
AUDIT_PATTERNS = [
    (
        "路径硬猜",
        r"filesDir\.parentFile\?\.\w+\?\.\w+\?\.\w+",
        "用包名路径深度硬猜项目根（得到 /data/user）；应改用 resolveGradleProjectRoot()",
    ),
    (
        "FUSE 直接执行 gradlew",
        r"(?<!sh )\./gradlew",
        "/storage/emulated (FUSE) 上 exec 位不可靠，直接 ./gradlew 会 Permission denied；应 sh ./gradlew",
    ),
    (
        "KDoc 嵌套块注释",
        r"^\s*\*.*?/\*(?! *\*/)",
        "Kotlin 块注释会嵌套：KDoc 行内含 /* 必须再补一个 */ 闭合，否则整文件尾部被当注释吞掉，"
        "报 Unclosed comment 加一串假的 Unresolved reference / Missing '}'（一次误判等于 3 分钟编译白跑）。"
        "写法见 PresetManager.kt：写「presets/ 目录下的 .json」而非 presets/*.json。"
        "本条为提示（run_audit 跳过注释行，不会命中），硬检查见 scan_closure",
    ),
    (
        "jsonPrimitive 假安全调用",
        r"\w+\[\"[^\"]+\"\]\?\.jsonPrimitive\?\.(content|intValue|boolean)",
        "kotlinx 的 .jsonPrimitive 对 JsonArray/JsonObject 是抛异常而非返回 null，?. 链形同虚设；"
        "参数形状不确定时应 as? JsonPrimitive 或 runCatching（参见 AskUserTool 的 safeText）",
    ),
]


def scan_balance(src: str):
    """词法扫描花括号配平。返回 (未闭合栈, 多余闭括号数)。"""
    i, n = 0, len(src)
    stack = []          # 未闭合 '{' 的行号
    extra_close = 0
    while i < n:
        c = src[i]
        if c == "\n":
            i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":          # 行注释
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":          # 块注释
            j = src.find("*/", i + 2)
            if j < 0:
                break
            i = j + 2
            continue
        if c == "`":                                              # 反引号标识符
            j = src.find("`", i + 1)
            i = (j + 1) if j > 0 else n
            continue
        if c == '"':
            if src.startswith('"""', i):                          # raw string
                # 整段连续引号一起消耗：开/合两端的 `"` 连成 4 个以上时
                # （内容本身以 `"` 结尾）只跳 3 个会把后面的引号错当
                # 普通字符串，从这往后全部错帧——BuildTools.kt 的 3 个
                # 假未闭合就是这么来的。
                k = i
                while k < n and src[k] == '"':
                    k += 1
                j = src.find('"""', k)
                if j < 0:
                    break
                while j < n and src[j] == '"':
                    j += 1
                i = j
                continue
            j = i + 1                                             # 普通字符串
            while j < n:
                ch = src[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == '"':
                    break
                if ch == "$" and j + 1 < n and src[j + 1] == "{":  # ${...} 模板表达式
                    depth = 1
                    j += 2
                    while j < n and depth:
                        if src[j] == '"':                          # 表达式内嵌套字符串
                            jj = j + 1
                            while jj < n:
                                if src[jj] == "\\":
                                    jj += 2
                                    continue
                                if src[jj] == '"':
                                    break
                                jj += 1
                            j = jj
                        elif src[j] == "{":
                            depth += 1
                        elif src[j] == "}":
                            depth -= 1
                        j += 1
                    continue
                j += 1
            i = j + 1
            continue
        if c == "'":                                              # 字符字面量
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        if c == "{":
            stack.append(i)
        elif c == "}":
            if stack:
                stack.pop()
            else:
                extra_close += 1
        i += 1
    return stack, extra_close


def line_of(src: str, pos: int) -> int:
    return src.count("\n", 0, pos) + 1


def head_version(path: str) -> str:
    """取文件的 HEAD 版本内容；新文件返回 None。"""
    r = subprocess.run(["git", "show", f"HEAD:{path}"],
                       capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def changed_kt_files():
    r = subprocess.run(
        ["git", "diff", "--name-only", "HEAD", "--", "*.kt"],
        capture_output=True, text=True,
    )
    return [f for f in r.stdout.splitlines() if f.endswith(".kt")]


# 闭括号 → 它要求的开括号
_CLOSE_OPEN = {"}": "{", ")": "(", "]": "["}

# 未闭合字面量 → 报错文案
_UNCLOSED_MSG = {
    "R": "原始字符串（三连引号）未闭合",
    "S": "字符串字面量未闭合",
    "C": "字符字面量未闭合",
}


def scan_closure(src: str):
    """词法扫描闭合状态。返回 [(pos, 说明, 是否致命), ...]。

    与 scan_balance 的扫描不同，这里：
    1. 块注释做**嵌套计数**——Kotlin 块注释会嵌套，KDoc 里出现 `/*` 必须
       再有一个 `*/` 才闭合，否则整文件尾部被当注释吞掉，报 Unclosed
       comment 加一串假的 Unresolved reference / Missing '}'。
    2. 普通字符串里的 `${...}` 模板表达式**递归**扫描——表达式内可以再嵌
       字符串与注释，`"\"${f(\"x\")}\""` 这类「转义引号 + 嵌套字符串」最
       容易让单层状态机错帧（LogTools / WeChatTools 的假告警就是这么来的）。
    3. 跳过字符串与注释后统计 `{} () []` 配平，并定位到未闭合开括号的行。
       check_balance 是「HEAD 基线 vs 当前」的差值判断，同一处词法错帧若
       HEAD 也存在就被抵消；这里不依赖 git，单文件也能独立判定。
    """
    n = len(src)
    findings = []
    pstack = []                                   # 未闭合的开括号 [(字符, 位置), ...]
    st = {"mode": "N", "pos": n, "bdepth": 0}     # 最后进入、尚未闭合的结构

    def scan_raw(i):
        """i 指向三连引号；整段连续引号一起消耗（内容以 " 结尾会连成 4 个以上）。"""
        st.update(mode="R", pos=i, bdepth=0)
        k = i
        while k < n and src[k] == '"':
            k += 1
        j = src.find('"""', k)
        if j < 0:
            return n
        k = j
        while k < n and src[k] == '"':
            k += 1
        st["mode"] = "N"
        return k

    def scan_block(i):
        """i 指向 `/*`；块注释会嵌套，计数归零才算闭合。"""
        st.update(mode="B", pos=i, bdepth=1)
        depth = 1
        i += 2
        while i < n:
            if src.startswith("/*", i):
                findings.append(
                    (i, "块注释内又出现 /*（Kotlin 块注释会嵌套，此处需再补一个 */）", False))
                depth += 1
                i += 2
            elif src.startswith("*/", i):
                depth -= 1
                i += 2
                if depth == 0:
                    st["mode"] = "N"
                    return i
            else:
                i += 1
        st["bdepth"] = depth
        return n

    def scan_squote(i):
        st.update(mode="C", pos=i, bdepth=0)
        i += 1
        while i < n:
            if src[i] == "\\":
                i += 2
            elif src[i] == "'":
                st["mode"] = "N"
                return i + 1
            else:
                i += 1
        return n

    def scan_dquote(i):
        """i 指向开引号：三连引号走原始字符串，`${...}` 内可再嵌字面量。"""
        st.update(mode="S", pos=i, bdepth=0)
        if src.startswith('"""', i):
            return scan_raw(i)
        i += 1
        while i < n:
            c = src[i]
            if c == "\\":
                i += 2
            elif c == '"':
                st["mode"] = "N"
                return i + 1
            elif c == "$" and i + 1 < n and src[i + 1] == "{":
                i += 2
                depth = 1
                while i < n and depth:
                    if src[i] == '"':
                        i = scan_dquote(i)
                    elif src[i] == "'":
                        i = scan_squote(i)
                    elif src.startswith("//", i):
                        nl = src.find("\n", i)
                        i = n if nl < 0 else nl + 1
                    elif src.startswith("/*", i):
                        i = scan_block(i)
                    elif src[i] == "{":
                        depth += 1
                        i += 1
                    elif src[i] == "}":
                        depth -= 1
                        i += 1
                    else:
                        i += 1
                continue
            else:
                i += 1
        return n

    def scan_code(i):
        while i < n:
            if src.startswith("//", i):
                nl = src.find("\n", i)
                i = n if nl < 0 else nl + 1
            elif src.startswith("/*", i):
                i = scan_block(i)
            elif src[i] == '"':
                i = scan_dquote(i)
            elif src[i] == "'":
                i = scan_squote(i)
            elif src[i] in "({[":
                pstack.append((src[i], i))
                i += 1
            elif src[i] in ")}]":
                # 字符串/注释里的括号不参与统计，这里只认真代码里的。
                if pstack and pstack[-1][0] == _CLOSE_OPEN[src[i]]:
                    pstack.pop()
                else:
                    findings.append((i, f"多余的 {src[i]}：没有匹配的开括号", True))
                i += 1
            else:
                i += 1
        return i

    scan_code(0)

    if pstack:
        last = pstack[-1]
        findings.append((last[1],
                         f"括号未闭合：{last[0]}（共 {len(pstack)} 个开括号未闭合，首个出现在更早位置）",
                         True))
    if st["mode"] == "B":
        findings.append((st["pos"], f"块注释未闭合（嵌套深度 {st['bdepth']}）", True))
    elif st["mode"] in _UNCLOSED_MSG:
        findings.append((st["pos"], _UNCLOSED_MSG[st["mode"]], True))
    return findings

def check_closure(files):
    """注释/字面量闭合检查：EOF 处未闭合才判失败（中间行命中仅作提示）。"""
    bad = False
    for path in files:
        try:
            src = open(path, encoding="utf-8").read()
        except OSError as e:
            print(f"  SKIP {path}（读取失败：{e}）")
            continue
        findings = scan_closure(src)
        if not findings:
            print(f"  PASS {path}")
            continue
        fatal = False
        for pos, msg, is_fatal in findings:
            line = src.count("\n", 0, pos) + 1
            text = src.splitlines()[line - 1].strip()[:100] if 1 <= line <= len(src.splitlines()) else ""
            if is_fatal:
                fatal = True
                print(f"  FAIL {path}:{line}: {msg}")
            else:
                print(f"  WARN {path}:{line}: {msg}")
            if text:
                print(f"       {text}")
        if fatal:
            bad = True
    return not bad


def check_balance(files):
    ok = True
    for path in files:
        try:
            cur = open(path, encoding="utf-8").read()
        except OSError as e:
            print(f"  SKIP {path}（读取失败：{e}）")
            continue
        cur_stack, cur_extra = scan_balance(cur)

        base = head_version(path)
        if base is None:
            base_stack, base_extra = [], 0
            note = "新文件"
        else:
            base_stack, base_extra = scan_balance(base)
            note = ""

        same = (len(cur_stack) == len(base_stack)) and (cur_extra == base_extra)
        if same:
            print(f"  PASS {path} {note}")
        else:
            ok = False
            first_line = line_of(cur, cur_stack[0]) if cur_stack else "?"
            print(f"  FAIL {path}: 未闭合 {{ {len(base_stack)}→{len(cur_stack)}, "
                  f"多余 }} {base_extra}→{cur_extra}（首个未闭合于行 {first_line}）")
    return ok


def run_audit(files):
    hits = 0
    for path in files:
        try:
            lines = open(path, encoding="utf-8").read().splitlines()
        except OSError:
            continue
        for name, pattern, why in AUDIT_PATTERNS:
            rx = re.compile(pattern)
            for idx, line in enumerate(lines, 1):
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue  # 跳过注释行，避免文档/说明里的示例文本误报
                if rx.search(line):
                    hits += 1
                    print(f"  HIT [{name}] {path}:{idx}")
                    print(f"       {line.strip()[:100]}")
                    print(f"       → {why}")
    if hits == 0:
        print("  （无命中）")
    return hits


def main():
    args = sys.argv[1:]
    audit_only = "--audit" in args
    scan_all = "--all" in args
    positional = [a for a in args if not a.startswith("--")]
    # 位置参数传目录是常见误用（`python3 脚本 .` 想表达「全仓」）。以前会被当
    # 成一个文件，读取时报 Is a directory，于是整仓一个都没查却返回 0——看起来
    # 像全绿。这里显式识别成 --all，宁可提示也不要假绿。
    dirs = [d for d in positional if os.path.isdir(d)]
    if dirs:
        print(f"提示：位置参数 {', '.join(dirs)} 是目录，改按 --all 全仓扫描。")
        scan_all = True
        positional = [d for d in positional if not os.path.isdir(d)]

    if positional:
        files = positional
    elif scan_all:
        # --all：全仓（不止改动文件）。CI 用这个；默认模式仍只看工作区改动，
        # 以便秒级反馈。
        r = subprocess.run(["git", "ls-files", "*.kt"], capture_output=True, text=True)
        files = sorted(r.stdout.splitlines())
    else:
        files = sorted(set(changed_kt_files()))

    if not files:
        print("没有待检查的 .kt 文件（工作区无改动）。")
        return 0

    rc = 0
    if not audit_only:
        print("== 括号配平基线对照 ==")
        if not check_balance(files):
            rc = 1

        # 注释/字面量闭合（嵌套感知）：秒级抓住"块注释未闭合"这类词法陷阱。
        # 它触发 Unclosed comment 加一串假的 Unresolved reference，
        # 一次误判就是 3 分钟编译白跑；ktlint/detekt 都抓不到。
        print("== 注释/字面量闭合检查 ==")
        if not check_closure(files):
            rc = 1

    if audit_only or not files or True:
        print("== 坏味道审计 ==")
        # audit 默认也只看改动文件；--all 扩展到全仓由上方 files 分支处理
        hits = run_audit(files)
        if audit_only and hits:
            rc = 1

    return rc


if __name__ == "__main__":
    sys.exit(main())
