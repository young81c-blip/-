package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AutomationRepository
import com.example.data.AutomationStepEntity
import com.example.data.AutomationTaskEntity
import com.example.data.BranchConditionType
import com.example.data.ExecutionClickEventEntity
import com.example.data.ExecutionLogWithEvents
import com.example.data.ExportJsonPayload
import com.example.data.ImportConflictPolicy
import com.example.data.LoopMode
import com.example.data.StepType
import com.example.data.TargetSceneType
import com.example.data.TaskWithSteps
import com.example.data.TextMatchMode
import com.example.engine.DiagnosticOverlayBus
import com.example.engine.DiagnosticOverlayState
import com.example.engine.FloatingControlService
import com.example.engine.FloatingWindowBus
import com.example.engine.GeminiDiagnosticEngine
import com.example.engine.GeminiDiagnosticOutcome
import com.example.engine.ImageMatchResult
import com.example.engine.ImageTemplateMatcher
import com.example.engine.LoopControlEvaluator
import com.example.engine.ManualConfirmationBus
import com.example.engine.MediaSequenceSourceType
import com.example.engine.NotificationConfig
import com.example.engine.OcrRecognitionEngine
import com.example.engine.PendingConfirmationRequest
import com.example.engine.PreloadEngineState
import com.example.engine.PreloadTelemetryBus
import com.example.engine.RecognizedTextElement
import com.example.engine.RegionCropHelper
import com.example.engine.RecommendedAlgorithm
import com.example.engine.ReplayVerificationMode
import com.example.engine.RootAutomationService
import com.example.engine.RootExecutionBus
import com.example.engine.RootRunnerState
import com.example.engine.RootShellExecutor
import com.example.engine.RootStatus
import com.example.engine.SmartDetectionResult
import com.example.engine.SmartRegionDetector
import com.example.engine.TaskNotificationManager
import com.example.engine.TaskPreloadEngine
import com.example.engine.TouchDispatcher
import com.example.engine.VirtualReplayTestEngine
import com.example.engine.VirtualSandboxUiState
import com.example.engine.VirtualStepMatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class SandboxSceneState(
    val adDialogVisible: Boolean = true,
    val checkedInToday: Boolean = false,
    val pointsClaimedCount: Int = 0,
    val rewardBadgeActivated: Boolean = false,
    val rewardConfirmedCount: Int = 0,
    val couponActivated: Boolean = false,
    val couponClaimedCount: Int = 0,
    val customCanvasClicks: List<Offset> = emptyList()
)

data class SandboxUiState(
    val selectedTaskId: Long? = null,
    val selectedScene: TargetSceneType = TargetSceneType.DAILY_CHECKIN,
    val customScreenshot: Bitmap? = null,
    val isRunning: Boolean = false,
    val currentRound: Int = 0,
    val loopBadgeText: String = "",
    val currentStepIndex: Int = -1,
    val detectedRect: Rect? = null,
    val viewportWidthPx: Int = 0,
    val viewportHeightPx: Int = 0,
    val tapPoint: Offset? = null,
    val matchLabel: String? = null,
    val statusMessage: String = "选择任务后即可在沙盒中实时识字识图并触发点击",
    val logs: List<String> = emptyList(),
    val sceneState: SandboxSceneState = SandboxSceneState()
)

data class CropBoxNorm(
    val left: Float = 0.28f,
    val top: Float = 0.28f,
    val right: Float = 0.72f,
    val bottom: Float = 0.52f
)

data class InspectorUiState(
    val sourceBitmap: Bitmap? = null,
    val sourceLabel: String = "未加载截图",
    val recognizedTexts: List<RecognizedTextElement> = emptyList(),
    val selectedTextElement: RecognizedTextElement? = null,
    val cropBox: CropBoxNorm = CropBoxNorm(),
    val searchRoi: CropBoxNorm? = null,
    val ocrElapsedMs: Long? = null,
    val lastScanWasRegion: Boolean = false,
    val croppedTemplatePreview: Bitmap? = null,
    val savedTemplatePath: String? = null,
    val testMatchResult: ImageMatchResult? = null,
    val smartDetection: SmartDetectionResult? = null,
    val isSmartDetecting: Boolean = false,
    val isAnalyzing: Boolean = false,
    val feedbackMessage: String? = null
)

data class JsonTransferUiState(
    val activeExportPayload: ExportJsonPayload? = null,
    val showImportDialog: Boolean = false,
    val isBusy: Boolean = false,
    val statusBanner: String? = null,
    val isErrorBanner: Boolean = false
)

class AutomationViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    val repository = AutomationRepository(application, database.automationDao())

    private val ocrEngine = OcrRecognitionEngine()
    private val templateMatcher = ImageTemplateMatcher()
    private val rootShell = RootShellExecutor()
    private val preloadEngine = TaskPreloadEngine(ocrEngine, templateMatcher)

    val tasksWithSteps: StateFlow<List<TaskWithSteps>> = repository.tasksWithSteps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val executionLogsWithEvents: StateFlow<List<ExecutionLogWithEvents>> = repository.executionLogsWithEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentClickEvents: StateFlow<List<ExecutionClickEventEntity>> = repository.recentClickEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rootRunnerState: StateFlow<RootRunnerState> = RootExecutionBus.state
    val isFloatingWindowVisible: StateFlow<Boolean> = FloatingWindowBus.isOverlayVisible
    val notificationConfig: StateFlow<NotificationConfig> = TaskNotificationManager.configFlow(application)
    val pendingConfirmation: StateFlow<PendingConfirmationRequest?> = ManualConfirmationBus.pendingRequest
    val diagnosticOverlayState: StateFlow<DiagnosticOverlayState> = DiagnosticOverlayBus.state
    val preloadState: StateFlow<PreloadEngineState> = PreloadTelemetryBus.state

    private val _rootStatus = MutableStateFlow<RootStatus?>(null)
    val rootStatus: StateFlow<RootStatus?> = _rootStatus.asStateFlow()

    private val _sandboxState = MutableStateFlow(SandboxUiState())
    val sandboxState: StateFlow<SandboxUiState> = _sandboxState.asStateFlow()

    private val _inspectorState = MutableStateFlow(InspectorUiState())
    val inspectorState: StateFlow<InspectorUiState> = _inspectorState.asStateFlow()

    private val _jsonTransferState = MutableStateFlow(JsonTransferUiState())
    val jsonTransferState: StateFlow<JsonTransferUiState> = _jsonTransferState.asStateFlow()

    private val _virtualSandboxState = MutableStateFlow(VirtualSandboxUiState())
    val virtualSandboxState: StateFlow<VirtualSandboxUiState> = _virtualSandboxState.asStateFlow()

    private var sandboxJob: Job? = null
    private var smartDetectJob: Job? = null
    private var geminiDiagnosticJob: Job? = null
    private var virtualVerifyJob: Job? = null
    private var lastVirtualVideoUri: Uri? = null

    init {
        TaskNotificationManager.ensureChannels(application)
        refreshRootStatus()
    }

    fun setGeminiDiagnosticEnabled(enabled: Boolean) {
        DiagnosticOverlayBus.setEnabled(enabled)
    }

    fun setPreloadEngineEnabled(enabled: Boolean) {
        PreloadTelemetryBus.setEnabled(enabled)
    }

    fun clearGeminiDiagnosticOverlay() {
        geminiDiagnosticJob?.cancel()
        DiagnosticOverlayBus.clearOverlay()
    }

    fun runManualGeminiDiagnostic(
        taskWithSteps: TaskWithSteps,
        captureViewport: suspend () -> Bitmap?
    ) {
        val targetStep = taskWithSteps.sortedSteps.firstOrNull {
            it.isEnabled && (it.stepType == StepType.OCR_TEXT_TAP.name || it.stepType == StepType.IMAGE_TEMPLATE_TAP.name)
        } ?: taskWithSteps.sortedSteps.firstOrNull { it.isEnabled }
        if (targetStep == null) {
            DiagnosticOverlayBus.publishError(
                executionMode = "SANDBOX",
                stepTitle = taskWithSteps.task.name,
                targetingLogic = "Gemini 诊断",
                targetHint = "无可用步骤",
                errorMessage = "当前任务没有启用的步骤可供诊断"
            )
            return
        }
        viewModelScope.launch {
            val frame = captureViewport()
            if (frame == null) {
                DiagnosticOverlayBus.publishError(
                    executionMode = "SANDBOX",
                    stepTitle = targetStep.title,
                    targetingLogic = targetStep.stepType,
                    targetHint = targetStep.targetText,
                    errorMessage = "沙盒画布未就绪，无法采集截图用于 Gemini 诊断"
                )
                return@launch
            }
            try {
                triggerGeminiDiagnosticOnFrame(
                    frame = frame,
                    step = targetStep,
                    executionMode = "SANDBOX"
                )
            } finally {
                frame.recycle()
            }
        }
    }

    private fun triggerGeminiDiagnosticOnFrame(
        frame: Bitmap,
        step: AutomationStepEntity,
        executionMode: String,
        ocrHint: String = ""
    ) {
        if (!DiagnosticOverlayBus.state.value.isEnabled) return
        val frameCopy = frame.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val frameW = frameCopy.width
        val frameH = frameCopy.height
        val stepType = runCatching { StepType.valueOf(step.stepType) }.getOrDefault(StepType.OCR_TEXT_TAP)
        val targetingLogic = stepType.label
        val targetHint = when (stepType) {
            StepType.OCR_TEXT_TAP -> step.targetText
            StepType.IMAGE_TEMPLATE_TAP -> "图像模板 (${(step.matchThreshold * 100).toInt()}%)"
            else -> step.title
        }

        geminiDiagnosticJob?.cancel()
        geminiDiagnosticJob = viewModelScope.launch {
            var tplCopy: Bitmap? = null
            try {
                DiagnosticOverlayBus.markAnalyzing(
                    executionMode = executionMode,
                    stepTitle = step.title,
                    targetingLogic = targetingLogic,
                    targetHint = targetHint,
                    viewportWidthPx = frameW,
                    viewportHeightPx = frameH
                )
                if (stepType == StepType.IMAGE_TEMPLATE_TAP && step.templateImagePath.isNotBlank()) {
                    tplCopy = repository.loadTemplateBitmap(step.templateImagePath)
                }
                when (val outcome = GeminiDiagnosticEngine.diagnoseStepTarget(
                    screenBitmap = frameCopy,
                    step = step,
                    templateBitmap = tplCopy,
                    ocrHint = ocrHint
                )) {
                    is GeminiDiagnosticOutcome.Success -> {
                        DiagnosticOverlayBus.publishSuccess(
                            executionMode = executionMode,
                            stepTitle = step.title,
                            targetingLogic = targetingLogic,
                            targetHint = targetHint,
                            elementFound = outcome.found,
                            elementLabel = outcome.elementLabel,
                            elementType = outcome.elementType,
                            highlightRect = outcome.highlightRect,
                            viewportWidthPx = frameW,
                            viewportHeightPx = frameH,
                            confidence = outcome.confidence,
                            diagnosticNote = outcome.diagnosticNote,
                            elapsedMs = outcome.elapsedMs
                        )
                    }

                    is GeminiDiagnosticOutcome.Error -> {
                        DiagnosticOverlayBus.publishError(
                            executionMode = executionMode,
                            stepTitle = step.title,
                            targetingLogic = targetingLogic,
                            targetHint = targetHint,
                            errorMessage = outcome.message,
                            elapsedMs = outcome.elapsedMs
                        )
                    }
                }
            } finally {
                frameCopy.recycle()
                tplCopy?.recycle()
            }
        }
    }

    fun updateNotificationConfig(config: NotificationConfig) {
        TaskNotificationManager.saveConfig(getApplication(), config)
    }

    fun sendTestNotification(): Boolean {
        return TaskNotificationManager.sendTestNotification(getApplication())
    }

    fun resolvePendingConfirmation(confirmed: Boolean) {
        ManualConfirmationBus.resolve(confirmed = confirmed)
        TaskNotificationManager.cancelManualConfirmationNotification(getApplication())
    }

    fun refreshRootStatus() {
        viewModelScope.launch {
            _rootStatus.value = rootShell.checkRootAccess()
        }
    }

    fun startRootTask(taskId: Long, countdownSec: Int = 3) {
        RootAutomationService.startRootTask(getApplication(), taskId, countdownSec)
    }

    fun pauseRootTask() {
        RootAutomationService.pauseRootTask(getApplication())
    }

    fun resumeRootTask() {
        RootAutomationService.resumeRootTask(getApplication())
    }

    fun stopRootTask() {
        RootAutomationService.stopRootTask(getApplication())
    }

    fun startFloatingControl(initialTaskId: Long? = null) {
        FloatingControlService.start(getApplication(), initialTaskId)
    }

    fun stopFloatingControl() {
        FloatingControlService.stop(getApplication())
    }

    fun selectTaskForSandbox(task: TaskWithSteps) {
        val scene = runCatching { TargetSceneType.valueOf(task.task.sceneType) }
            .getOrDefault(TargetSceneType.DAILY_CHECKIN)
        _sandboxState.update {
            it.copy(
                selectedTaskId = task.task.id,
                selectedScene = scene,
                detectedRect = null,
                tapPoint = null,
                matchLabel = null,
                sceneState = SandboxSceneState()
            )
        }
    }

    fun selectSandboxScene(scene: TargetSceneType) {
        _sandboxState.update {
            it.copy(
                selectedScene = scene,
                detectedRect = null,
                tapPoint = null,
                matchLabel = null,
                sceneState = SandboxSceneState()
            )
        }
    }

    fun resetSandboxScene() {
        _sandboxState.update {
            it.copy(
                detectedRect = null,
                tapPoint = null,
                matchLabel = null,
                sceneState = SandboxSceneState(),
                statusMessage = "目标场景已重置"
            )
        }
    }

    fun onCustomSceneImagePicked(uri: Uri) {
        viewModelScope.launch {
            val bmp = repository.decodeUriBitmap(uri, 1280)
            if (bmp != null) {
                _sandboxState.update {
                    it.copy(
                        selectedScene = TargetSceneType.CUSTOM_IMAGE_CANVAS,
                        customScreenshot = bmp,
                        detectedRect = null,
                        tapPoint = null,
                        sceneState = SandboxSceneState()
                    )
                }
            }
        }
    }

    // Scene interactions triggered by REAL MotionEvent taps (or manual user taps)
    fun dismissSandboxAdDialog() {
        _sandboxState.update { state ->
            state.copy(sceneState = state.sceneState.copy(adDialogVisible = false))
        }
    }

    fun performSandboxCheckin() {
        _sandboxState.update { state ->
            state.copy(sceneState = state.sceneState.copy(checkedInToday = true))
        }
    }

    fun claimSandboxPoints() {
        _sandboxState.update { state ->
            state.copy(
                sceneState = state.sceneState.copy(
                    pointsClaimedCount = state.sceneState.pointsClaimedCount + 1
                )
            )
        }
    }

    fun activateRewardBadge() {
        _sandboxState.update { state ->
            state.copy(sceneState = state.sceneState.copy(rewardBadgeActivated = true))
        }
    }

    fun confirmRewardClaim() {
        _sandboxState.update { state ->
            state.copy(
                sceneState = state.sceneState.copy(
                    rewardConfirmedCount = state.sceneState.rewardConfirmedCount + 1
                )
            )
        }
    }

    fun activateCouponBadge() {
        _sandboxState.update { state ->
            state.copy(sceneState = state.sceneState.copy(couponActivated = true))
        }
    }

    fun claimFlashCoupon() {
        _sandboxState.update { state ->
            state.copy(
                sceneState = state.sceneState.copy(
                    couponClaimedCount = state.sceneState.couponClaimedCount + 1
                )
            )
        }
    }

    fun recordCustomCanvasTap(offset: Offset) {
        _sandboxState.update { state ->
            state.copy(
                sceneState = state.sceneState.copy(
                    customCanvasClicks = (state.sceneState.customCanvasClicks + offset).takeLast(12)
                )
            )
        }
    }

    fun stopSandboxExecution() {
        ManualConfirmationBus.cancelActive()
        TaskNotificationManager.cancelManualConfirmationNotification(getApplication())
        geminiDiagnosticJob?.cancel()
        DiagnosticOverlayBus.clearOverlay()
        preloadEngine.clearSession()
        sandboxJob?.cancel()
        sandboxJob = null
        _sandboxState.update {
            it.copy(
                isRunning = false,
                currentStepIndex = -1,
                statusMessage = "已停止执行"
            )
        }
    }

    fun runTaskInSandbox(
        taskWithSteps: TaskWithSteps,
        captureViewport: suspend () -> Bitmap?,
        dispatchTap: suspend (Float, Float, Long, Float) -> Boolean
    ) {
        sandboxJob?.cancel()
        sandboxJob = viewModelScope.launch {
            val task = taskWithSteps.task
            val steps = taskWithSteps.sortedSteps
            if (steps.isEmpty() || steps.none { it.isEnabled }) {
                _sandboxState.update {
                    it.copy(
                        statusMessage = if (steps.isEmpty()) "该任务没有可执行步骤"
                        else "所有步骤或节点容器均已禁用，请先启用后再运行"
                    )
                }
                return@launch
            }

            val loopMode = runCatching { LoopMode.valueOf(task.loopMode) }.getOrDefault(LoopMode.FIXED_COUNT)
            val maxRounds = if (loopMode == LoopMode.FIXED_COUNT) task.repeatCount.coerceAtLeast(1) else Int.MAX_VALUE

            _sandboxState.update {
                it.copy(
                    selectedTaskId = task.id,
                    isRunning = true,
                    currentRound = 0,
                    loopBadgeText = if (loopMode == LoopMode.FIXED_COUNT) "固定 $maxRounds 轮" else loopMode.label,
                    currentStepIndex = 0,
                    detectedRect = null,
                    tapPoint = null,
                    matchLabel = null,
                    statusMessage = "正在启动沙盒引擎：${task.name}",
                    logs = listOf("[启动] 任务「${task.name}」共 ${steps.size} 步 · 模式：${loopMode.label}")
                )
            }

            val startedAt = System.currentTimeMillis()
            var round = 0
            var matchedSteps = 0
            var totalStepsExecuted = 0
            var clicksTriggered = 0
            var allSucceeded = true
            var abortedByManualBranch = false
            var stopReason = ""
            val clickEvents = mutableListOf<ExecutionClickEventEntity>()
            var runPersisted = false
            preloadEngine.prepareTaskInBackground(task.name, steps, repository)
            if (PreloadTelemetryBus.state.value.isEnabled) {
                appendSandboxLog("[预加载] 后台启动 OCR 模型预热与步骤特征预解析")
            }

            try {
                while (isActive && round < maxRounds && !abortedByManualBranch) {
                    val preCheck = LoopControlEvaluator.evaluateStopCondition(
                        task = task,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        anyStepFailedInRound = false,
                        captureFrame = captureViewport,
                        ocrEngine = ocrEngine,
                        templateMatcher = templateMatcher,
                        repository = repository
                    )
                    if (preCheck.shouldStop) {
                        stopReason = preCheck.reason
                        appendSandboxLog("[条件停止] $stopReason")
                        break
                    }

                    round++
                    val roundLabel = if (loopMode == LoopMode.FIXED_COUNT) {
                        "第 $round/$maxRounds 轮"
                    } else {
                        "第 $round 轮 · ${loopMode.label}"
                    }
                    _sandboxState.update { it.copy(currentRound = round, loopBadgeText = roundLabel) }
                    appendSandboxLog("[轮次] $roundLabel 开始")

                    var anyStepMissed = false
                    for ((idx, step) in steps.withIndex()) {
                        if (!isActive) break
                        if (!step.isEnabled) {
                            val groupTag = if (step.groupName.isNotBlank()) "（容器：${step.groupName}）" else ""
                            appendSandboxLog("[跳过] 已禁用节点「${step.title}」$groupTag")
                            continue
                        }
                        _sandboxState.update {
                            it.copy(
                                currentStepIndex = idx,
                                detectedRect = null,
                                tapPoint = null,
                                statusMessage = "$roundLabel · 步骤 ${idx + 1}/${steps.size}：${step.title}"
                            )
                        }

                        totalStepsExecuted++
                        val upcomingStep = steps.drop(idx + 1).firstOrNull { it.isEnabled }
                            ?: if (round < maxRounds) steps.firstOrNull { it.isEnabled } else null
                        val maxRetries = if (
                            step.stepType == StepType.WAIT_DELAY.name ||
                            step.stepType == StepType.MANUAL_CONFIRM_BRANCH.name
                        ) {
                            0
                        } else {
                            step.maxRetryCount.coerceIn(0, 10)
                        }
                        val retryInterval = step.retryIntervalMs.coerceIn(100L, 30000L)
                        var retryAttempt = 0
                        var outcome: StepRunOutcome
                        while (true) {
                            outcome = executeSandboxStep(
                                task = task,
                                roundIndex = round,
                                step = step,
                                upcomingStep = upcomingStep,
                                captureViewport = captureViewport,
                                dispatchTap = dispatchTap
                            )
                            if (outcome.matched || outcome.abortedByUser || retryAttempt >= maxRetries || !isActive) {
                                if (retryAttempt > 0 && outcome.matched) {
                                    outcome = outcome.copy(
                                        event = outcome.event?.copy(
                                            recognizedTarget = "${outcome.event?.recognizedTarget}（第${retryAttempt}次重试命中）"
                                        )
                                    )
                                } else if (retryAttempt > 0 && !outcome.matched) {
                                    outcome = outcome.copy(
                                        event = outcome.event?.copy(
                                            failureReason = "${outcome.event?.failureReason}（已自动重试 ${retryAttempt}/${maxRetries} 次仍失败）"
                                        )
                                    )
                                }
                                break
                            }
                            retryAttempt++
                            outcome.event?.let { failedEv ->
                                clickEvents.add(
                                    failedEv.copy(
                                        failureReason = "${failedEv.failureReason}（将间隔 ${retryInterval}ms 第 $retryAttempt/$maxRetries 次重试）"
                                    )
                                )
                            }
                            appendSandboxLog(
                                "[自动重试] 「${step.title}」执行失败，等待 ${retryInterval}ms 后进行第 $retryAttempt/$maxRetries 次重试"
                            )
                            _sandboxState.update {
                                it.copy(
                                    statusMessage = "$roundLabel · 步骤 ${idx + 1} 失败，${retryInterval}ms 后第 $retryAttempt/$maxRetries 次重试"
                                )
                            }
                            delay(retryInterval)
                            if (!isActive) break
                        }
                        outcome.event?.let { clickEvents.add(it) }
                        if (outcome.abortedByUser) {
                            allSucceeded = false
                            anyStepMissed = true
                            abortedByManualBranch = true
                            stopReason = "人工确认分支终止执行"
                            break
                        }
                        if (outcome.matched) matchedSteps++ else {
                            allSucceeded = false
                            anyStepMissed = true
                        }
                        if (outcome.clicked) clicksTriggered++

                        val waitMs = TouchDispatcher.applyJitterDelay(
                            baseDelayMs = step.delayAfterMs.coerceAtLeast(0L),
                            jitterMs = if (step.randomOffsetEnabled) step.randomDelayJitterMs else 0L
                        )
                        if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                            if (waitMs >= 260L || !preloadEngine.hasFreshOrActivePreloadFor(upcomingStep)) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = waitMs,
                                    repository = repository,
                                    captureBackgroundFrame = captureViewport
                                )
                            }
                        }
                        if (waitMs > 0L) delay(waitMs)
                    }

                    if (abortedByManualBranch) break

                    val postCheck = LoopControlEvaluator.evaluateStopCondition(
                        task = task,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        anyStepFailedInRound = anyStepMissed,
                        captureFrame = captureViewport,
                        ocrEngine = ocrEngine,
                        templateMatcher = templateMatcher,
                        repository = repository
                    )
                    if (postCheck.shouldStop) {
                        stopReason = postCheck.reason
                        appendSandboxLog("[条件停止] $stopReason")
                        break
                    }

                    if (round < maxRounds) {
                        val intervalWait = TouchDispatcher.applyJitterDelay(
                            baseDelayMs = task.loopIntervalMs.coerceAtLeast(200L),
                            jitterMs = 120L
                        )
                        appendSandboxLog("[轮间等待] ${intervalWait}ms 后进入下一轮")
                        delay(intervalWait)
                    }
                }

                val duration = System.currentTimeMillis() - startedAt
                val reasonText = if (stopReason.isNotBlank()) "（$stopReason）" else ""
                val summary = "沙盒完成 $round 轮$reasonText：命中 $matchedSteps/$totalStepsExecuted 步，真实触控 $clicksTriggered 次"
                val runSucceeded = (allSucceeded || stopReason.isNotBlank()) && !abortedByManualBranch
                appendSandboxLog("[完成] $summary (${duration}ms)")
                repository.recordRun(
                    taskId = task.id,
                    taskName = task.name,
                    executionMode = "SANDBOX",
                    startedAt = startedAt,
                    durationMs = duration,
                    matchedSteps = matchedSteps,
                    totalSteps = totalStepsExecuted.coerceAtLeast(1),
                    clicksTriggered = clicksTriggered,
                    success = runSucceeded,
                    summary = summary,
                    events = clickEvents
                )
                runPersisted = true

                if (runSucceeded) {
                    TaskNotificationManager.notifyTaskCompleted(
                        context = getApplication(),
                        taskId = task.id,
                        taskName = task.name,
                        executionMode = "SANDBOX",
                        durationMs = duration,
                        summary = summary,
                        taskNotifyEnabled = task.notifyOnComplete,
                        taskLongRunThresholdSec = task.longRunThresholdSec,
                        taskCustomNote = task.customNotifyNote
                    )
                } else {
                    val lastFailure = clickEvents.lastOrNull { !it.success }?.failureReason
                        ?.takeIf { it.isNotBlank() } ?: summary
                    TaskNotificationManager.notifyTaskFailed(
                        context = getApplication(),
                        taskId = task.id,
                        taskName = task.name,
                        executionMode = "SANDBOX",
                        reason = lastFailure,
                        taskNotifyEnabled = task.notifyOnFailure,
                        taskCustomNote = task.customNotifyNote
                    )
                }

                _sandboxState.update {
                    it.copy(
                        isRunning = false,
                        currentStepIndex = -1,
                        statusMessage = summary
                    )
                }
            } catch (ce: CancellationException) {
                ManualConfirmationBus.cancelActive()
                TaskNotificationManager.cancelManualConfirmationNotification(getApplication())
                if (!runPersisted && clickEvents.isNotEmpty()) {
                    withContext(NonCancellable) {
                        val duration = System.currentTimeMillis() - startedAt
                        repository.recordRun(
                            taskId = task.id,
                            taskName = task.name,
                            executionMode = "SANDBOX",
                            startedAt = startedAt,
                            durationMs = duration,
                            matchedSteps = matchedSteps,
                            totalSteps = totalStepsExecuted.coerceAtLeast(1),
                            clicksTriggered = clicksTriggered,
                            success = false,
                            summary = "沙盒任务被手动终止（已执行 $round 轮，触控 $clicksTriggered 次）",
                            events = clickEvents
                        )
                    }
                }
                throw ce
            } catch (err: Exception) {
                val errMsg = err.message ?: "沙盒运行异常"
                appendSandboxLog("[异常] $errMsg")
                TaskNotificationManager.notifyTaskFailed(
                    context = getApplication(),
                    taskId = task.id,
                    taskName = task.name,
                    executionMode = "SANDBOX",
                    reason = errMsg,
                    taskNotifyEnabled = task.notifyOnFailure,
                    taskCustomNote = task.customNotifyNote
                )
                _sandboxState.update {
                    it.copy(
                        isRunning = false,
                        currentStepIndex = -1,
                        statusMessage = "执行异常：$errMsg"
                    )
                }
            } finally {
                preloadEngine.clearSession()
            }
        }
    }

    private data class StepRunOutcome(
        val matched: Boolean,
        val clicked: Boolean,
        val abortedByUser: Boolean = false,
        val event: ExecutionClickEventEntity? = null
    )

    private suspend fun executeSandboxStep(
        task: AutomationTaskEntity,
        roundIndex: Int,
        step: AutomationStepEntity,
        upcomingStep: AutomationStepEntity?,
        captureViewport: suspend () -> Bitmap?,
        dispatchTap: suspend (Float, Float, Long, Float) -> Boolean
    ): StepRunOutcome {
        val taskId = task.id
        val taskName = task.name
        val type = runCatching { StepType.valueOf(step.stepType) }.getOrDefault(StepType.WAIT_DELAY)
        if (type == StepType.WAIT_DELAY) {
            appendSandboxLog("[延时] 暂停 ${step.delayAfterMs}ms")
            return StepRunOutcome(matched = true, clicked = false, event = null)
        }

        if (type == StepType.MANUAL_CONFIRM_BRANCH) {
            val branchType = runCatching { BranchConditionType.valueOf(step.branchConditionType) }
                .getOrDefault(BranchConditionType.ALWAYS_PAUSE)
            var shouldPause = true
            var triggerReason = "流程到达人工确认节点「${step.title}」，请处理或确认后继续"

            if (branchType != BranchConditionType.ALWAYS_PAUSE && step.targetText.isNotBlank()) {
                val frame = captureViewport()
                if (frame != null) {
                    val found = try {
                        val roiFrame = RegionCropHelper.cropForRegion(
                            source = frame,
                            enabled = step.roiEnabled,
                            normLeft = step.roiLeft,
                            normTop = step.roiTop,
                            normRight = step.roiRight,
                            normBottom = step.roiBottom
                        )
                        val elements = try {
                            ocrEngine.recognizeText(roiFrame.bitmap)
                        } finally {
                            if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
                        }
                        val mode = runCatching { TextMatchMode.valueOf(step.textMatchMode) }
                            .getOrDefault(TextMatchMode.CONTAINS)
                        ocrEngine.findBestMatch(elements, step.targetText, mode)
                    } finally {
                        frame.recycle()
                    }
                    when (branchType) {
                        BranchConditionType.WHEN_TEXT_APPEARS -> {
                            if (found == null) {
                                shouldPause = false
                                appendSandboxLog("[分支跳过] 未检测到「${step.targetText}」，继续执行")
                            } else {
                                triggerReason = "检测到分支关键词「${found.text}」，需人工确认"
                            }
                        }
                        BranchConditionType.WHEN_TEXT_MISSING -> {
                            if (found != null) {
                                shouldPause = false
                                appendSandboxLog("[分支通过] 已检测到「${found.text}」，继续执行")
                            } else {
                                triggerReason = "画布缺少预期文字「${step.targetText}」，需人工确认"
                            }
                        }
                        BranchConditionType.ALWAYS_PAUSE -> Unit
                    }
                }
            }

            if (!shouldPause) {
                return StepRunOutcome(matched = true, clicked = false, event = null)
            }

            val req = PendingConfirmationRequest(
                taskId = taskId,
                taskName = taskName,
                stepTitle = step.title,
                reason = triggerReason,
                executionMode = "SANDBOX"
            )
            appendSandboxLog("[人工确认] $triggerReason")
            _sandboxState.update {
                it.copy(
                    statusMessage = "等待人工确认：${step.title}",
                    matchLabel = "等待人工确认"
                )
            }
            TaskNotificationManager.notifyManualConfirmationRequired(
                context = getApplication(),
                request = req,
                taskNotifyEnabled = task.notifyOnManualConfirm,
                taskCustomNote = task.customNotifyNote
            )
            val confirmed = try {
                ManualConfirmationBus.awaitDecision(req)
            } finally {
                TaskNotificationManager.cancelManualConfirmationNotification(getApplication())
            }
            return if (confirmed) {
                appendSandboxLog("[人工确认] 用户已确认继续后续步骤")
                StepRunOutcome(
                    matched = true,
                    clicked = false,
                    event = ExecutionClickEventEntity(
                        taskId = taskId,
                        taskName = taskName,
                        roundIndex = roundIndex,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        executionMode = "SANDBOX",
                        actionType = type.name,
                        recognizedTarget = "人工确认分支：已放行继续（$triggerReason）",
                        success = true
                    )
                )
            } else {
                appendSandboxLog("[人工确认] 用户选择终止当前任务")
                StepRunOutcome(
                    matched = false,
                    clicked = false,
                    abortedByUser = true,
                    event = ExecutionClickEventEntity(
                        taskId = taskId,
                        taskName = taskName,
                        roundIndex = roundIndex,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        executionMode = "SANDBOX",
                        actionType = type.name,
                        recognizedTarget = "人工确认分支：已终止",
                        success = false,
                        failureReason = "用户在人工确认分支选择了终止任务（$triggerReason）"
                    )
                )
            }
        }

        val preScanWait = step.preScanDelayMs.coerceIn(0L, 60000L)
        if (preScanWait > 0L) {
            appendSandboxLog("[页面加载等待] 等待 ${preScanWait}ms 待界面渲染稳定...")
            _sandboxState.update {
                it.copy(statusMessage = "第 $roundIndex 轮 · ${step.title}（页面加载等待 ${preScanWait}ms）")
            }
            if (preScanWait >= 300L &&
                (type == StepType.OCR_TEXT_TAP || type == StepType.IMAGE_TEMPLATE_TAP) &&
                PreloadTelemetryBus.state.value.isEnabled
            ) {
                preloadEngine.scheduleNextStepPreload(
                    nextStep = step,
                    delayWindowMs = preScanWait,
                    repository = repository,
                    captureBackgroundFrame = captureViewport
                )
            }
            delay(preScanWait)
        }

        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceIn(500L, 60000L)
        val scanInterval = step.scanIntervalMs.coerceIn(100L, 10000L)
        var capturedAnyFrame = false
        var geminiDispatchedForStep = false
        var scanAttempt = 0
        var lastOcrCount = 0
        var lastOcrPreview = ""
        var bestImageSimilarity = -1f

        while (System.currentTimeMillis() <= deadline) {
            val frame = captureViewport()
            if (frame == null) {
                delay(scanInterval.coerceAtMost(500L))
                continue
            }
            capturedAnyFrame = true
            scanAttempt++
            if (!geminiDispatchedForStep &&
                (type == StepType.OCR_TEXT_TAP || type == StepType.IMAGE_TEMPLATE_TAP) &&
                DiagnosticOverlayBus.state.value.isEnabled
            ) {
                geminiDispatchedForStep = true
                triggerGeminiDiagnosticOnFrame(
                    frame = frame,
                    step = step,
                    executionMode = "SANDBOX",
                    ocrHint = lastOcrPreview
                )
            }

            try {
                val frameW = frame.width
                val frameH = frame.height

                when (type) {
                    StepType.COORDINATE_TAP -> {
                        val baseX = (step.normalizedX * frameW).coerceIn(1f, (frameW - 1).toFloat())
                        val baseY = (step.normalizedY * frameH).coerceIn(1f, (frameH - 1).toFloat())
                        val offsetPt = TouchDispatcher.applyHumanizedOffset(
                            centerX = baseX,
                            centerY = baseY,
                            boundingBox = null,
                            enabled = step.randomOffsetEnabled,
                            radiusPx = step.randomOffsetRadiusPx
                        )
                        val tx = offsetPt.x.coerceIn(1f, (frameW - 1).toFloat())
                        val ty = offsetPt.y.coerceIn(1f, (frameH - 1).toFloat())
                        val box = Rect(
                            (baseX - 32).toInt().coerceAtLeast(0),
                            (baseY - 32).toInt().coerceAtLeast(0),
                            (baseX + 32).toInt().coerceAtMost(frameW),
                            (baseY + 32).toInt().coerceAtMost(frameH)
                        )
                        _sandboxState.update {
                            it.copy(
                                detectedRect = box,
                                viewportWidthPx = frameW,
                                viewportHeightPx = frameH,
                                tapPoint = Offset(tx, ty),
                                matchLabel = "固定坐标 (${tx.toInt()}, ${ty.toInt()}) 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY})"
                            )
                        }
                        if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                            preloadEngine.scheduleNextStepPreload(
                                nextStep = upcomingStep,
                                delayWindowMs = 0L,
                                immediateFrame = frame,
                                repository = repository,
                                captureBackgroundFrame = captureViewport
                            )
                        }
                        delay(if (step.delayBeforeMs > 0L) step.delayBeforeMs else 240L)
                        val dispatched = dispatchTap(tx, ty, step.pressDurationMs, step.touchPressure)
                        val targetDesc = String.format(Locale.US, "固定坐标 (%.2f, %.2f)", step.normalizedX, step.normalizedY)
                        appendSandboxLog("[坐标点击] 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY}) -> 触控 (${tx.toInt()}, ${ty.toInt()})")
                        return StepRunOutcome(
                            matched = dispatched,
                            clicked = dispatched,
                            event = ExecutionClickEventEntity(
                                taskId = taskId,
                                taskName = taskName,
                                roundIndex = roundIndex,
                                stepOrder = step.stepOrder,
                                stepTitle = step.title,
                                executionMode = "SANDBOX",
                                actionType = type.name,
                                recognizedTarget = targetDesc,
                                tapX = tx.toInt(),
                                tapY = ty.toInt(),
                                normX = (tx / frameW.toFloat()).coerceIn(0f, 1f),
                                normY = (ty / frameH.toFloat()).coerceIn(0f, 1f),
                                offsetDeltaX = offsetPt.deltaX,
                                offsetDeltaY = offsetPt.deltaY,
                                success = dispatched,
                                failureReason = if (dispatched) "" else "沙盒 MotionEvent 触控注入失败"
                            )
                        )
                    }

                    StepType.OCR_TEXT_TAP -> {
                        val preloadedHit = preloadEngine.tryConsumePreloadedOcr(step, frame)
                        val mode = runCatching { TextMatchMode.valueOf(step.textMatchMode) }
                            .getOrDefault(TextMatchMode.CONTAINS)
                        if (preloadedHit != null && preloadedHit.preMatchedElement != null) {
                            val match = preloadedHit.preMatchedElement
                            val lineElements = preloadedHit.elements.filter { it.isLine }
                            lastOcrCount = lineElements.size
                            lastOcrPreview = lineElements.take(3).joinToString("、") { it.text }
                            val fullBox = match.boundingBox
                            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                                centerX = match.centerX,
                                centerY = match.centerY,
                                boundingBox = fullBox,
                                enabled = step.randomOffsetEnabled,
                                radiusPx = step.randomOffsetRadiusPx
                            )
                            val roiTag = if (step.roiEnabled) "·区域识别" else ""
                            _sandboxState.update {
                                it.copy(
                                    detectedRect = fullBox,
                                    viewportWidthPx = frameW,
                                    viewportHeightPx = frameH,
                                    tapPoint = Offset(offsetPt.x, offsetPt.y),
                                    matchLabel = "预加载OCR「${match.text}」(-${preloadedHit.savedMs}ms)"
                                )
                            }
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = frame,
                                    repository = repository,
                                    captureBackgroundFrame = captureViewport
                                )
                            }
                            delay(if (step.delayBeforeMs > 0L) step.delayBeforeMs else 240L)
                            val dispatched = dispatchTap(offsetPt.x, offsetPt.y, step.pressDurationMs, step.touchPressure)
                            appendSandboxLog(
                                "[预加载命中] 「${match.text}」$roiTag 省时 ${preloadedHit.savedMs}ms -> 点击 (${offsetPt.x.toInt()}, ${offsetPt.y.toInt()})"
                            )
                            return StepRunOutcome(
                                matched = dispatched,
                                clicked = dispatched,
                                event = ExecutionClickEventEntity(
                                    taskId = taskId,
                                    taskName = taskName,
                                    roundIndex = roundIndex,
                                    stepOrder = step.stepOrder,
                                    stepTitle = step.title,
                                    executionMode = "SANDBOX",
                                    actionType = type.name,
                                    recognizedTarget = "预加载OCR「${match.text}」$roiTag",
                                    tapX = offsetPt.x.toInt(),
                                    tapY = offsetPt.y.toInt(),
                                    normX = (offsetPt.x / frameW.toFloat()).coerceIn(0f, 1f),
                                    normY = (offsetPt.y / frameH.toFloat()).coerceIn(0f, 1f),
                                    offsetDeltaX = offsetPt.deltaX,
                                    offsetDeltaY = offsetPt.deltaY,
                                    success = dispatched,
                                    failureReason = if (dispatched) "" else "预加载命中「${match.text}」，但沙盒触控注入失败"
                                )
                            )
                        }

                        val roiFrame = RegionCropHelper.cropForRegion(
                            source = frame,
                            enabled = step.roiEnabled,
                            normLeft = step.roiLeft,
                            normTop = step.roiTop,
                            normRight = step.roiRight,
                            normBottom = step.roiBottom
                        )
                        val ocrStartMs = System.currentTimeMillis()
                        val elements = try {
                            ocrEngine.recognizeText(roiFrame.bitmap)
                        } finally {
                            if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
                        }
                        val ocrElapsed = System.currentTimeMillis() - ocrStartMs
                        val fullElements = if (roiFrame.offsetX == 0 && roiFrame.offsetY == 0) {
                            elements
                        } else {
                            elements.map { el ->
                                val mapped = RegionCropHelper.mapRectToFullScreen(el.boundingBox, roiFrame.offsetX, roiFrame.offsetY)
                                el.copy(boundingBox = mapped, centerX = mapped.exactCenterX(), centerY = mapped.exactCenterY())
                            }
                        }
                        preloadEngine.publishCompletedFullScreenOcr(frame, step, fullElements, ocrElapsed)

                        val lineElements = elements.filter { it.isLine }
                        lastOcrCount = lineElements.size
                        lastOcrPreview = lineElements.take(3).joinToString("、") { it.text }
                        val match = ocrEngine.findBestMatch(elements, step.targetText, mode)
                        if (match != null) {
                            val fullBox = RegionCropHelper.mapRectToFullScreen(
                                match.boundingBox,
                                roiFrame.offsetX,
                                roiFrame.offsetY
                            )
                            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                                centerX = match.centerX + roiFrame.offsetX,
                                centerY = match.centerY + roiFrame.offsetY,
                                boundingBox = fullBox,
                                enabled = step.randomOffsetEnabled,
                                radiusPx = step.randomOffsetRadiusPx
                            )
                            val roiTag = if (step.roiEnabled) "·区域识别" else ""
                            _sandboxState.update {
                                it.copy(
                                    detectedRect = fullBox,
                                    viewportWidthPx = frameW,
                                    viewportHeightPx = frameH,
                                    tapPoint = Offset(offsetPt.x, offsetPt.y),
                                    matchLabel = "OCR「${match.text}」$roiTag 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY})"
                                )
                            }
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = frame,
                                    repository = repository,
                                    captureBackgroundFrame = captureViewport
                                )
                            }
                            delay(if (step.delayBeforeMs > 0L) step.delayBeforeMs else 240L)
                            val dispatched = dispatchTap(offsetPt.x, offsetPt.y, step.pressDurationMs, step.touchPressure)
                            appendSandboxLog(
                                "[OCR 命中] 「${match.text}」$roiTag 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY}) -> 点击 (${offsetPt.x.toInt()}, ${offsetPt.y.toInt()})"
                            )
                            return StepRunOutcome(
                                matched = dispatched,
                                clicked = dispatched,
                                event = ExecutionClickEventEntity(
                                    taskId = taskId,
                                    taskName = taskName,
                                    roundIndex = roundIndex,
                                    stepOrder = step.stepOrder,
                                    stepTitle = step.title,
                                    executionMode = "SANDBOX",
                                    actionType = type.name,
                                    recognizedTarget = "OCR「${match.text}」$roiTag",
                                    tapX = offsetPt.x.toInt(),
                                    tapY = offsetPt.y.toInt(),
                                    normX = (offsetPt.x / frameW.toFloat()).coerceIn(0f, 1f),
                                    normY = (offsetPt.y / frameH.toFloat()).coerceIn(0f, 1f),
                                    offsetDeltaX = offsetPt.deltaX,
                                    offsetDeltaY = offsetPt.deltaY,
                                    success = dispatched,
                                    failureReason = if (dispatched) "" else "已识别「${match.text}」，但沙盒触控注入失败"
                                )
                            )
                        }
                    }

                    StepType.IMAGE_TEMPLATE_TAP -> {
                        val preloadedImageHit = preloadEngine.tryConsumePreloadedTemplateMatch(step, frame)
                        if (preloadedImageHit != null) {
                            val match = preloadedImageHit.matchResult
                            val pct = (match.similarity * 100).toInt()
                            val fullBox = match.boundingBox
                            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                                centerX = match.centerX,
                                centerY = match.centerY,
                                boundingBox = fullBox,
                                enabled = step.randomOffsetEnabled,
                                radiusPx = step.randomOffsetRadiusPx
                            )
                            val roiTag = if (step.roiEnabled) "·区域加速" else ""
                            _sandboxState.update {
                                it.copy(
                                    detectedRect = fullBox,
                                    viewportWidthPx = frameW,
                                    viewportHeightPx = frameH,
                                    tapPoint = Offset(offsetPt.x, offsetPt.y),
                                    matchLabel = "预加载识图 ${pct}%$roiTag (-${preloadedImageHit.savedMs}ms)"
                                )
                            }
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = frame,
                                    repository = repository,
                                    captureBackgroundFrame = captureViewport
                                )
                            }
                            delay(if (step.delayBeforeMs > 0L) step.delayBeforeMs else 240L)
                            val dispatched = dispatchTap(offsetPt.x, offsetPt.y, step.pressDurationMs, step.touchPressure)
                            appendSandboxLog(
                                "[预加载命中] 图像模板 ${pct}%$roiTag 省时 ${preloadedImageHit.savedMs}ms -> 点击 (${offsetPt.x.toInt()}, ${offsetPt.y.toInt()})"
                            )
                            return StepRunOutcome(
                                matched = dispatched,
                                clicked = dispatched,
                                event = ExecutionClickEventEntity(
                                    taskId = taskId,
                                    taskName = taskName,
                                    roundIndex = roundIndex,
                                    stepOrder = step.stepOrder,
                                    stepTitle = step.title,
                                    executionMode = "SANDBOX",
                                    actionType = type.name,
                                    recognizedTarget = "预加载图像模板（相似度 ${pct}%$roiTag）",
                                    tapX = offsetPt.x.toInt(),
                                    tapY = offsetPt.y.toInt(),
                                    normX = (offsetPt.x / frameW.toFloat()).coerceIn(0f, 1f),
                                    normY = (offsetPt.y / frameH.toFloat()).coerceIn(0f, 1f),
                                    offsetDeltaX = offsetPt.deltaX,
                                    offsetDeltaY = offsetPt.deltaY,
                                    success = dispatched,
                                    failureReason = if (dispatched) "" else "预加载匹配图像(${pct}%)，但沙盒触控注入失败"
                                )
                            )
                        }

                        val tpl = preloadEngine.loadTemplateCopy(step.templateImagePath, repository)
                        if (tpl == null) {
                            appendSandboxLog("[错误] 找不到图像模板：${step.templateImagePath}")
                            return StepRunOutcome(
                                matched = false,
                                clicked = false,
                                event = ExecutionClickEventEntity(
                                    taskId = taskId,
                                    taskName = taskName,
                                    roundIndex = roundIndex,
                                    stepOrder = step.stepOrder,
                                    stepTitle = step.title,
                                    executionMode = "SANDBOX",
                                    actionType = type.name,
                                    recognizedTarget = "图像模板（文件缺失）",
                                    success = false,
                                    failureReason = "找不到图像模板文件：${step.templateImagePath}"
                                )
                            )
                        }
                        val roiFrame = RegionCropHelper.cropForRegion(
                            source = frame,
                            enabled = step.roiEnabled,
                            normLeft = step.roiLeft,
                            normTop = step.roiTop,
                            normRight = step.roiRight,
                            normBottom = step.roiBottom
                        )
                        val match = try {
                            templateMatcher.findTemplate(roiFrame.bitmap, tpl, step.matchThreshold)
                        } finally {
                            tpl.recycle()
                            if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
                        }
                        if (match.similarity > bestImageSimilarity) {
                            bestImageSimilarity = match.similarity
                        }
                        if (match.found) {
                            val pct = (match.similarity * 100).toInt()
                            val fullBox = RegionCropHelper.mapRectToFullScreen(
                                match.boundingBox,
                                roiFrame.offsetX,
                                roiFrame.offsetY
                            )
                            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                                centerX = match.centerX + roiFrame.offsetX,
                                centerY = match.centerY + roiFrame.offsetY,
                                boundingBox = fullBox,
                                enabled = step.randomOffsetEnabled,
                                radiusPx = step.randomOffsetRadiusPx
                            )
                            val roiTag = if (step.roiEnabled) "·区域加速" else ""
                            _sandboxState.update {
                                it.copy(
                                    detectedRect = fullBox,
                                    viewportWidthPx = frameW,
                                    viewportHeightPx = frameH,
                                    tapPoint = Offset(offsetPt.x, offsetPt.y),
                                    matchLabel = "图像 ${pct}%$roiTag · 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY})"
                                )
                            }
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = frame,
                                    repository = repository,
                                    captureBackgroundFrame = captureViewport
                                )
                            }
                            delay(if (step.delayBeforeMs > 0L) step.delayBeforeMs else 240L)
                            val dispatched = dispatchTap(offsetPt.x, offsetPt.y, step.pressDurationMs, step.touchPressure)
                            appendSandboxLog(
                                "[图像命中] ${pct}%$roiTag 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY}) -> 点击 (${offsetPt.x.toInt()}, ${offsetPt.y.toInt()})"
                            )
                            return StepRunOutcome(
                                matched = dispatched,
                                clicked = dispatched,
                                event = ExecutionClickEventEntity(
                                    taskId = taskId,
                                    taskName = taskName,
                                    roundIndex = roundIndex,
                                    stepOrder = step.stepOrder,
                                    stepTitle = step.title,
                                    executionMode = "SANDBOX",
                                    actionType = type.name,
                                    recognizedTarget = "图像模板（相似度 ${pct}%$roiTag）",
                                    tapX = offsetPt.x.toInt(),
                                    tapY = offsetPt.y.toInt(),
                                    normX = (offsetPt.x / frameW.toFloat()).coerceIn(0f, 1f),
                                    normY = (offsetPt.y / frameH.toFloat()).coerceIn(0f, 1f),
                                    offsetDeltaX = offsetPt.deltaX,
                                    offsetDeltaY = offsetPt.deltaY,
                                    success = dispatched,
                                    failureReason = if (dispatched) "" else "已匹配图像(${pct}%)，但沙盒触控注入失败"
                                )
                            )
                        }
                    }

                    StepType.COORDINATE_TAP,
                    StepType.WAIT_DELAY,
                    StepType.MANUAL_CONFIRM_BRANCH -> Unit
                }
            } finally {
                frame.recycle()
            }

            if (System.currentTimeMillis() + scanInterval <= deadline) {
                _sandboxState.update {
                    it.copy(
                        statusMessage = "第 $roundIndex 轮 · ${step.title} 第${scanAttempt}次未命中，等待 ${scanInterval}ms 后继续识别..."
                    )
                }
            }
            delay(scanInterval)
        }

        val targetDesc = when (type) {
            StepType.OCR_TEXT_TAP -> "期望文字「${step.targetText}」"
            StepType.IMAGE_TEMPLATE_TAP -> "图像模板（阈值 ${(step.matchThreshold * 100).toInt()}%）"
            else -> step.title
        }
        val reason = when {
            !capturedAnyFrame -> "沙盒画面帧采集失败（画布未就绪）"
            type == StepType.OCR_TEXT_TAP -> {
                val previewSuffix = if (lastOcrPreview.isNotBlank()) "（如：$lastOcrPreview）" else ""
                "超时 ${step.timeoutMs}ms 未匹配「${step.targetText}」：当前画布共识别 $lastOcrCount 处文字$previewSuffix"
            }
            type == StepType.IMAGE_TEMPLATE_TAP -> {
                val bestPct = if (bestImageSimilarity >= 0f) (bestImageSimilarity * 100).toInt() else 0
                val thresholdPct = (step.matchThreshold * 100).toInt()
                "超时 ${step.timeoutMs}ms 未命中图像模板：最高相似度 ${bestPct}% 低于阈值 ${thresholdPct}%"
            }
            else -> "超时未命中：${step.title}"
        }
        appendSandboxLog("[未命中] $reason")
        val roiNormX = if (step.roiEnabled) ((step.roiLeft + step.roiRight) / 2f).coerceIn(0f, 1f) else null
        val roiNormY = if (step.roiEnabled) ((step.roiTop + step.roiBottom) / 2f).coerceIn(0f, 1f) else null
        return StepRunOutcome(
            matched = false,
            clicked = false,
            event = ExecutionClickEventEntity(
                taskId = taskId,
                taskName = taskName,
                roundIndex = roundIndex,
                stepOrder = step.stepOrder,
                stepTitle = step.title,
                executionMode = "SANDBOX",
                actionType = type.name,
                recognizedTarget = targetDesc,
                normX = roiNormX,
                normY = roiNormY,
                success = false,
                failureReason = reason
            )
        )
    }

    private fun appendSandboxLog(line: String) {
        _sandboxState.update {
            it.copy(logs = (it.logs + line).takeLast(60))
        }
    }

    // --- Inspector Lab Actions ---

    fun loadImageIntoInspector(uri: Uri) {
        viewModelScope.launch {
            _inspectorState.update { it.copy(isAnalyzing = true, smartDetection = null, feedbackMessage = null) }
            val bmp = repository.decodeUriBitmap(uri, 1280)
            if (bmp != null) {
                _inspectorState.update {
                    it.copy(
                        sourceBitmap = bmp,
                        sourceLabel = "相册导入截图 (${bmp.width}×${bmp.height})",
                        recognizedTexts = emptyList(),
                        selectedTextElement = null,
                        croppedTemplatePreview = null,
                        savedTemplatePath = null,
                        testMatchResult = null,
                        smartDetection = null,
                        isAnalyzing = false,
                        feedbackMessage = "已加载图片，正在自动分析框选区域特征并推荐匹配算法..."
                    )
                }
                scheduleSmartDetection(immediate = true)
            } else {
                _inspectorState.update {
                    it.copy(isAnalyzing = false, feedbackMessage = "图片解析失败，请重新选择")
                }
            }
        }
    }

    fun captureRootScreenIntoInspector() {
        viewModelScope.launch {
            _inspectorState.update {
                it.copy(isAnalyzing = true, smartDetection = null, feedbackMessage = "正在通过 su screencap 截取屏幕...")
            }
            val result = captureRootBitmapForDetection()
            result.onSuccess { bmp ->
                _inspectorState.update {
                    it.copy(
                        sourceBitmap = bmp,
                        sourceLabel = "Root 实时截屏 (${bmp.width}×${bmp.height})",
                        recognizedTexts = emptyList(),
                        selectedTextElement = null,
                        croppedTemplatePreview = null,
                        savedTemplatePath = null,
                        testMatchResult = null,
                        smartDetection = null,
                        isAnalyzing = false,
                        feedbackMessage = "Root 截屏成功！已启动区域智能特征分析"
                    )
                }
                scheduleSmartDetection(immediate = true)
            }.onFailure { err ->
                _inspectorState.update {
                    it.copy(
                        isAnalyzing = false,
                        feedbackMessage = "Root 截屏失败：${err.message}"
                    )
                }
            }
        }
    }

    suspend fun captureRootBitmapForDetection(): Result<Bitmap> {
        val outFile = File(getApplication<Application>().cacheDir, "inspector_screencap.png")
        return rootShell.captureScreenBitmap(outFile)
    }

    suspend fun buildSampleInspectorBitmap(): Bitmap = withContext(Dispatchers.Default) {
        // Render a crisp composite sample frame containing the reward emblem + Chinese UI text
        val w = 720
        val h = 960
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.parseColor("#F4F7FB"))

        val cardPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }
        canvas.drawRoundRect(48f, 64f, 672f, 896f, 36f, 36f, cardPaint)

        val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            textSize = 44f
            isFakeBoldText = true
        }
        canvas.drawText("每日福利任务中心", 104f, 165f, titlePaint)

        val subPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#475569")
            textSize = 28f
        }
        canvas.drawText("点击下方金色徽章或按钮即可领取奖励", 104f, 222f, subPaint)

        val badge = repository.loadTemplateBitmap("drawable:img_reward_emblem")
        if (badge != null) {
            canvas.drawBitmap(badge, (w - badge.width) / 2f, 310f, null)
            badge.recycle()
        }

        val btnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0099C6")
        }
        canvas.drawRoundRect(140f, 560f, 580f, 668f, 24f, 24f, btnPaint)
        val btnTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 36f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("立即领取奖励", 360f, 626f, btnTextPaint)

        val closePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E2E8F0")
        }
        canvas.drawRoundRect(200f, 720f, 520f, 808f, 20f, 20f, closePaint)
        val closeTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#334155")
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("跳过弹窗", 360f, 774f, closeTextPaint)
        out
    }

    fun loadSampleRewardIntoInspector() {
        viewModelScope.launch {
            val bmp = buildSampleInspectorBitmap()
            _inspectorState.update {
                it.copy(
                    sourceBitmap = bmp,
                    sourceLabel = "示例界面截图 (${bmp.width}×${bmp.height})",
                    recognizedTexts = emptyList(),
                    selectedTextElement = null,
                    croppedTemplatePreview = null,
                    savedTemplatePath = null,
                    testMatchResult = null,
                    smartDetection = null,
                    feedbackMessage = "已加载示例截图，正在自动识别框选区域特征..."
                )
            }
            scheduleSmartDetection(immediate = true)
        }
    }

    fun runInspectorOcr(regionOnly: Boolean = false) {
        val bmp = _inspectorState.value.sourceBitmap ?: return
        val box = _inspectorState.value.cropBox
        viewModelScope.launch {
            _inspectorState.update {
                it.copy(
                    isAnalyzing = true,
                    feedbackMessage = if (regionOnly) "正在对框选区域运行高精度 OCR..." else "正在运行全屏 ML Kit 中英文 OCR..."
                )
            }
            val startMs = System.currentTimeMillis()
            val roiFrame = RegionCropHelper.cropForRegion(
                source = bmp,
                enabled = regionOnly,
                normLeft = box.left,
                normTop = box.top,
                normRight = box.right,
                normBottom = box.bottom
            )
            runCatching {
                try {
                    ocrEngine.recognizeText(roiFrame.bitmap)
                } finally {
                    if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
                }
            }.onSuccess { elements ->
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val lineItems = elements.filter { it.isLine }.map { el ->
                    el.copy(
                        boundingBox = RegionCropHelper.mapRectToFullScreen(
                            el.boundingBox,
                            roiFrame.offsetX,
                            roiFrame.offsetY
                        ),
                        centerX = el.centerX + roiFrame.offsetX,
                        centerY = el.centerY + roiFrame.offsetY
                    )
                }
                val areaPct = (roiFrame.areaRatio * 100).toInt()
                _inspectorState.update {
                    it.copy(
                        recognizedTexts = lineItems,
                        selectedTextElement = lineItems.firstOrNull(),
                        ocrElapsedMs = elapsed,
                        lastScanWasRegion = regionOnly,
                        isAnalyzing = false,
                        feedbackMessage = if (lineItems.isEmpty()) {
                            if (regionOnly) "框选区域内未识别到文字（耗时 ${elapsed}ms），请扩大选区重试"
                            else "未识别到文字，请尝试更清晰的截图"
                        } else {
                            if (regionOnly) {
                                "区域 OCR 完成（耗时 ${elapsed}ms · 扫描面积 ${areaPct}%）：识别到 ${lineItems.size} 处文字"
                            } else {
                                "全屏 OCR 完成（耗时 ${elapsed}ms）：识别到 ${lineItems.size} 处文字区域"
                            }
                        }
                    )
                }
            }.onFailure { err ->
                _inspectorState.update {
                    it.copy(isAnalyzing = false, feedbackMessage = "OCR 识别异常：${err.message}")
                }
            }
        }
    }

    fun selectInspectorTextElement(element: RecognizedTextElement) {
        _inspectorState.update { it.copy(selectedTextElement = element) }
    }

    fun updateCropBox(newBox: CropBoxNorm) {
        _inspectorState.update { it.copy(cropBox = newBox) }
        scheduleSmartDetection(immediate = false)
    }

    fun runSmartDetectionOnInspectorRegion() {
        scheduleSmartDetection(immediate = true)
    }

    private fun scheduleSmartDetection(immediate: Boolean) {
        val bmp = _inspectorState.value.sourceBitmap ?: return
        smartDetectJob?.cancel()
        smartDetectJob = viewModelScope.launch {
            if (!immediate) delay(260L)
            val currentBmp = _inspectorState.value.sourceBitmap ?: return@launch
            val currentBox = _inspectorState.value.cropBox
            _inspectorState.update { it.copy(isSmartDetecting = true) }
            runCatching {
                SmartRegionDetector.analyzeRegion(currentBmp, currentBox, ocrEngine)
            }.onSuccess { res ->
                _inspectorState.update {
                    it.copy(
                        smartDetection = res,
                        isSmartDetecting = false
                    )
                }
            }.onFailure {
                _inspectorState.update { it.copy(isSmartDetecting = false) }
            }
        }
    }

    suspend fun analyzeRegionForDetection(
        bitmap: Bitmap,
        cropBox: CropBoxNorm
    ): SmartDetectionResult = SmartRegionDetector.analyzeRegion(bitmap, cropBox, ocrEngine)

    suspend fun buildSmartTemplateStep(
        bitmap: Bitmap,
        cropBox: CropBoxNorm,
        threshold: Float,
        title: String,
        bindRoi: Boolean
    ): AutomationStepEntity {
        val left = (cropBox.left * bitmap.width).toInt().coerceIn(0, (bitmap.width - 24).coerceAtLeast(0))
        val top = (cropBox.top * bitmap.height).toInt().coerceIn(0, (bitmap.height - 24).coerceAtLeast(0))
        val right = (cropBox.right * bitmap.width).toInt().coerceIn((left + 24).coerceAtMost(bitmap.width), bitmap.width)
        val bottom = (cropBox.bottom * bitmap.height).toInt().coerceIn((top + 24).coerceAtMost(bitmap.height), bitmap.height)
        val cropped = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(
                bitmap,
                left,
                top,
                (right - left).coerceAtLeast(1),
                (bottom - top).coerceAtLeast(1)
            )
        }
        val savedPath = try {
            repository.saveTemplateBitmap(cropped, "smart")
        } finally {
            cropped.recycle()
        }
        return AutomationStepEntity(
            taskId = 0L,
            stepOrder = 0,
            title = title,
            stepType = StepType.IMAGE_TEMPLATE_TAP.name,
            templateImagePath = savedPath,
            matchThreshold = threshold,
            roiEnabled = bindRoi,
            roiLeft = cropBox.left,
            roiTop = cropBox.top,
            roiRight = cropBox.right,
            roiBottom = cropBox.bottom
        )
    }

    fun applySmartDetectionRecommendation(
        taskId: Long?,
        result: SmartDetectionResult,
        bindRegionRoi: Boolean
    ) {
        val src = _inspectorState.value.sourceBitmap ?: return
        val box = _inspectorState.value.cropBox
        viewModelScope.launch {
            if (result.recommendedAlgorithm == RecommendedAlgorithm.OCR_TEXT &&
                !result.detectedText.isNullOrBlank()
            ) {
                repository.appendQuickOcrStepToTask(
                    taskId = taskId,
                    targetText = result.detectedText,
                    title = result.suggestedStepTitle,
                    textMatchMode = result.suggestedTextMatchMode.name,
                    roiEnabled = bindRegionRoi,
                    roiLeft = box.left,
                    roiTop = box.top,
                    roiRight = box.right,
                    roiBottom = box.bottom
                )
                _inspectorState.update {
                    it.copy(
                        feedbackMessage = "已采纳智能推荐（${result.featureKind.label} → OCR ${result.suggestedTextMatchMode.label}）：已生成「${result.suggestedStepTitle}」"
                    )
                }
            } else {
                _inspectorState.update { it.copy(isAnalyzing = true) }
                val left = (box.left * src.width).toInt().coerceIn(0, (src.width - 24).coerceAtLeast(0))
                val top = (box.top * src.height).toInt().coerceIn(0, (src.height - 24).coerceAtLeast(0))
                val right = (box.right * src.width).toInt().coerceIn((left + 24).coerceAtMost(src.width), src.width)
                val bottom = (box.bottom * src.height).toInt().coerceIn((top + 24).coerceAtMost(src.height), src.height)
                val cropped = withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(
                        src,
                        left,
                        top,
                        (right - left).coerceAtLeast(1),
                        (bottom - top).coerceAtLeast(1)
                    )
                }
                val savedPath = repository.saveTemplateBitmap(cropped, "smart")
                val roi = _inspectorState.value.searchRoi ?: box
                val roiFrame = if (bindRegionRoi) {
                    RegionCropHelper.cropForRegion(
                        source = src,
                        enabled = true,
                        normLeft = roi.left,
                        normTop = roi.top,
                        normRight = roi.right,
                        normBottom = roi.bottom
                    )
                } else {
                    RegionCropHelper.cropForRegion(src, false, 0f, 0f, 1f, 1f)
                }
                val rawMatch = try {
                    templateMatcher.findTemplate(roiFrame.bitmap, cropped, result.suggestedTemplateThreshold)
                } finally {
                    if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
                }
                val mappedMatch = rawMatch.copy(
                    boundingBox = RegionCropHelper.mapRectToFullScreen(
                        rawMatch.boundingBox,
                        roiFrame.offsetX,
                        roiFrame.offsetY
                    ),
                    centerX = rawMatch.centerX + roiFrame.offsetX,
                    centerY = rawMatch.centerY + roiFrame.offsetY
                )
                repository.appendQuickImageStepToTask(
                    taskId = taskId,
                    templatePath = savedPath,
                    threshold = result.suggestedTemplateThreshold,
                    title = result.suggestedStepTitle,
                    roiEnabled = bindRegionRoi,
                    roiLeft = roi.left,
                    roiTop = roi.top,
                    roiRight = roi.right,
                    roiBottom = roi.bottom
                )
                _inspectorState.update {
                    it.copy(
                        croppedTemplatePreview = cropped,
                        savedTemplatePath = savedPath,
                        testMatchResult = mappedMatch,
                        isAnalyzing = false,
                        feedbackMessage = "已采纳智能推荐（${result.featureKind.label} → 图像模板匹配 ${(result.suggestedTemplateThreshold * 100).toInt()}%）：已生成步骤"
                    )
                }
            }
        }
    }

    fun applyCropBoxAsSearchRoi(threshold: Float = 0.75f) {
        val box = _inspectorState.value.cropBox
        _inspectorState.update { it.copy(searchRoi = box) }
        val preview = _inspectorState.value.croppedTemplatePreview
        val src = _inspectorState.value.sourceBitmap
        if (preview != null && src != null) {
            viewModelScope.launch {
                _inspectorState.update { it.copy(isAnalyzing = true) }
                val roiFrame = RegionCropHelper.cropForRegion(
                    source = src,
                    enabled = true,
                    normLeft = box.left,
                    normTop = box.top,
                    normRight = box.right,
                    normBottom = box.bottom
                )
                val match = try {
                    templateMatcher.findTemplate(roiFrame.bitmap, preview, threshold)
                } finally {
                    if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
                }
                val mappedMatch = match.copy(
                    boundingBox = RegionCropHelper.mapRectToFullScreen(
                        match.boundingBox,
                        roiFrame.offsetX,
                        roiFrame.offsetY
                    ),
                    centerX = match.centerX + roiFrame.offsetX,
                    centerY = match.centerY + roiFrame.offsetY
                )
                _inspectorState.update {
                    it.copy(
                        testMatchResult = mappedMatch,
                        isAnalyzing = false,
                        feedbackMessage = "已锁定搜索区域（面积 ${(roiFrame.areaRatio * 100).toInt()}%）：回测相似度 ${(mappedMatch.similarity * 100).toInt()}%，耗时 ${mappedMatch.elapsedMs}ms"
                    )
                }
            }
        } else {
            _inspectorState.update {
                it.copy(feedbackMessage = "已将当前框选设定为限定搜索区域 (ROI)，保存步骤时将一并生效")
            }
        }
    }

    fun clearSearchRoi() {
        _inspectorState.update {
            it.copy(searchRoi = null, feedbackMessage = "已清除搜索区域限制，恢复全屏匹配")
        }
    }

    fun cropAndSaveTemplateAndTest(threshold: Float = 0.75f) {
        val src = _inspectorState.value.sourceBitmap ?: return
        val box = _inspectorState.value.cropBox
        val activeSearchRoi = _inspectorState.value.searchRoi
        viewModelScope.launch {
            _inspectorState.update { it.copy(isAnalyzing = true) }
            val left = (box.left * src.width).toInt().coerceIn(0, src.width - 24)
            val top = (box.top * src.height).toInt().coerceIn(0, src.height - 24)
            val right = (box.right * src.width).toInt().coerceIn(left + 24, src.width)
            val bottom = (box.bottom * src.height).toInt().coerceIn(top + 24, src.height)

            val cropped = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(src, left, top, right - left, bottom - top)
            }
            val savedPath = repository.saveTemplateBitmap(cropped, "crop")
            val roiFrame = if (activeSearchRoi != null) {
                RegionCropHelper.cropForRegion(
                    source = src,
                    enabled = true,
                    normLeft = activeSearchRoi.left,
                    normTop = activeSearchRoi.top,
                    normRight = activeSearchRoi.right,
                    normBottom = activeSearchRoi.bottom
                )
            } else {
                RegionCropHelper.cropForRegion(src, false, 0f, 0f, 1f, 1f)
            }
            val rawMatch = try {
                templateMatcher.findTemplate(roiFrame.bitmap, cropped, threshold)
            } finally {
                if (roiFrame.ownsBitmap) roiFrame.bitmap.recycle()
            }
            val mappedMatch = rawMatch.copy(
                boundingBox = RegionCropHelper.mapRectToFullScreen(
                    rawMatch.boundingBox,
                    roiFrame.offsetX,
                    roiFrame.offsetY
                ),
                centerX = rawMatch.centerX + roiFrame.offsetX,
                centerY = rawMatch.centerY + roiFrame.offsetY
            )

            _inspectorState.update {
                it.copy(
                    croppedTemplatePreview = cropped,
                    savedTemplatePath = savedPath,
                    testMatchResult = mappedMatch,
                    isAnalyzing = false,
                    feedbackMessage = "模板已保存（${cropped.width}×${cropped.height}）！回测匹配度 ${(mappedMatch.similarity * 100).toInt()}%，耗时 ${mappedMatch.elapsedMs}ms"
                )
            }
        }
    }

    fun saveOcrStepFromInspector(taskId: Long?, text: String, useRegionRoi: Boolean) {
        val box = _inspectorState.value.cropBox
        viewModelScope.launch {
            repository.appendQuickOcrStepToTask(
                taskId = taskId,
                targetText = text,
                roiEnabled = useRegionRoi,
                roiLeft = box.left,
                roiTop = box.top,
                roiRight = box.right,
                roiBottom = box.bottom
            )
            _inspectorState.update {
                it.copy(
                    feedbackMessage = if (useRegionRoi) {
                        "已保存「$text」的区域 OCR 点击步骤（仅在框选区域内识别）"
                    } else {
                        "已将「$text」的 OCR 点击步骤保存到任务库"
                    }
                )
            }
        }
    }

    fun saveImageStepFromInspector(
        taskId: Long?,
        templatePath: String,
        threshold: Float,
        useRegionRoi: Boolean
    ) {
        val roi = _inspectorState.value.searchRoi ?: _inspectorState.value.cropBox
        viewModelScope.launch {
            repository.appendQuickImageStepToTask(
                taskId = taskId,
                templatePath = templatePath,
                threshold = threshold,
                roiEnabled = useRegionRoi,
                roiLeft = roi.left,
                roiTop = roi.top,
                roiRight = roi.right,
                roiBottom = roi.bottom
            )
            _inspectorState.update {
                it.copy(
                    feedbackMessage = if (useRegionRoi) {
                        "已保存图像模板点击步骤（已绑定区域搜索加速）"
                    } else {
                        "已将图像模板点击步骤保存到任务库"
                    }
                )
            }
        }
    }

    // --- Task CRUD ---

    fun toggleTaskEnabled(taskId: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setTaskEnabled(taskId, enabled) }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch { repository.deleteTask(taskId) }
    }

    fun clearExecutionLogs() {
        viewModelScope.launch { repository.clearLogs() }
    }

    fun saveEditedTask(
        task: AutomationTaskEntity,
        steps: List<AutomationStepEntity>,
        onSaved: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = repository.saveTask(task, steps)
            onSaved(id)
        }
    }

    // --- JSON Export / Import ---

    fun requestExportTasks(taskId: Long? = null) {
        viewModelScope.launch {
            _jsonTransferState.update { it.copy(isBusy = true, statusBanner = null) }
            repository.exportTasksAsJson(taskId)
                .onSuccess { payload ->
                    _jsonTransferState.update {
                        it.copy(
                            activeExportPayload = payload,
                            isBusy = false
                        )
                    }
                }
                .onFailure { err ->
                    _jsonTransferState.update {
                        it.copy(
                            isBusy = false,
                            isErrorBanner = true,
                            statusBanner = "导出失败：${err.message ?: "未知错误"}"
                        )
                    }
                }
        }
    }

    fun dismissExportDialog() {
        _jsonTransferState.update { it.copy(activeExportPayload = null) }
    }

    fun openImportDialog() {
        _jsonTransferState.update { it.copy(showImportDialog = true) }
    }

    fun dismissImportDialog() {
        _jsonTransferState.update { it.copy(showImportDialog = false) }
    }

    fun confirmImportJson(rawJson: String, policy: ImportConflictPolicy) {
        viewModelScope.launch {
            _jsonTransferState.update { it.copy(isBusy = true) }
            repository.importTasksFromJson(rawJson, policy)
                .onSuccess { summary ->
                    val parts = buildList {
                        if (summary.insertedCount > 0) add("新增 ${summary.insertedCount} 个任务")
                        if (summary.updatedCount > 0) add("覆盖更新 ${summary.updatedCount} 个任务")
                        add("共 ${summary.stepCount} 步")
                        if (summary.restoredTemplatesCount > 0) {
                            add("还原 ${summary.restoredTemplatesCount} 张图像模板")
                        }
                    }
                    _jsonTransferState.update {
                        it.copy(
                            showImportDialog = false,
                            isBusy = false,
                            isErrorBanner = false,
                            statusBanner = "JSON 导入成功：${parts.joinToString("，")}"
                        )
                    }
                }
                .onFailure { err ->
                    _jsonTransferState.update {
                        it.copy(
                            isBusy = false,
                            isErrorBanner = true,
                            statusBanner = "导入失败：${err.message ?: "JSON 格式无效"}"
                        )
                    }
                }
        }
    }

    fun notifyTransferStatus(message: String, isError: Boolean = false) {
        _jsonTransferState.update {
            it.copy(statusBanner = message, isErrorBanner = isError)
        }
    }

    fun clearTransferBanner() {
        _jsonTransferState.update { it.copy(statusBanner = null, isErrorBanner = false) }
    }

    // --- Virtual Replay Test Sandbox (Dry-Run Matching Accuracy Verification) ---

    fun importVirtualTestVideo(uri: Uri) {
        lastVirtualVideoUri = uri
        val intervalMs = _virtualSandboxState.value.videoSampleIntervalMs
        virtualVerifyJob?.cancel()
        viewModelScope.launch {
            _virtualSandboxState.update {
                it.copy(
                    isLoadingMedia = true,
                    isVerifying = false,
                    report = null,
                    selectedResultStepOrder = null,
                    statusBanner = "正在按 ${intervalMs}ms 间隔解析模拟录屏关键帧..."
                )
            }
            VirtualReplayTestEngine.extractFramesFromVideo(
                context = getApplication(),
                uri = uri,
                intervalMs = intervalMs
            ).onSuccess { frames ->
                _virtualSandboxState.update {
                    it.copy(
                        sourceType = MediaSequenceSourceType.VIDEO_RECORDING,
                        sourceTitle = "模拟录屏（已抽取 ${frames.size} 帧）",
                        frames = frames,
                        selectedFrameIndex = 0,
                        isLoadingMedia = false,
                        report = null,
                        selectedResultStepOrder = null,
                        statusBanner = "录屏已解析 ${frames.size} 个时间帧，可点击“开始无害化匹配验证”评测脚本准确性"
                    )
                }
            }.onFailure { err ->
                _virtualSandboxState.update {
                    it.copy(
                        isLoadingMedia = false,
                        statusBanner = "录屏解析失败：${err.message ?: "请检查视频格式"}"
                    )
                }
            }
        }
    }

    fun appendVirtualTestImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lastVirtualVideoUri = null
        virtualVerifyJob?.cancel()
        viewModelScope.launch {
            val currentFrames = if (_virtualSandboxState.value.sourceType == MediaSequenceSourceType.IMAGE_SEQUENCE) {
                _virtualSandboxState.value.frames
            } else {
                emptyList()
            }
            _virtualSandboxState.update {
                it.copy(
                    isLoadingMedia = true,
                    isVerifying = false,
                    statusBanner = "正在解码导入 ${uris.size} 张截图序列..."
                )
            }
            VirtualReplayTestEngine.loadFramesFromImageUris(
                context = getApplication(),
                uris = uris,
                existingFrames = currentFrames
            ).onSuccess { merged ->
                _virtualSandboxState.update {
                    it.copy(
                        sourceType = MediaSequenceSourceType.IMAGE_SEQUENCE,
                        sourceTitle = "截图序列（共 ${merged.size} 帧）",
                        frames = merged,
                        selectedFrameIndex = it.selectedFrameIndex.coerceIn(0, (merged.size - 1).coerceAtLeast(0)),
                        isLoadingMedia = false,
                        report = null,
                        selectedResultStepOrder = null,
                        statusBanner = "已加载 ${merged.size} 张截图序列，可调整帧顺序后开始匹配验证"
                    )
                }
            }.onFailure { err ->
                _virtualSandboxState.update {
                    it.copy(
                        isLoadingMedia = false,
                        statusBanner = "截图导入失败：${err.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun loadSampleVirtualTestSequence(sceneHint: TargetSceneType) {
        lastVirtualVideoUri = null
        virtualVerifyJob?.cancel()
        viewModelScope.launch {
            _virtualSandboxState.update {
                it.copy(
                    isLoadingMedia = true,
                    isVerifying = false,
                    report = null,
                    selectedResultStepOrder = null,
                    statusBanner = "正在生成示例测试帧序列..."
                )
            }
            val frames = VirtualReplayTestEngine.buildSampleTestSequence(repository, sceneHint)
            _virtualSandboxState.update {
                it.copy(
                    sourceType = MediaSequenceSourceType.BUILTIN_SAMPLE,
                    sourceTitle = "内置示例序列（${frames.size} 帧 · ${sceneHint.label}）",
                    frames = frames,
                    selectedFrameIndex = 0,
                    isLoadingMedia = false,
                    report = null,
                    selectedResultStepOrder = null,
                    statusBanner = "已载入 ${frames.size} 帧示例场景，可立即验证脚本匹配准确性"
                )
            }
        }
    }

    fun selectVirtualTestFrame(index: Int) {
        val frames = _virtualSandboxState.value.frames
        if (frames.isEmpty()) return
        _virtualSandboxState.update {
            it.copy(selectedFrameIndex = index.coerceIn(0, frames.lastIndex))
        }
    }

    fun removeVirtualTestFrame(index: Int) {
        val current = _virtualSandboxState.value.frames
        if (index !in current.indices) return
        val updated = current.filterIndexed { i, _ -> i != index }
            .mapIndexed { newIdx, frame -> frame.copy(index = newIdx) }
        if (updated.isEmpty()) {
            clearVirtualTestFrames()
            return
        }
        _virtualSandboxState.update {
            it.copy(
                frames = updated,
                sourceTitle = "${it.sourceType.label}（共 ${updated.size} 帧）",
                selectedFrameIndex = it.selectedFrameIndex.coerceIn(0, updated.lastIndex),
                report = null,
                selectedResultStepOrder = null,
                statusBanner = "已移除第 ${index + 1} 帧，剩余 ${updated.size} 帧待验证"
            )
        }
    }

    fun moveVirtualTestFrame(fromIndex: Int, toIndex: Int) {
        val current = _virtualSandboxState.value.frames.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        val reindexed = current.mapIndexed { idx, frame -> frame.copy(index = idx) }
        _virtualSandboxState.update {
            it.copy(
                frames = reindexed,
                selectedFrameIndex = toIndex,
                report = null,
                selectedResultStepOrder = null,
                statusBanner = "已调整测试帧顺序（帧 #${fromIndex + 1} -> #${toIndex + 1}）"
            )
        }
    }

    fun clearVirtualTestFrames() {
        lastVirtualVideoUri = null
        virtualVerifyJob?.cancel()
        _virtualSandboxState.update {
            VirtualSandboxUiState(
                videoSampleIntervalMs = it.videoSampleIntervalMs,
                verificationMode = it.verificationMode
            )
        }
    }

    fun setVirtualVerificationMode(mode: ReplayVerificationMode) {
        _virtualSandboxState.update {
            it.copy(
                verificationMode = mode,
                report = null,
                selectedResultStepOrder = null
            )
        }
    }

    fun setVirtualVideoSampleInterval(intervalMs: Long) {
        val safeInterval = intervalMs.coerceIn(300L, 5000L)
        _virtualSandboxState.update { it.copy(videoSampleIntervalMs = safeInterval) }
        val videoUri = lastVirtualVideoUri
        if (videoUri != null && _virtualSandboxState.value.sourceType == MediaSequenceSourceType.VIDEO_RECORDING) {
            importVirtualTestVideo(videoUri)
        }
    }

    fun inspectVirtualStepResult(result: VirtualStepMatchResult) {
        val frames = _virtualSandboxState.value.frames
        val targetFrame = result.matchedFrameIndex?.takeIf { it in frames.indices }
        _virtualSandboxState.update { state ->
            state.copy(
                selectedFrameIndex = targetFrame ?: state.selectedFrameIndex,
                selectedResultStepOrder = result.stepOrder
            )
        }
    }

    fun stopVirtualVerification() {
        virtualVerifyJob?.cancel()
        virtualVerifyJob = null
        _virtualSandboxState.update {
            it.copy(
                isVerifying = false,
                verifyProgressLabel = "",
                statusBanner = "已中止虚拟回放验证"
            )
        }
    }

    fun runVirtualScriptVerification(taskWithSteps: TaskWithSteps) {
        val frames = _virtualSandboxState.value.frames
        if (frames.isEmpty()) {
            _virtualSandboxState.update {
                it.copy(statusBanner = "请先上传模拟录屏、导入截图序列或加载示例帧")
            }
            return
        }
        val mode = _virtualSandboxState.value.verificationMode
        val focusedIdx = _virtualSandboxState.value.selectedFrameIndex

        virtualVerifyJob?.cancel()
        virtualVerifyJob = viewModelScope.launch {
            _virtualSandboxState.update {
                it.copy(
                    isVerifying = true,
                    verifyProgressCurrent = 0,
                    verifyProgressTotal = taskWithSteps.sortedSteps.size.coerceAtLeast(1),
                    verifyProgressLabel = "初始化虚拟回放核验（不触发触控与写入日志）...",
                    report = null,
                    selectedResultStepOrder = null,
                    statusBanner = "正在「${mode.label}」模式下校验「${taskWithSteps.task.name}」..."
                )
            }
            try {
                val report = VirtualReplayTestEngine.verifyScriptOnFrames(
                    taskWithSteps = taskWithSteps,
                    frames = frames,
                    mode = mode,
                    focusedFrameIndex = focusedIdx,
                    repository = repository,
                    ocrEngine = ocrEngine,
                    templateMatcher = templateMatcher,
                    onProgress = { cur, total, label ->
                        _virtualSandboxState.update {
                            it.copy(
                                verifyProgressCurrent = cur,
                                verifyProgressTotal = total,
                                verifyProgressLabel = label
                            )
                        }
                    }
                )
                val pct = (report.accuracyRate * 100f).toInt()
                val firstHighlight = report.results.firstOrNull { !it.isSkippedWait && it.matchedFrameIndex != null }
                _virtualSandboxState.update {
                    it.copy(
                        isVerifying = false,
                        verifyProgressLabel = "",
                        report = report,
                        selectedFrameIndex = firstHighlight?.matchedFrameIndex ?: it.selectedFrameIndex,
                        selectedResultStepOrder = firstHighlight?.stepOrder,
                        statusBanner = "虚拟核验完成（未实际执行）：命中 ${report.matchedStepCount}/${report.verifiableStepCount} 步 · 准确率 $pct% · 平均 ${report.avgMatchMs}ms"
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Exception) {
                _virtualSandboxState.update {
                    it.copy(
                        isVerifying = false,
                        verifyProgressLabel = "",
                        statusBanner = "虚拟核验异常：${err.message ?: "未知错误"}"
                    )
                }
            }
        }
    }
}
