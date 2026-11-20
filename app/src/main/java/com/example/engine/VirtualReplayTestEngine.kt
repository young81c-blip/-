package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.example.data.AutomationRepository
import com.example.data.AutomationStepEntity
import com.example.data.BranchConditionType
import com.example.data.StepType
import com.example.data.TargetSceneType
import com.example.data.TaskWithSteps
import com.example.data.TextMatchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class MediaSequenceSourceType(val label: String) {
    NONE("未加载媒体"),
    VIDEO_RECORDING("模拟录屏视频"),
    IMAGE_SEQUENCE("截图序列"),
    BUILTIN_SAMPLE("内置示例序列")
}

enum class ReplayVerificationMode(val label: String, val description: String) {
    SEQUENTIAL_FLOW("顺序流程回放", "按步骤顺序沿录屏/截图时间轴依次推进，验证整条链路匹配与流转"),
    FRAME_BY_FRAME_MATRIX("单帧全步骤核验", "将当前选定帧与所有启用步骤逐一比对，快速排查漏检与误匹配")
}

data class VirtualTestFrame(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val label: String,
    val timestampMs: Long,
    val bitmap: Bitmap
)

data class VirtualStepMatchResult(
    val stepId: Long,
    val stepOrder: Int,
    val stepTitle: String,
    val stepType: StepType,
    val isEnabled: Boolean,
    val isSkippedWait: Boolean = false,
    val matched: Boolean,
    val matchedFrameIndex: Int?,
    val matchedFrameLabel: String,
    val matchedTimestampMs: Long?,
    val detectedRect: Rect?,
    val virtualTapPoint: Offset?,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val confidenceScore: Float?,
    val configuredThreshold: Float?,
    val elapsedMs: Long,
    val roiEnabled: Boolean,
    val roiLeft: Float,
    val roiTop: Float,
    val roiRight: Float,
    val roiBottom: Float,
    val diagnosticMessage: String,
    val suggestion: String = ""
)

data class VirtualTestReport(
    val taskId: Long,
    val taskName: String,
    val mode: ReplayVerificationMode,
    val totalFrames: Int,
    val verifiableStepCount: Int,
    val matchedStepCount: Int,
    val accuracyRate: Float,
    val avgMatchMs: Long,
    val results: List<VirtualStepMatchResult>,
    val executedAt: Long = System.currentTimeMillis()
)

data class VirtualSandboxUiState(
    val sourceType: MediaSequenceSourceType = MediaSequenceSourceType.NONE,
    val sourceTitle: String = "尚未导入测试媒体",
    val frames: List<VirtualTestFrame> = emptyList(),
    val selectedFrameIndex: Int = 0,
    val videoSampleIntervalMs: Long = 1000L,
    val verificationMode: ReplayVerificationMode = ReplayVerificationMode.SEQUENTIAL_FLOW,
    val isLoadingMedia: Boolean = false,
    val isVerifying: Boolean = false,
    val verifyProgressCurrent: Int = 0,
    val verifyProgressTotal: Int = 0,
    val verifyProgressLabel: String = "",
    val selectedResultStepOrder: Int? = null,
    val report: VirtualTestReport? = null,
    val statusBanner: String = "上传模拟录屏或截图序列，在不触发真实点击与任务记录的前提下验证脚本匹配准确率"
) {
    val activeFrame: VirtualTestFrame?
        get() = frames.getOrNull(selectedFrameIndex)
}

object VirtualReplayTestEngine {

    private const val MAX_FRAME_DIMENSION = 1080
    private const val MAX_VIDEO_FRAMES = 14
    private const val MAX_IMAGE_SEQUENCE_FRAMES = 20

    suspend fun extractFramesFromVideo(
        context: Context,
        uri: Uri,
        intervalMs: Long = 1000L,
        maxFrames: Int = MAX_VIDEO_FRAMES
    ): Result<List<VirtualTestFrame>> = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L) ?: 0L

                val safeInterval = intervalMs.coerceIn(300L, 5000L)
                val timestamps = if (durationMs <= 200L) {
                    listOf(0L)
                } else {
                    val rawCount = (durationMs / safeInterval).toInt() + 1
                    if (rawCount <= maxFrames) {
                        (0 until rawCount).map { idx ->
                            (idx * safeInterval).coerceAtMost(durationMs)
                        }
                    } else {
                        val step = durationMs.toFloat() / (maxFrames - 1).coerceAtLeast(1)
                        (0 until maxFrames).map { idx ->
                            (idx * step).roundToInt().toLong().coerceAtMost(durationMs)
                        }
                    }
                }.distinct()

                val option = if (durationMs <= 20_000L) {
                    MediaMetadataRetriever.OPTION_CLOSEST
                } else {
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                }

                val extracted = mutableListOf<VirtualTestFrame>()
                for ((idx, tsMs) in timestamps.withIndex()) {
                    var frameBmp = retriever.getFrameAtTime(tsMs * 1000L, option)
                    if (frameBmp == null && option != MediaMetadataRetriever.OPTION_CLOSEST_SYNC) {
                        frameBmp = retriever.getFrameAtTime(tsMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    if (frameBmp != null) {
                        val normalized = normalizeBitmapForAnalysis(frameBmp, MAX_FRAME_DIMENSION)
                        if (normalized !== frameBmp) frameBmp.recycle()
                        extracted.add(
                            VirtualTestFrame(
                                index = extracted.size,
                                label = String.format(Locale.US, "录屏 #${idx + 1} (%.1fs)", tsMs / 1000f),
                                timestampMs = tsMs,
                                bitmap = normalized
                            )
                        )
                    }
                }
                if (extracted.isEmpty()) {
                    throw IllegalStateException("无法从所选录屏中解析出视频帧，请尝试常见 MP4/H.264 格式")
                }
                extracted
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    suspend fun loadFramesFromImageUris(
        context: Context,
        uris: List<Uri>,
        existingFrames: List<VirtualTestFrame> = emptyList()
    ): Result<List<VirtualTestFrame>> = withContext(Dispatchers.IO) {
        runCatching {
            val remainingCapacity = (MAX_IMAGE_SEQUENCE_FRAMES - existingFrames.size).coerceAtLeast(0)
            if (remainingCapacity == 0) {
                throw IllegalStateException("截图序列最多支持 $MAX_IMAGE_SEQUENCE_FRAMES 帧，请先清理部分截图")
            }
            val added = mutableListOf<VirtualTestFrame>()
            for (uri in uris.take(remainingCapacity)) {
                val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: continue
                val normalized = normalizeBitmapForAnalysis(decoded, MAX_FRAME_DIMENSION)
                if (normalized !== decoded) decoded.recycle()
                val orderNum = existingFrames.size + added.size + 1
                added.add(
                    VirtualTestFrame(
                        index = orderNum - 1,
                        label = "截图 #$orderNum",
                        timestampMs = (orderNum - 1) * 1000L,
                        bitmap = normalized
                    )
                )
            }
            if (added.isEmpty()) {
                throw IllegalStateException("未能解码选中的截图文件")
            }
            (existingFrames + added).mapIndexed { i, frame ->
                frame.copy(index = i)
            }
        }
    }

    suspend fun buildSampleTestSequence(
        repository: AutomationRepository,
        sceneHint: TargetSceneType
    ): List<VirtualTestFrame> = withContext(Dispatchers.Default) {
        when (sceneHint) {
            TargetSceneType.REWARD_CENTER -> listOf(
                VirtualTestFrame(
                    index = 0,
                    label = "示例帧 #1 · 徽章待激活",
                    timestampMs = 0L,
                    bitmap = renderSampleRewardFrame(repository, activated = false)
                ),
                VirtualTestFrame(
                    index = 1,
                    label = "示例帧 #2 · 徽章已解锁",
                    timestampMs = 1000L,
                    bitmap = renderSampleRewardFrame(repository, activated = true)
                )
            )

            TargetSceneType.FLASH_COUPON -> listOf(
                VirtualTestFrame(
                    index = 0,
                    label = "示例帧 #1 · 闪购图标页",
                    timestampMs = 0L,
                    bitmap = renderSampleCouponFrame(repository, claimedCount = 0)
                ),
                VirtualTestFrame(
                    index = 1,
                    label = "示例帧 #2 · 抢券完成页",
                    timestampMs = 1000L,
                    bitmap = renderSampleCouponFrame(repository, claimedCount = 2)
                )
            )

            TargetSceneType.DAILY_CHECKIN,
            TargetSceneType.CUSTOM_IMAGE_CANVAS -> listOf(
                VirtualTestFrame(
                    index = 0,
                    label = "示例帧 #1 · 营销弹窗遮挡",
                    timestampMs = 0L,
                    bitmap = renderSampleCheckinFrame(showAd = true, checkedIn = false, points = 0)
                ),
                VirtualTestFrame(
                    index = 1,
                    label = "示例帧 #2 · 每日签到主界面",
                    timestampMs = 1000L,
                    bitmap = renderSampleCheckinFrame(showAd = false, checkedIn = false, points = 0)
                ),
                VirtualTestFrame(
                    index = 2,
                    label = "示例帧 #3 · 已签到领积分状态",
                    timestampMs = 2000L,
                    bitmap = renderSampleCheckinFrame(showAd = false, checkedIn = true, points = 1)
                )
            )
        }
    }

    suspend fun verifyScriptOnFrames(
        taskWithSteps: TaskWithSteps,
        frames: List<VirtualTestFrame>,
        mode: ReplayVerificationMode,
        focusedFrameIndex: Int,
        repository: AutomationRepository,
        ocrEngine: OcrRecognitionEngine,
        templateMatcher: ImageTemplateMatcher,
        onProgress: suspend (current: Int, total: Int, label: String) -> Unit
    ): VirtualTestReport {
        val steps = taskWithSteps.sortedSteps
        val total = steps.size.coerceAtLeast(1)
        val ocrCache = mutableMapOf<String, Pair<List<RecognizedTextElement>, Long>>()
        val templateCache = mutableMapOf<String, Bitmap>()
        val results = mutableListOf<VirtualStepMatchResult>()

        var sequentialFrameCursor = 0
        val safeFocusIdx = focusedFrameIndex.coerceIn(0, (frames.size - 1).coerceAtLeast(0))

        try {
            for ((idx, step) in steps.withIndex()) {
                onProgress(idx + 1, total, "正在回放验证步骤 ${idx + 1}/$total：${step.title}")
                val type = runCatching { StepType.valueOf(step.stepType) }.getOrDefault(StepType.WAIT_DELAY)

                if (!step.isEnabled) {
                    results.add(
                        VirtualStepMatchResult(
                            stepId = step.id,
                            stepOrder = step.stepOrder,
                            stepTitle = step.title,
                            stepType = type,
                            isEnabled = false,
                            isSkippedWait = true,
                            matched = false,
                            matchedFrameIndex = null,
                            matchedFrameLabel = "已禁用跳过",
                            matchedTimestampMs = null,
                            detectedRect = null,
                            virtualTapPoint = null,
                            viewportWidthPx = 0,
                            viewportHeightPx = 0,
                            confidenceScore = null,
                            configuredThreshold = null,
                            elapsedMs = 0L,
                            roiEnabled = step.roiEnabled,
                            roiLeft = step.roiLeft,
                            roiTop = step.roiTop,
                            roiRight = step.roiRight,
                            roiBottom = step.roiBottom,
                            diagnosticMessage = "该步骤或所属节点容器当前处于禁用状态，回放已跳过"
                        )
                    )
                    continue
                }

                if (type == StepType.WAIT_DELAY) {
                    results.add(
                        VirtualStepMatchResult(
                            stepId = step.id,
                            stepOrder = step.stepOrder,
                            stepTitle = step.title,
                            stepType = type,
                            isEnabled = true,
                            isSkippedWait = true,
                            matched = true,
                            matchedFrameIndex = if (mode == ReplayVerificationMode.FRAME_BY_FRAME_MATRIX) safeFocusIdx else sequentialFrameCursor,
                            matchedFrameLabel = frames.getOrNull(
                                if (mode == ReplayVerificationMode.FRAME_BY_FRAME_MATRIX) safeFocusIdx else sequentialFrameCursor
                            )?.label ?: "-",
                            matchedTimestampMs = null,
                            detectedRect = null,
                            virtualTapPoint = null,
                            viewportWidthPx = 0,
                            viewportHeightPx = 0,
                            confidenceScore = null,
                            configuredThreshold = null,
                            elapsedMs = 0L,
                            roiEnabled = false,
                            roiLeft = 0f,
                            roiTop = 0f,
                            roiRight = 1f,
                            roiBottom = 1f,
                            diagnosticMessage = "纯延时等待节点（${step.delayAfterMs}ms），离线验证无需等待"
                        )
                    )
                    continue
                }

                val candidateRange = when (mode) {
                    ReplayVerificationMode.SEQUENTIAL_FLOW -> sequentialFrameCursor until frames.size
                    ReplayVerificationMode.FRAME_BY_FRAME_MATRIX -> safeFocusIdx..safeFocusIdx
                }

                val evalOutcome = evaluateStepAcrossCandidateFrames(
                    step = step,
                    stepType = type,
                    frames = frames,
                    candidateIndices = candidateRange,
                    repository = repository,
                    ocrEngine = ocrEngine,
                    templateMatcher = templateMatcher,
                    ocrCache = ocrCache,
                    templateCache = templateCache
                )

                if (mode == ReplayVerificationMode.SEQUENTIAL_FLOW && evalOutcome.matched && evalOutcome.matchedFrameIndex != null) {
                    sequentialFrameCursor = evalOutcome.matchedFrameIndex
                }
                results.add(evalOutcome)
            }
        } finally {
            templateCache.values.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
            templateCache.clear()
        }

        val verifiable = results.filter { it.isEnabled && !it.isSkippedWait }
        val matchedCount = verifiable.count { it.matched }
        val accuracy = if (verifiable.isEmpty()) 1f else matchedCount.toFloat() / verifiable.size.toFloat()
        val avgMs = if (verifiable.isEmpty()) 0L else verifiable.map { it.elapsedMs }.average().roundToInt().toLong()

        return VirtualTestReport(
            taskId = taskWithSteps.task.id,
            taskName = taskWithSteps.task.name,
            mode = mode,
            totalFrames = frames.size,
            verifiableStepCount = verifiable.size,
            matchedStepCount = matchedCount,
            accuracyRate = accuracy,
            avgMatchMs = avgMs,
            results = results
        )
    }

    private suspend fun evaluateStepAcrossCandidateFrames(
        step: AutomationStepEntity,
        stepType: StepType,
        frames: List<VirtualTestFrame>,
        candidateIndices: IntRange,
        repository: AutomationRepository,
        ocrEngine: OcrRecognitionEngine,
        templateMatcher: ImageTemplateMatcher,
        ocrCache: MutableMap<String, Pair<List<RecognizedTextElement>, Long>>,
        templateCache: MutableMap<String, Bitmap>
    ): VirtualStepMatchResult {
        var totalElapsedMs = 0L
        var bestImageSimilarity = -1f
        var bestImageFrame: VirtualTestFrame? = null
        var bestImageRect: Rect? = null
        var lastOcrPreview = ""
        var lastOcrCount = 0

        for (frameIdx in candidateIndices) {
            val frame = frames.getOrNull(frameIdx) ?: continue
            val fw = frame.bitmap.width
            val fh = frame.bitmap.height

            when (stepType) {
                StepType.COORDINATE_TAP -> {
                    val tx = (step.normalizedX * fw).coerceIn(1f, (fw - 1).toFloat())
                    val ty = (step.normalizedY * fh).coerceIn(1f, (fh - 1).toFloat())
                    val box = Rect(
                        (tx - 34).toInt().coerceAtLeast(0),
                        (ty - 34).toInt().coerceAtLeast(0),
                        (tx + 34).toInt().coerceAtMost(fw),
                        (ty + 34).toInt().coerceAtMost(fh)
                    )
                    return VirtualStepMatchResult(
                        stepId = step.id,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        stepType = stepType,
                        isEnabled = true,
                        matched = true,
                        matchedFrameIndex = frameIdx,
                        matchedFrameLabel = frame.label,
                        matchedTimestampMs = frame.timestampMs,
                        detectedRect = box,
                        virtualTapPoint = Offset(tx, ty),
                        viewportWidthPx = fw,
                        viewportHeightPx = fh,
                        confidenceScore = 1f,
                        configuredThreshold = null,
                        elapsedMs = 1L,
                        roiEnabled = false,
                        roiLeft = 0f,
                        roiTop = 0f,
                        roiRight = 1f,
                        roiBottom = 1f,
                        diagnosticMessage = String.format(
                            Locale.US,
                            "固定坐标落点 (${tx.toInt()}, ${ty.toInt()}) · 归一化 (%.2f, %.2f)",
                            step.normalizedX,
                            step.normalizedY
                        )
                    )
                }

                StepType.OCR_TEXT_TAP -> {
                    val (elements, elapsed) = recognizeWithCache(frame, step, ocrEngine, ocrCache)
                    totalElapsedMs += elapsed
                    val lines = elements.filter { it.isLine }
                    lastOcrCount = lines.size
                    if (lines.isNotEmpty()) {
                        lastOcrPreview = lines.take(3).joinToString("、") { it.text }
                    }
                    val mode = runCatching { TextMatchMode.valueOf(step.textMatchMode) }
                        .getOrDefault(TextMatchMode.CONTAINS)
                    val match = ocrEngine.findBestMatch(elements, step.targetText, mode)
                    if (match != null) {
                        val isExact = match.text.replace(" ", "")
                            .equals(step.targetText.trim().replace(" ", ""), ignoreCase = true)
                        val score = if (isExact) 1.0f else 0.90f
                        return VirtualStepMatchResult(
                            stepId = step.id,
                            stepOrder = step.stepOrder,
                            stepTitle = step.title,
                            stepType = stepType,
                            isEnabled = true,
                            matched = true,
                            matchedFrameIndex = frameIdx,
                            matchedFrameLabel = frame.label,
                            matchedTimestampMs = frame.timestampMs,
                            detectedRect = match.boundingBox,
                            virtualTapPoint = Offset(match.centerX, match.centerY),
                            viewportWidthPx = fw,
                            viewportHeightPx = fh,
                            confidenceScore = score,
                            configuredThreshold = null,
                            elapsedMs = totalElapsedMs.coerceAtLeast(1L),
                            roiEnabled = step.roiEnabled,
                            roiLeft = step.roiLeft,
                            roiTop = step.roiTop,
                            roiRight = step.roiRight,
                            roiBottom = step.roiBottom,
                            diagnosticMessage = "在 ${frame.label} 命中文字「${match.text}」（${mode.label} · 扫描 ${lines.size} 行）"
                        )
                    }
                }

                StepType.IMAGE_TEMPLATE_TAP -> {
                    if (step.templateImagePath.isBlank()) {
                        return VirtualStepMatchResult(
                            stepId = step.id,
                            stepOrder = step.stepOrder,
                            stepTitle = step.title,
                            stepType = stepType,
                            isEnabled = true,
                            matched = false,
                            matchedFrameIndex = null,
                            matchedFrameLabel = "模板路径为空",
                            matchedTimestampMs = null,
                            detectedRect = null,
                            virtualTapPoint = null,
                            viewportWidthPx = fw,
                            viewportHeightPx = fh,
                            confidenceScore = 0f,
                            configuredThreshold = step.matchThreshold,
                            elapsedMs = 0L,
                            roiEnabled = step.roiEnabled,
                            roiLeft = step.roiLeft,
                            roiTop = step.roiTop,
                            roiRight = step.roiRight,
                            roiBottom = step.roiBottom,
                            diagnosticMessage = "步骤未绑定图像模板文件",
                            suggestion = "请先在任务编辑器或“识别拾取”页面截取并保存目标图像模板"
                        )
                    }
                    val tpl = templateCache.getOrPut(step.templateImagePath) {
                        repository.loadTemplateBitmap(step.templateImagePath)
                            ?: return VirtualStepMatchResult(
                                stepId = step.id,
                                stepOrder = step.stepOrder,
                                stepTitle = step.title,
                                stepType = stepType,
                                isEnabled = true,
                                matched = false,
                                matchedFrameIndex = null,
                                matchedFrameLabel = "模板读取失败",
                                matchedTimestampMs = null,
                                detectedRect = null,
                                virtualTapPoint = null,
                                viewportWidthPx = fw,
                                viewportHeightPx = fh,
                                confidenceScore = 0f,
                                configuredThreshold = step.matchThreshold,
                                elapsedMs = 0L,
                                roiEnabled = step.roiEnabled,
                                roiLeft = step.roiLeft,
                                roiTop = step.roiTop,
                                roiRight = step.roiRight,
                                roiBottom = step.roiBottom,
                                diagnosticMessage = "找不到本地模板文件：${step.templateImagePath}",
                                suggestion = "请重新裁剪或选择有效的模板图像"
                            )
                    }

                    val roiFrame = RegionCropHelper.cropForRegion(
                        source = frame.bitmap,
                        enabled = step.roiEnabled,
                        normLeft = step.roiLeft,
                        normTop = step.roiTop,
                        normRight = step.roiRight,
                        normBottom = step.roiBottom
                    )
                    val match = try {
                        templateMatcher.findTemplate(roiFrame.bitmap, tpl, step.matchThreshold)
                    } finally {
                        if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
                    }
                    totalElapsedMs += match.elapsedMs.coerceAtLeast(1L)
                    val mappedBox = RegionCropHelper.mapRectToFullScreen(
                        match.boundingBox,
                        roiFrame.offsetX,
                        roiFrame.offsetY
                    )
                    if (match.similarity > bestImageSimilarity) {
                        bestImageSimilarity = match.similarity
                        bestImageFrame = frame
                        bestImageRect = mappedBox
                    }
                    if (match.found) {
                        val pct = (match.similarity * 100f).roundToInt()
                        val thPct = (step.matchThreshold * 100f).roundToInt()
                        return VirtualStepMatchResult(
                            stepId = step.id,
                            stepOrder = step.stepOrder,
                            stepTitle = step.title,
                            stepType = stepType,
                            isEnabled = true,
                            matched = true,
                            matchedFrameIndex = frameIdx,
                            matchedFrameLabel = frame.label,
                            matchedTimestampMs = frame.timestampMs,
                            detectedRect = mappedBox,
                            virtualTapPoint = Offset(
                                match.centerX + roiFrame.offsetX,
                                match.centerY + roiFrame.offsetY
                            ),
                            viewportWidthPx = fw,
                            viewportHeightPx = fh,
                            confidenceScore = match.similarity,
                            configuredThreshold = step.matchThreshold,
                            elapsedMs = totalElapsedMs.coerceAtLeast(1L),
                            roiEnabled = step.roiEnabled,
                            roiLeft = step.roiLeft,
                            roiTop = step.roiTop,
                            roiRight = step.roiRight,
                            roiBottom = step.roiBottom,
                            diagnosticMessage = "在 ${frame.label} 图像匹配成功：相似度 $pct% ≥ 阈值 $thPct%"
                        )
                    }
                }

                StepType.MANUAL_CONFIRM_BRANCH -> {
                    val branchType = runCatching { BranchConditionType.valueOf(step.branchConditionType) }
                        .getOrDefault(BranchConditionType.ALWAYS_PAUSE)
                    if (branchType == BranchConditionType.ALWAYS_PAUSE || step.targetText.isBlank()) {
                        return VirtualStepMatchResult(
                            stepId = step.id,
                            stepOrder = step.stepOrder,
                            stepTitle = step.title,
                            stepType = stepType,
                            isEnabled = true,
                            matched = true,
                            matchedFrameIndex = frameIdx,
                            matchedFrameLabel = frame.label,
                            matchedTimestampMs = frame.timestampMs,
                            detectedRect = null,
                            virtualTapPoint = null,
                            viewportWidthPx = fw,
                            viewportHeightPx = fh,
                            confidenceScore = 1f,
                            configuredThreshold = null,
                            elapsedMs = 1L,
                            roiEnabled = step.roiEnabled,
                            roiLeft = step.roiLeft,
                            roiTop = step.roiTop,
                            roiRight = step.roiRight,
                            roiBottom = step.roiBottom,
                            diagnosticMessage = "分支策略为直接暂停确认，在 ${frame.label} 处将触发人工确认弹窗（模拟放行）"
                        )
                    }

                    val (elements, elapsed) = recognizeWithCache(frame, step, ocrEngine, ocrCache)
                    totalElapsedMs += elapsed
                    val mode = runCatching { TextMatchMode.valueOf(step.textMatchMode) }
                        .getOrDefault(TextMatchMode.CONTAINS)
                    val found = ocrEngine.findBestMatch(elements, step.targetText, mode)
                    val wouldPause = when (branchType) {
                        BranchConditionType.WHEN_TEXT_APPEARS -> found != null
                        BranchConditionType.WHEN_TEXT_MISSING -> found == null
                        BranchConditionType.ALWAYS_PAUSE -> true
                    }
                    return VirtualStepMatchResult(
                        stepId = step.id,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        stepType = stepType,
                        isEnabled = true,
                        matched = true,
                        matchedFrameIndex = frameIdx,
                        matchedFrameLabel = frame.label,
                        matchedTimestampMs = frame.timestampMs,
                        detectedRect = found?.boundingBox,
                        virtualTapPoint = found?.let { Offset(it.centerX, it.centerY) },
                        viewportWidthPx = fw,
                        viewportHeightPx = fh,
                        confidenceScore = if (found != null) 0.95f else 0.85f,
                        configuredThreshold = null,
                        elapsedMs = totalElapsedMs.coerceAtLeast(1L),
                        roiEnabled = step.roiEnabled,
                        roiLeft = step.roiLeft,
                        roiTop = step.roiTop,
                        roiRight = step.roiRight,
                        roiBottom = step.roiBottom,
                        diagnosticMessage = if (wouldPause) {
                            "分支命中：在 ${frame.label} 满足「${branchType.label}」条件，将挂起并通知人工处理"
                        } else {
                            "分支自动跳过：在 ${frame.label} 未触发「${branchType.label}」，流程顺畅继续"
                        }
                    )
                }

                StepType.WAIT_DELAY -> Unit
            }
        }

        // Missed across all candidate frames
        val fallbackFrame = bestImageFrame ?: frames.getOrNull(candidateIndices.first.coerceIn(0, (frames.size - 1).coerceAtLeast(0)))
        val fw = fallbackFrame?.bitmap?.width ?: 0
        val fh = fallbackFrame?.bitmap?.height ?: 0
        val diagnostic: String
        val suggestion: String
        when (stepType) {
            StepType.OCR_TEXT_TAP -> {
                val previewSuffix = if (lastOcrPreview.isNotBlank()) "（最近帧识别到：$lastOcrPreview 等 $lastOcrCount 处文字）" else "（当前扫描范围内未提取到文字）"
                diagnostic = "待测媒体序列中未匹配到「${step.targetText}」$previewSuffix"
                suggestion = buildString {
                    if (step.roiEnabled) append("已启用 ROI 区域裁剪，请核对目标文字是否落在虚线区域内；")
                    if (step.textMatchMode == TextMatchMode.EXACT.name) append("当前为精确匹配，建议改用“包含匹配”容错前后空格或符号；")
                    if (isEmpty()) append("建议检查目标文案或补充覆盖该界面的截图/录屏片段")
                }
            }

            StepType.IMAGE_TEMPLATE_TAP -> {
                val bestPct = if (bestImageSimilarity >= 0f) (bestImageSimilarity * 100f).roundToInt() else 0
                val thPct = (step.matchThreshold * 100f).roundToInt()
                diagnostic = "未命中图像模板：最高相似度 ${bestPct}%（位于 ${bestImageFrame?.label ?: "未知帧"}）低于设定阈值 ${thPct}%"
                suggestion = when {
                    bestImageSimilarity >= (step.matchThreshold - 0.14f) -> {
                        val recommendedPct = ((bestImageSimilarity - 0.04f).coerceIn(0.45f, 0.90f) * 100f).roundToInt()
                        "最高相似度接近阈值，可尝试将匹配阈值下调至 $recommendedPct% 或关闭过严的 ROI 限制"
                    }
                    step.roiEnabled -> "最高相似度仅 $bestPct%，请确认模板是否处于当前 ROI 搜索框内或目标尺寸变化较大"
                    else -> "建议在“识别拾取”页针对当前真实截图重新裁剪更高对比度的图标模板"
                }
            }

            else -> {
                diagnostic = "当前测试帧序列未满足步骤匹配条件"
                suggestion = "请检查脚本步骤配置与测试媒体是否对应"
            }
        }

        return VirtualStepMatchResult(
            stepId = step.id,
            stepOrder = step.stepOrder,
            stepTitle = step.title,
            stepType = stepType,
            isEnabled = true,
            matched = false,
            matchedFrameIndex = bestImageFrame?.index ?: candidateIndices.firstOrNull(),
            matchedFrameLabel = bestImageFrame?.label ?: fallbackFrame?.label ?: "-",
            matchedTimestampMs = bestImageFrame?.timestampMs ?: fallbackFrame?.timestampMs,
            detectedRect = if (bestImageRect != null && !bestImageRect.isEmpty) bestImageRect else null,
            virtualTapPoint = null,
            viewportWidthPx = fw,
            viewportHeightPx = fh,
            confidenceScore = if (bestImageSimilarity >= 0f) bestImageSimilarity else 0f,
            configuredThreshold = if (stepType == StepType.IMAGE_TEMPLATE_TAP) step.matchThreshold else null,
            elapsedMs = totalElapsedMs.coerceAtLeast(1L),
            roiEnabled = step.roiEnabled,
            roiLeft = step.roiLeft,
            roiTop = step.roiTop,
            roiRight = step.roiRight,
            roiBottom = step.roiBottom,
            diagnosticMessage = diagnostic,
            suggestion = suggestion
        )
    }

    private suspend fun recognizeWithCache(
        frame: VirtualTestFrame,
        step: AutomationStepEntity,
        ocrEngine: OcrRecognitionEngine,
        cache: MutableMap<String, Pair<List<RecognizedTextElement>, Long>>
    ): Pair<List<RecognizedTextElement>, Long> {
        val roiKey = if (step.roiEnabled) {
            String.format(
                Locale.US,
                "%.2f_%.2f_%.2f_%.2f",
                step.roiLeft,
                step.roiTop,
                step.roiRight,
                step.roiBottom
            )
        } else {
            "FULL"
        }
        val key = "${frame.id}::$roiKey"
        cache[key]?.let { return it }

        val roiFrame = RegionCropHelper.cropForRegion(
            source = frame.bitmap,
            enabled = step.roiEnabled,
            normLeft = step.roiLeft,
            normTop = step.roiTop,
            normRight = step.roiRight,
            normBottom = step.roiBottom
        )
        val startMs = System.currentTimeMillis()
        val raw = try {
            ocrEngine.recognizeText(roiFrame.bitmap)
        } finally {
            if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
        }
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val mapped = if (roiFrame.offsetX == 0 && roiFrame.offsetY == 0) {
            raw
        } else {
            raw.map { el ->
                val fullBox = RegionCropHelper.mapRectToFullScreen(
                    el.boundingBox,
                    roiFrame.offsetX,
                    roiFrame.offsetY
                )
                el.copy(
                    boundingBox = fullBox,
                    centerX = fullBox.exactCenterX(),
                    centerY = fullBox.exactCenterY()
                )
            }
        }
        return (mapped to elapsed).also { cache[key] = it }
    }

    private fun normalizeBitmapForAnalysis(src: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        val scaled = if (maxSide > maxDimension) {
            val ratio = maxDimension.toFloat() / maxSide.toFloat()
            val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true)
        } else {
            src
        }
        return if (scaled.config != Bitmap.Config.ARGB_8888) {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (scaled !== src && scaled !== copy) scaled.recycle()
            copy ?: scaled
        } else {
            scaled
        }
    }

    private fun renderSampleCheckinFrame(
        showAd: Boolean,
        checkedIn: Boolean,
        points: Int
    ): Bitmap {
        val w = 720
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#F6F9FC"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            textSize = 46f
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#475569")
            textSize = 28f
        }
        canvas.drawText("每日签到中心", 48f, 120f, titlePaint)
        canvas.drawText(
            if (checkedIn) "今日已完成签到 · 积分 +${points * 50}" else "连续签到可领取双倍积分奖励",
            48f,
            175f,
            subPaint
        )

        val leftBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (checkedIn) "#10B981" else "#0099C6")
        }
        canvas.drawRoundRect(48f, 440f, 344f, 540f, 24f, 24f, leftBtnPaint)

        val rightBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#F59E0B")
        }
        canvas.drawRoundRect(376f, 440f, 672f, 540f, 24f, 24f, rightBtnPaint)

        val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 32f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(if (checkedIn) "已签到" else "立即签到", 196f, 502f, btnText)
        canvas.drawText("领取积分 ($points)", 524f, 502f, btnText)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EAF2FA")
        }
        canvas.drawRoundRect(48f, 820f, 672f, 940f, 24f, 24f, cardPaint)
        canvas.drawText("当前可用积分", 84f, 890f, subPaint)
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0099C6")
            textSize = 36f
            textAlign = Paint.Align.RIGHT
            isFakeBoldText = true
        }
        canvas.drawText("${120 + points * 50} 分", 636f, 892f, scorePaint)

        if (showAd) {
            canvas.drawColor(android.graphics.Color.parseColor("#990F172A"))
            val modalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
            }
            canvas.drawRoundRect(76f, 320f, 644f, 720f, 32f, 32f, modalPaint)
            val modalTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#0F172A")
                textSize = 38f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val modalDesc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#64748B")
                textSize = 26f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("限时活动提醒", 360f, 410f, modalTitle)
            canvas.drawText("模拟录屏弹窗拦截场景测试", 360f, 470f, modalDesc)

            val closeBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#EF4444")
            }
            canvas.drawRoundRect(180f, 550f, 540f, 646f, 24f, 24f, closeBtn)
            canvas.drawText("关闭广告", 360f, 610f, btnText)
        }
        return bmp
    }

    private fun renderSampleRewardFrame(
        repository: AutomationRepository,
        activated: Boolean
    ): Bitmap {
        val w = 720
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#F6F9FC"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            textSize = 44f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("徽章视觉识图领奖", 360f, 130f, titlePaint)

        val badgeCard = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (activated) "#D1FAE5" else "#FFFFFF")
        }
        canvas.drawRoundRect(180f, 310f, 540f, 640f, 32f, 32f, badgeCard)
        val badgeBmp = repository.loadTemplateBitmap("drawable:img_reward_emblem")
        if (badgeBmp != null) {
            val scaledBadge = Bitmap.createScaledBitmap(badgeBmp, 190, 190, true)
            canvas.drawBitmap(scaledBadge, (w - 190) / 2f, 355f, null)
            scaledBadge.recycle()
            badgeBmp.recycle()
        }
        val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1E293B")
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(if (activated) "徽章已激活" else "点击星标徽章", 360f, 600f, statePaint)

        val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (activated) "#10B981" else "#0099C6")
        }
        canvas.drawRoundRect(110f, 820f, 610f, 920f, 26f, 26f, btnPaint)
        val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("确认领取 (${if (activated) 1 else 0})", 360f, 882f, btnText)
        return bmp
    }

    private fun renderSampleCouponFrame(
        repository: AutomationRepository,
        claimedCount: Int
    ): Bitmap {
        val w = 720
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#F6F9FC"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            textSize = 44f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("闪购神券双模抢购", 360f, 130f, titlePaint)

        val iconCard = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(if (claimedCount > 0) "#FFEDD5" else "#FFFFFF")
        }
        canvas.drawRoundRect(84f, 360f, 300f, 576f, 28f, 28f, iconCard)
        val couponBmp = repository.loadTemplateBitmap("drawable:img_coupon_badge")
        if (couponBmp != null) {
            val scaled = Bitmap.createScaledBitmap(couponBmp, 172, 172, true)
            canvas.drawBitmap(scaled, 106f, 382f, null)
            scaled.recycle()
            couponBmp.recycle()
        }

        val infoTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            textSize = 36f
            isFakeBoldText = true
        }
        val infoSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 28f
        }
        canvas.drawText(if (claimedCount > 0) "神券已解锁" else "请先识别左侧图标", 340f, 445f, infoTitle)
        canvas.drawText("已抢到 $claimedCount 张", 340f, 505f, infoSub)

        val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EA580C")
        }
        canvas.drawRoundRect(90f, 820f, 630f, 920f, 26f, 26f, btnPaint)
        val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("立即抢券", 360f, 882f, btnText)
        return bmp
    }
}
