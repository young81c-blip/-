package com.example.engine

import android.graphics.Bitmap
import com.example.data.AutomationRepository
import com.example.data.AutomationTaskEntity
import com.example.data.StopConditionType
import com.example.data.TextMatchMode

data class LoopStopCheckResult(
    val shouldStop: Boolean,
    val reason: String = ""
)

object LoopControlEvaluator {

    suspend fun evaluateStopCondition(
        task: AutomationTaskEntity,
        elapsedMs: Long,
        anyStepFailedInRound: Boolean,
        captureFrame: suspend () -> Bitmap?,
        ocrEngine: OcrRecognitionEngine,
        templateMatcher: ImageTemplateMatcher,
        repository: AutomationRepository
    ): LoopStopCheckResult {
        if (task.maxDurationMinutes > 0 && elapsedMs >= task.maxDurationMinutes * 60_000L) {
            return LoopStopCheckResult(
                shouldStop = true,
                reason = "达到最长运行时长上限 (${task.maxDurationMinutes} 分钟)"
            )
        }

        val conditionType = runCatching { StopConditionType.valueOf(task.stopConditionType) }
            .getOrDefault(StopConditionType.NONE)

        when (conditionType) {
            StopConditionType.NONE -> return LoopStopCheckResult(false)

            StopConditionType.STOP_AFTER_DURATION -> {
                val limitMin = task.maxDurationMinutes.coerceAtLeast(1)
                if (elapsedMs >= limitMin * 60_000L) {
                    return LoopStopCheckResult(true, "已运行满 $limitMin 分钟，触发定时停止")
                }
                return LoopStopCheckResult(false)
            }

            StopConditionType.STOP_ON_STEP_MISS -> {
                if (anyStepFailedInRound) {
                    return LoopStopCheckResult(true, "存在步骤未能识别目标，触发异常停止")
                }
                return LoopStopCheckResult(false)
            }

            StopConditionType.STOP_WHEN_TEXT_APPEARS -> {
                val query = task.stopConditionText.trim()
                if (query.isEmpty()) return LoopStopCheckResult(false)
                val frame = captureFrame() ?: return LoopStopCheckResult(false)
                try {
                    val elements = ocrEngine.recognizeText(frame)
                    val hit = ocrEngine.findBestMatch(elements, query, TextMatchMode.CONTAINS)
                    if (hit != null) {
                        return LoopStopCheckResult(
                            shouldStop = true,
                            reason = "识别到停止文字「${hit.text}」"
                        )
                    }
                } finally {
                    frame.recycle()
                }
                return LoopStopCheckResult(false)
            }

            StopConditionType.STOP_WHEN_TEXT_MISSING -> {
                val query = task.stopConditionText.trim()
                if (query.isEmpty()) return LoopStopCheckResult(false)
                val frame = captureFrame() ?: return LoopStopCheckResult(false)
                try {
                    val elements = ocrEngine.recognizeText(frame)
                    val hit = ocrEngine.findBestMatch(elements, query, TextMatchMode.CONTAINS)
                    if (hit == null) {
                        return LoopStopCheckResult(
                            shouldStop = true,
                            reason = "目标文字「$query」已从屏幕消失，触发停止"
                        )
                    }
                } finally {
                    frame.recycle()
                }
                return LoopStopCheckResult(false)
            }

            StopConditionType.STOP_WHEN_IMAGE_APPEARS -> {
                val path = task.stopConditionImagePath
                if (path.isBlank()) return LoopStopCheckResult(false)
                val tpl = repository.loadTemplateBitmap(path) ?: return LoopStopCheckResult(false)
                val frame = captureFrame()
                if (frame == null) {
                    tpl.recycle()
                    return LoopStopCheckResult(false)
                }
                try {
                    val match = templateMatcher.findTemplate(frame, tpl, task.stopConditionThreshold)
                    if (match.found) {
                        val pct = (match.similarity * 100).toInt()
                        return LoopStopCheckResult(
                            shouldStop = true,
                            reason = "识别到停止图像模板（相似度 ${pct}%）"
                        )
                    }
                } finally {
                    tpl.recycle()
                    frame.recycle()
                }
                return LoopStopCheckResult(false)
            }
        }
    }
}
