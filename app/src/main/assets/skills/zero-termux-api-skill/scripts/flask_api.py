#!/usr/bin/env python3
# skill:zero-termux-api / script:flask_api — Termux API 服务（完整版）
import os, subprocess, json, sys
from flask import Flask, request, jsonify

app = Flask(__name__)

@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "service": "zero-termux-api"})

@app.route('/ping', methods=['GET'])
def ping():
    return jsonify({"status": "ok"})

@app.route('/api/exec', methods=['POST'])
def exec_cmd():
    data = request.get_json()
    cmd = data.get('cmd', '')
    if not cmd:
        return jsonify({"error": "missing cmd"}), 400
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=30)
        return jsonify({"stdout": result.stdout, "stderr": result.stderr, "exit_code": result.returncode})
    except subprocess.TimeoutExpired:
        return jsonify({"error": "命令执行超时（30秒）"}), 408
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/file', methods=['GET'])
def read_file():
    path = request.args.get('path', '')
    if not path:
        return jsonify({"error": "missing path"}), 400
    try:
        with open(path, 'r') as f:
            return jsonify({"content": f.read()})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/file', methods=['POST'])
def write_file():
    data = request.get_json()
    path = data.get('path', '')
    content = data.get('content', '')
    if not path:
        return jsonify({"error": "missing path"}), 400
    try:
        with open(path, 'w') as f:
            f.write(content)
        return jsonify({"ok": True, "path": path})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/device', methods=['GET'])
def device_info():
    return jsonify({
        "os": "Android",
        "termux": True,
        "api": "zero-termux-api",
        "host": request.host
    })

if __name__ == "__main__":
    # 参数按 skill.json 的 params 声明顺序作为**位置参数**传入（python3 -c <body> <port>），
    # 所以读 sys.argv[1]；旧的 --port 写法只在手工命令行下有效，保留兼容。
    port = 8765
    argv = sys.argv[1:]
    if "--port" in argv:
        idx = argv.index("--port")
        if idx + 1 < len(argv):
            port = int(argv[idx + 1])
    elif argv and argv[0].strip():
        port = int(argv[0])
    print(f"🚀 ZeroTermux API 服务启动在端口 {port}")
    print(f"📍 健康检查: http://127.0.0.1:{port}/health")
    app.run(host="0.0.0.0", port=port, debug=False)
