package com.example.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.AutomationRepository
import com.example.data.AutomationStepEntity
import com.example.data.AutomationTaskEntity
import com.example.data.BranchConditionType
import com.example.data.ExecutionClickEventEntity
import com.example.data.LoopMode
import com.example.data.StepType
import com.example.data.TextMatchMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class RootRunnerState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val taskId: Long? = null,
    val taskName: String = "",
    val statusMessage: String = "就绪",
    val currentStepIndex: Int = -1,
    val totalSteps: Int = 0,
    val logs: List<String> = emptyList()
)

object RootExecutionBus {
    private val _state = MutableStateFlow(RootRunnerState())
    val state: StateFlow<RootRunnerState> = _state.asStateFlow()

    fun update(transform: (RootRunnerState) -> RootRunnerState) {
        _state.value = transform(_state.value)
    }

    fun appendLog(line: String) {
        _state.value = _state.value.copy(
            logs = (_state.value.logs + line).takeLast(80)
        )
    }
}

class RootAutomationService : Service() {

    companion object {
        const val ACTION_START_TASK = "com.example.action.START_ROOT_TASK"
        const val ACTION_PAUSE_TASK = "com.example.action.PAUSE_ROOT_TASK"
        const val ACTION_RESUME_TASK = "com.example.action.RESUME_ROOT_TASK"
        const val ACTION_STOP_TASK = "com.example.action.STOP_ROOT_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_COUNTDOWN_SEC = "extra_countdown_sec"
        private const val CHANNEL_ID = "root_automation_channel"
        private const val NOTIFICATION_ID = 4102

        fun startRootTask(context: Context, taskId: Long, countdownSec: Int = 3) {
            val intent = Intent(context, RootAutomationService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_COUNTDOWN_SEC, countdownSec)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseRootTask(context: Context) {
            val intent = Intent(context, RootAutomationService::class.java).apply {
                action = ACTION_PAUSE_TASK
            }
            context.startService(intent)
        }

        fun resumeRootTask(context: Context) {
            val intent = Intent(context, RootAutomationService::class.java).apply {
                action = ACTION_RESUME_TASK
            }
            context.startService(intent)
        }

        fun stopRootTask(context: Context) {
            val intent = Intent(context, RootAutomationService::class.java).apply {
                action = ACTION_STOP_TASK
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var executionJob: Job? = null
    private var rootDiagnosticJob: Job? = null

    private val rootShell = RootShellExecutor()
    private val ocrEngine = OcrRecognitionEngine()
    private val templateMatcher = ImageTemplateMatcher()
    private val preloadEngine by lazy { TaskPreloadEngine(ocrEngine, templateMatcher) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                stopExecutionAndSelf("已手动终止 Root 自动化任务")
                return START_NOT_STICKY
            }

            ACTION_PAUSE_TASK -> {
                if (RootExecutionBus.state.value.isRunning && !RootExecutionBus.state.value.isPaused) {
                    RootExecutionBus.update { it.copy(isPaused = true, statusMessage = "任务已暂停") }
                    RootExecutionBus.appendLog("[暂停] 用户在控制台暂停执行")
                    updateNotification("已暂停 · 点击返回或从悬浮窗继续")
                }
                return START_NOT_STICKY
            }

            ACTION_RESUME_TASK -> {
                if (RootExecutionBus.state.value.isRunning && RootExecutionBus.state.value.isPaused) {
                    RootExecutionBus.update { it.copy(isPaused = false, statusMessage = "恢复执行中...") }
                    RootExecutionBus.appendLog("[恢复] 继续执行剩余任务流")
                }
                return START_NOT_STICKY
            }

            ACTION_START_TASK -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                val countdown = intent.getIntExtra(EXTRA_COUNTDOWN_SEC, 3)
                if (taskId <= 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification("准备启动 Root 自动化引擎...")
                launchRootTask(taskId, countdown)
            }
        }
        return START_NOT_STICKY
    }

    private fun launchRootTask(taskId: Long, countdownSec: Int) {
        executionJob?.cancel()
        executionJob = serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val repository = AutomationRepository(applicationContext, db.automationDao())
            val taskWithSteps = repository.getTaskWithSteps(taskId)
            if (taskWithSteps == null) {
                stopExecutionAndSelf("未找到指定任务")
                return@launch
            }

            val task = taskWithSteps.task
            val steps = taskWithSteps.sortedSteps
            if (steps.isEmpty() || steps.none { it.isEnabled }) {
                stopExecutionAndSelf("所有步骤或节点容器均已禁用，无法运行")
                return@launch
            }
            val capFile = File(cacheDir, "root_screencap.png")

            RootExecutionBus.update {
                RootRunnerState(
                    isRunning = true,
                    taskId = task.id,
                    taskName = task.name,
                    statusMessage = "正在校验 Root 权限...",
                    totalSteps = steps.size,
                    logs = listOf("[Root] 初始化任务：${task.name}（共 ${steps.size} 步，循环 ${task.repeatCount} 次）")
                )
            }

            val rootStatus = rootShell.checkRootAccess()
            if (!rootStatus.hasRootAccess) {
                val now = System.currentTimeMillis()
                RootExecutionBus.appendLog("[错误] ${rootStatus.message}")
                val failEvent = ExecutionClickEventEntity(
                    taskId = task.id,
                    taskName = task.name,
                    roundIndex = 0,
                    stepOrder = 0,
                    stepTitle = "Root 环境校验",
                    executionMode = "ROOT",
                    actionType = StepType.COORDINATE_TAP.name,
                    recognizedTarget = "su 权限检测",
                    success = false,
                    failureReason = "Root 权限校验失败：${rootStatus.message}",
                    timestamp = now
                )
                repository.recordRun(
                    taskId = task.id,
                    taskName = task.name,
                    executionMode = "ROOT",
                    startedAt = now,
                    durationMs = 0L,
                    matchedSteps = 0,
                    totalSteps = steps.size,
                    clicksTriggered = 0,
                    success = false,
                    summary = "Root 权限校验失败：${rootStatus.message}",
                    events = listOf(failEvent)
                )
                TaskNotificationManager.notifyTaskFailed(
                    context = applicationContext,
                    taskId = task.id,
                    taskName = task.name,
                    executionMode = "ROOT",
                    reason = "Root 权限校验失败：${rootStatus.message}",
                    taskNotifyEnabled = task.notifyOnFailure,
                    taskCustomNote = task.customNotifyNote
                )
                stopExecutionAndSelf("无 Root 权限，任务已终止")
                return@launch
            }

            RootExecutionBus.appendLog("[Root] 权限校验通过：${rootStatus.uidOutput}")
            val preloadCapFile = File(cacheDir, "root_screencap_preload.png")
            preloadEngine.prepareTaskInBackground(task.name, steps, repository)
            if (PreloadTelemetryBus.state.value.isEnabled) {
                RootExecutionBus.appendLog("[预加载] 后台预热 OCR 模型与下一步图像/正则特征")
            }

            for (sec in countdownSec downTo 1) {
                if (!isActive) return@launch
                val msg = "${sec} 秒后开始截屏识图，请切换到目标界面..."
                RootExecutionBus.update { it.copy(statusMessage = msg) }
                updateNotification("${task.name} · $msg")
                RootExecutionBus.appendLog("[倒计时] $msg")
                delay(1000L)
            }

            val startedAt = System.currentTimeMillis()
            val loopMode = runCatching { LoopMode.valueOf(task.loopMode) }.getOrDefault(LoopMode.FIXED_COUNT)
            val maxRounds = if (loopMode == LoopMode.FIXED_COUNT) task.repeatCount.coerceAtLeast(1) else Int.MAX_VALUE
            var round = 0
            var matchedSteps = 0
            var totalStepsExecuted = 0
            var clicksTriggered = 0
            var allSucceeded = true
            var abortedByManualBranch = false
            var stopReason = ""
            val clickEvents = mutableListOf<ExecutionClickEventEntity>()
            var runPersisted = false

            try {
                while (isActive && round < maxRounds && !abortedByManualBranch) {
                    waitWhilePaused()
                    if (!isActive) break

                    val preCheck = LoopControlEvaluator.evaluateStopCondition(
                        task = task,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        anyStepFailedInRound = false,
                        captureFrame = { rootShell.captureScreenBitmap(capFile).getOrNull() },
                        ocrEngine = ocrEngine,
                        templateMatcher = templateMatcher,
                        repository = repository
                    )
                    if (preCheck.shouldStop) {
                        stopReason = preCheck.reason
                        RootExecutionBus.appendLog("[条件停止] $stopReason")
                        break
                    }

                    round++
                    val roundLabel = if (loopMode == LoopMode.FIXED_COUNT) {
                        "第 $round/$maxRounds 轮"
                    } else {
                        "第 $round 轮 (${loopMode.label})"
                    }
                    RootExecutionBus.appendLog("[轮次] $roundLabel 开始")

                    var anyStepMissed = false
                    for ((index, step) in steps.withIndex()) {
                        waitWhilePaused()
                        if (!isActive) break
                        if (!step.isEnabled) {
                            val groupTag = if (step.groupName.isNotBlank()) "（容器：${step.groupName}）" else ""
                            RootExecutionBus.appendLog("[跳过] 已禁用节点「${step.title}」$groupTag")
                            continue
                        }
                        RootExecutionBus.update {
                            it.copy(
                                currentStepIndex = index,
                                statusMessage = "$roundLabel · 步骤 ${index + 1}/${steps.size}：${step.title}"
                            )
                        }
                        updateNotification("$roundLabel · ${step.title}")

                        totalStepsExecuted++
                        val upcomingStep = steps.drop(index + 1).firstOrNull { it.isEnabled }
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
                        var stepOk: StepOutcome
                        while (true) {
                            stepOk = executeRootStep(
                                task = task,
                                roundIndex = round,
                                step = step,
                                upcomingStep = upcomingStep,
                                repository = repository,
                                capFile = capFile,
                                preloadCapFile = preloadCapFile
                            )
                            if (stepOk.matched || stepOk.abortedByUser || retryAttempt >= maxRetries || !isActive) {
                                if (retryAttempt > 0 && stepOk.matched) {
                                    stepOk = stepOk.copy(
                                        event = stepOk.event?.copy(
                                            recognizedTarget = "${stepOk.event?.recognizedTarget}（第${retryAttempt}次重试命中）"
                                        )
                                    )
                                } else if (retryAttempt > 0 && !stepOk.matched) {
                                    stepOk = stepOk.copy(
                                        event = stepOk.event?.copy(
                                            failureReason = "${stepOk.event?.failureReason}（已自动重试 ${retryAttempt}/${maxRetries} 次仍失败）"
                                        )
                                    )
                                }
                                break
                            }
                            retryAttempt++
                            stepOk.event?.let { failedEv ->
                                clickEvents.add(
                                    failedEv.copy(
                                        failureReason = "${failedEv.failureReason}（将间隔 ${retryInterval}ms 第 $retryAttempt/$maxRetries 次重试）"
                                    )
                                )
                            }
                            val retryMsg = "$roundLabel · ${step.title} 失败，${retryInterval}ms 后第 $retryAttempt/$maxRetries 次重试"
                            RootExecutionBus.appendLog(
                                "[自动重试] 「${step.title}」执行失败，等待 ${retryInterval}ms 后进行第 $retryAttempt/$maxRetries 次重试"
                            )
                            RootExecutionBus.update { it.copy(statusMessage = retryMsg) }
                            updateNotification(retryMsg)
                            delay(retryInterval)
                            waitWhilePaused()
                            if (!isActive) break
                        }
                        stepOk.event?.let { clickEvents.add(it) }
                        if (stepOk.abortedByUser) {
                            allSucceeded = false
                            anyStepMissed = true
                            abortedByManualBranch = true
                            stopReason = "人工确认分支终止执行"
                            break
                        }
                        if (stepOk.matched) matchedSteps++ else {
                            allSucceeded = false
                            anyStepMissed = true
                        }
                        if (stepOk.clicked) clicksTriggered++

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
                                    captureBackgroundFrame = {
                                        rootShell.captureScreenBitmap(preloadCapFile).getOrNull()
                                    }
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
                        captureFrame = { rootShell.captureScreenBitmap(capFile).getOrNull() },
                        ocrEngine = ocrEngine,
                        templateMatcher = templateMatcher,
                        repository = repository
                    )
                    if (postCheck.shouldStop) {
                        stopReason = postCheck.reason
                        RootExecutionBus.appendLog("[条件停止] $stopReason")
                        break
                    }

                    if (round < maxRounds) {
                        delay(task.loopIntervalMs.coerceAtLeast(200L))
                    }
                }

                val duration = System.currentTimeMillis() - startedAt
                val reasonText = if (stopReason.isNotBlank()) "（$stopReason）" else ""
                val summary = "Root 执行完成 $round 轮$reasonText：匹配 $matchedSteps/$totalStepsExecuted 步，触发点击 $clicksTriggered 次"
                val runSucceeded = (allSucceeded || stopReason.isNotBlank()) && !abortedByManualBranch
                RootExecutionBus.appendLog("[完成] $summary (${duration}ms)")
                repository.recordRun(
                    taskId = task.id,
                    taskName = task.name,
                    executionMode = "ROOT",
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
                        context = applicationContext,
                        taskId = task.id,
                        taskName = task.name,
                        executionMode = "ROOT",
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
                        context = applicationContext,
                        taskId = task.id,
                        taskName = task.name,
                        executionMode = "ROOT",
                        reason = lastFailure,
                        taskNotifyEnabled = task.notifyOnFailure,
                        taskCustomNote = task.customNotifyNote
                    )
                }
                stopExecutionAndSelf(summary)
            } catch (ce: CancellationException) {
                if (!runPersisted && clickEvents.isNotEmpty()) {
                    withContext(NonCancellable) {
                        val duration = System.currentTimeMillis() - startedAt
                        repository.recordRun(
                            taskId = task.id,
                            taskName = task.name,
                            executionMode = "ROOT",
                            startedAt = startedAt,
                            durationMs = duration,
                            matchedSteps = matchedSteps,
                            totalSteps = totalStepsExecuted.coerceAtLeast(1),
                            clicksTriggered = clicksTriggered,
                            success = false,
                            summary = "Root 任务被手动终止（已执行 $round 轮，点击 $clicksTriggered 次）",
                            events = clickEvents
                        )
                    }
                }
                throw ce
            } catch (err: Exception) {
                val errMsg = err.message ?: "Root 运行异常"
                RootExecutionBus.appendLog("[异常] $errMsg")
                TaskNotificationManager.notifyTaskFailed(
                    context = applicationContext,
                    taskId = task.id,
                    taskName = task.name,
                    executionMode = "ROOT",
                    reason = errMsg,
                    taskNotifyEnabled = task.notifyOnFailure,
                    taskCustomNote = task.customNotifyNote
                )
                stopExecutionAndSelf("Root 执行异常：$errMsg")
            }
        }
    }

    private data class StepOutcome(
        val matched: Boolean,
        val clicked: Boolean,
        val abortedByUser: Boolean = false,
        val event: ExecutionClickEventEntity? = null
    )

    private suspend fun executeRootStep(
        task: AutomationTaskEntity,
        roundIndex: Int,
        step: AutomationStepEntity,
        upcomingStep: AutomationStepEntity?,
        repository: AutomationRepository,
        capFile: File,
        preloadCapFile: File
    ): StepOutcome {
        val taskId = task.id
        val taskName = task.name
        val type = runCatching { StepType.valueOf(step.stepType) }.getOrDefault(StepType.WAIT_DELAY)
        if (type == StepType.WAIT_DELAY) {
            RootExecutionBus.appendLog("[延时] 等待 ${step.delayAfterMs}ms")
            return StepOutcome(matched = true, clicked = false, event = null)
        }

        if (type == StepType.MANUAL_CONFIRM_BRANCH) {
            val branchType = runCatching { BranchConditionType.valueOf(step.branchConditionType) }
                .getOrDefault(BranchConditionType.ALWAYS_PAUSE)
            var shouldPause = true
            var triggerReason = "流程到达人工确认节点「${step.title}」，请处理或确认后继续"

            if (branchType != BranchConditionType.ALWAYS_PAUSE && step.targetText.isNotBlank()) {
                val capResult = rootShell.captureScreenBitmap(capFile)
                val screenBmp = capResult.getOrNull()
                if (screenBmp != null) {
                    val found = try {
                        val roiFrame = RegionCropHelper.cropForRegion(
                            source = screenBmp,
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
                        screenBmp.recycle()
                    }
                    when (branchType) {
                        BranchConditionType.WHEN_TEXT_APPEARS -> {
                            if (found == null) {
                                shouldPause = false
                                RootExecutionBus.appendLog("[分支跳过] 未检测到「${step.targetText}」，无需人工干预")
                            } else {
                                triggerReason = "检测到分支关键词「${found.text}」，等待人工确认"
                            }
                        }
                        BranchConditionType.WHEN_TEXT_MISSING -> {
                            if (found != null) {
                                shouldPause = false
                                RootExecutionBus.appendLog("[分支通过] 已检测到「${found.text}」，继续执行")
                            } else {
                                triggerReason = "页面缺少预期文字「${step.targetText}」，等待人工确认"
                            }
                        }
                        BranchConditionType.ALWAYS_PAUSE -> Unit
                    }
                }
            }

            if (!shouldPause) {
                return StepOutcome(matched = true, clicked = false, event = null)
            }

            val req = PendingConfirmationRequest(
                taskId = taskId,
                taskName = taskName,
                stepTitle = step.title,
                reason = triggerReason,
                executionMode = "ROOT"
            )
            RootExecutionBus.appendLog("[人工确认] $triggerReason")
            RootExecutionBus.update { it.copy(statusMessage = "等待人工确认：${step.title}") }
            updateNotification("等待人工确认：${step.title}")
            TaskNotificationManager.notifyManualConfirmationRequired(
                context = applicationContext,
                request = req,
                taskNotifyEnabled = task.notifyOnManualConfirm,
                taskCustomNote = task.customNotifyNote
            )
            val confirmed = try {
                ManualConfirmationBus.awaitDecision(req)
            } finally {
                TaskNotificationManager.cancelManualConfirmationNotification(applicationContext)
            }
            return if (confirmed) {
                RootExecutionBus.appendLog("[人工确认] 已确认继续执行后续流程")
                StepOutcome(
                    matched = true,
                    clicked = false,
                    event = ExecutionClickEventEntity(
                        taskId = taskId,
                        taskName = taskName,
                        roundIndex = roundIndex,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        executionMode = "ROOT",
                        actionType = type.name,
                        recognizedTarget = "人工确认分支：已放行继续（$triggerReason）",
                        success = true
                    )
                )
            } else {
                RootExecutionBus.appendLog("[人工确认] 用户终止了当前任务")
                StepOutcome(
                    matched = false,
                    clicked = false,
                    abortedByUser = true,
                    event = ExecutionClickEventEntity(
                        taskId = taskId,
                        taskName = taskName,
                        roundIndex = roundIndex,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        executionMode = "ROOT",
                        actionType = type.name,
                        recognizedTarget = "人工确认分支：已终止",
                        success = false,
                        failureReason = "用户在人工确认分支选择了终止任务（$triggerReason）"
                    )
                )
            }
        }

        if (type == StepType.COORDINATE_TAP) {
            val cap = rootShell.captureScreenBitmap(capFile)
            val capBmp = cap.getOrNull()
            val width = capBmp?.width ?: 1080
            val height = capBmp?.height ?: 2400
            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                preloadEngine.scheduleNextStepPreload(
                    nextStep = upcomingStep,
                    delayWindowMs = 0L,
                    immediateFrame = capBmp,
                    repository = repository,
                    captureBackgroundFrame = {
                        rootShell.captureScreenBitmap(preloadCapFile).getOrNull()
                    }
                )
            }
            capBmp?.recycle()
            val baseX = step.normalizedX * width
            val baseY = step.normalizedY * height
            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                centerX = baseX,
                centerY = baseY,
                boundingBox = null,
                enabled = step.randomOffsetEnabled,
                radiusPx = step.randomOffsetRadiusPx
            )
            val x = offsetPt.x.toInt().coerceIn(1, width - 1)
            val y = offsetPt.y.toInt().coerceIn(1, height - 1)
            if (step.delayBeforeMs > 0L) {
                delay(step.delayBeforeMs)
            }
            val tapRes = rootShell.tap(
                x = x,
                y = y,
                pressDurationMs = step.pressDurationMs,
                touchPressure = step.touchPressure
            )
            val targetDesc = String.format(Locale.US, "固定坐标 (%.2f, %.2f)", step.normalizedX, step.normalizedY)
            return if (tapRes.isSuccess) {
                RootExecutionBus.appendLog(
                    "[坐标点击] 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY}) -> su input tap $x $y"
                )
                StepOutcome(
                    matched = true,
                    clicked = true,
                    event = ExecutionClickEventEntity(
                        taskId = taskId,
                        taskName = taskName,
                        roundIndex = roundIndex,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        executionMode = "ROOT",
                        actionType = type.name,
                        recognizedTarget = targetDesc,
                        tapX = x,
                        tapY = y,
                        normX = (x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        normY = (y.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f),
                        offsetDeltaX = offsetPt.deltaX,
                        offsetDeltaY = offsetPt.deltaY,
                        success = true
                    )
                )
            } else {
                val errMsg = tapRes.exceptionOrNull()?.message ?: "su 执行异常"
                RootExecutionBus.appendLog("[坐标点击失败] $errMsg")
                StepOutcome(
                    matched = false,
                    clicked = false,
                    event = ExecutionClickEventEntity(
                        taskId = taskId,
                        taskName = taskName,
                        roundIndex = roundIndex,
                        stepOrder = step.stepOrder,
                        stepTitle = step.title,
                        executionMode = "ROOT",
                        actionType = type.name,
                        recognizedTarget = targetDesc,
                        tapX = x,
                        tapY = y,
                        normX = (x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        normY = (y.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f),
                        offsetDeltaX = offsetPt.deltaX,
                        offsetDeltaY = offsetPt.deltaY,
                        success = false,
                        failureReason = "Root su input tap 失败：$errMsg"
                    )
                )
            }
        }

        val preScanWait = step.preScanDelayMs.coerceIn(0L, 60000L)
        if (preScanWait > 0L) {
            RootExecutionBus.appendLog("[页面加载等待] 等待 ${preScanWait}ms 待目标界面加载稳定...")
            RootExecutionBus.update {
                it.copy(statusMessage = "${step.title} · 页面加载等待 ${preScanWait}ms")
            }
            if (preScanWait >= 300L &&
                (type == StepType.OCR_TEXT_TAP || type == StepType.IMAGE_TEMPLATE_TAP) &&
                PreloadTelemetryBus.state.value.isEnabled
            ) {
                preloadEngine.scheduleNextStepPreload(
                    nextStep = step,
                    delayWindowMs = preScanWait,
                    repository = repository,
                    captureBackgroundFrame = {
                        rootShell.captureScreenBitmap(preloadCapFile).getOrNull()
                    }
                )
            }
            delay(preScanWait)
            waitWhilePaused()
        }

        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceIn(500L, 60000L)
        val scanInterval = step.scanIntervalMs.coerceIn(100L, 10000L)
        var geminiDispatchedForStep = false
        var scanAttempt = 0
        var lastCaptureError: String? = null
        var lastOcrCount = 0
        var lastOcrPreview = ""
        var bestImageSimilarity = -1f

        while (System.currentTimeMillis() <= deadline) {
            val capResult = rootShell.captureScreenBitmap(capFile)
            val screenBitmap = capResult.getOrNull()
            if (screenBitmap == null) {
                lastCaptureError = capResult.exceptionOrNull()?.message ?: "screencap 无输出"
                RootExecutionBus.appendLog("[截屏失败] $lastCaptureError")
                delay(scanInterval.coerceAtMost(800L))
                continue
            }
            lastCaptureError = null
            scanAttempt++
            if (!geminiDispatchedForStep &&
                (type == StepType.OCR_TEXT_TAP || type == StepType.IMAGE_TEMPLATE_TAP) &&
                DiagnosticOverlayBus.state.value.isEnabled
            ) {
                geminiDispatchedForStep = true
                triggerRootGeminiDiagnostic(
                    screenBitmap = screenBitmap,
                    step = step,
                    repository = repository,
                    ocrHint = lastOcrPreview
                )
            }

            try {
                when (type) {
                    StepType.OCR_TEXT_TAP -> {
                        val preloadedHit = preloadEngine.tryConsumePreloadedOcr(step, screenBitmap)
                        val mode = runCatching { TextMatchMode.valueOf(step.textMatchMode) }
                            .getOrDefault(TextMatchMode.CONTAINS)
                        if (preloadedHit != null && preloadedHit.preMatchedElement != null) {
                            val found = preloadedHit.preMatchedElement
                            val lineElements = preloadedHit.elements.filter { it.isLine }
                            lastOcrCount = lineElements.size
                            lastOcrPreview = lineElements.take(3).joinToString("、") { it.text }
                            val fullBox = found.boundingBox
                            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                                centerX = found.centerX,
                                centerY = found.centerY,
                                boundingBox = fullBox,
                                enabled = step.randomOffsetEnabled,
                                radiusPx = step.randomOffsetRadiusPx
                            )
                            val x = offsetPt.x.toInt()
                            val y = offsetPt.y.toInt()
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = screenBitmap,
                                    repository = repository,
                                    captureBackgroundFrame = {
                                        rootShell.captureScreenBitmap(preloadCapFile).getOrNull()
                                    }
                                )
                            }
                            if (step.delayBeforeMs > 0L) {
                                delay(step.delayBeforeMs)
                            }
                            val tapRes = rootShell.tap(
                                x = x,
                                y = y,
                                pressDurationMs = step.pressDurationMs,
                                touchPressure = step.touchPressure
                            )
                            val roiTag = if (step.roiEnabled) "（区域识别）" else ""
                            if (tapRes.isSuccess) {
                                RootExecutionBus.appendLog(
                                    "[预加载命中] 「${found.text}」$roiTag 省时 ${preloadedHit.savedMs}ms -> su input tap $x $y"
                                )
                                return StepOutcome(
                                    matched = true,
                                    clicked = true,
                                    event = ExecutionClickEventEntity(
                                        taskId = taskId,
                                        taskName = taskName,
                                        roundIndex = roundIndex,
                                        stepOrder = step.stepOrder,
                                        stepTitle = step.title,
                                        executionMode = "ROOT",
                                        actionType = type.name,
                                        recognizedTarget = "预加载OCR「${found.text}」$roiTag",
                                        tapX = x,
                                        tapY = y,
                                        normX = (x.toFloat() / screenBitmap.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        normY = (y.toFloat() / screenBitmap.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        offsetDeltaX = offsetPt.deltaX,
                                        offsetDeltaY = offsetPt.deltaY,
                                        success = true
                                    )
                                )
                            }
                        }

                        val roiFrame = RegionCropHelper.cropForRegion(
                            source = screenBitmap,
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
                        preloadEngine.publishCompletedFullScreenOcr(screenBitmap, step, fullElements, ocrElapsed)

                        val lineElements = elements.filter { it.isLine }
                        lastOcrCount = lineElements.size
                        lastOcrPreview = lineElements.take(3).joinToString("、") { it.text }
                        val found = ocrEngine.findBestMatch(elements, step.targetText, mode)
                        if (found != null) {
                            val fullBox = RegionCropHelper.mapRectToFullScreen(
                                found.boundingBox,
                                roiFrame.offsetX,
                                roiFrame.offsetY
                            )
                            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                                centerX = found.centerX + roiFrame.offsetX,
                                centerY = found.centerY + roiFrame.offsetY,
                                boundingBox = fullBox,
                                enabled = step.randomOffsetEnabled,
                                radiusPx = step.randomOffsetRadiusPx
                            )
                            val x = offsetPt.x.toInt()
                            val y = offsetPt.y.toInt()
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = screenBitmap,
                                    repository = repository,
                                    captureBackgroundFrame = {
                                        rootShell.captureScreenBitmap(preloadCapFile).getOrNull()
                                    }
                                )
                            }
                            if (step.delayBeforeMs > 0L) {
                                delay(step.delayBeforeMs)
                            }
                            val tapRes = rootShell.tap(
                                x = x,
                                y = y,
                                pressDurationMs = step.pressDurationMs,
                                touchPressure = step.touchPressure
                            )
                            val roiTag = if (step.roiEnabled) "（区域识别）" else ""
                            if (tapRes.isSuccess) {
                                RootExecutionBus.appendLog(
                                    "[OCR 命中] 「${found.text}」$roiTag 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY}) -> su input tap $x $y"
                                )
                                return StepOutcome(
                                    matched = true,
                                    clicked = true,
                                    event = ExecutionClickEventEntity(
                                        taskId = taskId,
                                        taskName = taskName,
                                        roundIndex = roundIndex,
                                        stepOrder = step.stepOrder,
                                        stepTitle = step.title,
                                        executionMode = "ROOT",
                                        actionType = type.name,
                                        recognizedTarget = "OCR「${found.text}」$roiTag",
                                        tapX = x,
                                        tapY = y,
                                        normX = (x.toFloat() / screenBitmap.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        normY = (y.toFloat() / screenBitmap.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        offsetDeltaX = offsetPt.deltaX,
                                        offsetDeltaY = offsetPt.deltaY,
                                        success = true
                                    )
                                )
                            } else {
                                val errMsg = tapRes.exceptionOrNull()?.message ?: "su 执行失败"
                                RootExecutionBus.appendLog("[OCR 点击失败] $errMsg")
                                return StepOutcome(
                                    matched = false,
                                    clicked = false,
                                    event = ExecutionClickEventEntity(
                                        taskId = taskId,
                                        taskName = taskName,
                                        roundIndex = roundIndex,
                                        stepOrder = step.stepOrder,
                                        stepTitle = step.title,
                                        executionMode = "ROOT",
                                        actionType = type.name,
                                        recognizedTarget = "OCR「${found.text}」$roiTag",
                                        tapX = x,
                                        tapY = y,
                                        normX = (x.toFloat() / screenBitmap.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        normY = (y.toFloat() / screenBitmap.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        offsetDeltaX = offsetPt.deltaX,
                                        offsetDeltaY = offsetPt.deltaY,
                                        success = false,
                                        failureReason = "已识别到「${found.text}」，但 su input tap 失败：$errMsg"
                                    )
                                )
                            }
                        }
                    }

                    StepType.IMAGE_TEMPLATE_TAP -> {
                        val preloadedImageHit = preloadEngine.tryConsumePreloadedTemplateMatch(step, screenBitmap)
                        if (preloadedImageHit != null) {
                            val match = preloadedImageHit.matchResult
                            val fullBox = match.boundingBox
                            val offsetPt = TouchDispatcher.applyHumanizedOffset(
                                centerX = match.centerX,
                                centerY = match.centerY,
                                boundingBox = fullBox,
                                enabled = step.randomOffsetEnabled,
                                radiusPx = step.randomOffsetRadiusPx
                            )
                            val x = offsetPt.x.toInt()
                            val y = offsetPt.y.toInt()
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = screenBitmap,
                                    repository = repository,
                                    captureBackgroundFrame = {
                                        rootShell.captureScreenBitmap(preloadCapFile).getOrNull()
                                    }
                                )
                            }
                            if (step.delayBeforeMs > 0L) {
                                delay(step.delayBeforeMs)
                            }
                            val tapRes = rootShell.tap(
                                x = x,
                                y = y,
                                pressDurationMs = step.pressDurationMs,
                                touchPressure = step.touchPressure
                            )
                            val pct = (match.similarity * 100).toInt()
                            val roiTag = if (step.roiEnabled) "·区域加速" else ""
                            if (tapRes.isSuccess) {
                                RootExecutionBus.appendLog(
                                    "[预加载命中] 图像模板 ${pct}%$roiTag 省时 ${preloadedImageHit.savedMs}ms -> su input tap $x $y"
                                )
                                return StepOutcome(
                                    matched = true,
                                    clicked = true,
                                    event = ExecutionClickEventEntity(
                                        taskId = taskId,
                                        taskName = taskName,
                                        roundIndex = roundIndex,
                                        stepOrder = step.stepOrder,
                                        stepTitle = step.title,
                                        executionMode = "ROOT",
                                        actionType = type.name,
                                        recognizedTarget = "预加载图像模板（相似度 ${pct}%$roiTag）",
                                        tapX = x,
                                        tapY = y,
                                        normX = (x.toFloat() / screenBitmap.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        normY = (y.toFloat() / screenBitmap.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        offsetDeltaX = offsetPt.deltaX,
                                        offsetDeltaY = offsetPt.deltaY,
                                        success = true
                                    )
                                )
                            }
                        }

                        val tpl = preloadEngine.loadTemplateCopy(step.templateImagePath, repository)
                        if (tpl == null) {
                            RootExecutionBus.appendLog("[图像匹配失败] 模板图片不存在：${step.templateImagePath}")
                            return StepOutcome(
                                matched = false,
                                clicked = false,
                                event = ExecutionClickEventEntity(
                                    taskId = taskId,
                                    taskName = taskName,
                                    roundIndex = roundIndex,
                                    stepOrder = step.stepOrder,
                                    stepTitle = step.title,
                                    executionMode = "ROOT",
                                    actionType = type.name,
                                    recognizedTarget = "图像模板（文件缺失）",
                                    success = false,
                                    failureReason = "找不到图像模板文件：${step.templateImagePath}"
                                )
                            )
                        }
                        val roiFrame = RegionCropHelper.cropForRegion(
                            source = screenBitmap,
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
                            val x = offsetPt.x.toInt()
                            val y = offsetPt.y.toInt()
                            if (upcomingStep != null && PreloadTelemetryBus.state.value.isEnabled) {
                                preloadEngine.scheduleNextStepPreload(
                                    nextStep = upcomingStep,
                                    delayWindowMs = 0L,
                                    immediateFrame = screenBitmap,
                                    repository = repository,
                                    captureBackgroundFrame = {
                                        rootShell.captureScreenBitmap(preloadCapFile).getOrNull()
                                    }
                                )
                            }
                            if (step.delayBeforeMs > 0L) {
                                delay(step.delayBeforeMs)
                            }
                            val tapRes = rootShell.tap(
                                x = x,
                                y = y,
                                pressDurationMs = step.pressDurationMs,
                                touchPressure = step.touchPressure
                            )
                            val pct = (match.similarity * 100).toInt()
                            val roiTag = if (step.roiEnabled) "·区域加速" else ""
                            if (tapRes.isSuccess) {
                                RootExecutionBus.appendLog(
                                    "[图像命中] 相似度 ${pct}%$roiTag 偏移(${offsetPt.deltaX}, ${offsetPt.deltaY}) -> su input tap $x $y"
                                )
                                return StepOutcome(
                                    matched = true,
                                    clicked = true,
                                    event = ExecutionClickEventEntity(
                                        taskId = taskId,
                                        taskName = taskName,
                                        roundIndex = roundIndex,
                                        stepOrder = step.stepOrder,
                                        stepTitle = step.title,
                                        executionMode = "ROOT",
                                        actionType = type.name,
                                        recognizedTarget = "图像模板（相似度 ${pct}%$roiTag）",
                                        tapX = x,
                                        tapY = y,
                                        normX = (x.toFloat() / screenBitmap.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        normY = (y.toFloat() / screenBitmap.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        offsetDeltaX = offsetPt.deltaX,
                                        offsetDeltaY = offsetPt.deltaY,
                                        success = true
                                    )
                                )
                            } else {
                                val errMsg = tapRes.exceptionOrNull()?.message ?: "su 执行失败"
                                RootExecutionBus.appendLog("[图像点击失败] $errMsg")
                                return StepOutcome(
                                    matched = false,
                                    clicked = false,
                                    event = ExecutionClickEventEntity(
                                        taskId = taskId,
                                        taskName = taskName,
                                        roundIndex = roundIndex,
                                        stepOrder = step.stepOrder,
                                        stepTitle = step.title,
                                        executionMode = "ROOT",
                                        actionType = type.name,
                                        recognizedTarget = "图像模板（相似度 ${pct}%$roiTag）",
                                        tapX = x,
                                        tapY = y,
                                        normX = (x.toFloat() / screenBitmap.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        normY = (y.toFloat() / screenBitmap.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        offsetDeltaX = offsetPt.deltaX,
                                        offsetDeltaY = offsetPt.deltaY,
                                        success = false,
                                        failureReason = "已匹配图像(${pct}%)，但 su input tap 失败：$errMsg"
                                    )
                                )
                            }
                        }
                    }

                    StepType.COORDINATE_TAP,
                    StepType.WAIT_DELAY,
                    StepType.MANUAL_CONFIRM_BRANCH -> Unit
                }
            } finally {
                screenBitmap.recycle()
            }
            if (System.currentTimeMillis() + scanInterval <= deadline) {
                RootExecutionBus.update {
                    it.copy(statusMessage = "${step.title} · 第${scanAttempt}轮未命中，${scanInterval}ms 后继续识图")
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
            lastCaptureError != null -> "Root 截屏失败：$lastCaptureError"
            type == StepType.OCR_TEXT_TAP -> {
                val previewSuffix = if (lastOcrPreview.isNotBlank()) "（如：$lastOcrPreview）" else ""
                "超时 ${step.timeoutMs}ms 未匹配「${step.targetText}」：当前屏共识别 $lastOcrCount 处文字$previewSuffix"
            }
            type == StepType.IMAGE_TEMPLATE_TAP -> {
                val bestPct = if (bestImageSimilarity >= 0f) (bestImageSimilarity * 100).toInt() else 0
                val thresholdPct = (step.matchThreshold * 100).toInt()
                "超时 ${step.timeoutMs}ms 未命中图像模板：最高相似度 ${bestPct}% 低于阈值 ${thresholdPct}%"
            }
            else -> "超时未命中：${step.title}"
        }
        RootExecutionBus.appendLog("[超时未匹配] $reason")
        val roiNormX = if (step.roiEnabled) ((step.roiLeft + step.roiRight) / 2f).coerceIn(0f, 1f) else null
        val roiNormY = if (step.roiEnabled) ((step.roiTop + step.roiBottom) / 2f).coerceIn(0f, 1f) else null
        return StepOutcome(
            matched = false,
            clicked = false,
            event = ExecutionClickEventEntity(
                taskId = taskId,
                taskName = taskName,
                roundIndex = roundIndex,
                stepOrder = step.stepOrder,
                stepTitle = step.title,
                executionMode = "ROOT",
                actionType = type.name,
                recognizedTarget = targetDesc,
                normX = roiNormX,
                normY = roiNormY,
                success = false,
                failureReason = reason
            )
        )
    }

    private fun triggerRootGeminiDiagnostic(
        screenBitmap: android.graphics.Bitmap,
        step: AutomationStepEntity,
        repository: AutomationRepository,
        ocrHint: String
    ) {
        val frameCopy = screenBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return
        val frameW = frameCopy.width
        val frameH = frameCopy.height
        val stepType = runCatching { StepType.valueOf(step.stepType) }.getOrDefault(StepType.OCR_TEXT_TAP)
        val targetingLogic = stepType.label
        val targetHint = if (stepType == StepType.OCR_TEXT_TAP) step.targetText else "图像模板"

        rootDiagnosticJob?.cancel()
        rootDiagnosticJob = serviceScope.launch {
            var tplCopy: android.graphics.Bitmap? = null
            try {
                DiagnosticOverlayBus.markAnalyzing(
                    executionMode = "ROOT",
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
                            executionMode = "ROOT",
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
                            executionMode = "ROOT",
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

    private suspend fun waitWhilePaused() {
        while (RootExecutionBus.state.value.isPaused) {
            delay(200L)
        }
    }

    private fun stopExecutionAndSelf(finalMessage: String) {
        ManualConfirmationBus.cancelActive()
        TaskNotificationManager.cancelManualConfirmationNotification(applicationContext)
        rootDiagnosticJob?.cancel()
        DiagnosticOverlayBus.clearOverlay()
        preloadEngine.clearSession()
        executionJob?.cancel()
        executionJob = null
        RootExecutionBus.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                currentStepIndex = -1,
                statusMessage = finalMessage
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RootAutomationService::class.java).apply {
            action = ACTION_STOP_TASK
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("智点任务 · Root 引擎运行中")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止任务", stopPending)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Root 自动化执行状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 Root 识图识字自动点击任务的运行进度与停止控制"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        executionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
