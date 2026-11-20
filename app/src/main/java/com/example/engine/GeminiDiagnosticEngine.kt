package com.example.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.data.AutomationStepEntity
import com.example.data.StepType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "GeminiDiagnosticEngine"

data class DiagnosticOverlayState(
    val isEnabled: Boolean = true,
    val isAnalyzing: Boolean = false,
    val isVisible: Boolean = false,
    val executionMode: String = "SANDBOX",
    val stepTitle: String = "",
    val targetingLogic: String = "",
    val targetHint: String = "",
    val elementFound: Boolean = false,
    val elementLabel: String = "",
    val elementType: String = "",
    val highlightRect: Rect? = null,
    val viewportWidthPx: Int = 0,
    val viewportHeightPx: Int = 0,
    val confidence: Float = 0f,
    val diagnosticNote: String = "",
    val errorMessage: String? = null,
    val elapsedMs: Long? = null,
    val updatedAt: Long = 0L
)

object DiagnosticOverlayBus {
    private val _state = MutableStateFlow(DiagnosticOverlayState())
    val state: StateFlow<DiagnosticOverlayState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _state.update {
            if (!enabled) {
                it.copy(
                    isEnabled = false,
                    isAnalyzing = false,
                    isVisible = false,
                    highlightRect = null,
                    errorMessage = null
                )
            } else {
                it.copy(isEnabled = true)
            }
        }
    }

    fun markAnalyzing(
        executionMode: String,
        stepTitle: String,
        targetingLogic: String,
        targetHint: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ) {
        if (!_state.value.isEnabled) return
        _state.update {
            it.copy(
                isAnalyzing = true,
                isVisible = true,
                executionMode = executionMode,
                stepTitle = stepTitle,
                targetingLogic = targetingLogic,
                targetHint = targetHint,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                errorMessage = null,
                diagnosticNote = "Gemini 正在定位当前目标 UI 元素...",
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun publishSuccess(
        executionMode: String,
        stepTitle: String,
        targetingLogic: String,
        targetHint: String,
        elementFound: Boolean,
        elementLabel: String,
        elementType: String,
        highlightRect: Rect?,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        confidence: Float,
        diagnosticNote: String,
        elapsedMs: Long
    ) {
        if (!_state.value.isEnabled) return
        _state.update {
            it.copy(
                isAnalyzing = false,
                isVisible = true,
                executionMode = executionMode,
                stepTitle = stepTitle,
                targetingLogic = targetingLogic,
                targetHint = targetHint,
                elementFound = elementFound,
                elementLabel = elementLabel,
                elementType = elementType,
                highlightRect = highlightRect,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                confidence = confidence,
                diagnosticNote = diagnosticNote,
                errorMessage = null,
                elapsedMs = elapsedMs,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun publishError(
        executionMode: String,
        stepTitle: String,
        targetingLogic: String,
        targetHint: String,
        errorMessage: String,
        elapsedMs: Long? = null
    ) {
        if (!_state.value.isEnabled) return
        _state.update {
            it.copy(
                isAnalyzing = false,
                isVisible = true,
                executionMode = executionMode,
                stepTitle = stepTitle,
                targetingLogic = targetingLogic,
                targetHint = targetHint,
                elementFound = false,
                highlightRect = null,
                errorMessage = errorMessage,
                diagnosticNote = errorMessage,
                elapsedMs = elapsedMs,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun clearOverlay() {
        _state.update {
            it.copy(
                isAnalyzing = false,
                isVisible = false,
                highlightRect = null,
                elementLabel = "",
                elementType = "",
                diagnosticNote = "",
                errorMessage = null,
                elapsedMs = null
            )
        }
    }
}

sealed interface GeminiDiagnosticOutcome {
    data class Success(
        val found: Boolean,
        val elementLabel: String,
        val elementType: String,
        val highlightRect: Rect?,
        val confidence: Float,
        val diagnosticNote: String,
        val elapsedMs: Long
    ) : GeminiDiagnosticOutcome

    data class Error(
        val message: String,
        val elapsedMs: Long
    ) : GeminiDiagnosticOutcome
}

object GeminiDiagnosticEngine {

    private val diagnosticSchema = Schema.obj(
        properties = mapOf(
            "found" to Schema.boolean(description = "Whether the targeted UI element is visible on the screen"),
            "elementLabel" to Schema.string(description = "Short Chinese label identifying the targeted UI element"),
            "elementType" to Schema.string(description = "UI element category in Chinese, e.g. 按钮, 图标, 弹窗关闭按钮, 徽章, 文本标签"),
            "boxTop" to Schema.integer(description = "Normalized bounding box top coordinate from 0 to 1000"),
            "boxLeft" to Schema.integer(description = "Normalized bounding box left coordinate from 0 to 1000"),
            "boxBottom" to Schema.integer(description = "Normalized bounding box bottom coordinate from 0 to 1000"),
            "boxRight" to Schema.integer(description = "Normalized bounding box right coordinate from 0 to 1000"),
            "confidence" to Schema.double(description = "Confidence score between 0.0 and 1.0"),
            "diagnosticNote" to Schema.string(description = "Concise Chinese diagnostic note explaining element visibility, state, or why it failed to match")
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = diagnosticSchema
                temperature = 0.1f
            }
        )
    }

    suspend fun diagnoseStepTarget(
        screenBitmap: Bitmap,
        step: AutomationStepEntity,
        templateBitmap: Bitmap? = null,
        ocrHint: String = ""
    ): GeminiDiagnosticOutcome = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        val scaledScreen = downscaleForVision(screenBitmap, 1024)
        val scaledTemplate = templateBitmap?.let { downscaleForVision(it, 320) }

        try {
            val origW = screenBitmap.width.coerceAtLeast(1)
            val origH = screenBitmap.height.coerceAtLeast(1)
            val stepType = runCatching { StepType.valueOf(step.stepType) }.getOrDefault(StepType.OCR_TEXT_TAP)
            val roiDesc = if (step.roiEnabled) {
                "ROI region normalized (${(step.roiLeft * 1000).roundToInt()}, ${(step.roiTop * 1000).roundToInt()}) to (${(step.roiRight * 1000).roundToInt()}, ${(step.roiBottom * 1000).roundToInt()}) on 0..1000 scale."
            } else {
                "Full screen search (no ROI crop)."
            }

            val promptText = buildString {
                appendLine("You are a visual UI diagnostic assistant for an Android screen automation app.")
                appendLine("Locate the exact UI element on the first image (the current screen) that is being targeted by the step below.")
                appendLine("Step title: ${step.title}")
                appendLine("Targeting mode: ${stepType.label} (${stepType.name})")
                when (stepType) {
                    StepType.OCR_TEXT_TAP -> {
                        appendLine("OCR target text: \"${step.targetText}\" (matchMode=${step.textMatchMode})")
                    }
                    StepType.IMAGE_TEMPLATE_TAP -> {
                        appendLine("Template matching threshold: ${(step.matchThreshold * 100).roundToInt()}%")
                        if (scaledTemplate != null) {
                            appendLine("The second image is the target template icon/button to locate on the first screen image.")
                        }
                    }
                    else -> {
                        appendLine("Target hint: ${step.targetText.ifBlank { step.title }}")
                    }
                }
                if (ocrHint.isNotBlank()) {
                    appendLine("Recent on-screen text context: $ocrHint")
                }
                appendLine(roiDesc)
                appendLine("Return normalized coordinates in 0..1000 integer range (boxTop, boxLeft, boxBottom, boxRight) relative to the FULL screen image.")
                appendLine("If the element is not visible or obscured (for example by a popup dialog), set found=false, coordinates to 0, and explain why in Chinese in diagnosticNote.")
            }

            val requestContent = content {
                text(promptText)
                image(scaledScreen)
                if (scaledTemplate != null) {
                    text("Target template image:")
                    image(scaledTemplate)
                }
            }

            val response = withTimeout(45_000L) {
                model.generateContent(requestContent)
            }
            val jsonText = response.text
                ?: throw IllegalStateException("Gemini 返回了空诊断结果")
            val json = JSONObject(jsonText)

            val found = json.optBoolean("found", false)
            val label = json.optString("elementLabel", step.title).ifBlank { step.title }
            val elemType = json.optString("elementType", "UI 元素").ifBlank { "UI 元素" }
            val topNorm = json.optInt("boxTop", 0).coerceIn(0, 1000)
            val leftNorm = json.optInt("boxLeft", 0).coerceIn(0, 1000)
            val bottomNorm = json.optInt("boxBottom", 0).coerceIn(0, 1000)
            val rightNorm = json.optInt("boxRight", 0).coerceIn(0, 1000)
            val confidence = json.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
            val note = json.optString("diagnosticNote", "").ifBlank {
                if (found) "Gemini 已定位目标元素「$label」" else "Gemini 未在当前画面找到目标元素"
            }

            val rect = if (found && rightNorm > leftNorm && bottomNorm > topNorm) {
                Rect(
                    ((leftNorm / 1000f) * origW).roundToInt().coerceIn(0, origW - 1),
                    ((topNorm / 1000f) * origH).roundToInt().coerceIn(0, origH - 1),
                    ((rightNorm / 1000f) * origW).roundToInt().coerceIn(1, origW),
                    ((bottomNorm / 1000f) * origH).roundToInt().coerceIn(1, origH)
                )
            } else {
                null
            }

            GeminiDiagnosticOutcome.Success(
                found = found && rect != null,
                elementLabel = label,
                elementType = elemType,
                highlightRect = rect,
                confidence = confidence,
                diagnosticNote = note,
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini diagnostic timed out", e)
            GeminiDiagnosticOutcome.Error(
                message = "Gemini 视觉诊断超时（超过 45 秒），请检查网络连接后重试。",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Gemini diagnostic failed", e)
            GeminiDiagnosticOutcome.Error(
                message = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "Gemini 视觉诊断调用失败，请检查网络或 Firebase App Check 配置。",
                elapsedMs = System.currentTimeMillis() - startMs
            )
        } finally {
            if (scaledScreen !== screenBitmap) {
                scaledScreen.recycle()
            }
            if (scaledTemplate != null && scaledTemplate !== templateBitmap) {
                scaledTemplate.recycle()
            }
        }
    }

    private fun downscaleForVision(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        val largest = max(w, h)
        if (largest <= maxEdge || w <= 0 || h <= 0) return source
        val scale = maxEdge.toFloat() / largest.toFloat()
        val dstW = (w * scale).roundToInt().coerceAtLeast(1)
        val dstH = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, dstW, dstH, true)
    }
}
