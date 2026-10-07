# demo-skill — 技能开发示例

这是一个**可直接运行的技能模板**，演示了技能系统的全部要素。
把它复制一份、改个名字，就是你自己的技能。

---

## 一、目录结构

```
demo-skill/
├── skill.json              ← 【必需】技能元数据 + 脚本声明
├── README.md               ← 【推荐】主文档（加载技能时注入上下文）
├── apt-requirements.txt    ← 【可选】系统依赖声明（见「三、依赖怎么声明」）
├── requirements.txt        ← 【可选】pip 依赖声明（本示例没有：三个脚本都不用第三方包）
├── quick_ref.md            ← 【可选】快速参考（追加到 content）
├── index.md                ← 【可选】索引（最后追加；本示例没有）
└── scripts/                ← 【可选】脚本目录（名字随意，entry 里写对即可）
    ├── greet.sh            ← bash，带参数
    ├── sysinfo.py          ← python3，无参数
    └── count_files.sh      ← bash，带整数参数 + 自定义超时
```

**最少只需两个文件**：`skill.json` + 至少一个 `.md`。没有脚本就是纯知识技能。

---

## 二、skill.json 字段说明

| 字段 | 必需 | 说明 |
|---|---|---|
| `name` | ✅ | 技能唯一标识，也是 `/skill <name>` 的名字 |
| `version` | | 语义版本，显示在技能列表 |
| `description` | | 一句话说明，给 LLM 和用户看 |
| `author` | | 作者 |
| `minApp` | | 最低宿主版本（如 `1.0.1`），不满足时**仅告警不阻断** |
| `requires` | | 依赖的其它技能名列表，缺失时告警 |
| `scripts` | | 脚本数组，每个脚本注册为一个工具 |
| `tools` | | 该技能**额外**注册的非脚本工具名（脚本工具已由 `scripts` 自动注册，**不要在这里重复列**——技能名或脚本名一改，手写的工具名就失真了） |

> **目录名 vs `name`**：技能按目录存放（`filesDir/skills/<目录名>`，或 APK 内置的 `assets/skills/<目录名>`），
> 脚本 `entry` 与 `requirements.txt` 都相对**目录**解析，不是相对 `name`。
> 两者可以不同（内置 `github-actions-skill` 目录里的 `name` 就是 `github-actions`，`skill_install` 从 git 装的技能目录名也取自 URL），
> 但**建议保持一致**：目录名、`name`、工具名三者对不上时最难排查。

### scripts[] 单项字段

| 字段 | 默认 | 说明 |
|---|---|---|
| `name` | — | 脚本名，最终工具名 = `skill_<技能名>_<脚本名>`（非 `[A-Za-z0-9_]` 字符会替换成 `_`，如 `demo-skill` → `demo_skill`） |
| `description` | `""` | 给 LLM 的说明 |
| `entry` | `""` | 脚本文件相对技能目录的路径，如 `scripts/greet.sh` |
| `interpreter` | `bash` | 可选 `bash` / `sh` / `python3` / `node` / `wasm` |
| `params` | `[]` | 参数声明，**按声明顺序**作为位置参数传给脚本 |
| `timeout` | `0` | 单次执行超时（秒）；`0` 用全局默认 300s |
| `background` | `false` | `true` 时后台常驻执行，立即返回 `bg_` 任务 ID |

### params[] 单项字段

| 字段 | 默认 | 说明 |
|---|---|---|
| `name` | — | 参数名 |
| `type` | `string` | `string` / `integer` / `number` / `boolean` |
| `description` | `""` | 参数说明 |
| `required` | `false` | 是否必填 |

---

## 三、依赖怎么声明

**依赖只在技能侧声明，分两个文件**（都放在技能目录根，按目录名解析）：

| 文件 | 声明什么 | 每行写法 |
|---|---|---|
| `requirements.txt` | pip 依赖 | 标准 pip 行：`flask>=2.3.0`、`#` 开头是注释 |
| `apt-requirements.txt` | 系统依赖（apt 包） | 一个包名：`git`、`smbclient`、`python3`（可写行尾 `#` 注释） |

示例：

```
# apt-requirements.txt        # requirements.txt
python3                       flask>=2.3.0
git                           openpyxl>=3.1.0
```

**为什么系统依赖也要声明**：随包 PRoot rootfs 只是一个 Debian **minbase**
（**没有 python3 / pip3 / git / curl / node**）。所以：

- 用 `python3` / `node` 解释器的技能，必须在 `apt-requirements.txt` 里声明 `python3`；
- 声明了 pip 依赖的技能，宿主会**自动额外补** `python3` + `python3-pip`（否则 requirements.txt 永远装不上），不用你重复写 `python3-pip`；
- 用 `bash` + 基础命令（`find`/`grep`/`du`/`tar` 等）的技能无需声明，它们已在镜像里；
- 需要服务端/客户端工具（`git`、`smbclient`、`samba`…）就在 `apt-requirements.txt` 里写包名，
  不要在脚本里写 `apt-get install` 临时补（两处声明必然漂移）。

安装行为（宿主侧统一处理）：

- 在 **PRoot 就绪后自动**执行：先 `dpkg -s` 逐包判缺再 `apt-get install`，最后 `pip3 install -r requirements.txt`；
- **幂等**：装成功后按「依赖清单 + PRoot 环境版本」记录指纹，清单没变就直接跳过、**完全不进 PRoot**；
  改了任一声明文件（或 rootfs 重装）才重装；
- **不放文件、或文件里只有注释 = 无依赖**，不会起任何安装动作；
- 不要在文档里让 LLM 临场 `pip install` / `apt install`——声明写进文件，装一次、之后都跳过；
- 安装失败只在 `SKILL` 日志里告警，**不阻断技能加载**，脚本仍可运行（缺依赖时由脚本自己报错）。
- `skill_list` / `skill_info` 会透出 `pipDependencies` / `aptDependencies`，方便排查「为什么脚本报 command not found」。

---

## 四、脚本怎么接收参数

**关键规则：参数按 `params` 声明顺序，作为位置参数传入。**

```bash
#!/bin/bash
# $1 = 第一个参数（name）
# $2 = 第二个参数（lang）
NAME="${1:-world}"
LANG="${2:-zh}"
```

```python
# python3 同理：sys.argv[1] 是第一个参数
import sys
first = sys.argv[1] if len(sys.argv) > 1 else ""
```

脚本的 **stdout 就是工具返回值**。要返回错误，输出 `{"error":"..."}` 即可。

---

## 五、执行环境

- 脚本在 **Debian PRoot Linux** 环境执行（ARM64），工作目录不是技能目录（脚本体经 stdin 交给解释器）
- 镜像只是 Debian **minbase**，**现成可用**的是 `bash` / `dash` / coreutils / `find` / `grep` / `tar` / `openssl` / `apt`；
  `python3`、`node`、`git`、`curl`、`smbclient` 一类**都没有预装**，要在依赖声明文件里写出来（见第三节）
- **没有 root**：不要尝试 `sudo`、改系统文件
- 网络可用（`apt` / `pip` 走网络）；需要外部命令就用 `apt-requirements.txt` 声明，不要依赖运行时手动安装
- PRoot 内路径：宿主 `filesDir/skills/<目录>` 映射为 `/host/app/skills/<目录>`，`/sdcard` 也可访问

---

## 六、本示例提供的工具

### `skill_demo_skill_greet`

演示最简脚本。参数：
- `name`（必填）：要问候的名字
- `lang`（可选）：`zh` 或 `en`

### `skill_demo_skill_sysinfo`

演示 `python3` 解释器，无参数。输出系统信息。

### `skill_demo_skill_count_files`

演示整数参数与自定义超时。参数：
- `dir`（必填）：目录绝对路径
- `max_depth`（可选，integer）：递归深度，默认 1

---

## 七、如何安装

**方式一：内置**（开发时）

把技能目录放到 `app/src/main/assets/skills/`，重新构建 APK。

**方式二：导入**（运行时）

技能页点「导入」，选一个包含 `.md` 文件的目录。
或让 LLM 调用 `skill_install` 传 URL（zip 包或 git 仓库）。

---

## 八、调试建议

1. **先确保脚本能独立跑通**：`bash scripts/greet.sh 世界 zh`
2. **换行符**：脚本是经 stdin/heredoc 交给 `bash` 的，CRLF 会让每行末尾多一个 `\r` 而直接语法报错
   （历史上内置技能里 33 个文件都是 CRLF）。宿主读取脚本时会统一归一化成 LF，
   但**建议仍用 LF 编辑**（`dos2unix` 或把编辑器默认设为 LF），省得本地手跑脚本时踩坑。
3. **看日志**：技能加载/注册失败会打 `SKILL` 标签的日志。
4. **改完要重扫**：技能页刷新会触发 `rescanSkills()`，重新注册工具。
