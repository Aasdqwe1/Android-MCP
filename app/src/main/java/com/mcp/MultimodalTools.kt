package com.mcp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.mcp.core.llm.BackendType
import com.mcp.deepseek.AuthPrefs
import com.mcp.llm.MessageEvent
import com.mcp.FileTarget.FileRef
import com.mcp.FileTarget.SafRef
import com.mcp.llm.ChatMessage
import com.mcp.llm.LLMClientFactory
import com.mcp.llm.LLMConfig
import com.mcp.llm.LLMRequest
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 多模态交互工具（第 5 节「多模态交互」）。
 *
 * 【视觉输入】两个互补工具：
 *  - `read_image`：本地图像特征分析（像素 + 元数据），不消耗模型轮次、离线可用——
 *    尺寸 / MIME 类型 / 格式（魔数判定）/ 文件大小 / 宽高比 / 平均色 / 低分辨率色块网格，
 *    让 Agent 不借助视觉模型也能粗略感知截图、报错图等的布局与明暗分布。
 *  - `describe_image`：视觉 LLM 后端，把图片作为多模态消息发给 OpenAI 兼容的视觉模型，
 *    做真正的 OCR / 文字理解 / 对象与布局描述（依赖 ChatMessage 的 imageUrls 多模态管道）。
 * 【生成能力】：
 *  - `generate_image`：调用 OpenAI 兼容 Images API（`/v1/images/generations`，DALL-E 风格）
 *    文生图，base64 结果落盘到 filesDir/generated 并返回路径（url 结果直接返回地址）。
 *
 * 以上涉及模型的部分仅针对 OpenAI 兼容后端（DeepSeek 逆向协议不支持，会自动返回错误）。
 */

/** 工作缩略图长边上限（像素）：平均色与色块网格只需比格数稍高的分辨率即可。 */
private const val MAX_WORK_LONG_SIDE = 64

/**
 * 读取图片并返回本地视觉特征。
 *
 * @param context Android 上下文（用于 SAF 解析与内容解析器）。
 */
fun readImageTool(context: Context): ToolDef = tool("read_image") {
    description = "读取图片文件并返回本地视觉特征：宽高、MIME 类型、格式（JPEG/PNG/GIF/WebP 等，按魔数判定）、文件大小、宽高比、平均色，以及低分辨率色块网格（把图缩放到 grid×grid 个色块返回各自的平均色，用于粗略感知截图/报错图的布局与明暗分布）。路径为绝对路径或相对于 filesDir 的路径。注意：这是本地图像分析（像素/元数据），不识别图中文字；如需 OCR/理解图中内容需配合视觉 LLM 后端另行接入。"
    string("path") { description = "图片文件路径（绝对路径 或 相对于 filesDir 的路径）" }
    integer("grid") {
        description = "色块网格边长：把图片缩放成 grid×grid 个色块，返回每个色块的平均色（十六进制）。默认 12，范围 4~32。"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "read_image")
                val grid = (args["grid"]?.jsonPrimitive?.content?.toIntOrNull() ?: 12).coerceIn(4, 32)
                val (target, err) = resolveTarget(context, path)
                if (err != null) return@runCatching """{"error":"$err"}"""
                analyzeImage(context, target, grid)
            }.getOrElse { e ->
                """{"error":"${(e.message ?: e.toString()).replace("\"", "'")}"}"""
            }
        }
    }
}

/** 视觉理解单张图片的字节上限（8 MB）：超出后拒绝 base64，避免内存/请求体爆炸。 */
private const val MAX_VISION_IMAGE_BYTES = 8L * 1024 * 1024

/** 未指定 prompt 时的默认视觉理解指令。 */
private const val DEFAULT_VISION_PROMPT =
    "请详细描述这张图片的内容：图中主要对象、文字（如有请尽量逐字转录）、界面/布局（若是截图或报错信息），以及有助于理解这张图的关键细节。"

/**
 * 视觉理解工具：把图片作为多模态消息发送给 OpenAI 兼容的视觉模型，返回对图片内容的理解
 * （文字转录 / OCR / 对象与布局描述等）。是【视觉输入】子项的「视觉 LLM 后端」落地。
 *
 * 与 [readImageTool]（本地像素/元数据特征）互补：`read_image` 不消耗模型轮次、离线可用；
 * `describe_image` 真正"看懂"图片，但依赖已配置 OpenAI 兼容后端 + 多模态模型。
 *
 * @param context Android 上下文（用于 SAF 解析与内容解析器）。
 * @param auth 登录态/后端配置，用于构造视觉 LLM 客户端。
 */
fun describeImageTool(context: Context, auth: AuthPrefs): ToolDef = tool("describe_image") {
    description = "把图片发送给视觉大模型并返回对图片内容的理解（OCR 文字转录、对象/界面/布局描述、报错截图解读）。OpenAI 兼容后端走 image_url 多模态；DeepSeek 逆向协议走 file/upload_file → ref_file_ids 通道（自动完成上传、解析轮询与会话创建）。路径为绝对路径或相对于 filesDir 的路径；prompt 可选，缺省用通用描述指令。"
    string("path") { description = "图片文件路径（绝对路径 或 相对于 filesDir 的路径）" }
    string("prompt") {
        description = "对图片的具体提问/要求（如「逐字转录图中所有文字」「这是什么报错」）；缺省为通用描述指令。"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "describe_image")
                val prompt = args["prompt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_VISION_PROMPT
                val (target, err) = resolveTarget(context, path)
                if (err != null) return@runCatching errJson(err)

                val image = readImageForVision(context, target)
                    ?: return@runCatching errJson("无法读取或编码图片（文件不存在、超过 ${MAX_VISION_IMAGE_BYTES / (1024 * 1024)} MB 上限，或内容不是有效图片）")

                val description = describeImageViaOpenAI(auth, image, prompt) ?: return@runCatching errJson("视觉模型未返回任何内容（请确认已配置的模型支持图片输入）")

                JSONObject()
                    .put("path", image.displayPath)
                    .put("format", image.format)
                    .put("description", description)
                    .toString()
            }.getOrElse { e ->
                errJson(e.message ?: e.toString())
            }
        }
    }
}

/** OpenAI 兼容后端：把 data URL 作为多模态 image_url 消息发出，收集 SSE 内容。 */
private suspend fun describeImageViaOpenAI(
    auth: AuthPrefs,
    image: VisionImage,
    prompt: String
): String? {
    val client = LLMClientFactory.create(auth)
    val request = LLMRequest(
        messages = listOf(ChatMessage(role = "user", content = prompt, imageUrls = listOf(image.dataUrl))),
        tools = null,
        config = LLMConfig(
            model = "", // 空串 → OpenAIClient 回退到用户配置的模型名
            temperature = auth.getOpenAITemperature(),
            maxTokens = auth.getOpenAIMaxTokens()
        )
    )
    val content = StringBuilder()
    var error: String? = null
    client.sendMessage(request).collect { ev ->
        when (ev) {
            is MessageEvent.Content -> content.append(ev.delta)
            is MessageEvent.Error -> error = ev.throwable.message ?: ev.throwable.toString()
            else -> {}
        }
    }
    if (error != null) throw RuntimeException(error!!)
    return content.toString().trim().ifEmpty { null }
}

/**
 * 生成能力：文生图工具。调用 OpenAI 兼容 Images API（`POST /v1/images/generations`，DALL-E 风格）
 * 生成图片；`b64_json` 结果落盘到 filesDir/generated 返回本地路径，`url` 结果直接返回地址。
 * 仅 OpenAI 兼容后端可用（DeepSeek 逆向协议不支持，会自动返回错误）。
 *
 * @param context Android 上下文（取 filesDir 作为生成图片落盘目录）。
 * @param auth 登录态/后端配置，用于取 baseUrl + apiKey 并校验后端类型。
 */
fun generateImageTool(context: Context, auth: AuthPrefs): ToolDef = tool("generate_image") {
    description = "调用 OpenAI 兼容 Images API（DALL-E 风格文生图）根据描述生成图片。仅当当前 LLM 后端为 OpenAI 兼容协议且实现 /images/generations 端点时可用；b64_json 结果落盘到 filesDir/generated 返回本地路径（可供 read_image 分析），url 结果直接返回地址。prompt 必填；size（1024x1024/1792x1024/1024x1792）、n（1–4）、model（默认 dall-e-3）可选。"
    string("prompt") { description = "描述要生成的图片（英文描述效果通常更好）" }
    string("size") {
        description = "图片尺寸，默认 1024x1024；DALL-E 3 支持 1024x1024 / 1792x1024 / 1024x1792"
        required = false
    }
    integer("n") {
        description = "生成数量，默认 1；DALL-E 3 仅支持 n=1"
        required = false
    }
    string("model") {
        description = "图片生成模型，默认 dall-e-3（也可填 gpt-image-1 等）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                    return@runCatching errJson("generate_image 需要 OpenAI 兼容后端：当前为 DeepSeek 逆向协议（不支持文生图）。请到「设置」把 LLM 后端切换为 OpenAI 兼容协议，并确保其实现 /images/generations 端点。")
                }
                val prompt = args.requireStr("prompt", "generate_image")
                val size = args["size"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "1024x1024"
                val n = (args["n"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(1, 4)
                val model = args["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "dall-e-3"

                val resp = postImagesGeneration(
                    apiKey = auth.getOpenAIApiKey(),
                    endpoint = imagesEndpoint(auth.getOpenAIBaseUrl()),
                    model = model, prompt = prompt, n = n, size = size
                )
                val data = resp.optJSONArray("data")
                    ?: return@runCatching errJson("图片生成接口未返回 data 数组，请确认 baseUrl 指向正确的 OpenAI 兼容服务")

                val dir = File(context.filesDir, "generated").apply { mkdirs() }
                val ts = System.currentTimeMillis()
                val images = JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val b64 = item.optString("b64_json", "")
                    val url = item.optString("url", "")
                    when {
                        b64.isNotEmpty() -> {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val f = File(dir, "image_${ts}_$i.png")
                            f.writeBytes(bytes)
                            images.put(f.absolutePath)
                        }
                        url.isNotEmpty() -> images.put(url)
                    }
                }
                if (images.length() == 0) {
                    return@runCatching errJson("图片生成接口未返回图片数据（b64_json 与 url 均为空）")
                }
                JSONObject()
                    .put("images", images)
                    .put("saved_dir", dir.absolutePath)
                    .toString()
            }.getOrElse { e ->
                errJson(e.message ?: e.toString())
            }
        }
    }
}

/** 由用户填写的 baseUrl 推导图片生成端点：去掉 /chat/completions 后缀后补 /images/generations。 */
private fun imagesEndpoint(baseUrl: String): String {
    var b = baseUrl.trim().trimEnd('/')
    if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
    return "$b/images/generations"
}

/** 调用 OpenAI 兼容 Images API，返回解析后的 JSONObject；失败抛 IOException（含可读错误）。 */
private fun postImagesGeneration(
    apiKey: String,
    endpoint: String,
    model: String,
    prompt: String,
    n: Int,
    size: String
): JSONObject {
    val body = JSONObject().apply {
        put("model", model)
        put("prompt", prompt)
        put("n", n)
        put("size", size)
        // 优先 base64：本地落盘供 read_image/后续处理；不支持该字段的服务会回退返回 url，二者均已兼容。
        put("response_format", "b64_json")
    }
    val conn = URL(endpoint).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val raw = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }
        if (code !in 200..299) {
            throw IOException("图片生成接口 HTTP $code：${extractApiError(raw)}")
        }
        return JSONObject(raw ?: "{}")
    } finally {
        runCatching { conn.disconnect() }
    }
}

/** 从 OpenAI 风格错误体（{"error":{"message":...}}）里摘出可读信息，摘不出就返回原文前 300 字。 */
private fun extractApiError(raw: String?): String {
    if (raw.isNullOrBlank()) return "(空响应)"
    return runCatching {
        val o = JSONObject(raw)
        when (val e = o.opt("error")) {
            is JSONObject -> e.optString("message", raw)
            else -> o.optString("message", raw)
        }
    }.getOrElse { raw.take(300) }
}

/** 把错误消息包成单字段 JSON，统一转义（双引号→单引号、换行→空格），避免塞进结果时破坏 JSON。 */
private fun errJson(msg: String): String =
    """{"error":"${msg.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ")}"}"""

private data class VisionImage(
    val dataUrl: String,
    val displayPath: String,
    val format: String
)

/**
 * 把图片读入内存并编码为 data URL（`data:<mime>;base64,...`）。
 * 用魔数判型得到 MIME（不信任扩展名），超过 [MAX_VISION_IMAGE_BYTES] 或读不到字节则返回 null。
 */
private fun readImageForVision(context: Context, target: FileTarget): VisionImage? {
    val bytes: ByteArray
    val displayPath: String
    when (target) {
        is FileTarget.FileRef -> {
            val f = target.file
            if (!f.exists() || !f.isFile) return null
            if (f.length() > MAX_VISION_IMAGE_BYTES) return null
            bytes = f.readBytes()
            displayPath = f.absolutePath
        }
        is FileTarget.SafRef -> {
            if (target.doc.length() > MAX_VISION_IMAGE_BYTES) return null
            bytes = context.contentResolver.openInputStream(target.doc.uri)?.use { it.readBytes() } ?: return null
            displayPath = target.displayPath
        }
        is FileTarget.None -> return null
    }
    if (bytes.isEmpty()) return null
    val format = detectImageFormat(bytes.copyOf(minOf(bytes.size, 16)))
    val mime = mimeForFormat(format)
    val dataUrl = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    return VisionImage(dataUrl, displayPath, format)
}

/**
 * 分析图片：魔数判型 + 尺寸/元数据 + 平均色 + 色块网格。
 * 全程用「按需打开流」的方式读取，避免一次性把整图读入内存（大图只 down-sample 到工作缩略图）。
 */
private fun analyzeImage(context: Context, target: FileTarget, grid: Int): String {
    val (displayPath, sizeBytes, openStream) = when (target) {
        is FileTarget.FileRef -> {
            val f = target.file
            if (!f.exists() || !f.isFile) {
                return """{"error":"文件不存在或不是文件: ${f.absolutePath}"}"""
            }
            Triple(f.absolutePath, f.length()) { f.inputStream() as InputStream? }
        }
        is FileTarget.SafRef -> Triple(
            target.displayPath,
            target.doc.length()
        ) { context.contentResolver.openInputStream(target.doc.uri) }
        is FileTarget.None -> return """{"error":"无法解析图片路径"}"""
    }

    // 魔数判定（前 16 字节即可区分常见格式）
    val head = openStream()?.use { readHead(it, 16) } ?: ByteArray(0)
    val format = detectImageFormat(head)
    val mimeFallback = mimeForFormat(format)

    // 解码边界（不加载全图）
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val width = bounds.outWidth
    val height = bounds.outHeight
    val mime = bounds.outMimeType ?: mimeFallback

    if (width <= 0 || height <= 0) {
        return JSONObject()
            .put("path", displayPath)
            .put("format", format)
            .put("file_size", sizeBytes)
            .put("error", "无法解码图片（可能已损坏或不是图片文件）")
            .toString()
    }

    val o = JSONObject()
        .put("path", displayPath)
        .put("width", width)
        .put("height", height)
        .put("mime_type", mime)
        .put("format", format)
        .put("file_size", sizeBytes)
        .put("aspect_ratio", round2(width.toDouble() / height.toDouble()))
        .put("aspect_label", "$width:$height ≈ ${reducedRatio(width, height)}")
        .put("is_probable_screenshot", isProbableScreenshot(width, height))

    // 解码工作缩略图 → 平均色 + 色块网格
    val sample = computeInSampleSize(width, height, MAX_WORK_LONG_SIDE)
    val bmpOpts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = openStream()?.use { BitmapFactory.decodeStream(it, null, bmpOpts) }
    if (bmp != null) {
        o.put("average_color", averageColor(bmp))
        o.put("grid_size", grid)
        val gridJson = JSONArray()
        for (row in computeGrid(bmp, grid)) {
            val arr = JSONArray()
            for (hex in row) arr.put(hex)
            gridJson.put(arr)
        }
        o.put("grid", gridJson)
        bmp.recycle()
    }
    return o.toString()
}

/** 从流中读取最多 [n] 字节（尽量读满，读不到为止）。兼容低 API：不用 InputStream.readNBytes。 */
private fun readHead(input: InputStream, n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = input.read(buf, off, n - off)
        if (r < 0) break
        off += r
    }
    return if (off == 0) ByteArray(0) else buf.copyOf(off)
}

/** 依据魔数判定图片格式（不依赖扩展名）。 */
private fun detectImageFormat(b: ByteArray): String {
    fun sig(offset: Int, vararg bytes: Int): Boolean {
        if (offset + bytes.size > b.size) return false
        for ((i, v) in bytes.withIndex()) {
            if ((b[offset + i].toInt() and 0xFF) != v) return false
        }
        return true
    }
    return when {
        sig(0, 0xFF, 0xD8, 0xFF) -> "JPEG"
        sig(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "PNG"
        sig(0, 0x47, 0x49, 0x46, 0x38) && b.size >= 6 &&
            (((b[4].toInt() and 0xFF) == 0x37) || ((b[4].toInt() and 0xFF) == 0x39)) &&
            ((b[5].toInt() and 0xFF) == 0x61) -> "GIF"
        sig(0, 0x42, 0x4D) -> "BMP"
        sig(0, 0x52, 0x49, 0x46, 0x46) && sig(8, 0x57, 0x45, 0x42, 0x50) -> "WEBP"
        sig(4, 0x66, 0x74, 0x79, 0x70) -> "HEIF"
        sig(0, 0x49, 0x49) -> "TIFF"
        sig(0, 0x4D, 0x4D) -> "TIFF"
        else -> "UNKNOWN"
    }
}

/** 格式 → MIME 兜底映射（当 BitmapFactory 未给出 outMimeType 时使用）。 */
private fun mimeForFormat(format: String): String = when (format) {
    "JPEG" -> "image/jpeg"
    "PNG" -> "image/png"
    "GIF" -> "image/gif"
    "BMP" -> "image/bmp"
    "WEBP" -> "image/webp"
    "HEIF" -> "image/heif"
    "TIFF" -> "image/tiff"
    else -> "application/octet-stream"
}

private fun computeInSampleSize(width: Int, height: Int, targetLongSide: Int): Int {
    var sample = 1
    while (maxOf(width, height) / sample > targetLongSide) sample *= 2
    return sample
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private fun reducedRatio(w: Int, h: Int): String {
    val g = gcd(w, h)
    return if (g <= 1) "$w:$h" else "${w / g}:${h / g}"
}

private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

/** 常见移动端屏幕宽高比（约分后的整数形式），用于粗判是否为截图。 */
private val COMMON_SCREEN_RATIOS = setOf(
    "16:9", "9:16", "8:5", "5:8", "4:3", "3:4", "20:9", "9:20",
    "2:1", "1:2", "13:6", "6:13", "7:3", "3:7", "19:9", "9:19", "18:9", "9:18"
)

private fun isProbableScreenshot(w: Int, h: Int): Boolean = reducedRatio(w, h) in COMMON_SCREEN_RATIOS

private fun averageColor(bmp: Bitmap): String {
    val w = bmp.width
    val h = bmp.height
    val n = w * h
    if (n <= 0) return "#000000"
    var r = 0L
    var g = 0L
    var b = 0L
    for (y in 0 until h) {
        for (x in 0 until w) {
            val c = bmp.getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
    }
    return hex((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/** 把工作缩略图按 grid×grid 分块，返回每块的平均色（方格左上到右下）。 */
private fun computeGrid(bmp: Bitmap, grid: Int): List<List<String>> {
    val w = bmp.width
    val h = bmp.height
    val sumR = Array(grid) { LongArray(grid) }
    val sumG = Array(grid) { LongArray(grid) }
    val sumB = Array(grid) { LongArray(grid) }
    val cnt = Array(grid) { IntArray(grid) }
    for (y in 0 until h) {
        val gy = if (h == 1) 0 else y * grid / h
        for (x in 0 until w) {
            val gx = if (w == 1) 0 else x * grid / w
            val c = bmp.getPixel(x, y)
            sumR[gy][gx] += ((c shr 16) and 0xFF).toLong()
            sumG[gy][gx] += ((c shr 8) and 0xFF).toLong()
            sumB[gy][gx] += (c and 0xFF).toLong()
            cnt[gy][gx]++
        }
    }
    return List(grid) { row ->
        List(grid) { col ->
            val n = cnt[row][col]
            if (n == 0) "#000000"
            else hex((sumR[row][col] / n).toInt(), (sumG[row][col] / n).toInt(), (sumB[row][col] / n).toInt())
        }
    }
}

private fun hex(r: Int, g: Int, b: Int): String =
    String.format("#%02X%02X%02X", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))