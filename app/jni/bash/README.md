# Bash for Android - arm64

本目录包含编译好的 Bash ARM64 Android 静态二进制。

## 文件

- `bash` - Bash 5.2.37, 静态链接 ARM64, 1.7 MB (stripped)

## 获取方式

从源码编译（NDK r27d, Clang 18.0.4, API 34）:

```
CC=clang.exe --target=aarch64-linux-android34
CFLAGS="-O2 -fPIE" LDFLAGS="-static -fPIE"
./configure --host=aarch64-linux-android --enable-static-link
make -j4
```

## 使用方式

推送到设备:
```
adb push app/jni/bash/bash /data/local/tmp/
adb shell chmod +x /data/local/tmp/bash
adb shell /data/local/tmp/bash
```

或者在 CMakeLists.txt 中引用（作为 native 工具）:
```
set(BASH_PATH "${CMAKE_SOURCE_DIR}/../jni/bash/bash")
```
