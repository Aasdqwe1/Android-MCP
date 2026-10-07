#!/usr/bin/env python3
"""
局域网 Web 服务器服务
提供 chat_desktop.html 供局域网设备访问

特性：
- 常驻服务（serve 子命令），由宿主侧 PRoot 进程直接运行，无二次子进程
- 通过命令行参数配置端口与静态资源目录
- 仅对外暴露白名单文件（无目录列表，禁止目录遍历）
"""

import os
import sys
import socket
import socketserver
import argparse
from http.server import BaseHTTPRequestHandler

# 模块级别默认端口
DEFAULT_PORT = 8693
# 默认静态资源目录（仅供无 --dir 调用兜底；App 会显式传入运行时目录）
DEFAULT_ASSETS_DIR = "/storage/emulated/0/Work/agent-toolbox-kotlin/app/src/main/assets"

# 允许对外提供的文件名白名单（杜绝目录列表与任意文件读取）
INDEX_FILE = "chat_desktop.html"
ALLOWED_FILES = {
    INDEX_FILE,
    "katex.min.css",
    "marked.min.js",
    "highlight.min.js",
    "purify.min.js",
    "katex.min.js",
    "mermaid.min.js",
}


def build_handler(web_dir):
    """构造只服务白名单静态文件的请求处理器，web_dir 通过闭包注入。"""

    class WhitelistHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            # 去掉查询串，取路径；"/" 或空路径落到首页
            name = self.path.split('?', 1)[0].lstrip('/')
            if name == "":
                name = INDEX_FILE
            base = os.path.basename(name)
            # 拒绝任何带目录的路径（目录遍历）及白名单之外的文件
            if name != base or base not in ALLOWED_FILES:
                self.send_error(403, "Forbidden")
                return
            full = os.path.join(web_dir, base)
            if not os.path.isfile(full):
                self.send_error(404, "Not Found")
                return
            if base.endswith(".html"):
                ctype = "text/html"
            elif base.endswith(".css"):
                ctype = "text/css"
            else:
                ctype = "application/javascript"
            try:
                with open(full, "rb") as f:
                    data = f.read()
            except OSError:
                self.send_error(500, "Internal Server Error")
                return
            self.send_response(200)
            self.send_header("Content-Type", ctype + "; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass  # 静默访问日志，避免刷屏

    return WhitelistHandler


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def serve_forever(port, web_dir):
    """常驻服务进程：只对白名单文件提供 HTTP，由宿主 PRoot 进程直接运行。"""
    if not os.path.isfile(os.path.join(web_dir, INDEX_FILE)):
        print(f"❌ 错误: 找不到 {INDEX_FILE}（目录 {web_dir}）", flush=True)
        sys.exit(1)
    os.chdir(web_dir)
    socketserver.TCPServer.allow_reuse_address = True
    handler = build_handler(web_dir)
    ip = get_local_ip()
    with socketserver.TCPServer(('0.0.0.0', port), handler) as httpd:
        print(f'🚀 服务器运行在端口 {port}', flush=True)
        print(f'   地址: http://{ip}:{port}/{INDEX_FILE}', flush=True)
        httpd.serve_forever()


def main():
    parser = argparse.ArgumentParser(description="MCP Web 服务器")
    parser.add_argument("action", choices=["serve"], help="操作：仅支持常驻服务 serve")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="端口号 (默认: 8693)")
    parser.add_argument("--dir", default=DEFAULT_ASSETS_DIR, help="静态资源目录（白名单文件所在目录）")
    args = parser.parse_args()
    serve_forever(args.port, args.dir)


if __name__ == "__main__":
    main()