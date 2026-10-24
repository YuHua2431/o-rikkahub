package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.await
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.buildMediaGenerationRequest
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

/** 工具最多等多久。超时后这一轮把现状告诉模型，不抛异常。 */
private val GENERATION_TIMEOUT = 5.minutes

/** 询问服务端任务状态的间隔。 */
private val POLL_INTERVAL = 5.seconds

/** 结果地址有时效，下载失败就没机会再取，所以重试几次。 */
private const val DOWNLOAD_ATTEMPTS = 3

/** 连续这么多次查询失败就放弃等待，免得对着一个坏掉的任务轮询到超时。 */
private const val MAX_QUERY_FAILURES = 10

/** 一次请求最多带几张参考图。 */
private const val MAX_REFERENCE_IMAGES = 4

/** 单张参考图（解码后）的大小上限。 */
private const val MAX_REFERENCE_IMAGE_BYTES = 10L * 1024 * 1024

/** 下载参考图的时限。全局客户端的读超时是十分钟，取一张图不该等那么久。 */
private val REFERENCE_DOWNLOAD_TIMEOUT = 2.minutes

/** 参考图落盘的位置，在缓存目录下，空间紧张时由系统清理。 */
private const val REFERENCE_IMAGE_DIR = "reference_images"

/**
 * 用媒体创作里配置好的图像模型，按提示词生成图片。
 *
 * 直接使用 [MediaGenerationManager] 生成，不经过媒体创作：这次生成不产生媒体创作会话或记录，
 * 生成的图片复制一份到聊天文件（[FilesManager.createChatFilesByByteArrays]），
 * 以 [UIMessagePart.Image] 作为工具结果交给模型，界面把它当作模型回复的图片展示。
 *
 * 模型可以通过 `reference_images` 交出参考图。图片接口都不要求公网地址（图生图的模型
 * `requiresRemoteInputs` 为 false），适配器认本地文件：OpenAI 以文件形式上传，其余厂商把本地文件
 * 编码成 data URI，所以参考图统一先落到缓存目录，再把本地路径当作输入地址交给适配器。
 */
internal fun buildImageGenerationTool(
    context: Context,
    manager: MediaGenerationManager,
    filesManager: FilesManager,
    okHttpClient: OkHttpClient,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "create_image",
    description = """
        Create an image from a text prompt with the image model the user configured in Media Creation.
        The generated image is attached to your reply and saved with the conversation, so you can look at it and
        describe or continue from it.
        Generating takes a while and the tool waits for the result, so call it once and expect to wait.
        Use it when the user asks you to draw, illustrate or design a picture, poster, icon or other image.
        'reference_images' hands reference pictures to the model for image-to-image or reference-guided generation:
        each item is one image location, either a 'paths' entry returned by an earlier create_image call, an
        http(s) image URL, or a data: URI. Images the user attaches are given to you as pixels and as
        <attached_image path="file:///..." /> tags in the user message they belong to; pass such a path to
        use one of them as a reference.
        At most $MAX_REFERENCE_IMAGES reference images, each up to ${MAX_REFERENCE_IMAGE_BYTES / 1024 / 1024} MB.
        Not every image model takes image input: when the selected
        model does not, the tool returns REFERENCE_NOT_SUPPORTED and generates nothing, so retry without
        'reference_images' or ask the user to switch to a model that supports image input.
        'aspect_ratio' is only honored by models that support it and is ignored otherwise.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "What to draw, in natural language: subject, style, composition, lighting.")
                })
                put("aspect_ratio", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional aspect ratio such as '1:1', '16:9' or '9:16'. " +
                            "Ignored when the selected model does not support it."
                    )
                })
                put("reference_images", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Optional reference images for image-to-image or reference-guided generation, at most " +
                            "$MAX_REFERENCE_IMAGES. Each item is a 'paths' entry from an earlier create_image " +
                            "result, an http(s) image URL, or a data: URI. Fails with REFERENCE_NOT_SUPPORTED " +
                            "when the selected model cannot take image input."
                    )
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("description", "Image location: a file:// path, an http(s) URL, or a data: URI.")
                    })
                })
            },
            required = listOf("prompt")
        )
    },
    execute = { args ->
        try {
            createImage(args.jsonObject, context, manager, filesManager, okHttpClient, settingsStore)
        } catch (e: CancellationException) {
            // 用户停止生成时必须让等待一起结束，而不是当成生成失败返回
            throw e
        } catch (e: ReferenceImageException) {
            // 参考图本身有问题：把具体原因告诉模型，让它换一张或者不带参考图重试
            toolError(e.code, e.message ?: "The reference image could not be read.")
        } catch (e: Exception) {
            // 工具不抛异常：失败原因作为结果告诉模型，由它决定怎么回应用户
            toolError("GENERATION_FAILED", e.toDisplayMessage())
        }
    }
)

private suspend fun createImage(
    params: JsonObject,
    context: Context,
    manager: MediaGenerationManager,
    filesManager: FilesManager,
    okHttpClient: OkHttpClient,
    settingsStore: SettingsStore,
): List<UIMessagePart> {
    val prompt = params.string("prompt")?.trim().orEmpty()
    if (prompt.isEmpty()) {
        return toolError("MISSING_PROMPT", "prompt is required and must not be blank.")
    }

    val referenceUrls = params.referenceImages()
    if (referenceUrls.size > MAX_REFERENCE_IMAGES) {
        return toolError(
            "TOO_MANY_REFERENCE_IMAGES",
            "At most $MAX_REFERENCE_IMAGES reference images are allowed, got ${referenceUrls.size}. " +
                "Call the tool again with fewer images."
        )
    }

    val selection = settingsStore.awaitLoaded().mediaGenerationProviders.firstImageModel()
        ?: return toolError(
            "NO_IMAGE_MODEL",
            "No image generation model is configured. Ask the user to configure an image model in the " +
                "Media Creation settings first."
        )
    val capabilities = selection.provider.capabilities(MediaKind.IMAGE)
        ?: return toolError("NO_IMAGE_MODEL", "The configured image model does not support image generation.")

    // 组装请求时 List<MediaCreationAsset>.supportedBy 会把不接受的图片素材直接丢掉，那样模型只会
    // 收到一张按提示词生成的图。这里先说明原因，让它换模型或者不带参考图重试。
    if (referenceUrls.isNotEmpty() && ImageRole.REFERENCE !in capabilities.imageRoles) {
        return toolError(
            "REFERENCE_NOT_SUPPORTED",
            "The image model ${selection.model.modelId} does not accept reference images, so nothing was " +
                "generated. Call the tool again without 'reference_images' and describe the reference in the " +
                "prompt instead, or ask the user to switch to an image model that supports image input."
        )
    }

    // 参考图可能是本地聊天文件、data URI 或公网地址，统一读成字节再落盘，交给适配器的是本地路径
    val referenceFiles = if (referenceUrls.isEmpty()) {
        emptyList()
    } else {
        withContext(Dispatchers.IO) { resolveReferenceImages(referenceUrls, context, okHttpClient) }
    }

    // 和媒体创作共用同一套请求组装：不支持的参数（例如部分模型的 aspect_ratio）在这里被丢掉
    val request = buildMediaGenerationRequest(
        prompt = prompt,
        params = MediaCreationParams(
            aspectRatio = params.string("aspect_ratio")?.trim()?.takeIf(String::isNotEmpty)
        ),
        // asset 在这里只是类型和角色的载体，buildMediaGenerationRequest 只读这两项，
        // 真正下发给适配器的地址在 inputUrls 里
        assets = referenceFiles.map { MediaCreationAsset(path = it.absolutePath, role = ImageRole.REFERENCE) },
        inputUrls = referenceFiles.map { it.absolutePath },
        capabilities = capabilities,
    )

    val created = manager.create(selection.provider, selection.model, request)
        .getOrElse { return toolError("GENERATION_FAILED", it.toDisplayMessage()) }
    val finished = if (created.isTerminal) {
        created
    } else {
        awaitTask(manager, selection.provider, selection.model, created.id)
            ?: return toolError(
                "TIMEOUT",
                "Image generation did not finish within ${GENERATION_TIMEOUT.inWholeMinutes} minutes."
            )
    }

    if (finished.status != MediaGenerationStatus.SUCCEEDED) {
        return toolError("GENERATION_FAILED", finished.failureReason())
    }

    val outputs = finished.outputs.filter { it.mimeType.startsWith("image/") }
    if (outputs.isEmpty()) {
        return toolError("NO_OUTPUT", "The image model returned no image.")
    }

    val images = withContext(Dispatchers.IO) {
        val bytes = downloadImages(okHttpClient, selection.provider, outputs)
        // 复制一份到聊天文件：历史消息永久有效，后续轮次模型也能继续看到这张图
        filesManager.createChatFilesByByteArrays(bytes)
    }
    if (images.isEmpty()) {
        return toolError("SAVE_FAILED", "The generated image could not be saved to the chat files.")
    }

    return buildList {
        images.forEach { uri -> add(UIMessagePart.Image(url = uri.toString())) }
        add(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("provider", selection.provider.name)
                    put("model", selection.model.modelId)
                    put("count", images.size)
                    // 模型看不到本地图片地址，把路径写进结果，它才能在之后把这张图当参考图交回来
                    put("paths", buildJsonArray { images.forEach { add(it.toString()) } })
                    put(
                        "message",
                        "Image generated and shown to the user in this reply. It is saved with the " +
                            "conversation; pass a 'paths' entry as 'reference_images' to build on it later."
                    )
                }.toString()
            )
        )
    }
}

/**
 * 参考图读取失败。带自己的错误码，交给模型判断是换一张图还是不带参考图重试。
 */
private class ReferenceImageException(val code: String, message: String) : Exception(message)

/**
 * 把 [urls] 逐个读出来并落到缓存目录，返回可直接交给适配器的本地文件。
 *
 * 下载用的客户端收紧了整体时限；两者都在 [Dispatchers.IO] 上调用。
 */
private suspend fun resolveReferenceImages(
    urls: List<String>,
    context: Context,
    client: OkHttpClient,
): List<File> {
    val downloadClient = client.newBuilder()
        .callTimeout(REFERENCE_DOWNLOAD_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)
        .build()
    // 用户上传的文件挂在 workspace 的 /upload 下，模型可能直接用它来引用附件
    val uploadDir = File(context.filesDir, FileFolders.UPLOAD)
    return urls.map { url -> resolveReferenceImage(url, context, downloadClient, uploadDir) }
}

private suspend fun resolveReferenceImage(
    url: String,
    context: Context,
    downloadClient: OkHttpClient,
    uploadDir: File,
): File {
    val bytes = readReferenceImage(url, downloadClient, uploadDir)
    if (bytes.size > MAX_REFERENCE_IMAGE_BYTES) throw tooLargeReference(url)
    // 适配器按扩展名判断格式（OpenAI 还要按扩展名给 MIME），所以按文件头定扩展名，不信 URL 的后缀
    val extension = bytes.referenceImageExtension() ?: throw ReferenceImageException(
        "INVALID_REFERENCE_IMAGE",
        "'$url' is not a PNG, JPEG, WebP or GIF image."
    )
    val dir = File(context.cacheDir, REFERENCE_IMAGE_DIR).apply { mkdirs() }
    return File(dir, "${Uuid.random()}.$extension").apply { writeBytes(bytes) }
}

private suspend fun readReferenceImage(
    url: String,
    downloadClient: OkHttpClient,
    uploadDir: File,
): ByteArray = when {
    url.startsWith("data:", ignoreCase = true) -> decodeReferenceDataUri(url)

    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
        downloadReferenceImage(downloadClient, url)

    url.startsWith("file:", ignoreCase = true) -> localReferenceImage(
        Uri.parse(url).path ?: throw invalidReference(url)
    ).readBytesChecked()

    // 工作区的 /upload/<name> 就是 filesDir/upload/<name>
    url.startsWith("/${FileFolders.UPLOAD}/") ->
        localReferenceImage(File(uploadDir, url.removePrefix("/${FileFolders.UPLOAD}/")).path)
            .readBytesChecked()

    url.startsWith("/") -> localReferenceImage(url).readBytesChecked()

    else -> throw invalidReference(url)
}

private fun localReferenceImage(path: String): File {
    val file = File(path)
    if (!file.isFile) throw ReferenceImageException(
        "INVALID_REFERENCE_IMAGE",
        "Reference image not found: $path. Only images that still exist in the chat files can be used."
    )
    return file
}

private fun File.readBytesChecked(): ByteArray {
    if (length() > MAX_REFERENCE_IMAGE_BYTES) throw tooLargeReference(absolutePath)
    return readBytes()
}

/**
 * data URI 只接受 base64：先按字符串长度粗算大小，避免为一个超大的输入先分配内存。
 */
private fun decodeReferenceDataUri(url: String): ByteArray {
    val header = url.substringBefore(',')
    val payload = url.substringAfter(',', missingDelimiterValue = "")
    if (payload.isEmpty() || !header.contains(";base64", ignoreCase = true)) {
        throw ReferenceImageException(
            "INVALID_REFERENCE_IMAGE",
            "Only base64 data URIs are supported as reference images."
        )
    }
    // base64 大约膨胀 4/3，先按字符串长度卡一次，免得为一个超大的输入分配内存
    if (payload.length > MAX_REFERENCE_IMAGE_BYTES * 4 / 3 + 4) throw tooLargeReference("data URI")
    return try {
        Base64.decode(payload)
    } catch (e: IllegalArgumentException) {
        throw ReferenceImageException(
            "INVALID_REFERENCE_IMAGE",
            "The base64 data URI for a reference image is malformed: ${e.message}"
        )
    }
}

private suspend fun downloadReferenceImage(client: OkHttpClient, url: String): ByteArray {
    val request = Request.Builder().url(url).build()
    var attempt = 0
    while (true) {
        attempt++
        try {
            return client.newCall(request).await().use { response ->
                check(response.isSuccessful) {
                    "Failed to download the reference image (HTTP ${response.code})."
                }
                response.body.readBytesChecked(url, response.headers["Content-Length"]?.toLongOrNull())
            }
        } catch (e: ReferenceImageException) {
            // 图片本身不可用（太大），重试没有意义
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: Exception) {
            // 取消时不能当成下载失败重试
            currentCoroutineContext().ensureActive()
            if (attempt >= DOWNLOAD_ATTEMPTS) {
                throw ReferenceImageException(
                    "REFERENCE_DOWNLOAD_FAILED",
                    "Could not download the reference image '$url': ${e.toDisplayMessage()}"
                )
            }
            delay(POLL_INTERVAL)
        }
    }
}

/** 边读边数，声明的长度不可信，实际大小也要卡住。 */
private fun ResponseBody.readBytesChecked(
    url: String,
    declaredLength: Long?,
): ByteArray {
    if (declaredLength != null && declaredLength > MAX_REFERENCE_IMAGE_BYTES) throw tooLargeReference(url)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    byteStream().use { input ->
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_REFERENCE_IMAGE_BYTES) throw tooLargeReference(url)
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}

/** 按文件头判断格式，只接受适配器认识的几种图片。 */
private fun ByteArray.referenceImageExtension(): String? {
    fun startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { (this[it].toInt() and 0xFF) == values[it] }

    return when {
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
        startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        size >= 12 && copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
        startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
        else -> null
    }
}

private fun invalidReference(url: String): ReferenceImageException = ReferenceImageException(
    "INVALID_REFERENCE_IMAGE",
    "Unsupported reference image '$url'. Use a file:// path from an earlier create_image result, " +
        "an http(s) URL, or a base64 data URI."
)

private fun tooLargeReference(source: String): ReferenceImageException = ReferenceImageException(
    "REFERENCE_IMAGE_TOO_LARGE",
    "A reference image can be at most ${MAX_REFERENCE_IMAGE_BYTES / 1024 / 1024} MB: $source"
)

/**
 * 轮询服务端任务直到终态，返回 null 表示等到超时。网络抖动和服务端的临时错误不代表任务失败，
 * 退避前继续查询；接口明确拒绝（4xx）或连续失败太多次才放弃，此时异常交给调用方转成工具错误。
 */
private suspend fun awaitTask(
    manager: MediaGenerationManager,
    setting: MediaGenerationProviderSetting,
    model: MediaGenerationModel,
    taskId: String,
): MediaGenerationTask? {
    val deadline = Instant.now() + GENERATION_TIMEOUT.toJavaDuration()
    var failures = 0
    while (true) {
        val result = manager.query(setting, model, taskId)
        currentCoroutineContext().ensureActive()
        result.onSuccess { task ->
            failures = 0
            if (task.isTerminal) return task
        }.onFailure { error ->
            failures++
            if (!error.isRetryable() || failures >= MAX_QUERY_FAILURES) throw error
        }
        if (!Instant.now().isBefore(deadline)) return null
        delay(POLL_INTERVAL)
    }
}

private suspend fun downloadImages(
    client: OkHttpClient,
    setting: MediaGenerationProviderSetting,
    outputs: List<MediaGenerationOutput>,
): List<ByteArray> = outputs.map { output -> download(client, setting, output) }

private suspend fun download(
    client: OkHttpClient,
    setting: MediaGenerationProviderSetting,
    output: MediaGenerationOutput,
): ByteArray {
    output.data?.let { return it }
    val url = output.url ?: error("The image model returned neither image data nor a download URL.")
    // 个别厂商的结果地址没有签名，要带着密钥去取
    val request = Request.Builder().url(url).headers(setting.downloadHeaders(url).toHeaders()).build()
    var attempt = 0
    while (true) {
        attempt++
        try {
            return client.newCall(request).await().use { response ->
                check(response.isSuccessful) {
                    "Failed to download the generated image (HTTP ${response.code})."
                }
                response.body.bytes()
            }
        } catch (e: Exception) {
            // 取消时不能当成下载失败重试
            currentCoroutineContext().ensureActive()
            if (attempt >= DOWNLOAD_ATTEMPTS) throw e
            delay(POLL_INTERVAL)
        }
    }
}

/** 媒体创作里配置的图像模型。 */
private data class ImageModelSelection(
    val provider: MediaGenerationProviderSetting,
    val model: MediaGenerationModel,
)

/** 没有单独的「默认生图模型」设置，取配置顺序里第一个可用的图像模型。 */
private fun List<MediaGenerationProviderSetting>.firstImageModel(): ImageModelSelection? {
    forEach { provider ->
        if (provider.capabilities(MediaKind.IMAGE) != null) {
            provider.models.firstOrNull { it.kind == MediaKind.IMAGE }
                ?.let { return ImageModelSelection(provider, it) }
        }
    }
    return null
}

private fun MediaGenerationTask.failureReason(): String {
    val reason = error?.let { listOfNotNull(it.code, it.message).joinToString(": ") }
    return reason?.takeIf(String::isNotBlank) ?: "Image generation ${status.name.lowercase()}."
}

// 4xx 是接口明确拒绝（密钥错误、任务不存在等），重试没有意义；限流和超时除外
private fun Throwable.isRetryable(): Boolean =
    this !is MediaGenerationApiException || statusCode !in 400..499 || statusCode == 408 || statusCode == 429

private fun Throwable.toDisplayMessage(): String = when (this) {
    is MediaGenerationApiException -> listOfNotNull("HTTP $statusCode", code, message).joinToString(" · ")
    else -> message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}

private fun toolError(error: String, message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("error", error)
            put("message", message)
        }.toString()
    )
)

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * `reference_images` 参数：数组里的每项是一个图片地址。模型偶尔把数组写成一个字符串，
 * 这里也当作一张图处理，其余类型的项直接忽略。
 */
private fun JsonObject.referenceImages(): List<String> = when (val element = this["reference_images"]) {
    null, JsonNull -> emptyList()
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(element.takeIf(JsonPrimitive::isString)?.contentOrNull)
    else -> emptyList()
}.map(String::trim).filter(String::isNotEmpty)
