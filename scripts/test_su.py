#!/usr/bin/env python3
"""
测试 run_su 工具的核心功能：在 PRoot 环境里能否调用宿主 su。
"""
import subprocess
import sys

def test_su():
    print("=" * 50)
    print("测试 run_su 核心逻辑")
    print("=" * 50)

    # 测试路径
    su_paths = [
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/magisk/.magisk/su"
    ]

    for path in su_paths:
        print(f"\n--- 测试: {path} ---")
        try:
            result = subprocess.run(
                [path, "-c", "id"],
                capture_output=True,
                text=True,
                timeout=5
            )
            print(f"  返回码: {result.returncode}")
            print(f"  stdout: {result.stdout.strip() if result.stdout else '(空)'}")
            print(f"  stderr: {result.stderr.strip() if result.stderr else '(空)'}")
            if result.returncode == 0:
                print("  ✅ 成功！这个 su 可以用")
        except FileNotFoundError:
            print("  ❌ 文件不存在")
        except Exception as e:
            print(f"  ❌ 异常: {e}")

    print("\n" + "=" * 50)
    print("测试完成")
    print("=" * 50)

if __name__ == "__main__":
    test_su()