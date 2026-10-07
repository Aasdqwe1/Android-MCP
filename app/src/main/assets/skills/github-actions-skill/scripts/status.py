#!/usr/bin/env python3
# skill:github-actions / script:status — 查看工作流状态
# 仅依赖标准库 urllib，无需第三方 requests
import sys, json, urllib.request, urllib.error

def list_workflow_runs(owner, repo, token, limit=5):
    url = f"https://api.github.com/repos/{owner}/{repo}/actions/runs?per_page={limit}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "GitHubActionsSkill/1.0",
    })
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))["workflow_runs"]

def format_status_report(runs):
    lines = ["=== GitHub Actions 运行状态 ==="]
    for r in runs:
        status = r["status"]
        conclusion = r.get("conclusion", "")
        if conclusion:
            if conclusion == "success":
                icon = "✅"
            elif conclusion == "failure":
                icon = "❌"
            elif conclusion in ("cancelled", "skipped", "timed_out", "action_required", "stale"):
                # 已完成但不是成功/失败：不能显示成「进行中」
                icon = "⚪"
            else:
                icon = "❓"
            lines.append(f"{icon} #{r['run_number']}: {r['name']} - {conclusion} ({r['head_sha'][:7]})")
        else:
            lines.append(f"⏳ #{r['run_number']}: {r['name']} - {status} ({r['head_sha'][:7]})")
    return "\n".join(lines)

if __name__ == "__main__":
    if len(sys.argv) < 4:
        print(json.dumps({"error": "缺少必填参数：需要 owner, repo, token（用法 status.py <owner> <repo> <token> [limit]）"}, ensure_ascii=False))
        sys.exit(1)
    owner, repo, token = sys.argv[1], sys.argv[2], sys.argv[3]
    limit = int(sys.argv[4]) if len(sys.argv) > 4 else 5
    runs = list_workflow_runs(owner, repo, token, limit)
    print(format_status_report(runs))
