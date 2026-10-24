package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

internal const val CHART_DISPLAY_TOOL_NAME = "chart_display"
internal const val CREATE_IMAGE_TOOL_NAME = "create_image"

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock

    /** 成功执行的 chart_display 工具调用, 在正文中以图表卡片展示 */
    data class ChartBlock(val tool: UIMessagePart.Tool, val index: Int) : MessagePartBlock

    /** create_image 的工具调用, 生成出的图片在正文中按图片展示 (生成中显示占位) */
    data class GeneratedImageBlock(val tool: UIMessagePart.Tool, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中
 * 成功执行的 chart_display 原地替换为 ChartBlock (会切断所在的 ThinkingBlock);
 * 生成中或失败的调用仍作为普通 ToolStep 展示
 *
 * [generating] 表示这条消息还在生成: create_image 还没产出结果时, 正文里要显示「正在生成」
 */
fun List<UIMessagePart>.groupMessageParts(generating: Boolean): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                when {
                    part.isSuccessfulChartDisplay() -> {
                        flushThinkingSteps()
                        result.add(MessagePartBlock.ChartBlock(part, index))
                    }

                    part.isShownAsGeneratedImage(generating) -> {
                        flushThinkingSteps()
                        result.add(MessagePartBlock.GeneratedImageBlock(part, index))
                    }

                    else -> currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                }
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/**
 * create_image 的产出直接当正文图片展示, 不再折叠在工具卡片里:
 * 已经拿到图片时总是提升; 还在生成中 (仅当这条消息正在生成) 也提升, 好在正文里显示「正在生成」。
 * 等待审批、生成失败或生成被中断的调用仍作为普通工具步骤展示, 用户还能展开看细节。
 */
private fun UIMessagePart.Tool.isShownAsGeneratedImage(generating: Boolean): Boolean {
    if (toolName != CREATE_IMAGE_TOOL_NAME || isPending) return false
    if (isExecuted) return output.any { it is UIMessagePart.Image }
    return generating
}

private fun UIMessagePart.Tool.isSuccessfulChartDisplay(): Boolean {
    if (toolName != CHART_DISPLAY_TOOL_NAME || !isExecuted) return false
    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val result = runCatching { JsonInstant.parseToJsonElement(outputText) }.getOrNull() as? JsonObject
    return (result?.get("success") as? JsonPrimitive)?.booleanOrNull == true
}
