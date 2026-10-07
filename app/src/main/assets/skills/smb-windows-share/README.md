# smb-windows-share

在 Android（Debian PRoot）上通过 SMB/CIFS 协议访问 Windows 网络文件共享的技能。底层使用用户态 `smbclient`，无需 root、无需把共享挂载成系统目录，即可列出共享、浏览目录、下载/上传文件、查看空间占用。

## 能力（注册为工具）

| 工具 | 作用 |
|------|------|
| `skill_smb_windows_share_list_shares` | 列出某台 Windows 主机的所有网络共享 |
| `skill_smb_windows_share_list_files` | 列出共享中某目录的文件/子目录 |
| `skill_smb_windows_share_download` | 从共享下载文件到本机 |
| `skill_smb_windows_share_upload` | 上传本机文件到共享 |
| `skill_smb_windows_share_space` | 统计共享目录的空间占用 |
| `skill_smb_windows_share_serve` | 验证模式：同步启动 SMB 服务端并自检（命令结束即被 `--kill-on-exit` 回收，仅快速验证；`persist` 默认 `0`） |
| `skill_smb_windows_share_serve_bg` | 常驻模式：后台启动 SMB 服务端并保持运行，立即返回任务 ID（`bg_` 开头），跨命令存活；**必须传 `persist="1"`** |

## Windows 端准备（必须）

1. **开启文件共享**
   - 控制面板 → 网络和共享中心 → 更改高级共享设置 → 启用「网络发现」和「文件和打印机共享」。
   - 对目标文件夹：右键 → 属性 → 共享 → 高级共享 → 勾选「共享此文件夹」，记下共享名。
2. **设置共享权限 / 账号**
   - 共享权限里给相应用户「读取」或「读取/写入」。
   - 用 Microsoft 账号登录时，用户名通常是 `MicrosoftAccount\your@email.com`；也可新建一个本地用户专门共享。
3. **放行防火墙**
   - Windows Defender 防火墙 → 允许应用通过防火墙 → 勾选「文件和打印机共享」（专用/公用按需）。
   - SMB 走 **TCP 445** 端口。
4. **确认 IP**
   - 在 Windows 上 `ipconfig` 查看 IPv4 地址（如 `192.168.1.100`）。Android 与 Windows 需在同一局域网。

## 使用示例（由 LLM 调用对应工具）

- 发现共享：`list_shares(host="192.168.1.100", user="Alice", password="****")`
- 浏览目录：`list_files(host="192.168.1.100", share="Documents", remote_path="Photos")`
- 下载：`download(host="192.168.1.100", share="Documents", remote_file="a.pdf", local_path="/tmp/a.pdf")`
- 上传：`upload(host="192.168.1.100", share="Documents", local_path="/tmp/b.txt", remote_path="b.txt")`
- 空间：`space(host="192.168.1.100", share="Documents", remote_path="Photos")`

## 服务端：把本机目录共享出去（SMB 服务端）

本技能不仅能访问 Windows 共享，还能把本机（Android）目录以 SMB 服务端共享出去，供 Windows 文件管理器等客户端连接读写。底层是 samba 的 smbd（真实 SMB3 服务端）。

### 验证模式（快速自检）

`skill_smb_windows_share_serve(share_path="/storage/emulated/0/Work", persist="0")`

启动并自检后脚本立即退出。注意 PRoot 命令带 `--kill-on-exit`，**命令一结束服务进程即被回收**，本模式只用于验证配置与连通性。

### 常驻模式（推荐，服务跨命令存活）

`skill_smb_windows_share_serve_bg(share_path="/storage/emulated/0/Work", persist="1")`

- 由 `run_bash_bg` 的宿主侧常驻 PRoot 进程承载，无命令超时；脚本前台等待，服务进程不退脚本不退。
- 立即返回任务 ID（`bg_` 开头），随后用 `bash_task_status` / `bash_task_logs` / `bash_task_kill` 管理（状态 / 日志 / 停止）。
- 匿名共享：user/password 留空即访客访问；默认绑定 `0.0.0.0` 便于外部连接（同网络任意客户端可读写，生产建议传 user/password）。

### 连接方式

- 本机：`smbclient //127.0.0.1:445/share -N`
- 外部 Windows：`\\<Android IP>\share`（445 端口可直接连；回退到 8445 时需支持自定义端口的客户端）

### 已内建修复（本机验证）

- 独立运行时目录 `/root/samba_run`（pid/lock/state/cache/private/log 全部放这里），规避 `/tmp/msg.lock` 权限/归属问题导致的启动失败。
- 共享块 `force user = root`，解决 Android FUSE 存储对访客（nobody）不可读导致的匿名访问失败。

### 限制

- 445 为特权端口，无 root 时自动回退 8445；回退后 Windows 原生资源管理器连不上，需支持自定义端口的客户端（smbclient / Total Commander）。
- 后台任务随 App 进程退出而终止（App 被杀或重启后服务停止）；如需开机自启常驻，请在 Termux 原生环境用 `smbd -D -s <conf>` 运行并配合 Termux:Boot。

## 说明与限制

- **无法挂载成系统目录**：PRoot 环境没有真实 root 与内核 cifs 模块，不能 `mount -t cifs`。本技能在协议层用 `smbclient` 直接传输，功能等价于命令行访问，但不是系统级挂载。
- **匿名访问**：留空 user/password 会以访客（guest）身份连接；多数 Windows 默认禁用 guest，需提供有效账号。
- **凭据安全**：脚本把密码写入临时凭据文件（`-A`，权限 600）再交给 smbclient，用完即删，避免密码出现在进程列表。
- **路径空格**：共享内路径尽量不含空格；含空格时可能被拆分成多段。
- **下载落盘位置**：文件下载到 PRoot 工作目录（如 `/tmp` 或当前目录），如需在 Android 宿主侧访问，请下载到 PRoot 已映射到宿主的目录。
- **依赖声明**：`smbclient` 与 `samba` 写在技能目录的 `apt-requirements.txt` 里，宿主在 PRoot 就绪后自动安装（逐包判缺、装过即跳过，需网络）；脚本内只做可用性检查，不再临时 `apt-get install`。
- **协议版本**：现代 Windows（10/11）默认 SMB3，smbclient 会自动协商；极老设备若仅支持 SMB1，连接会失败，需另行配置。
