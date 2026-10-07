#!/bin/bash
# 演示脚本：按顺序接收位置参数
# $1 = name（必填）
# $2 = lang（可选，默认 zh）

NAME="${1:-world}"
LANG="${2:-zh}"

if [ -z "$NAME" ]; then
  echo '{"error":"缺少参数 name"}'
  exit 0
fi

case "$LANG" in
  en)
    echo "Hello, ${NAME}!"
    ;;
  zh|*)
    echo "你好，${NAME}！"
    ;;
esac
