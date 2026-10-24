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
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.OkHttpClient

/**
 * CogVideoX 视频生成。提交后拿到任务 ID，再按任务 ID 查询 `task_status`：`PROCESSING` 处理中、
 * `SUCCESS` 从 `video_result` 取地址、`FAIL` 失败。
 */
internal class ZhipuVideoGeneration(
    private val client: OkHttpClient,
) {
    private val id = ZhipuMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.Zhipu,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val httpRequest = bearerRequest("${setting.baseUrl.trimEnd('/')}/videos/generations", setting.apiKey)
            .postJson(buildCreateBody(model, request))
            .build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    suspend fun query(
        setting: MediaGenerationProviderSetting.Zhipu,
        model: MediaGenerationModel,
        taskId: String,
    ): MediaGenerationTask {
        // 图像任务的查询地址是 .../async-result/{id}，视频任务也走这一个接口
        val httpRequest = bearerRequest("${setting.baseUrl.trimEnd('/')}/async-result/$taskId", setting.apiKey)
            .get()
            .build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId, fallbackId = taskId)
    }

    internal fun buildCreateBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
    }

    internal fun parseTask(
        root: JsonObject,
        fallbackModel: String? = null,
        fallbackId: String? = null,
    ): MediaGenerationTask {
        // 提交结果带 id，查询结果的文档里没有 id，用请求时使用的任务 ID 兜底
        val taskId = root.string("id") ?: fallbackId ?: error("Zhipu response does not contain id")
        val status = mapStatus(root.string("task_status"))
        val urls = (root["video_result"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("url") }
        val errorObject = root.obj("error")
        return MediaGenerationTask(
            id = taskId,
            provider = id,
            model = root.string("model") ?: fallbackModel,
            status = status,
            outputs = urls.map { url ->
                MediaGenerationOutput(url = url, mimeType = "video/mp4")
            },
            // 失败时接口没有固定的错误字段，能给就用它，否则给一句泛化说明
            error = if (status == MediaGenerationStatus.FAILED) {
                MediaGenerationError(
                    code = errorObject?.string("code"),
                    message = errorObject?.string("message") ?: "Zhipu video generation failed",
                )
            } else {
                null
            },
            metadata = root,
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "Zhipu video generation requires a non-empty prompt" }
        require(request.inputs.isEmpty()) { "Zhipu video generation does not support material inputs yet" }
        require(request.count == null || request.count == 1) { "Zhipu generates one video per task" }
        require(request.resolution == null) { "Zhipu video generation does not expose resolution" }
        require(request.aspectRatio == null) { "Zhipu video generation does not expose aspectRatio" }
        require(request.durationSeconds == null) { "Zhipu video generation does not expose duration" }
        require(request.generateAudio == null) { "Zhipu video generation does not expose generateAudio" }
        require(request.watermark == null) { "Zhipu video generation does not expose watermark" }
        require(request.seed == null) { "Zhipu video generation does not expose seed" }
        require(request.promptEnhancement == null) {
            "Zhipu video generation does not expose prompt enhancement"
        }
        require(request.callbackUrl == null) { "Zhipu video generation does not support callbacks" }
    }

    // 只有这三种状态，处理中不是终态，成功后再从 video_result 取地址
    private fun mapStatus(status: String?): MediaGenerationStatus = when (status?.uppercase()) {
        "PROCESSING" -> MediaGenerationStatus.RUNNING
        "SUCCESS" -> MediaGenerationStatus.SUCCEEDED
        "FAIL" -> MediaGenerationStatus.FAILED
        else -> MediaGenerationStatus.UNKNOWN
    }
}