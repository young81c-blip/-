package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ImageMatchResult(
    val found: Boolean,
    val boundingBox: Rect,
    val centerX: Float,
    val centerY: Float,
    val similarity: Float,
    val elapsedMs: Long
)

class ImageTemplateMatcher {

    suspend fun findTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Float
    ): ImageMatchResult = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        if (screenBitmap.width < 16 || screenBitmap.height < 16 ||
            templateBitmap.width < 8 || templateBitmap.height < 8
        ) {
            return@withContext ImageMatchResult(
                found = false,
                boundingBox = Rect(),
                centerX = 0f,
                centerY = 0f,
                similarity = 0f,
                elapsedMs = System.currentTimeMillis() - startMs
            )
        }

        // Downscale screen to ~260px wide for fast multi-scale sliding-window search
        val targetDownWidth = 260f
        val downScale = min(1f, targetDownWidth / screenBitmap.width.toFloat())
        val sW = max(24, (screenBitmap.width * downScale).roundToInt())
        val sH = max(24, (screenBitmap.height * downScale).roundToInt())
        val smallScreen = if (downScale < 0.99f) {
            Bitmap.createScaledBitmap(screenBitmap, sW, sH, true)
        } else {
            screenBitmap
        }
        val screenPixels = IntArray(sW * sH)
        smallScreen.getPixels(screenPixels, 0, sW, 0, 0, sW, sH)
        if (smallScreen !== screenBitmap) {
            smallScreen.recycle()
        }

        val screenR = FloatArray(sW * sH)
        val screenG = FloatArray(sW * sH)
        val screenB = FloatArray(sW * sH)
        val screenLuma = FloatArray(sW * sH)
        for (i in screenPixels.indices) {
            val px = screenPixels[i]
            val r = Color.red(px).toFloat()
            val g = Color.green(px).toFloat()
            val b = Color.blue(px).toFloat()
            screenR[i] = r
            screenG[i] = g
            screenB[i] = b
            screenLuma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val candidateScales = listOf(1.0f, 0.85f, 1.15f, 0.70f, 1.35f, 0.55f)
        var bestScore = -1f
        var bestRectSmall = Rect()

        for (scale in candidateScales) {
            val tW = (templateBitmap.width * downScale * scale).roundToInt()
            val tH = (templateBitmap.height * downScale * scale).roundToInt()
            if (tW < 10 || tH < 10 || tW >= sW - 2 || tH >= sH - 2) continue

            val scaledTpl = Bitmap.createScaledBitmap(templateBitmap, tW, tH, true)
            val tplPixels = IntArray(tW * tH)
            scaledTpl.getPixels(tplPixels, 0, tW, 0, 0, tW, tH)
            scaledTpl.recycle()

            val sampleOffsets = ArrayList<Int>(128)
            val sampleR = ArrayList<Float>(128)
            val sampleG = ArrayList<Float>(128)
            val sampleB = ArrayList<Float>(128)
            val sampleLuma = ArrayList<Float>(128)

            val step = max(1, min(tW, tH) / 10)
            for (y in 0 until tH step step) {
                for (x in 0 until tW step step) {
                    val px = tplPixels[y * tW + x]
                    if (Color.alpha(px) >= 128) {
                        val r = Color.red(px).toFloat()
                        val g = Color.green(px).toFloat()
                        val b = Color.blue(px).toFloat()
                        sampleOffsets.add(y * sW + x)
                        sampleR.add(r)
                        sampleG.add(g)
                        sampleB.add(b)
                        sampleLuma.add(0.299f * r + 0.587f * g + 0.114f * b)
                    }
                }
            }

            if (sampleOffsets.size < 12) continue

            val count = sampleOffsets.size
            var tplMean = 0f
            for (i in 0 until count) tplMean += sampleLuma[i]
            tplMean /= count
            var tplVar = 0f
            for (i in 0 until count) {
                val d = sampleLuma[i] - tplMean
                tplVar += d * d
            }
            val tplStd = sqrt(tplVar.toDouble()).toFloat().coerceAtLeast(1e-3f)

            val maxX = sW - tW
            val maxY = sH - tH

            var peakX = 0
            var peakY = 0
            var peakScore = -1f

            // Coarse stride-3 scan
            for (y in 0..maxY step 3) {
                val rowBase = y * sW
                for (x in 0..maxX step 3) {
                    val score = computeHybridSimilarity(
                        baseIdx = rowBase + x,
                        sampleOffsets = sampleOffsets,
                        sampleR = sampleR,
                        sampleG = sampleG,
                        sampleB = sampleB,
                        sampleLuma = sampleLuma,
                        tplMean = tplMean,
                        tplStd = tplStd,
                        screenR = screenR,
                        screenG = screenG,
                        screenB = screenB,
                        screenLuma = screenLuma
                    )
                    if (score > peakScore) {
                        peakScore = score
                        peakX = x
                        peakY = y
                    }
                }
            }

            // Fine stride-1 local refinement around coarse peak
            val fineStartX = max(0, peakX - 4)
            val fineEndX = min(maxX, peakX + 4)
            val fineStartY = max(0, peakY - 4)
            val fineEndY = min(maxY, peakY + 4)

            for (y in fineStartY..fineEndY) {
                val rowBase = y * sW
                for (x in fineStartX..fineEndX) {
                    val score = computeHybridSimilarity(
                        baseIdx = rowBase + x,
                        sampleOffsets = sampleOffsets,
                        sampleR = sampleR,
                        sampleG = sampleG,
                        sampleB = sampleB,
                        sampleLuma = sampleLuma,
                        tplMean = tplMean,
                        tplStd = tplStd,
                        screenR = screenR,
                        screenG = screenG,
                        screenB = screenB,
                        screenLuma = screenLuma
                    )
                    if (score > bestScore) {
                        bestScore = score
                        bestRectSmall = Rect(x, y, x + tW, y + tH)
                    }
                }
            }
        }

        val invScaleX = screenBitmap.width.toFloat() / sW.toFloat()
        val invScaleY = screenBitmap.height.toFloat() / sH.toFloat()
        val mappedRect = Rect(
            (bestRectSmall.left * invScaleX).roundToInt(),
            (bestRectSmall.top * invScaleY).roundToInt(),
            (bestRectSmall.right * invScaleX).roundToInt(),
            (bestRectSmall.bottom * invScaleY).roundToInt()
        )
        val clampedSimilarity = bestScore.coerceIn(0f, 1f)
        val elapsed = System.currentTimeMillis() - startMs

        ImageMatchResult(
            found = clampedSimilarity >= threshold && mappedRect.width() > 0,
            boundingBox = mappedRect,
            centerX = mappedRect.exactCenterX(),
            centerY = mappedRect.exactCenterY(),
            similarity = clampedSimilarity,
            elapsedMs = elapsed
        )
    }

    private fun computeHybridSimilarity(
        baseIdx: Int,
        sampleOffsets: List<Int>,
        sampleR: List<Float>,
        sampleG: List<Float>,
        sampleB: List<Float>,
        sampleLuma: List<Float>,
        tplMean: Float,
        tplStd: Float,
        screenR: FloatArray,
        screenG: FloatArray,
        screenB: FloatArray,
        screenLuma: FloatArray
    ): Float {
        val count = sampleOffsets.size
        var patchMean = 0f
        var absColorDiffSum = 0f
        for (i in 0 until count) {
            val idx = baseIdx + sampleOffsets[i]
            patchMean += screenLuma[idx]
            absColorDiffSum += (
                abs(screenR[idx] - sampleR[i]) +
                    abs(screenG[idx] - sampleG[i]) +
                    abs(screenB[idx] - sampleB[i])
                ) / 3f
        }
        patchMean /= count
        val colorSim = (1f - (absColorDiffSum / (count * 255f))).coerceIn(0f, 1f)

        var cross = 0f
        var patchVar = 0f
        for (i in 0 until count) {
            val idx = baseIdx + sampleOffsets[i]
            val pDiff = screenLuma[idx] - patchMean
            val tDiff = sampleLuma[i] - tplMean
            cross += pDiff * tDiff
            patchVar += pDiff * pDiff
        }
        val patchStd = sqrt(patchVar.toDouble()).toFloat()
        val zncc = if (patchStd < 1e-3f) 0f else (cross / (patchStd * tplStd)).coerceIn(0f, 1f)

        return 0.55f * zncc + 0.45f * colorSim
    }
}
