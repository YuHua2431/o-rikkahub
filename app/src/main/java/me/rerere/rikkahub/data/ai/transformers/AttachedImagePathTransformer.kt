package me.rerere.rikkahub.data.ai.transformers

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * 把用户消息里附带的图片路径写成 `<attached_image path="..." />` 标签，放进这段文本交给模型。
 *
 * 请求里的图片一律编码成 base64（`me.rerere.ai.util.encodeBase64`），模型拿不到本地路径，
 * 也就没法把用户发的图交给 `create_image` 当参考图。这里在用户消息末尾追加每个附件的本地文件地址，
 * 模型可以把 path 原样放进 `create_image` 的 `reference_images`。
 *
 * 只看用户消息：工具结果里的图（`create_image` 的产出）由工具自己把 paths 写进结果 JSON。
 * 没有可引用的图片时原样返回，不追加空标签。标签只依赖消息本身，多次请求渲染结果一致，不影响提示词缓存。
 */
object AttachedImagePathTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val hasImage = messages.any { message ->
            message.role == MessageRole.USER && message.parts.any { it is UIMessagePart.Image }
        }
        if (!hasImage) return messages

        return withContext(Dispatchers.IO) {
            messages.map { message ->
                if (message.role != MessageRole.USER) return@map message
                val paths = message.parts
                    .filterIsInstance<UIMessagePart.Image>()
                    .mapNotNull { part -> localImagePath(part) }
                // data: / http(s) 附件没有可交给工具的本地路径，文件已被清理的也不写
                if (paths.isEmpty()) return@map message
                message.copy(
                    parts = message.parts + UIMessagePart.Text(
                        paths.joinToString(separator = "\n") { path ->
                            "<attached_image path=\"$path\" />"
                        }
                    )
                )
            }
        }
    }

    private fun localImagePath(part: UIMessagePart.Image): String? {
        if (!part.url.startsWith("file:", ignoreCase = true)) return null
        val path = Uri.parse(part.url).path ?: return null
        // 统一成 file:///... 的形式，和 create_image 结果里的 paths 一致
        return "file://$path".takeIf { File(path).isFile }
    }
}
