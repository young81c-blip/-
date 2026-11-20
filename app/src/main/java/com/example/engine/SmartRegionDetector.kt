package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.TextMatchMode
import com.example.ui.CropBoxNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DetectedFeatureKind(val label: String) {
    INTERACTIVE_BUTTON("交互按钮"),
    INPUT_FIELD("输入框"),
    TEXT_CONTENT("文本元素"),
    ICON_OR_BADGE("图形图标/徽章")
}

enum class RecommendedAlgorithm(val label: String) {
    OCR_TEXT("OCR 文字识别"),
    TEMPLATE_MATCH("图像模板匹配")
}

data class SmartDetectionResult(
    val featureKind: DetectedFeatureKind,
    val recommendedAlgorithm: RecommendedAlgorithm,
    val confidenceScore: Int,
    val detectedText: String?,
    val suggestedTextMatchMode: TextMatchMode,
    val suggestedTemplateThreshold: Float,
    val suggestedStepTitle: String,
    val reasonSummary: String,
    val featureMetrics: List<String>,
    val elapsedMs: Long
)

private data class VisualPatchMetrics(
    val aspectRatio: Float,
    val edgeDensity: Float,
    val borderBoxScore: Float,
    val colorRichness: Float,
    val interiorUniformity: Float
)

object SmartRegionDetector {

    private val inputKeywords = listOf(
        "请输入", "输入", "搜索", "查找", "账号", "用户名", "手机号", "手机",
        "验证码", "密码", "邮箱", "留言", "备注", "search", "enter", "input",
        "password", "email", "phone", "keyword"
    )

    private val buttonActionKeywords = listOf(
        "确定", "确认", "提交", "登录", "注册", "签到", "领取", "立即", "抢券",
        "购买", "支付", "去使用", "下一步", "完成", "关闭", "跳过", "取消",
        "允许", "同意", "继续", "重试", "保存", "ok", "submit", "login",
        "claim", "buy", "skip", "close", "next", "confirm"
    )

    suspend fun analyzeRegion(
        sourceBitmap: Bitmap,
        cropBox: CropBoxNorm,
        ocrEngine: OcrRecognitionEngine
    ): SmartDetectionResult {
        val startMs = System.currentTimeMillis()
        val roiFrame = RegionCropHelper.cropForRegion(
            source = sourceBitmap,
            enabled = true,
            normLeft = cropBox.left,
            normTop = cropBox.top,
            normRight = cropBox.right,
            normBottom = cropBox.bottom
        )

        return try {
            val cropBmp = roiFrame.bitmap
            val visual = withContext(Dispatchers.Default) {
                extractVisualMetrics(cropBmp)
            }
            val ocrElements = runCatching {
                ocrEngine.recognizeText(cropBmp)
            }.getOrDefault(emptyList())

            val lineElements = ocrElements.filter { it.isLine && it.text.isNotBlank() }
            val cropArea = max(1f, (cropBmp.width * cropBmp.height).toFloat())
            val textAreaSum = lineElements.sumOf {
                it.boundingBox.width().coerceAtLeast(1) * it.boundingBox.height().coerceAtLeast(1)
            }.toFloat()
            val textAreaRatio = (textAreaSum / cropArea).coerceIn(0f, 1f)

            val bestLine = lineElements.maxByOrNull { el ->
                val lenBonus = el.text.length.coerceAtMost(12) * 500
                el.boundingBox.width() * el.boundingBox.height() + lenBonus
            }
            val combinedText = when {
                lineElements.isEmpty() -> null
                lineElements.size == 1 -> lineElements.first().text.trim()
                else -> bestLine?.text?.trim() ?: lineElements.first().text.trim()
            }?.takeIf { it.isNotBlank() }

            val lowerText = combinedText?.lowercase().orEmpty()
            val hasInputHint = lowerText.isNotEmpty() && inputKeywords.any { lowerText.contains(it) }
            val hasButtonVerb = lowerText.isNotEmpty() && buttonActionKeywords.any { lowerText.contains(it) }

            val textPct = (textAreaRatio * 100f).roundToInt()
            val colorPct = (visual.colorRichness * 100f).roundToInt()
            val borderPct = (visual.borderBoxScore * 100f).roundToInt()
            val metrics = listOf(
                "文字占比 ${textPct}%",
                "边缘盒框 ${borderPct}%",
                "色彩饱和 ${colorPct}%",
                String.format(java.util.Locale.US, "宽高比 %.1f", visual.aspectRatio)
            )

            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

            classifyAndRecommend(
                combinedText = combinedText,
                lineCount = lineElements.size,
                textAreaRatio = textAreaRatio,
                hasInputHint = hasInputHint,
                hasButtonVerb = hasButtonVerb,
                visual = visual,
                metrics = metrics,
                elapsedMs = elapsed
            )
        } finally {
            if (roiFrame.ownsBitmap) {
                roiFrame.bitmap.recycle()
            }
        }
    }

    private fun classifyAndRecommend(
        combinedText: String?,
        lineCount: Int,
        textAreaRatio: Float,
        hasInputHint: Boolean,
        hasButtonVerb: Boolean,
        visual: VisualPatchMetrics,
        metrics: List<String>,
        elapsedMs: Long
    ): SmartDetectionResult {
        val textLen = combinedText?.length ?: 0

        // 1. Input field detection: placeholder hint OR wide uniform bordered box
        val isWideBox = visual.aspectRatio >= 2.0f &&
            visual.borderBoxScore >= 0.30f &&
            visual.interiorUniformity >= 0.62f &&
            visual.colorRichness < 0.24f

        if (hasInputHint || (isWideBox && textLen <= 16 && !hasButtonVerb)) {
            return if (!combinedText.isNullOrBlank() && textLen >= 2) {
                SmartDetectionResult(
                    featureKind = DetectedFeatureKind.INPUT_FIELD,
                    recommendedAlgorithm = RecommendedAlgorithm.OCR_TEXT,
                    confidenceScore = if (hasInputHint) 93 else 84,
                    detectedText = combinedText,
                    suggestedTextMatchMode = TextMatchMode.CONTAINS,
                    suggestedTemplateThreshold = 0.78f,
                    suggestedStepTitle = "定位输入框「$combinedText」",
                    reasonSummary = "检测到宽矩形边框与输入提示文本「$combinedText」，推荐采用 OCR 文字识别（包含匹配 + ROI 区域限定）精准点击聚焦输入框。",
                    featureMetrics = metrics,
                    elapsedMs = elapsedMs
                )
            } else {
                SmartDetectionResult(
                    featureKind = DetectedFeatureKind.INPUT_FIELD,
                    recommendedAlgorithm = RecommendedAlgorithm.TEMPLATE_MATCH,
                    confidenceScore = 82,
                    detectedText = null,
                    suggestedTextMatchMode = TextMatchMode.CONTAINS,
                    suggestedTemplateThreshold = 0.78f,
                    suggestedStepTitle = "图像匹配空白输入框",
                    reasonSummary = "检测到空白输入框外框特征（高宽高比、规则边界且无可读占位文字），推荐采用图像模板匹配定位框体中心。",
                    featureMetrics = metrics,
                    elapsedMs = elapsedMs
                )
            }
        }

        // 2. Interactive button detection: action keyword or framed short text
        val looksLikeFramedButton = !combinedText.isNullOrBlank() &&
            textLen in 1..14 &&
            lineCount <= 2 &&
            (hasButtonVerb || (visual.borderBoxScore >= 0.26f && visual.interiorUniformity >= 0.45f))

        if (looksLikeFramedButton && combinedText != null) {
            return if (textLen >= 2) {
                val matchMode = if (textLen <= 5) TextMatchMode.EXACT else TextMatchMode.CONTAINS
                SmartDetectionResult(
                    featureKind = DetectedFeatureKind.INTERACTIVE_BUTTON,
                    recommendedAlgorithm = RecommendedAlgorithm.OCR_TEXT,
                    confidenceScore = if (hasButtonVerb) 95 else 88,
                    detectedText = combinedText,
                    suggestedTextMatchMode = matchMode,
                    suggestedTemplateThreshold = 0.75f,
                    suggestedStepTitle = "智能点击按钮「$combinedText」",
                    reasonSummary = "检测到交互按钮「$combinedText」（边缘边界清晰、文字对比度高），推荐 OCR 文字识别：跨分辨率更稳定且可自动限定区域识别。",
                    featureMetrics = metrics,
                    elapsedMs = elapsedMs
                )
            } else {
                SmartDetectionResult(
                    featureKind = DetectedFeatureKind.INTERACTIVE_BUTTON,
                    recommendedAlgorithm = RecommendedAlgorithm.TEMPLATE_MATCH,
                    confidenceScore = 84,
                    detectedText = combinedText,
                    suggestedTextMatchMode = TextMatchMode.EXACT,
                    suggestedTemplateThreshold = 0.76f,
                    suggestedStepTitle = "模板匹配图标按钮",
                    reasonSummary = "按钮仅含单字符/符号「$combinedText」且轮廓独立，直接用 OCR 易受干扰，推荐采用图像模板匹配。",
                    featureMetrics = metrics,
                    elapsedMs = elapsedMs
                )
            }
        }

        // 3. Graphic icon / badge detection: rich colors or complex edges without meaningful text
        val isGraphicDominant = combinedText == null ||
            (textAreaRatio < 0.05f && (visual.colorRichness >= 0.14f || visual.edgeDensity >= 0.12f)) ||
            (textLen <= 1 && visual.colorRichness >= 0.12f)

        if (isGraphicDominant) {
            val threshold = if (visual.colorRichness >= 0.24f) 0.73f else 0.77f
            return SmartDetectionResult(
                featureKind = DetectedFeatureKind.ICON_OR_BADGE,
                recommendedAlgorithm = RecommendedAlgorithm.TEMPLATE_MATCH,
                confidenceScore = if (combinedText == null) 92 else 85,
                detectedText = combinedText,
                suggestedTextMatchMode = TextMatchMode.CONTAINS,
                suggestedTemplateThreshold = threshold,
                suggestedStepTitle = "图像识别目标图标",
                reasonSummary = "检测到该区域以图形轮廓与色彩纹理为主（色彩饱和 ${(visual.colorRichness * 100).roundToInt()}%，无稳定文本），推荐采用多尺度图像模板匹配（阈值 ${(threshold * 100).roundToInt()}%）。",
                featureMetrics = metrics,
                elapsedMs = elapsedMs
            )
        }

        // 4. General text label / link / menu item
        val safeText = combinedText ?: ""
        return SmartDetectionResult(
            featureKind = DetectedFeatureKind.TEXT_CONTENT,
            recommendedAlgorithm = RecommendedAlgorithm.OCR_TEXT,
            confidenceScore = if (textAreaRatio >= 0.10f) 90 else 82,
            detectedText = safeText,
            suggestedTextMatchMode = TextMatchMode.CONTAINS,
            suggestedTemplateThreshold = 0.75f,
            suggestedStepTitle = "识别点击「$safeText」",
            reasonSummary = "检测到区域内主要包含文本内容「$safeText」，文字区域明确，推荐采用 OCR 文字识别（含区域限定加速）直接定位文字中心点击。",
            featureMetrics = metrics,
            elapsedMs = elapsedMs
        )
    }

    private fun extractVisualMetrics(bmp: Bitmap): VisualPatchMetrics {
        val aspectRatio = bmp.width.toFloat() / max(1, bmp.height).toFloat()
        val targetW = 96
        val targetH = max(24, (targetW / aspectRatio.coerceIn(0.25f, 4f)).roundToInt()).coerceAtMost(160)
        val scaled = if (bmp.width != targetW || bmp.height != targetH) {
            Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        } else {
            bmp
        }

        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled !== bmp) scaled.recycle()

        val luma = FloatArray(pixels.size)
        var satSum = 0f
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            satSum += (maxC - minC) / 255f
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        val colorRichness = (satSum / max(1, pixels.size)).coerceIn(0f, 1f)

        var edgeHits = 0
        var totalInner = 0
        for (y in 1 until targetH - 1) {
            val row = y * targetW
            for (x in 1 until targetW - 1) {
                val gx = abs(luma[row + x + 1] - luma[row + x - 1])
                val gy = abs(luma[(y + 1) * targetW + x] - luma[(y - 1) * targetW + x])
                if (gx + gy > 42f) edgeHits++
                totalInner++
            }
        }
        val edgeDensity = (edgeHits.toFloat() / max(1, totalInner)).coerceIn(0f, 1f)

        // Check horizontal & vertical border edge presence near the perimeter bands (outer 18%)
        val bandY = max(2, (targetH * 0.18f).roundToInt())
        val bandX = max(2, (targetW * 0.15f).roundToInt())
        var horizBorderHits = 0
        var horizChecks = 0
        for (x in bandX until targetW - bandX) {
            var topHit = false
            for (y in 1 until bandY) {
                if (abs(luma[(y + 1) * targetW + x] - luma[(y - 1) * targetW + x]) > 24f) {
                    topHit = true
                    break
                }
            }
            var botHit = false
            for (y in (targetH - bandY) until targetH - 1) {
                if (abs(luma[(y + 1) * targetW + x] - luma[(y - 1) * targetW + x]) > 24f) {
                    botHit = true
                    break
                }
            }
            if (topHit) horizBorderHits++
            if (botHit) horizBorderHits++
            horizChecks += 2
        }

        var vertBorderHits = 0
        var vertChecks = 0
        for (y in bandY until targetH - bandY) {
            var leftHit = false
            for (x in 1 until bandX) {
                if (abs(luma[y * targetW + x + 1] - luma[y * targetW + x - 1]) > 24f) {
                    leftHit = true
                    break
                }
            }
            var rightHit = false
            for (x in (targetW - bandX) until targetW - 1) {
                if (abs(luma[y * targetW + x + 1] - luma[y * targetW + x - 1]) > 24f) {
                    rightHit = true
                    break
                }
            }
            if (leftHit) vertBorderHits++
            if (rightHit) vertBorderHits++
            vertChecks += 2
        }

        val hRatio = horizBorderHits.toFloat() / max(1, horizChecks)
        val vRatio = vertBorderHits.toFloat() / max(1, vertChecks)
        val borderBoxScore = (0.55f * hRatio + 0.45f * vRatio).coerceIn(0f, 1f)

        // Interior uniformity: check how consistently lit the middle 50% patch is
        val yStart = (targetH * 0.25f).roundToInt()
        val yEnd = (targetH * 0.75f).roundToInt().coerceAtLeast(yStart + 1)
        val xStart = (targetW * 0.2f).roundToInt()
        val xEnd = (targetW * 0.8f).roundToInt().coerceAtLeast(xStart + 1)
        var innerSum = 0f
        var innerCount = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                innerSum += luma[y * targetW + x]
                innerCount++
            }
        }
        val innerMean = innerSum / max(1, innerCount)
        var uniformCount = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                if (abs(luma[y * targetW + x] - innerMean) < 28f) {
                    uniformCount++
                }
            }
        }
        val interiorUniformity = (uniformCount.toFloat() / max(1, innerCount)).coerceIn(0f, 1f)

        return VisualPatchMetrics(
            aspectRatio = aspectRatio,
            edgeDensity = edgeDensity,
            borderBoxScore = borderBoxScore,
            colorRichness = colorRichness,
            interiorUniformity = interiorUniformity
        )
    }
}
