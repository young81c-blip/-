package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.data.AutomationRepository
import com.example.data.AutomationStepEntity
import com.example.data.StepType
import com.example.data.TextMatchMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

data class PreloadEngineState(
    val isEnabled: Boolean = true,
    val isPreloading: Boolean = false,
    val warmedTaskName: String = "",
    val preparedStepCount: Int = 0,
    val nextStepHint: String = "",
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val totalSavedLatencyMs: Long = 0L,
    val lastPreloadOcrMs: Long? = null,
    val statusSummary: String = "预加载引擎就绪：将在当前步骤执行及间隙后台预抓取截图并预匹配 OCR 与图像模板特征"
)

object PreloadTelemetryBus {
    private val _state = MutableStateFlow(PreloadEngineState())
    val state: StateFlow<PreloadEngineState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                isEnabled = enabled,
                isPreloading = if (enabled) it.isPreloading else false,
                statusSummary = if (enabled) {
                    "预加载引擎已开启：执行期间提前抓取截图并预计算下一步特征匹配"
                } else {
                    "预加载引擎已关闭（全部使用实时识别）"
                }
            )
        }
    }

    fun resetSession(taskName: String, totalSteps: Int) {
        _state.update {
            it.copy(
                isPreloading = false,
                warmedTaskName = taskName,
                preparedStepCount = 0,
                nextStepHint = "正在预热 $totalSteps 个步骤特征...",
                cacheHits = 0,
                cacheMisses = 0,
                totalSavedLatencyMs = 0L,
                lastPreloadOcrMs = null,
                statusSummary = if (it.isEnabled) "正在后台预热「$taskName」OCR 模型与图像模板特征" else it.statusSummary
            )
        }
    }

    fun markPrepared(preparedCount: Int, nextHint: String, warmUpMs: Long? = null) {
        _state.update {
            if (!it.isEnabled) return@update it
            val warmSuffix = warmUpMs?.let { ms -> "（OCR 预热 ${ms}ms）" } ?: ""
            it.copy(
                preparedStepCount = preparedCount,
                nextStepHint = nextHint,
                statusSummary = "已预加载 $preparedCount 步特征$warmSuffix"
            )
        }
    }

    fun markPreloading(nextStepTitle: String) {
        _state.update {
            if (!it.isEnabled) return@update it
            it.copy(
                isPreloading = true,
                nextStepHint = nextStepTitle,
                statusSummary = "后台正在提前抓取画面并预匹配下一步「$nextStepTitle」..."
            )
        }
    }

    fun markSnapshotReady(
        nextStepTitle: String,
        ocrMs: Long,
        elementCount: Int,
        detailLabel: String? = null
    ) {
        _state.update {
            if (!it.isEnabled) return@update it
            val detail = detailLabel ?: "缓存 $elementCount 处文本特征"
            it.copy(
                isPreloading = false,
                nextStepHint = nextStepTitle,
                lastPreloadOcrMs = ocrMs,
                statusSummary = "已预匹配「$nextStepTitle」：$detail（预耗时 ${ocrMs}ms）"
            )
        }
    }

    fun recordHit(stepTitle: String, savedMs: Long, modeLabel: String = "OCR") {
        _state.update {
            it.copy(
                isPreloading = false,
                cacheHits = it.cacheHits + 1,
                totalSavedLatencyMs = it.totalSavedLatencyMs + savedMs,
                statusSummary = "命中「$stepTitle」${modeLabel}预加载缓存 · 本次节省 ${savedMs}ms 延迟"
            )
        }
    }

    fun recordMiss(stepTitle: String, reason: String, modeLabel: String = "OCR") {
        _state.update {
            it.copy(
                isPreloading = false,
                cacheMisses = it.cacheMisses + 1,
                statusSummary = "「$stepTitle」$reason，已无缝回退实时$modeLabel"
            )
        }
    }
}

data class PreloadedOcrHit(
    val elements: List<RecognizedTextElement>,
    val preMatchedElement: RecognizedTextElement?,
    val savedMs: Long
)

data class PreloadedImageHit(
    val matchResult: ImageMatchResult,
    val savedMs: Long
)

private data class FrameSignature(
    val width: Int,
    val height: Int,
    val samples: IntArray
)

private data class PreloadedScreenSnapshot(
    val targetStepOrder: Int,
    val roiKey: String,
    val isFullScreen: Boolean,
    val signature: FrameSignature,
    val elements: List<RecognizedTextElement>,
    val preMatchedElement: RecognizedTextElement?,
    val ocrElapsedMs: Long,
    val capturedAtMs: Long
)

private data class PreloadedImageSnapshot(
    val targetStepOrder: Int,
    val templateImagePath: String,
    val matchThreshold: Float,
    val roiKey: String,
    val signature: FrameSignature,
    val matchResult: ImageMatchResult,
    val matchElapsedMs: Long,
    val capturedAtMs: Long
)

/**
 * Background Task Execution Preload Engine:
 * 1. Warms up ML Kit Chinese OCR recognizer and pre-decodes template Bitmaps / Regex patterns at task launch.
 * 2. Overlapping with active step execution (pre-scan waits, pre-tap dwells) and post-tap delays, captures
 *    background screenshots and pre-computes BOTH OCR text matching and Image Template similarity matching
 *    for the upcoming task step.
 * 3. Serves near-zero-latency cached hits on step entry whenever the screen remains visually consistent,
 *    or safely falls back to live OCR/template matching when UI transitions occurred.
 */
class TaskPreloadEngine(
    private val ocrEngine: OcrRecognitionEngine,
    private val templateMatcher: ImageTemplateMatcher
) {
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val templateCache = ConcurrentHashMap<String, Bitmap>()
    private val compiledRegexCache = ConcurrentHashMap<String, Regex>()

    @Volatile
    private var latestSnapshot: PreloadedScreenSnapshot? = null

    @Volatile
    private var latestImageSnapshot: PreloadedImageSnapshot? = null

    @Volatile
    private var activeRepository: AutomationRepository? = null

    @Volatile
    private var activePreloadStepOrder: Int = -1

    private var warmUpJob: Job? = null
    private var speculativeJob: Job? = null

    fun prepareTaskInBackground(
        taskName: String,
        steps: List<AutomationStepEntity>,
        repository: AutomationRepository
    ) {
        clearSession(resetTelemetry = false)
        activeRepository = repository
        PreloadTelemetryBus.resetSession(taskName, steps.size)
        if (!PreloadTelemetryBus.state.value.isEnabled) return

        warmUpJob = preloadScope.launch(Dispatchers.IO) {
            try {
                val warmMs = ocrEngine.warmUp()
                var prepared = 0
                for (step in steps) {
                    if (!isActive) break
                    if (!step.isEnabled) continue
                    when (runCatching { StepType.valueOf(step.stepType) }.getOrNull()) {
                        StepType.IMAGE_TEMPLATE_TAP -> {
                            if (step.templateImagePath.isNotBlank() && !templateCache.containsKey(step.templateImagePath)) {
                                repository.loadTemplateBitmap(step.templateImagePath)?.let { bmp ->
                                    templateCache[step.templateImagePath] = bmp
                                }
                            }
                            prepared++
                        }
                        StepType.OCR_TEXT_TAP -> {
                            if (step.textMatchMode == TextMatchMode.REGEX.name && step.targetText.isNotBlank()) {
                                runCatching {
                                    Regex(step.targetText.trim(), RegexOption.IGNORE_CASE)
                                }.getOrNull()?.let { regex ->
                                    compiledRegexCache[step.targetText.trim()] = regex
                                }
                            }
                            prepared++
                        }
                        else -> prepared++
                    }
                }
                val firstTarget = steps.firstOrNull { it.isEnabled }?.title ?: "待执行"
                PreloadTelemetryBus.markPrepared(prepared, firstTarget, warmMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Non-fatal; execution path still runs live loading
            }
        }
    }

    /**
     * Retrieves a pre-decoded template bitmap copy safe for caller lifecycle, or loads on demand.
     */
    suspend fun loadTemplateCopy(
        path: String,
        repository: AutomationRepository
    ): Bitmap? {
        val cached = templateCache[path]
        if (cached != null && !cached.isRecycled) {
            val copy = cached.copy(cached.config ?: Bitmap.Config.ARGB_8888, false)
            if (copy != null) return copy
        }
        val loaded = repository.loadTemplateBitmap(path) ?: return null
        if (PreloadTelemetryBus.state.value.isEnabled && !loaded.isRecycled) {
            loaded.copy(loaded.config ?: Bitmap.Config.ARGB_8888, false)?.let { cacheCopy ->
                templateCache[path] = cacheCopy
            }
        }
        return loaded
    }

    fun hasFreshOrActivePreloadFor(step: AutomationStepEntity, maxAgeMs: Long = 1400L): Boolean {
        if (!PreloadTelemetryBus.state.value.isEnabled) return false
        if (speculativeJob?.isActive == true && activePreloadStepOrder == step.stepOrder) {
            return true
        }
        val now = System.currentTimeMillis()
        val type = runCatching { StepType.valueOf(step.stepType) }.getOrNull()
        return when (type) {
            StepType.IMAGE_TEMPLATE_TAP -> {
                val snap = latestImageSnapshot
                snap != null &&
                    snap.targetStepOrder == step.stepOrder &&
                    snap.templateImagePath == step.templateImagePath &&
                    (now - snap.capturedAtMs) <= maxAgeMs
            }
            StepType.OCR_TEXT_TAP, StepType.MANUAL_CONFIRM_BRANCH -> {
                val snap = latestSnapshot
                snap != null &&
                    snap.targetStepOrder == step.stepOrder &&
                    (now - snap.capturedAtMs) <= maxAgeMs
            }
            else -> false
        }
    }

    /**
     * Speculatively captures and analyzes the next step against either an already-captured frame
     * (when overlapping active step execution) or a fresh background capture taken during wait/dwell windows.
     */
    fun scheduleNextStepPreload(
        nextStep: AutomationStepEntity?,
        delayWindowMs: Long = 0L,
        immediateFrame: Bitmap? = null,
        repository: AutomationRepository? = null,
        captureBackgroundFrame: suspend () -> Bitmap?
    ) {
        if (!PreloadTelemetryBus.state.value.isEnabled || nextStep == null || !nextStep.isEnabled) {
            return
        }
        if (repository != null) {
            activeRepository = repository
        }
        val nextType = runCatching { StepType.valueOf(nextStep.stepType) }.getOrNull() ?: return
        if (nextType != StepType.OCR_TEXT_TAP &&
            nextType != StepType.IMAGE_TEMPLATE_TAP &&
            nextType != StepType.MANUAL_CONFIRM_BRANCH
        ) {
            return
        }

        val immediateCopy = immediateFrame
            ?.takeIf { !it.isRecycled }
            ?.copy(Bitmap.Config.ARGB_8888, false)

        activePreloadStepOrder = nextStep.stepOrder
        speculativeJob?.cancel()
        speculativeJob = preloadScope.launch {
            try {
                PreloadTelemetryBus.markPreloading(nextStep.title)

                // If there is a settle/wait window (e.g. 300-1500ms), wait ~60% of it so any UI
                // transition settles, then capture a fresh background screenshot before next step runs.
                val frame = if (delayWindowMs >= 260L) {
                    immediateCopy?.recycle()
                    val settleDelay = (delayWindowMs * 0.62f).roundToInt().toLong().coerceIn(140L, 1200L)
                    delay(settleDelay)
                    if (!isActive) return@launch
                    captureBackgroundFrame()
                } else {
                    immediateCopy ?: captureBackgroundFrame()
                } ?: return@launch

                try {
                    if (!isActive) return@launch
                    analyzeFrameForStep(frame, nextStep, nextType, repository ?: activeRepository)
                } finally {
                    if (!frame.isRecycled) {
                        frame.recycle()
                    }
                }
            } catch (ce: CancellationException) {
                immediateCopy?.takeIf { !it.isRecycled }?.recycle()
                throw ce
            } catch (_: Exception) {
                immediateCopy?.takeIf { !it.isRecycled }?.recycle()
            } finally {
                if (activePreloadStepOrder == nextStep.stepOrder) {
                    activePreloadStepOrder = -1
                }
            }
        }
    }

    private suspend fun analyzeFrameForStep(
        frame: Bitmap,
        nextStep: AutomationStepEntity,
        nextType: StepType,
        repository: AutomationRepository?
    ) = withContext(Dispatchers.Default) {
        if (nextType == StepType.IMAGE_TEMPLATE_TAP) {
            val repo = repository ?: activeRepository ?: return@withContext
            if (nextStep.templateImagePath.isBlank()) return@withContext
            val tpl = loadTemplateCopy(nextStep.templateImagePath, repo) ?: return@withContext
            val sig = computeFrameSignature(frame)
            val startMs = System.currentTimeMillis()
            val roiFrame = RegionCropHelper.cropForRegion(
                source = frame,
                enabled = nextStep.roiEnabled,
                normLeft = nextStep.roiLeft,
                normTop = nextStep.roiTop,
                normRight = nextStep.roiRight,
                normBottom = nextStep.roiBottom
            )
            val rawMatch = try {
                templateMatcher.findTemplate(roiFrame.bitmap, tpl, nextStep.matchThreshold)
            } finally {
                tpl.recycle()
                if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
            }
            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val fullBox = RegionCropHelper.mapRectToFullScreen(
                rawMatch.boundingBox,
                roiFrame.offsetX,
                roiFrame.offsetY
            )
            val fullMatch = rawMatch.copy(
                boundingBox = fullBox,
                centerX = if (rawMatch.found) rawMatch.centerX + roiFrame.offsetX else fullBox.exactCenterX(),
                centerY = if (rawMatch.found) rawMatch.centerY + roiFrame.offsetY else fullBox.exactCenterY(),
                elapsedMs = elapsedMs
            )

            latestImageSnapshot = PreloadedImageSnapshot(
                targetStepOrder = nextStep.stepOrder,
                templateImagePath = nextStep.templateImagePath,
                matchThreshold = nextStep.matchThreshold,
                roiKey = buildRoiKey(nextStep),
                signature = sig,
                matchResult = fullMatch,
                matchElapsedMs = elapsedMs,
                capturedAtMs = System.currentTimeMillis()
            )
            val simPct = (fullMatch.similarity * 100f).roundToInt()
            val detail = if (fullMatch.found) {
                "图像模板特征命中 (${simPct}%)"
            } else {
                "图像模板已预计算 (最高 ${simPct}%)"
            }
            PreloadTelemetryBus.markSnapshotReady(
                nextStepTitle = nextStep.title,
                ocrMs = elapsedMs,
                elementCount = if (fullMatch.found) 1 else 0,
                detailLabel = detail
            )
            return@withContext
        }

        val sig = computeFrameSignature(frame)
        val startMs = System.currentTimeMillis()
        val roiFrame = RegionCropHelper.cropForRegion(
            source = frame,
            enabled = nextStep.roiEnabled,
            normLeft = nextStep.roiLeft,
            normTop = nextStep.roiTop,
            normRight = nextStep.roiRight,
            normBottom = nextStep.roiBottom
        )
        val rawElements = try {
            ocrEngine.recognizeText(roiFrame.bitmap)
        } finally {
            if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
        }
        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

        // Normalize bounding boxes to full-screen space so snapshot can serve ROI or full-screen checks cleanly
        val fullElements = if (roiFrame.offsetX == 0 && roiFrame.offsetY == 0) {
            rawElements
        } else {
            rawElements.map { el ->
                val mappedBox = RegionCropHelper.mapRectToFullScreen(
                    el.boundingBox,
                    roiFrame.offsetX,
                    roiFrame.offsetY
                )
                el.copy(
                    boundingBox = mappedBox,
                    centerX = mappedBox.exactCenterX(),
                    centerY = mappedBox.exactCenterY()
                )
            }
        }

        val matched = if (nextStep.targetText.isNotBlank()) {
            val mode = runCatching { TextMatchMode.valueOf(nextStep.textMatchMode) }
                .getOrDefault(TextMatchMode.CONTAINS)
            ocrEngine.findBestMatch(fullElements, nextStep.targetText, mode)
        } else {
            null
        }

        latestSnapshot = PreloadedScreenSnapshot(
            targetStepOrder = nextStep.stepOrder,
            roiKey = buildRoiKey(nextStep),
            isFullScreen = !nextStep.roiEnabled,
            signature = sig,
            elements = fullElements,
            preMatchedElement = matched,
            ocrElapsedMs = elapsedMs,
            capturedAtMs = System.currentTimeMillis()
        )
        PreloadTelemetryBus.markSnapshotReady(
            nextStepTitle = nextStep.title,
            ocrMs = elapsedMs,
            elementCount = fullElements.count { it.isLine }
        )
    }

    /**
     * Checks if the background preloaded OCR snapshot matches the current live frame.
     * Consumes the snapshot once on hit or invalidates if the screen visually changed.
     */
    suspend fun tryConsumePreloadedOcr(
        step: AutomationStepEntity,
        currentFrame: Bitmap,
        maxAgeMs: Long = 1600L
    ): PreloadedOcrHit? = withContext(Dispatchers.Default) {
        if (!PreloadTelemetryBus.state.value.isEnabled) return@withContext null
        val snap = latestSnapshot ?: return@withContext null
        val age = System.currentTimeMillis() - snap.capturedAtMs
        if (age > maxAgeMs) {
            latestSnapshot = null
            PreloadTelemetryBus.recordMiss(step.title, "预加载快照已过期(${age}ms)")
            return@withContext null
        }

        val stepRoiKey = buildRoiKey(step)
        if (!snap.isFullScreen && snap.roiKey != stepRoiKey) {
            return@withContext null
        }

        val currentSig = computeFrameSignature(currentFrame)
        val sim = computeSignatureSimilarity(snap.signature, currentSig)
        if (sim < 0.975f) {
            latestSnapshot = null
            PreloadTelemetryBus.recordMiss(
                step.title,
                "界面变化(${(sim * 100).roundToInt()}%)"
            )
            return@withContext null
        }

        // Filter to step ROI if step uses ROI and snapshot was full-screen
        val scopedElements = if (step.roiEnabled && snap.isFullScreen) {
            val leftPx = (step.roiLeft * currentFrame.width).roundToInt()
            val topPx = (step.roiTop * currentFrame.height).roundToInt()
            val rightPx = (step.roiRight * currentFrame.width).roundToInt()
            val bottomPx = (step.roiBottom * currentFrame.height).roundToInt()
            val roiRect = Rect(leftPx, topPx, rightPx, bottomPx)
            snap.elements.filter { roiRect.contains(it.centerX.roundToInt(), it.centerY.roundToInt()) }
        } else {
            snap.elements
        }

        val mode = runCatching { TextMatchMode.valueOf(step.textMatchMode) }
            .getOrDefault(TextMatchMode.CONTAINS)
        val bestMatch = if (snap.targetStepOrder == step.stepOrder && snap.roiKey == stepRoiKey) {
            snap.preMatchedElement
        } else {
            ocrEngine.findBestMatch(scopedElements, step.targetText, mode)
        }

        if (bestMatch == null) {
            // Target text not present in preloaded snapshot (page might still be loading target text);
            // keep allowing live polling without clearing on partial render.
            return@withContext null
        }

        latestSnapshot = null
        PreloadTelemetryBus.recordHit(step.title, snap.ocrElapsedMs)
        PreloadedOcrHit(
            elements = scopedElements,
            preMatchedElement = bestMatch,
            savedMs = snap.ocrElapsedMs
        )
    }

    /**
     * Checks if the background preloaded image template match snapshot matches the current live frame.
     * Consumes the snapshot once on hit or invalidates if the screen visually changed.
     */
    suspend fun tryConsumePreloadedTemplateMatch(
        step: AutomationStepEntity,
        currentFrame: Bitmap,
        maxAgeMs: Long = 1600L
    ): PreloadedImageHit? = withContext(Dispatchers.Default) {
        if (!PreloadTelemetryBus.state.value.isEnabled) return@withContext null
        val snap = latestImageSnapshot ?: return@withContext null
        val age = System.currentTimeMillis() - snap.capturedAtMs
        if (age > maxAgeMs) {
            latestImageSnapshot = null
            PreloadTelemetryBus.recordMiss(step.title, "识图预加载快照已过期(${age}ms)", modeLabel = "识图")
            return@withContext null
        }

        val stepRoiKey = buildRoiKey(step)
        if (snap.templateImagePath != step.templateImagePath || snap.roiKey != stepRoiKey) {
            return@withContext null
        }

        val currentSig = computeFrameSignature(currentFrame)
        val sim = computeSignatureSimilarity(snap.signature, currentSig)
        if (sim < 0.975f) {
            latestImageSnapshot = null
            PreloadTelemetryBus.recordMiss(
                step.title,
                "界面变化(${(sim * 100).roundToInt()}%)",
                modeLabel = "识图"
            )
            return@withContext null
        }

        if (!snap.matchResult.found || snap.matchResult.similarity < step.matchThreshold) {
            return@withContext null
        }

        latestImageSnapshot = null
        PreloadTelemetryBus.recordHit(step.title, snap.matchElapsedMs, modeLabel = "图像模板")
        PreloadedImageHit(
            matchResult = snap.matchResult,
            savedMs = snap.matchElapsedMs
        )
    }

    /**
     * Also lets subsequent steps on an unchanged frame share OCR results from the just-completed scan.
     */
    fun publishCompletedFullScreenOcr(
        frame: Bitmap,
        step: AutomationStepEntity,
        elements: List<RecognizedTextElement>,
        elapsedMs: Long
    ) {
        if (!PreloadTelemetryBus.state.value.isEnabled || frame.isRecycled) return
        val sig = computeFrameSignature(frame)
        latestSnapshot = PreloadedScreenSnapshot(
            targetStepOrder = step.stepOrder,
            roiKey = buildRoiKey(step),
            isFullScreen = !step.roiEnabled,
            signature = sig,
            elements = elements,
            preMatchedElement = null,
            ocrElapsedMs = elapsedMs.coerceAtLeast(1L),
            capturedAtMs = System.currentTimeMillis()
        )
    }

    fun clearSession(resetTelemetry: Boolean = false) {
        warmUpJob?.cancel()
        speculativeJob?.cancel()
        warmUpJob = null
        speculativeJob = null
        activePreloadStepOrder = -1
        latestSnapshot = null
        latestImageSnapshot = null
        activeRepository = null
        templateCache.values.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        templateCache.clear()
        compiledRegexCache.clear()
        if (resetTelemetry) {
            PreloadTelemetryBus.markPrepared(0, "")
        }
    }

    private fun buildRoiKey(step: AutomationStepEntity): String {
        return if (!step.roiEnabled) {
            "FULL"
        } else {
            "${(step.roiLeft * 1000).roundToInt()}_${(step.roiTop * 1000).roundToInt()}_${(step.roiRight * 1000).roundToInt()}_${(step.roiBottom * 1000).roundToInt()}"
        }
    }

    private fun computeFrameSignature(bitmap: Bitmap): FrameSignature {
        val grid = 16
        val samples = IntArray(grid * grid)
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        var idx = 0
        for (gy in 0 until grid) {
            val py = ((gy + 0.5f) / grid * h).toInt().coerceIn(0, h - 1)
            for (gx in 0 until grid) {
                val px = ((gx + 0.5f) / grid * w).toInt().coerceIn(0, w - 1)
                samples[idx++] = bitmap.getPixel(px, py)
            }
        }
        return FrameSignature(width = w, height = h, samples = samples)
    }

    private fun computeSignatureSimilarity(a: FrameSignature, b: FrameSignature): Float {
        if (a.width != b.width || a.height != b.height || a.samples.size != b.samples.size) {
            return 0f
        }
        var totalDiff = 0L
        for (i in a.samples.indices) {
            val pa = a.samples[i]
            val pb = b.samples[i]
            val dr = abs(Color.red(pa) - Color.red(pb))
            val dg = abs(Color.green(pa) - Color.green(pb))
            val db = abs(Color.blue(pa) - Color.blue(pb))
            totalDiff += (dr + dg + db)
        }
        val maxDiff = a.samples.size.toLong() * 3L * 255L
        return (1f - totalDiff.toFloat() / maxDiff.toFloat()).coerceIn(0f, 1f)
    }
}
