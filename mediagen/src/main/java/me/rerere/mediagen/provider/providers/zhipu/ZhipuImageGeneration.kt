package me.rerere.mediagen.provider.providers.zhipu

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bearerRequest
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.imageMimeType
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import me.rerere.mediagen.provider.providers.urlFileExtension
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

/**
 * CogView 图像生成，接口同步返回结果，目前只支持文生图：一次请求产出一张图，不接受图片输入。
 */
internal class ZhipuImageGeneration(
    private val client: OkHttpClient,
) {
    private val id = ZhipuMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.Zhipu,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val httpRequest = bearerRequest("${setting.baseUrl.trimEnd('/')}/images/generations", setting.apiKey)
            .postJson(buildBody(model, request))
            .build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        // 像素尺寸直接下发，CogView 的取值范围由模型决定
        request.resolution?.takeIf(String::isNotBlank)?.let { put("size", it) }
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        // 接口只给图片地址，没有 b64_json 之类的内联内容
        val outputs = (root["data"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { item ->
                val url = item.string("url") ?: return@mapNotNull null
                MediaGenerationOutput(
                    url = url,
                    mimeType = imageMimeType(url.urlFileExtension(), "image/png"),
                )
            }
        val errorObject = root.obj("error")
        return MediaGenerationTask(
            // 同步接口没有服务端任务 ID
            id = Uuid.random().toString(),
            provider = id,
            model = fallbackModel,
            status = if (outputs.isEmpty()) MediaGenerationStatus.FAILED else MediaGenerationStatus.SUCCEEDED,
            outputs = outputs,
            error = if (outputs.isEmpty()) {
                MediaGenerationError(
                    code = errorObject?.string("code"),
                    message = errorObject?.string("message") ?: "Zhipu returned no image",
                )
            } else {
                null
            },
            createdAtEpochSeconds = root.long("created"),
            metadata = root,
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "Zhipu image generation requires a non-empty prompt" }
        require(request.inputs.isEmpty()) { "Zhipu image generation does not support image inputs" }
        require(request.count == null || request.count == 1) { "Zhipu generates one image per request" }
        require(request.aspectRatio == null) {
            "Zhipu image generation takes the aspect ratio from the pixel size"
        }
        require(request.durationSeconds == null) { "Zhipu image generation does not expose duration" }
        require(request.generateAudio == null) { "Zhipu image generation does not expose generateAudio" }
        require(request.watermark == null) { "Zhipu image generation does not expose watermark" }
        require(request.seed == null) { "Zhipu image generation does not expose seed" }
        require(request.promptEnhancement == null) {
            "Zhipu image generation does not expose prompt enhancement"
        }
        require(request.callbackUrl == null) { "Zhipu image generation returns results synchronously" }
    }
}