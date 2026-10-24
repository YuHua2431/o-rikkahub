package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.await
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
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.buildMediaGenerationRequest
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** 工具最多等多久。超时后这一轮把现状告诉模型，不抛异常。 */
private val GENERATION_TIMEOUT = 5.minutes

/** 询问服务端任务状态的间隔。 */
private val POLL_INTERVAL = 5.seconds

/** 结果地址有时效，下载失败就没机会再取，所以重试几次。 */
private const val DOWNLOAD_ATTEMPTS = 3

/** 连续这么多次查询失败就放弃等待，免得对着一个坏掉的任务轮询到超时。 */
private const val MAX_QUERY_FAILURES = 10

/**
 * 用媒体创作里配置好的图像模型，按提示词生成图片。
 *
 * 直接使用 [MediaGenerationManager] 生成，不经过媒体创作：这次生成不产生媒体创作会话或记录，
 * 生成的图片复制一份到聊天文件（[FilesManager.createChatFilesByByteArrays]），
 * 以 [UIMessagePart.Image] 作为工具结果交给模型，界面把它当作模型回复的图片展示。
 */
internal fun buildImageGenerationTool(
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
        Only text prompts are supported: reference images cannot be passed, so describe the subject, style,
        composition and lighting in the prompt instead.
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
            },
            required = listOf("prompt")
        )
    },
    execute = { args ->
        try {
            createImage(args.jsonObject, manager, filesManager, okHttpClient, settingsStore)
        } catch (e: CancellationException) {
            // 用户停止生成时必须让等待一起结束，而不是当成生成失败返回
            throw e
        } catch (e: Exception) {
            // 工具不抛异常：失败原因作为结果告诉模型，由它决定怎么回应用户
            toolError("GENERATION_FAILED", e.toDisplayMessage())
        }
    }
)

private suspend fun createImage(
    params: JsonObject,
    manager: MediaGenerationManager,
    filesManager: FilesManager,
    okHttpClient: OkHttpClient,
    settingsStore: SettingsStore,
): List<UIMessagePart> {
    val prompt = params.string("prompt")?.trim().orEmpty()
    if (prompt.isEmpty()) {
        return toolError("MISSING_PROMPT", "prompt is required and must not be blank.")
    }

    val selection = settingsStore.awaitLoaded().mediaGenerationProviders.firstImageModel()
        ?: return toolError(
            "NO_IMAGE_MODEL",
            "No image generation model is configured. Ask the user to configure an image model in the " +
                "Media Creation settings first."
        )
    val capabilities = selection.provider.capabilities(MediaKind.IMAGE)
        ?: return toolError("NO_IMAGE_MODEL", "The configured image model does not support image generation.")

    // 和媒体创作共用同一套请求组装：不支持的参数（例如部分模型的 aspect_ratio）在这里被丢掉
    val request = buildMediaGenerationRequest(
        prompt = prompt,
        params = MediaCreationParams(
            aspectRatio = params.string("aspect_ratio")?.trim()?.takeIf(String::isNotEmpty)
        ),
        assets = emptyList(),
        inputUrls = emptyList(),
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
                    put(
                        "message",
                        "Image generated and shown to the user in this reply. It is saved with the " +
                            "conversation, so it can be referred to later."
                    )
                }.toString()
            )
        )
    }
}

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
