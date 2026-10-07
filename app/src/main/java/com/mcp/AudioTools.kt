package com.mcp

import android.content.Context
import com.mcp.core.llm.BackendType
import com.mcp.deepseek.AuthPrefs
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 语音交互工具（第 5 节「多模态交互」的【语音】子项）。
 *
 *  - `transcribe_audio`：语音转写（输入），把本地音频上传到 OpenAI 兼容
 *    `/audio/transcriptions`（Whisper 风格）转为文字。
 *  - `text_to_speech`：文本转语音（输出），调用 `/audio/speech` 合成音频并落盘。
 *
 * 仅 OpenAI 兼容后端可用（DeepSeek 逆向协议不支持，会自动返回错误）。
 */

/** 转写音频文件字节上限（OpenAI 对 audio/transcriptions 的建议上限约 25MB）。 */
private const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

/**
 * 语音转写工具：把本地音频文件转为文字。
 *
 * @param context Android 上下文（用于路径解析 / SAF 读取）。
 * @param auth 登录态/后端配置，用于取 baseUrl + apiKey 并校验后端类型。
 */
fun transcribeAudioTool(context: Context, auth: AuthPrefs): ToolDef = tool("transcribe_audio") {
    description = "把本地音频文件转为文字（调用 OpenAI 兼容 /audio/transcriptions，Whisper 风格）。仅当当前 LLM 后端为 OpenAI 兼容协议时可用。path 必填（绝对路径或相对 filesDir 的路径）；model（默认 whisper-1）、language、prompt、response_format（json/text/srt/verbose_json/vtt，默认 json）可选。"
    string("path") { description = "音频文件路径（绝对路径 或 相对于 filesDir 的路径）；支持 mp3/mp4/m4a/wav/webm/ogg/flac 等。" }
    string("model") {
        description = "转写模型，默认 whisper-1"
        required = false
    }
    string("language") {
        description = "音频语言（ISO-639-1，如 zh/en）；缺省自动检测"
        required = false
    }
    string("prompt") {
        description = "提示词：指定专有名词/口音/标点风格等，提升转写准确度"
        required = false
    }
    string("response_format") {
        description = "返回格式，默认 json；可选 json / text / srt / verbose_json / vtt"
        required = false
        enumValues = listOf("json", "text", "srt", "verbose_json", "vtt")
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                    return@runCatching errJson("transcribe_audio 需要 OpenAI 兼容后端：当前为 DeepSeek 逆向协议（不支持语音）。请到「设置」把 LLM 后端切换为 OpenAI 兼容协议并确保其实现 /audio/transcriptions 端点。")
                }
                if (!auth.isAudioTranscribeEnabled()) {
                    return@runCatching errJson("语音转写未启用：请到「设置 → 语音」开启「语音转写」，并确认端点可用。")
                }
                val path = args.requireStr("path", "transcribe_audio")
                val model = args["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: auth.getAudioTranscribeModel().takeIf { it.isNotBlank() }
                    ?: "whisper-1"
                val language = args["language"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: ""
                val prompt = args["prompt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: ""
                val responseFormat = args["response_format"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "json"

                val (target, err) = resolveTarget(context, path)
                if (err != null) return@runCatching errJson(err)
                val (bytes, fileName) = readAudioBytes(context, target)
                    ?: return@runCatching errJson("无法读取音频文件（文件不存在或内容为空）")

                val fields = mutableListOf<Pair<String, String>>()
                fields += "model" to model
                if (language.isNotBlank()) fields += "language" to language
                if (prompt.isNotBlank()) fields += "prompt" to prompt
                fields += "response_format" to responseFormat

                val boundary = "----AgentToolbox${System.currentTimeMillis()}"
                val body = buildMultipart(boundary, fields, "file", fileName, guessAudioMime(fileName), bytes)

                val resp = postTranscription(
                    apiKey = auth.getAudioTranscribeApiKey().ifBlank { auth.getOpenAIApiKey() },
                    endpoint = auth.getAudioTranscribeUrl().ifBlank { openAiEndpoint(auth.getOpenAIBaseUrl(), "transcriptions") },
                    boundary = boundary,
                    body = body
                )
                val text = resp.optString("text", "")
                if (text.isBlank()) {
                    return@runCatching errJson("转写结果为空（原始响应：${resp.toString().take(300)}）")
                }
                JSONObject().put("text", text).toString()
            }.getOrElse { e ->
                errJson(e.message ?: e.toString())
            }
        }
    }
}

/**
 * 文本转语音工具：合成音频并落盘到 filesDir/generated，返回本地路径。
 *
 * @param context Android 上下文（取 filesDir 作为音频落盘目录）。
 * @param auth 登录态/后端配置，用于取 baseUrl + apiKey 并校验后端类型。
 */
fun textToSpeechTool(context: Context, auth: AuthPrefs): ToolDef = tool("text_to_speech") {
    description = "把文本合成为语音（调用 OpenAI 兼容 /audio/speech），音频落盘到 filesDir/generated 并返回本地文件路径。仅当当前 LLM 后端为 OpenAI 兼容协议时可用。text 必填；voice（默认 alloy）、model（默认 tts-1）、speed（默认 1.0，范围约 0.25~4.0）、response_format（mp3/opus/aac/flac/wav/pcm，默认 mp3）可选。"
    string("text") { description = "要合成语音的文本" }
    string("voice") {
        description = "音色，默认 alloy；常见可选 alloy / echo / fable / onyx / nova / shimmer"
        required = false
        enumValues = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
    }
    string("model") {
        description = "语音合成模型，默认 tts-1（更高质量可用 tts-1-hd）"
        required = false
    }
    number("speed") {
        description = "语速，默认 1.0（约 0.25~4.0）"
        required = false
    }
    string("response_format") {
        description = "音频格式，默认 mp3；可选 mp3 / opus / aac / flac / wav / pcm"
        required = false
        enumValues = listOf("mp3", "opus", "aac", "flac", "wav", "pcm")
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                    return@runCatching errJson("text_to_speech 需要 OpenAI 兼容后端：当前为 DeepSeek 逆向协议（不支持语音）。请到「设置」把 LLM 后端切换为 OpenAI 兼容协议并确保其实现 /audio/speech 端点。")
                }
                if (!auth.isAudioTtsEnabled()) {
                    return@runCatching errJson("语音合成（TTS）未启用：请到「设置 → 语音」开启「文字转语音」，并确认端点可用。")
                }
                val text = args.requireStr("text", "text_to_speech")
                val model = args["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: auth.getAudioTtsModel().takeIf { it.isNotBlank() }
                    ?: "tts-1"
                val voice = args["voice"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "alloy"
                val speed = args["speed"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0
                val responseFormat = args["response_format"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "mp3"

                val body = JSONObject().apply {
                    put("model", model)
                    put("input", text)
                    put("voice", voice)
                    put("speed", speed)
                    put("response_format", responseFormat)
                }

                val audio = postSpeech(
                    apiKey = auth.getAudioTtsApiKey().ifBlank { auth.getOpenAIApiKey() },
                    endpoint = auth.getAudioTtsUrl().ifBlank { openAiEndpoint(auth.getOpenAIBaseUrl(), "speech") },
                    jsonBody = body.toString()
                )
                if (audio.isEmpty()) return@runCatching errJson("语音合成返回空音频")

                val dir = File(context.filesDir, "generated").apply { mkdirs() }
                val out = File(dir, "speech_${System.currentTimeMillis()}.$responseFormat")
                out.writeBytes(audio)

                JSONObject()
                    .put("audio_path", out.absolutePath)
                    .put("bytes", audio.size)
                    .put("format", responseFormat)
                    .toString()
            }.getOrElse { e ->
                errJson(e.message ?: e.toString())
            }
        }
    }
}

/** 由 baseUrl 推导语音端点：去掉 /chat/completions 后缀后补 /audio/<path>。 */
private fun openAiEndpoint(baseUrl: String, path: String): String {
    var b = baseUrl.trim().trimEnd('/')
    if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
    return "$b/audio/$path"
}

/** 读取音频字节与文件名；不存在/为空返回 null，超过上限抛 IOException。 */
private fun readAudioBytes(context: Context, target: FileTarget): Pair<ByteArray, String>? {
    val bytes: ByteArray
    val fileName: String
    when (target) {
        is FileTarget.FileRef -> {
            val f = target.file
            if (!f.exists() || !f.isFile) return null
            if (f.length() > MAX_AUDIO_BYTES) throw IOException("音频文件超过 25MB 上限，请先压缩或裁剪后再转写")
            bytes = f.readBytes()
            fileName = f.name
        }
        is FileTarget.SafRef -> {
            if (target.doc.length() > MAX_AUDIO_BYTES) throw IOException("音频文件超过 25MB 上限，请先压缩或裁剪后再转写")
            bytes = context.contentResolver.openInputStream(target.doc.uri)?.use { it.readBytes() } ?: return null
            fileName = target.doc.name ?: "audio"
        }
        is FileTarget.None -> return null
    }
    if (bytes.isEmpty()) return null
    return bytes to fileName
}

/** 按扩展名猜测音频 MIME（用于 multipart 的 file 段），猜不出退回 application/octet-stream。 */
private fun guessAudioMime(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp3" -> "audio/mpeg"
        "mp4" -> "audio/mp4"
        "m4a" -> "audio/m4a"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        else -> "application/octet-stream"
    }
}

/** 拼装 multipart/form-data 请求体：文本字段在前，文件段在后。 */
private fun buildMultipart(
    boundary: String,
    fields: List<Pair<String, String>>,
    fileField: String,
    fileName: String,
    fileMime: String,
    fileBytes: ByteArray
): ByteArray {
    val out = ByteArrayOutputStream()
    fun write(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }
    val crlf = "\r\n"
    for ((k, v) in fields) {
        write("--$boundary$crlf")
        write("Content-Disposition: form-data; name=\"$k\"$crlf$crlf")
        write("$v$crlf")
    }
    write("--$boundary$crlf")
    write("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$crlf")
    write("Content-Type: $fileMime$crlf$crlf")
    out.write(fileBytes)
    write("$crlf--$boundary--$crlf")
    return out.toByteArray()
}

/** 调用 audio/transcriptions（multipart），返回解析后的 JSONObject；失败抛 IOException。 */
private fun postTranscription(apiKey: String, endpoint: String, boundary: String, body: ByteArray): JSONObject {
    val conn = URL(endpoint).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val raw = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }
        if (code !in 200..299) {
            throw IOException("语音转写接口 HTTP $code：${apiErrMsg(raw)}")
        }
        return JSONObject(raw ?: "{}")
    } finally {
        runCatching { conn.disconnect() }
    }
}

/** 调用 audio/speech（JSON），返回音频字节；失败抛 IOException。 */
private fun postSpeech(apiKey: String, endpoint: String, jsonBody: String): ByteArray {
    val conn = URL(endpoint).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw IOException("语音合成接口 HTTP $code：${apiErrMsg(raw)}")
        }
        return conn.inputStream.use { it.readBytes() }
    } finally {
        runCatching { conn.disconnect() }
    }
}

/** 从 OpenAI 风格错误体里摘出可读信息，摘不出返回原文前 300 字。 */
private fun apiErrMsg(raw: String?): String {
    if (raw.isNullOrBlank()) return "(空响应)"
    return runCatching {
        val o = JSONObject(raw)
        when (val e = o.opt("error")) {
            is JSONObject -> e.optString("message", raw)
            else -> o.optString("message", raw)
        }
    }.getOrElse { raw.take(300) }
}

/** 把错误消息包成单字段 JSON（转义双引号/换行），避免塞进结果时破坏 JSON。 */
private fun errJson(msg: String): String =
    """{"error":"${msg.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ")}"}"""