#!/bin/bash
# skill:zero-termux-api / script:status — 检查 API 服务状态

PORT="${1:-8765}"
if curl -s "http://localhost:$PORT/health" > /dev/null 2>&1; then
    echo "✅ Termux API 服务运行中 (端口 $PORT)"
    curl -s "http://localhost:$PORT/health" | head -5
else
    echo "❌ Termux API 服务未运行 (端口 $PORT)"
    echo "请调用工具 skill_zero_termux_api_start（port=$PORT，默认 8765）"
fi
