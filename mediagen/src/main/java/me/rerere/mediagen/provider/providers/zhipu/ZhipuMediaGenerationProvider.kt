package me.rerere.mediagen.provider.providers.zhipu

import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import okhttp3.OkHttpClient

/**
 * 智谱开放平台：CogView 图像同步返回结果，CogVideoX 视频是异步任务。
 */
class ZhipuMediaGenerationProvider(
    client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.Zhipu> {
    override val id: String = PROVIDER_ID

    private val image = ZhipuImageGeneration(client)
    private val video = ZhipuVideoGeneration(client)

    override suspend fun create(
        setting: MediaGenerationProviderSetting.Zhipu,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        when (model.kind) {
            MediaKind.IMAGE -> image.create(setting, model, request)
            MediaKind.VIDEO -> video.create(setting, model, request)
        }
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.Zhipu,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = when (model.kind) {
        MediaKind.IMAGE -> super.query(setting, model, taskId)
        MediaKind.VIDEO -> runCatching { video.query(setting, model, taskId) }
    }

    internal companion object {
        const val PROVIDER_ID = "zhipu"
    }
}