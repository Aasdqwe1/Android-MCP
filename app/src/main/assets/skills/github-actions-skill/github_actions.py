#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
GitHub Actions Skill - GitHub Actions API 封装
提供查询工作流运行状态、获取日志、管理产物等功能
"""

import json
import os
import re
import tempfile
import zipfile
from datetime import datetime
from typing import Dict, List, Optional, Any

import requests


class GitHubActionsError(Exception):
    """GitHub Actions API 异常基类"""
    pass


class AuthError(GitHubActionsError):
    """认证错误"""
    pass


class RateLimitError(GitHubActionsError):
    """API 限流错误"""
    pass


class NotFoundError(GitHubActionsError):
    """资源不存在错误"""
    pass


class GitHubActionsClient:
    """GitHub Actions API 客户端"""

    BASE_URL = "https://api.github.com"
    LOGS_RETENTION_DAYS = 90

    def __init__(self, token: str, owner: str, repo: str, timeout: int = 30):
        self.token = token
        self.owner = owner
        self.repo = repo
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update({
            "Authorization": f"token {token}",
            "Accept": "application/vnd.github.v3+json",
            "User-Agent": "GitHubActionsSkill/1.0"
        })

    def _request(self, method: str, path: str, params: Optional[Dict] = None,
                 data: Optional[Dict] = None) -> Dict:
        url = f"{self.BASE_URL}{path}"
        try:
            response = self.session.request(
                method=method,
                url=url,
                params=params,
                json=data,
                timeout=self.timeout
            )
        except requests.exceptions.Timeout:
            raise GitHubActionsError(f"请求超时: {url}")
        except requests.exceptions.RequestException as e:
            raise GitHubActionsError(f"请求失败: {e}")

        if response.status_code == 401:
            raise AuthError("Token 无效或已过期")
        elif response.status_code == 403:
            remaining = response.headers.get("X-RateLimit-Remaining")
            if remaining == "0":
                reset = response.headers.get("X-RateLimit-Reset")
                reset_time = datetime.fromtimestamp(int(reset)) if reset else "未知"
                raise RateLimitError(f"API 限流已触发，重置时间: {reset_time}")
            raise AuthError("权限不足，请检查 Token 权限范围（需 repo 和 workflow）")
        elif response.status_code == 404:
            raise NotFoundError(f"资源不存在: {path}")
        elif response.status_code == 410:
            raise GitHubActionsError("日志已过期（超过90天），请访问 GitHub 网页查看")
        elif response.status_code >= 500:
            raise GitHubActionsError(f"GitHub 服务端错误 ({response.status_code})，请稍后重试")

        try:
            return response.json()
        except json.JSONDecodeError:
            raise GitHubActionsError(f"响应解析失败，状态码: {response.status_code}")

    def list_runs(self, per_page: int = 30, status: Optional[str] = None,
                  conclusion: Optional[str] = None, branch: Optional[str] = None,
                  event: Optional[str] = None, page: int = 1) -> Dict:
        params = {"per_page": per_page, "page": page}
        if status:
            params["status"] = status
        if conclusion:
            params["conclusion"] = conclusion
        if branch:
            params["branch"] = branch
        if event:
            params["event"] = event
        return self._request("GET", f"/repos/{self.owner}/{self.repo}/actions/runs", params=params)

    def get_run(self, run_id: int) -> Dict:
        return self._request("GET", f"/repos/{self.owner}/{self.repo}/actions/runs/{run_id}")

    def get_logs(self, run_id: int, extract: bool = True) -> Dict[str, str]:
        url = f"/repos/{self.owner}/{self.repo}/actions/runs/{run_id}/logs"
        try:
            response = self.session.get(
                f"{self.BASE_URL}{url}",
                headers={
                    "Authorization": f"token {self.token}",
                    "Accept": "application/vnd.github.v3+json"
                },
                timeout=self.timeout
            )
        except requests.exceptions.Timeout:
            raise GitHubActionsError(f"日志下载超时: run_id={run_id}")

        if response.status_code != 200:
            if response.status_code == 410:
                raise GitHubActionsError("日志已过期（超过90天），请访问 GitHub 网页查看")
            raise GitHubActionsError(f"日志下载失败: HTTP {response.status_code}")

        if not extract:
            return {"download_url": f"{self.BASE_URL}{url}"}

        logs = {}
        try:
            with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as tmp_file:
                tmp_file.write(response.content)
                tmp_path = tmp_file.name

            with zipfile.ZipFile(tmp_path, "r") as zip_ref:
                for file_name in zip_ref.namelist():
                    match = re.match(r"^\d+_(.+)\.txt$", file_name)
                    job_name = match.group(1) if match else file_name
                    with zip_ref.open(file_name) as f:
                        logs[job_name] = f.read().decode("utf-8", errors="ignore")

            os.unlink(tmp_path)
        except zipfile.BadZipFile:
            logs["raw"] = response.content.decode("utf-8", errors="ignore")
        except Exception as e:
            raise GitHubActionsError(f"日志解压失败: {e}")

        return logs

    def list_artifacts(self, run_id: int) -> Dict:
        return self._request("GET", f"/repos/{self.owner}/{self.repo}/actions/runs/{run_id}/artifacts")

    def download_artifact(self, artifact_id: int, output_path: Optional[str] = None) -> str:
        url = f"/repos/{self.owner}/{self.repo}/actions/artifacts/{artifact_id}/zip"
        try:
            response = self.session.get(
                f"{self.BASE_URL}{url}",
                headers={
                    "Authorization": f"token {self.token}",
                    "Accept": "application/vnd.github.v3+json"
                },
                timeout=self.timeout
            )
        except requests.exceptions.Timeout:
            raise GitHubActionsError(f"产物下载超时: artifact_id={artifact_id}")

        if response.status_code != 200:
            raise GitHubActionsError(f"产物下载失败: HTTP {response.status_code}")

        if output_path is None:
            output_path = f"artifact_{artifact_id}.zip"

        with open(output_path, "wb") as f:
            f.write(response.content)

        return output_path

    def get_latest_status(self, branch: Optional[str] = None) -> Dict:
        result = self.list_runs(per_page=1, branch=branch)
        if result.get("total_count", 0) == 0:
            raise NotFoundError("未找到运行记录")
        return result["workflow_runs"][0]

    def get_latest_failure(self, branch: Optional[str] = None) -> Dict:
        result = self.list_runs(per_page=1, conclusion="failure", branch=branch)
        if result.get("total_count", 0) == 0:
            raise NotFoundError("未找到失败的运行记录")
        return result["workflow_runs"][0]


def parse_logs(logs: Dict[str, str]) -> Dict[str, List[str]]:
    """
    解析日志内容，提取错误和警告

    Args:
        logs: get_logs() 返回的日志字典

    Returns:
        包含 errors 和 warnings 的字典
    """
    errors = []
    warnings = []

    # 完善的错误匹配模式
    error_patterns = [
        r"^e: ",                    # Kotlin 编译器错误
        r"^error: ",
        r"ERROR:",
        r"\[ERROR\]",
        r"Exception:",
        r"Error:",
        r"FATAL:",
        r"::error::",
        r"FAILED:",
        r"BUILD FAILED",
        r"Execution failed for task",
        r"Compilation error",
        r"unresolved reference",
        r"Unresolved reference",
        r"cannot find symbol",
        r"package .* does not exist",
    ]

    warning_patterns = [
        r"^w: ",                    # Kotlin 编译器警告
        r"WARNING:",
        r"\[WARN\]",
        r"Warning:",
        r"Deprecated:",
        r"::warning::",
        r"Note:",
    ]

    for job_name, content in logs.items():
        lines = content.split("\n")
        for line_num, line in enumerate(lines, 1):
            line_lower = line.lower()
            for pattern in error_patterns:
                if re.search(pattern, line_lower, re.IGNORECASE):
                    errors.append(f"[{job_name}:{line_num}] {line.strip()}")
                    break
            for pattern in warning_patterns:
                if re.search(pattern, line_lower, re.IGNORECASE):
                    warnings.append(f"[{job_name}:{line_num}] {line.strip()}")
                    break

    return {"errors": errors, "warnings": warnings}


def format_status_report(run: Dict) -> str:
    """
    格式化运行状态为可读报告

    Args:
        run: 运行记录字典

    Returns:
        Markdown 格式的状态报告
    """
    lines = [
        f"## 运行状态报告 — #{run.get('run_number', 'N/A')}",
        "",
        f"- **名称**: {run.get('name', 'N/A')}",
        f"- **状态**: {run.get('status', 'N/A')}",
        f"- **结论**: {run.get('conclusion', 'N/A')}",
        f"- **分支**: {run.get('head_branch', 'N/A')}",
        f"- **事件**: {run.get('event', 'N/A')}",
        f"- **触发时间**: {run.get('run_started_at', 'N/A')}",
        f"- **更新时间**: {run.get('updated_at', 'N/A')}",
        f"- **链接**: {run.get('html_url', 'N/A')}",
    ]

    if run.get("conclusion") == "failure":
        lines.append("")
        lines.append("⚠️ 构建失败，请查看日志获取详细错误信息。")

    return "\n".join(lines)


# ============ 简便函数（供 Skill 直接调用） ============

def list_workflow_runs(owner: str, repo: str, token: str,
                       per_page: int = 30,
                       status: Optional[str] = None,
                       conclusion: Optional[str] = None,
                       branch: Optional[str] = None) -> Dict:
    client = GitHubActionsClient(token, owner, repo)
    return client.list_runs(per_page=per_page, status=status,
                            conclusion=conclusion, branch=branch)


def get_latest_status(owner: str, repo: str, token: str,
                      branch: Optional[str] = None) -> Dict:
    client = GitHubActionsClient(token, owner, repo)
    return client.get_latest_status(branch=branch)


def get_failure_logs(owner: str, repo: str, token: str,
                     branch: Optional[str] = None) -> Dict:
    client = GitHubActionsClient(token, owner, repo)
    run = client.get_latest_failure(branch=branch)
    logs = client.get_logs(run["id"], extract=True)
    parsed = parse_logs(logs)
    return {
        "run": run,
        "logs": logs,
        "parsed": parsed
    }


def download_latest_artifact(owner: str, repo: str, token: str,
                             output_dir: Optional[str] = None,
                             artifact_name: Optional[str] = None) -> List[str]:
    client = GitHubActionsClient(token, owner, repo)
    run = client.get_latest_status()
    if run.get("conclusion") != "success":
        raise GitHubActionsError(f"最新运行未成功: {run.get('conclusion')}")

    artifacts_data = client.list_artifacts(run["id"])
    artifacts = artifacts_data.get("artifacts", [])

    if artifact_name:
        artifacts = [a for a in artifacts if artifact_name in a.get("name", "")]

    if not artifacts:
        raise NotFoundError("未找到产物")

    output_dir = output_dir or "."
    os.makedirs(output_dir, exist_ok=True)

    downloaded = []
    for artifact in artifacts:
        output_path = os.path.join(output_dir, f"{artifact['name']}.zip")
        client.download_artifact(artifact["id"], output_path)
        downloaded.append(output_path)

    return downloaded