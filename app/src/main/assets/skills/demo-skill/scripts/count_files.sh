#!/bin/bash
# 演示整数参数与自定义超时
# $1 = dir（必填）
# $2 = max_depth（可选，默认 1）

DIR="${1:-}"
DEPTH="${2:-1}"

if [ -z "$DIR" ]; then
  echo '{"error":"缺少参数 dir"}'
  exit 0
fi

if [ ! -d "$DIR" ]; then
  echo "{\"error\":\"目录不存在: ${DIR}\"}"
  exit 0
fi

COUNT=$(find "$DIR" -maxdepth "$DEPTH" -type f 2>/dev/null | wc -l)
SIZE=$(du -sh "$DIR" 2>/dev/null | cut -f1)

echo "目录: ${DIR}"
echo "深度: ${DEPTH}"
echo "文件数: ${COUNT}"
echo "占用: ${SIZE}"
