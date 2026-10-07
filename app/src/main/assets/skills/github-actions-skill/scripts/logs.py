#!/usr/bin/env python3
# skill:github-actions / script:logs — 获取工作流日志
# 仅依赖标准库（urllib + zipfile），无需第三方 requests
import sys, io, json, zipfile, urllib.request, urllib.error

def render_logs(data):
    """
    GitHub 的 /actions/runs/<id>/logs 返回的是 **ZIP**（每个 job 一个 .txt）。
    早期实现直接 decode("utf-8")，把二进制当文本，输出全是乱码——这里按魔数判断后解压。
    """
    if data[:2] == b"PK":
        try:
            parts = []
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                for name in sorted(zf.namelist()):
                    if name.endswith("/"):
                        continue
                    parts.append(f"===== {name} =====")
                    parts.append(zf.read(name).decode("utf-8", errors="replace"))
            text = "\n".join(parts)
            return text[:8000] + ("\n... (truncated)" if len(text) > 8000 else "")
        except Exception as e:
            return f"日志 ZIP 解析失败：{e}"
    # 非 ZIP（例如接口直接返回文本，或错误页）按文本处理
    text = data.decode("utf-8", errors="ignore")
    return text[:5000] + ("\n... (truncated)" if len(text) > 5000 else "")

def get_workflow_logs(owner, repo, token, run_id):
    url = f"https://api.github.com/repos/{owner}/{repo}/actions/runs/{run_id}/logs"
    req = urllib.request.Request(url, headers={
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "GitHubActionsSkill/1.0",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
    except urllib.error.HTTPError as e:
        if e.code == 204:
            return "No logs available yet."
        if e.code == 404:
            return ("日志接口返回 404：该运行可能无日志，或当前 Token 缺少 "
                    "`workflow` 权限范围（下载日志需 workflow scope）。")
        if e.code == 401:
            return "Token 无效或已过期（401）。"
        if e.code == 403:
            return "权限不足（403）：请确认 Token 具备 repo 与 workflow 权限范围。"
        return f"日志下载失败：HTTP {e.code}。"
    except urllib.error.URLError as e:
        return f"网络请求失败：{e.reason}。"
    return render_logs(data)

if __name__ == "__main__":
    if len(sys.argv) < 5:
        print(json.dumps({"error": "缺少必填参数：需要 owner, repo, token, run_id（用法 logs.py <owner> <repo> <token> <run_id>）"}, ensure_ascii=False))
        sys.exit(1)
    owner, repo, token, run_id = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    print(get_workflow_logs(owner, repo, token, run_id))
