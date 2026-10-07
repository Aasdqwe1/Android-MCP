package com.alibaba.mnnllm.android.llm

/**
 * MNN native 的音频数据回调接口（TTS/语音输出用）。
 *
 * 包名同样必须精确对齐——native 通过它回传波形。本适配只做文本对话，
 * 该接口保留定义以避免 JNI 解析期找不到类。
 */
interface AudioDataListener {
    fun onAudioData(data: FloatArray, isEnd: Boolean): Boolean
}
