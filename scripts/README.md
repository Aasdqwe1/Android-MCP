# 构建辅助脚本

## setup-aapt2-arm.sh — aarch64 主机启用原生 AAPT2

Google Maven 的 AAPT2 仅有 x86_64 Linux 二进制。在 aarch64 主机
（Android 设备 PRoot Debian、Termux 混合环境）上构建时资源编译阶段会报：

    AAPT2 aapt2-8.11.1-...-linux Daemon #0: Daemon startup failed

本脚本从 Termux 仓库拉取 aarch64 原生 AAPT2 及依赖库，组装到独立前缀目录，
生成 bionic linker 引导的 wrapper，并写入 `gradle.properties`：

```bash
bash scripts/setup-aapt2-arm.sh                # 默认安装到 /opt/aapt2-arm
bash scripts/setup-aapt2-arm.sh --prefix $HOME/.aapt2-arm   # 自定义位置
```

要求：`curl`、`ar`（binutils）、`tar`（xz）、可访问 `/system/bin/linker64`
（Android 环境）。

x86_64 主机 / CI 无需此脚本；删除 `gradle.properties` 中的
`android.aapt2FromMavenOverride` 行即可还原默认行为。

## kotlin_sanity_check.py — Kotlin 静态自检

三层验证（详见脚本头注释）：

1. 括号配平 HEAD 基线对照
2. 注释/字面量闭合（嵌套感知，硬门 rc=1）
3. 已知坏味道审计（正则模式库，踩坑即追加）

第 2 层是重点：Kotlin 块注释会**嵌套**，KDoc 里写 `presets/*.json`
这类 `/*` 必须再补一个 `*/` 才闭合，否则整个文件尾部被当成注释吞掉，
编译器报 Unclosed comment 加一串假的 Unresolved reference / Missing '}'
（行号列号都可能指向别处，极易误判成写盘竞态而盲重跑）。
闭合检查本身 0.7 秒扫完全仓 132 个 .kt；整条 `--all --ci` 约 16 秒
（其中 93% 花在括号基线对照逐个 `git show`），仍比一次 ~3 分钟编译
省一个数量级；ktlint / detekt 抓不到这类词法错误。

```bash
python3 scripts/kotlin_sanity_check.py                # 只查 git 工作区改动
python3 scripts/kotlin_sanity_check.py --all          # 全仓
python3 scripts/kotlin_sanity_check.py --audit        # 仅坏味道审计
python3 scripts/kotlin_sanity_check.py --all --ci    # CI 同款硬门，有问题 rc=1
python3 scripts/kotlin_sanity_check.py a.kt b.kt      # 显式指定文件
```

已挂到 CI（`.github/workflows/build.yml` 的 Kotlin sanity check 步骤），
先于测试与编译执行。本地建议提交前跑一次，避免把词法错误留给编译器。

## architecture_check.py — 模块依赖方向校验

`AGENTS.md` §2.2 规定了七个 Gradle 模块的依赖方向，本脚本把它从
「文档约定」变成「可执行硬门」：解析各模块 `build.gradle.kts` 里的
`project(":X")` 声明，对照白名单，超出即 FAIL（rc=1）。

另检查一项：纯 JVM 模块（`tool-compiler`）下不得出现 `import android.`
——那是它能脱离 Android SDK 直接跑测试的前提。

```bash
python3 scripts/architecture_check.py             # 检查全部
python3 scripts/architecture_check.py --verbose   # 附带打印各模块依赖
```

白名单写在脚本的 `ALLOWED` 里，来源是 `AGENTS.md` §2.2。
**改依赖方向时，先改 AGENTS.md，再改脚本——两者不一致视为缺陷。**

已挂到 CI，在 Kotlin sanity check 之后、`assembleDebug` 之前执行。

