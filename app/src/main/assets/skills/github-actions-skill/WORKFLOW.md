# GitHub Actions Skill — 工作流程文档

## 1. 简介

本 Skill 封装 GitHub Actions API 的常用操作，用于查询工作流运行状态、获取执行日志、监控 CI/CD 流水线，帮助开发者快速定位构建问题。

---

## 2. 核心工作流程

```
用户触发 → 解析参数 → 调用 API → 处理结果 → 输出报告
```

**详细步骤：**

1. **用户输入**：用户提供仓库信息（`owner/repo`）和意图（查询状态、获取日志等）
2. **参数解析**：提取分支、状态过滤、运行 ID 等关键参数
3. **API 调用**：向 GitHub API 发起请求（需提供有效 Token）
4. **数据加工**：整理运行记录、提取错误信息、格式化输出
5. **结果呈现**：以 Markdown 表格或文本形式返回可读报告

---

## 3. API 规范

### 3.1 基础信息

| 项目 | 内容 |
|------|------|
| Base URL | `https://api.github.com` |
| API 版本 | `application/vnd.github.v3+json` |
| 认证方式 | `Authorization: token {GITHUB_TOKEN}` |
| 限流 | 5000 请求/小时（认证后） |

### 3.2 核心端点

| 方法 | 端点 | 用途 |
|------|------|------|
| GET | `/repos/{owner}/{repo}/actions/runs` | 列出运行记录 |
| GET | `/repos/{owner}/{repo}/actions/runs/{run_id}` | 获取单次运行详情 |
| GET | `/repos/{owner}/{repo}/actions/runs/{run_id}/logs` | 下载日志 ZIP |
| GET | `/repos/{owner}/{repo}/actions/runs/{run_id}/artifacts` | 列出产物 |
| GET | `/repos/{owner}/{repo}/actions/artifacts/{id}/zip` | 下载产物 |

### 3.3 常用查询参数（列出运行记录）

| 参数 | 说明 | 示例 |
|------|------|------|
| `per_page` | 每页数量（默认 30，最大 100） | `per_page=10` |
| `status` | 过滤状态：`queued` / `in_progress` / `completed` | `status=completed` |
| `conclusion` | 过滤结论：`success` / `failure` / `cancelled` / `skipped` | `conclusion=failure` |
| `branch` | 过滤分支 | `branch=main` |
| `event` | 过滤触发事件 | `event=pull_request` |

---

## 4. 典型使用场景

### 场景一：查看最新 CI 状态

```
用户：查看 owner/repo 最新 CI 状态
→ 调用 list_workflow_runs(per_page=1)
→ 返回：运行编号、状态、结论、触发时间、链接
```

### 场景二：获取构建失败原因

```
用户：owner/repo 的 main 分支构建失败了，帮我看看
→ 过滤：branch=main, conclusion=failure, per_page=1
→ 获取该次运行的日志
→ 解析日志，提取 ERROR / Exception / ::error:: 行
→ 输出错误摘要
```

### 场景三：下载产物

```
用户：下载 owner/repo 最新一次成功的构建产物
→ 查询最近一次 conclusion=success 的运行
→ 列出该运行的所有 artifacts
→ 下载指定产物 ZIP
```

---

## 5. 日志解析

### 5.1 日志包结构

GitHub 返回的日志为 ZIP 压缩包，解压后结构：

```
logs.zip
├── 1_<job_name>.txt      # 第1个 job
├── 2_<job_name>.txt      # 第2个 job
└── ...
```

### 5.2 错误/警告关键词

| 类型 | 匹配模式 |
|------|----------|
| 错误 | `ERROR:`, `[ERROR]`, `Exception:`, `Error:`, `FATAL:`, `::error::` |
| 警告 | `WARNING:`, `[WARN]`, `Warning:`, `Deprecated:`, `::warning::` |
| 分组 | `::group::`, `::endgroup::` |

### 5.3 解析输出示例

```markdown
## 构建失败摘要（运行 #42）

**错误数：** 3
**警告数：** 1

### 错误列表
1. `[ERROR]` test/AppTest.kt:42 — NullPointerException
2. `::error::` 编译失败：unresolved reference 'build'
3. `Exception:` java.io.IOException: 磁盘空间不足
```

---

## 6. 错误处理

| HTTP 状态 | 含义 | 处理方式 |
|-----------|------|----------|
| 401 | Token 无效或过期 | 提示重新配置 `GITHUB_TOKEN` |
| 403 | 权限不足或限流 | 检查 Token 权限（需 `workflow` 和 `repo` 范围）；等待限流重置 |
| 404 | 仓库或运行记录不存在 | 检查 `owner/repo` 路径是否正确 |
| 410 | 日志已过期 | 日志仅保留 90 天，提示用户访问 GitHub 网页 |
| 500 | GitHub 服务端错误 | 稍后重试 |

---

## 7. 安全与最佳实践

1. **Token 管理**：使用环境变量存储 `GITHUB_TOKEN`，禁止硬编码
2. **最小权限**：Token 只需 `repo` 和 `workflow` 两个权限范围
3. **敏感信息脱敏**：日志输出前替换 Token、密码等敏感字符串
4. **限流策略**：高频查询时使用缓存，减少重复请求
5. **超时控制**：日志下载可设置 30 秒超时，避免卡死

---

## 8. 工具函数一览

| 函数名 | 说明 |
|--------|------|
> 下表按 `github_actions.py` 的**实际实现**列出（早前文档里的 `get_run_details` /
> `get_workflow_logs(owner, repo, run_id, token)` 等名字并不存在，签名顺序也写反了）。

**模块级便捷函数**

| 函数名 | 说明 |
|--------|------|
| `list_workflow_runs(owner, repo, token, per_page=5)` | 列出运行记录 |
| `get_latest_status(owner, repo, token, branch=None)` | 最新一次运行状态 |
| `get_failure_logs(owner, repo, token, branch=None)` | 最近失败的日志 |
| `download_latest_artifact(owner, repo, token, output_path=None)` | 下载最新产物 |
| `parse_logs(logs)` | 从 {job: 文本} 提取错误/警告行 |
| `format_status_report(run)` | 格式化为 Markdown 报告 |

**面向对象接口 `GitHubActionsClient(token, owner, repo, timeout=30)`**

| 方法 | 说明 |
|------|------|
| `list_runs(per_page=30, status=None, branch=None)` | 列出运行记录（可过滤状态/分支） |
| `get_run(run_id)` | 单次运行详情 |
| `get_logs(run_id, extract=True)` | 下载日志 **ZIP** 并解压为 {job: 文本} |
| `list_artifacts(run_id)` | 列出产物 |
| `download_artifact(artifact_id, output_path=None)` | 下载产物 ZIP |

---

## 9. 附录：响应示例（运行记录）

```json
{
  "total_count": 1,
  "workflow_runs": [
    {
      "id": 123456789,
      "name": "CI",
      "status": "completed",
      "conclusion": "success",
      "head_branch": "main",
      "run_number": 42,
      "run_started_at": "2026-08-13T10:00:00Z",
      "updated_at": "2026-08-13T10:05:00Z",
      "html_url": "https://github.com/owner/repo/actions/runs/123456789",
      "event": "push"
    }
  ]
}
```