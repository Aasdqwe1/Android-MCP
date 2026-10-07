package com.alibaba.mnnllm.android.llm

/**
 * MNN native 侧的回调接口。
 *
 * 【为什么包名是 com.alibaba.mnnllm.android.llm】
 * JNI 按「包名 + 类名 + 方法名 + 参数签名」精确绑定。MNN Chat 预编译的
 * libmnnllmapp.so 里，submitNative 的回调是通过 GetMethodID 在
 * `com/alibaba/mnnllm/android/llm/GenerateProgressListener` 上查 onProgress 的。
 * 包名/类名/方法名任一不符，native 就找不到方法、回调静默失效（表现为
 * 有输出但永不结束，或直接崩在 JNI 层）。故此处刻意复刻原包名，而非改成 com.mcp。
 *
 * 语义：native 每生成一段增量文本调用一次；返回 true 表示请求中止生成。
 */
interface GenerateProgressListener {
    /** @param progress 增量文本；null 表示生成结束 */
    fun onProgress(progress: String?): Boolean
}
