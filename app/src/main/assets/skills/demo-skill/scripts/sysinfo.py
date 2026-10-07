#!/usr/bin/env python3
# 演示 python3 解释器：无参数脚本
import platform
import os
import sys

print("=== 运行环境 ===")
print(f"Python : {sys.version.split()[0]}")
print(f"平台   : {platform.system()} {platform.machine()}")
print(f"内核   : {platform.release()}")
print(f"工作目录: {os.getcwd()}")
print()
print("=== 提示 ===")
print("脚本 stdout 会原样作为工具返回值。")
print("需要结构化数据时，直接 print JSON 字符串即可。")
