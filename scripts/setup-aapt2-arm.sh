#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# setup-aapt2-arm.sh — 在 aarch64 Linux（PRoot/Termux 混合环境）上启用原生 AAPT2
#
# 背景：Google Maven 的 AAPT2 只发布 x86_64 Linux 二进制，aarch64 主机（如
# Android 设备上的 PRoot Debian）执行时 "Daemon startup failed"。本脚本从
# Termux 仓库拉取 aarch64 原生 AAPT2 及其依赖，组装到独立目录，并生成
# bionic linker 引导的 wrapper，最后写入 gradle.properties 的
# android.aapt2FromMavenOverride（若尚无配置）。
#
# 用法：  bash scripts/setup-aapt2-arm.sh [--prefix /opt/aapt2-arm]
# 幂等：  重复执行安全（已就绪则跳过下载）。
# 卸载：  删除 prefix 目录 + gradle.properties 中 override 行即可。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

PREFIX="/opt/aapt2-arm"
[[ "${1:-}" == "--prefix" && -n "${2:-}" ]] && PREFIX="$2"

REPO_BASE="https://packages.termux.dev/apt/termux-main"
INDEX="dists/stable/main/binary-aarch64/Packages"

AAPT2_VER="16.0.0.4-2"          # Termux aapt2 版本（对应 Android 16 build-tools）
# 2026-09 起仓库已下架 16.0.0.4-1（pool 路径 404），跟到 -2；升级 build-tools 后需同步此处。
DEPS="fmt libprotobuf abseil-cpp libc++ libexpat libpng libzopfli zlib"

log() { echo "[setup-aapt2] $*"; }

command_exists() { command -v "$1" >/dev/null 2>&1; }
need() { command_exists "$1" || { log "缺少命令: $1"; exit 1; }; }

need curl
need ar
need tar

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch_index() {
    log "获取 Termux 包索引…"
    curl -sL --max-time 60 "$REPO_BASE/$INDEX" -o "$WORK/Packages.txt"
    [[ -s "$WORK/Packages.txt" ]] || { log "包索引下载失败"; exit 1; }
}

# 从索引取某包的 pool 相对路径（Filename 字段）
pool_path_of() {
    awk -v pkg="Package: $1" '
        $0 == pkg {inblk=1}
        inblk && /^Filename:/ {print $2; exit}
        /^$/ {inblk=0}' "$WORK/Packages.txt"
}

download_pkg() {
    local rel="$1" out="$2"
    local enc="${rel//:/\%3a}"           # pool 路径中 epoch 冒号需编码
    curl -sL --max-time 120 "$REPO_BASE/$enc" -o "$out"
    [[ -s "$out" && $(stat -c%s "$out") -gt 10000 ]]
}

extract_deb() {
    local deb="$1" dest="$2"
    local err="$dest/.tar.err"   # 拆行：同一 local 语句里引用 $dest 会报 unbound variable
    mkdir -p "$dest"
    ( cd "$dest"
      cp "$deb" pkg.deb
      ar x pkg.deb
      # PRoot（非真实 rootfs）下 tar 给深层目录改权限会 ENOENT
      # （"Cannot change mode ..."），于是非零退出，但文件其实已经解出。
      # 退出码在这里区分不了「无害告警」和「真失败」，所以只过滤这两类无害行，
      # 其余 stderr 原样回显；真失败由调用方的存在性校验兜住（找不到 aapt2 就 exit）。
      if ! tar --force-local -xJf data.tar.xz 2>"$err"; then
          sed '/Cannot change mode/d;/Exiting with failure status/d' "$err"
      fi
      rm -f "$err" )
}

main() {
    fetch_index

    # ── 1. 下载 aapt2 主程序与依赖包 ──
    log "下载 aapt2 ($AAPT2_VER) 与依赖…"
    download_pkg "pool/main/a/aapt2/aapt2_${AAPT2_VER}_aarch64.deb" \
                 "$WORK/aapt2.deb" || { log "aapt2 下载失败"; exit 1; }

    for p in $DEPS; do
        local rel f
        rel="$(pool_path_of "$p")"
        [[ -n "$rel" ]] || { log "索引中找不到依赖: $p"; exit 1; }
        f="$WORK/$(basename "$rel")"
        if download_pkg "$rel" "$f"; then
            log "  ✓ $p"
        else
            log "  ✗ $p 下载失败"; exit 1
        fi
    done

    # ── 2. 解包组装到独立目录（不进系统路径：Termux so 会污染 glibc 环境）──
    log "组装到 $PREFIX …"
    # 只清 aapt2.dir / wrapper，**保留 bionic/**：
    # bionic（linker64 + 系统库）需在宿主侧预置（PRoot 看不到 /system），
    # 全删会让每次重跑都丢失它，导致 linker 取不到、脚本提前退出。
    rm -rf "$PREFIX/aapt2.dir" "$PREFIX/wrapper"
    mkdir -p "$PREFIX/aapt2.dir"

    extract_deb "$WORK/aapt2.deb" "$WORK/x_aapt2"
    local bin
    bin="$(find "$WORK/x_aapt2" -type f -name aapt2 | head -1)"
    [[ -n "$bin" ]] || { log "解包后未找到 aapt2 可执行文件"; exit 1; }
    cp "$bin" "$PREFIX/aapt2.dir/aapt2"

    for p in $DEPS; do
        extract_deb "$WORK/$(basename "$(pool_path_of "$p")")" "$WORK/x_$p"
    done
    find "$WORK"/x_* -name "*.so*" -exec cp -a {} "$PREFIX/aapt2.dir/" \; 2>/dev/null || true

    # ── 3. bionic linker + 系统库：拷进 PREFIX，wrapper 不再依赖宿主 /system ──
    #
    # 为什么必须自带：Termux aapt2 按 bionic (Android linker) 链接，NEEDED 的
    # libc.so / libdl.so 无版本号，glibc 环境跑不了；而 PRoot 沙盒**默认与宿主
    # 完全隔离**（见 ProotEnvironment 的沙盒挂载策略）——guest 内看不到宿主
    # /system，直接 exec /system/bin/linker64 会 "required file not found"。
    #
    # 因此把 linker64 + 它需要的 bionic 系统库一并拷进 PREFIX/bionic/，
    # wrapper 用 guest 内路径调用，自包含、不依赖宿主是否 bind 了 /system。
    # 另需拷 /linkerconfig/ld.config.txt 到 guest 的 /linkerconfig/（bionic 启动读它）。
    mkdir -p "$PREFIX/bionic"
    # linker64（/system/bin/linker64 只是指向 /apex/... 的符号链接，必须 -L 跟进去）
    # PRoot 沙盒默认看不到宿主 /system，所以直接 cp /system/... 会失败。
    # 两条取用路径，按可用性依次尝试：
    #   1) su -c：宿主侧真 root 读 /system（root 设备首选，最稳）
    #   2) 直读：若沙盒恰好 bind 了 /system（非默认），也能命中
    LINKER_SRC=""
    if command -v su >/dev/null 2>&1 && su -c true >/dev/null 2>&1; then
        # 用宿主 su 把 linker64 与 bionic 库直接拷到 guest 可见的 PREFIX（路径对宿主同样可见）
        if su -c "cp -L /system/bin/linker64 '$PREFIX/bionic/linker64'" 2>/dev/null; then
            LINKER_SRC="su:/system/bin/linker64"
        fi
    fi
    if [[ -z "$LINKER_SRC" ]]; then
        for c in /system/bin/linker64 /apex/com.android.runtime/bin/linker64; do
            [[ -e "$c" ]] && { cp -L "$c" "$PREFIX/bionic/linker64" 2>/dev/null && LINKER_SRC="$c"; break; }
        done
    fi
    # 已有 linker（例如宿主机侧预先拷好，或上次运行留下）→ 跳过取用。
    if [[ -s "$PREFIX/bionic/linker64" ]]; then
        log "bionic/linker64 已存在，跳过取用"
        LINKER_SRC="existing"
    fi
    [[ -n "$LINKER_SRC" ]] || {
        log "找不到 linker64：沙盒未 bind /system 且 su 不可达宿主。"
        log "请先在宿主机侧执行（App 内 run_root 或终端）："
        log "  cp -L /system/bin/linker64 $PREFIX/bionic/linker64"
        exit 1
    }
    chmod +x "$PREFIX/bionic/linker64" 2>/dev/null || true
    log "bionic linker64 就绪（$LINKER_SRC）"
    # 用同样的方式取 bionic 库：优先 su，回退直读
    fetch_bionic() {
        local lib="$1" dest="$PREFIX/bionic/$1"
        for src in "/apex/com.android.runtime/lib64/bionic/$lib" \
                   "/apex/com.android.runtime/lib64/$lib" \
                   "/system/lib64/$lib" \
                   "/system/lib64/bionic/$lib"; do
            if su -c "cp -L '$src' '$dest'" 2>/dev/null && [[ -s "$dest" ]]; then return 0; fi
            if [[ -e "$src" ]] && cp -L "$src" "$dest" 2>/dev/null; then return 0; fi
        done
        return 1
    }
    # aapt2 直接 NEEDED 的 bionic 库 + 间接依赖（libabsl_* 需要 liblog）
    for lib in libc.so libdl.so libm.so liblog.so libbase.so libunwindstack.so \
               liblzma.so libprocinfo.so libc++_shared.so libc++.so; do
        fetch_bionic "$lib" && log "  ✓ bionic/$lib" || true
    done
    # ld.config.txt：bionic linker 启动时读它建立库搜索命名空间
    if [[ -f /linkerconfig/ld.config.txt ]]; then
        mkdir -p /linkerconfig
        cp -f /linkerconfig/ld.config.txt /linkerconfig/ 2>/dev/null \
            && log "  ✓ /linkerconfig/ld.config.txt" \
            || log "  ! 无法写 /linkerconfig（非致命，bionic 会退化到默认搜索）"
    fi

    # ── 3b. wrapper：guest 内自包含（/bin/sh + PREFIX/bionic/linker64）──
    # shebang 必须是 guest 的 /bin/sh —— /system/bin/sh 是宿主路径，沙盒内不可见。
    mkdir -p "$PREFIX/wrapper"
    cat > "$PREFIX/wrapper/aapt2" <<EOF
#!/bin/sh
BASE=$PREFIX
LD_LIBRARY_PATH=\$BASE/aapt2.dir:\$BASE/bionic exec \$BASE/bionic/linker64 \$BASE/aapt2.dir/aapt2 "\$@"
EOF
    chmod +x "$PREFIX/wrapper/aapt2"

    # ── 4. 自检 ──
    # 注意：aapt2 version 把结果打印到 stderr（AOSP 行为），必须 2>&1 捕获
    local ver
    ver="$("$PREFIX/wrapper/aapt2" version 2>&1 | tail -1)"
    [[ "$ver" == *"Android Asset Packaging Tool"* ]] \
        || { log "wrapper 自检失败：$ver"; exit 1; }
    log "自检通过: $ver"

    # ── 5. 写【用户级】~/.gradle/gradle.properties（幂等）──
    # 不写项目级：override 路径是本机专属，提交进仓库会让 CI（x86_64）报
    # "Specified AAPT2 executable does not exist"。Gradle 自动合并用户级与项目级。
    local gp="${HOME}/.gradle/gradle.properties"
    mkdir -p "${HOME}/.gradle"
    [[ -f "$gp" ]] || touch "$gp"
    if grep -q "aapt2FromMavenOverride" "$gp"; then
        sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$PREFIX/wrapper/aapt2|" "$gp"
        log "已更新 ~/.gradle/gradle.properties 中现有 override"
    else
        cat >> "$gp" <<EOF

# aarch64 主机用 Termux 原生 AAPT2（由 scripts/setup-aapt2-arm.sh 生成）
android.aapt2FromMavenOverride=$PREFIX/wrapper/aapt2
EOF
        log "已追加 ~/.gradle/gradle.properties override"
    fi

    log "完成 ✔  AAPT2(aarch64): $PREFIX/wrapper/aapt2"
}

main "$@"
