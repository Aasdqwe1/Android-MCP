# JNI 原生运行时

本目录包含 Android ARM64 预编译的原生运行时组件，供 App 通过 `run_bash` 使用。

## 目录结构

```
jni/
├── bash/                # Bash 5.2.37 for Android（静态链接）
│   └── bash             # ARM64 ELF 二进制
└── git/                 # Git 2.55.0 for Android（静态链接，HTTPS 支持）
    ├── git              # Git 主程序
    ├── git-remote-https # HTTPS 传输助手（含 libcurl + OpenSSL）
    └── README.md        # 构建说明
```

## 内嵌工具

| 工具 | 路径 | 用途 | 构建 / 获取 |
|------|------|------|-------------|
| Bash 5.2.37 | `app/jni/bash/bash` | 设备端 shell 命令执行 | NDK 静态编译 |
| Git 2.55.0 (+HTTPS) | `app/jni/git/git` + `app/jni/git/git-remote-https` | 设备端 git 操作（HTTPS clone/push/fetch） | NDK 交叉编译（OpenSSL + libcurl 静态链接） |

## 集成方式

### Bash
- 路径: `app/jni/bash/bash`
- 使用: `run_bash` 工具优先尝试 Termux bash，未就绪时回退内置二进制

### Git
- 路径: `app/jni/git/git` + `app/jni/git/git-remote-https`
- 推荐使用 Termux 安装: `apt install git`（可获得完整 Git 生态）
- 注意: `git-remote-https` 是独立 ELF 二进制，Git 的远程助手机制要求它独立可执行
- HTTPS 支持: 静态链接 OpenSSL + libcurl，可直接 clone/fetch/push
