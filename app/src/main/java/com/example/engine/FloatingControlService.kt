package com.example.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.AutomationRepository
import com.example.data.TaskWithSteps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

object FloatingWindowBus {
    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    fun setOverlayVisible(visible: Boolean) {
        _isOverlayVisible.value = visible
    }
}

class FloatingControlService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.action.SHOW_FLOATING_CONTROL"
        const val ACTION_CLOSE = "com.example.action.CLOSE_FLOATING_CONTROL"
        const val EXTRA_INITIAL_TASK_ID = "extra_initial_task_id"
        private const val CHANNEL_ID = "floating_control_channel"
        private const val NOTIFICATION_ID = 4103

        fun start(context: Context, initialTaskId: Long? = null) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingControlService::class.java).apply {
                action = ACTION_SHOW
                if (initialTaskId != null) putExtra(EXTRA_INITIAL_TASK_ID, initialTaskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!FloatingWindowBus.isOverlayVisible.value) return
            val intent = Intent(context, FloatingControlService::class.java).apply {
                action = ACTION_CLOSE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var rootOverlayView: View? = null
    private var diagnosticHighlightView: DiagnosticHighlightOverlayView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var diagnosticParams: WindowManager.LayoutParams

    private var tasks: List<TaskWithSteps> = emptyList()
    private var selectedTaskIndex: Int = 0
    private var isCollapsed: Boolean = false

    // UI references for reactive updates
    private lateinit var collapsedPillView: LinearLayout
    private lateinit var expandedCardView: LinearLayout
    private lateinit var pillDotView: View
    private lateinit var pillLabelView: TextView
    private lateinit var taskNameView: TextView
    private lateinit var taskMetaView: TextView
    private lateinit var statusDotView: View
    private lateinit var statusTextView: TextView
    private lateinit var miniLogView: TextView
    private lateinit var geminiDiagView: TextView
    private lateinit var primaryActionBtn: TextView
    private lateinit var stopActionBtn: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            cleanupOverlay()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundControlNotification()

        val requestedTaskId = intent?.getLongExtra(EXTRA_INITIAL_TASK_ID, -1L) ?: -1L
        if (rootOverlayView == null) {
            createAndAttachOverlay()
            observeDataAndRunner(requestedTaskId)
        } else if (requestedTaskId > 0L) {
            val idx = tasks.indexOfFirst { it.task.id == requestedTaskId }
            if (idx >= 0) {
                selectedTaskIndex = idx
                refreshUi()
            }
        }
        return START_STICKY
    }

    private fun observeDataAndRunner(initialTaskId: Long) {
        val db = AppDatabase.getInstance(applicationContext)
        val repo = AutomationRepository(applicationContext, db.automationDao())

        serviceScope.launch {
            repo.tasksWithSteps.collectLatest { list ->
                tasks = list
                if (initialTaskId > 0L && selectedTaskIndex == 0) {
                    val found = list.indexOfFirst { it.task.id == initialTaskId }
                    if (found >= 0) selectedTaskIndex = found
                }
                if (selectedTaskIndex >= tasks.size) {
                    selectedTaskIndex = 0
                }
                refreshUi()
            }
        }

        serviceScope.launch {
            RootExecutionBus.state.collectLatest {
                refreshUi()
            }
        }

        serviceScope.launch {
            ManualConfirmationBus.pendingRequest.collectLatest {
                refreshUi()
            }
        }

        serviceScope.launch {
            DiagnosticOverlayBus.state.collectLatest { diag ->
                diagnosticHighlightView?.updateState(diag)
                refreshUi()
            }
        }
    }

    private fun createAndAttachOverlay() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }

        diagnosticParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        diagnosticHighlightView = DiagnosticHighlightOverlayView(this)
        runCatching { windowManager.addView(diagnosticHighlightView, diagnosticParams) }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- 1. Collapsed Draggable Pill ---
        collapsedPillView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#EE0B1220"))
                setStroke(dp(1), Color.parseColor("#22D3EE"))
            }
            visibility = View.GONE
        }
        pillDotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22D3EE"))
            }
        }
        pillLabelView = TextView(this).apply {
            text = "智点悬浮"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        collapsedPillView.addView(pillDotView)
        collapsedPillView.addView(pillLabelView)

        attachDragBehavior(collapsedPillView) {
            isCollapsed = false
            refreshUi()
        }

        // --- 2. Expanded Control Card ---
        expandedCardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(264), LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F20B1220"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        // Header Row (Draggable)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        statusDotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(7)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22D3EE"))
            }
        }
        val titleView = TextView(this).apply {
            text = "智点 · 悬浮控制台"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapseBtn = makeHeaderIconButton("收起", Color.parseColor("#94A3B8")) {
            isCollapsed = true
            refreshUi()
        }
        val closeBtn = makeHeaderIconButton("关闭", Color.parseColor("#EF4444")) {
            stop(this)
        }
        headerRow.addView(statusDotView)
        headerRow.addView(titleView)
        headerRow.addView(collapseBtn)
        headerRow.addView(closeBtn)
        attachDragBehavior(headerRow, onTap = null)

        // Task Switcher Row
        val switcherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1A2942"))
            }
        }
        val prevBtn = makeMiniStepButton("◀") {
            if (tasks.isNotEmpty()) {
                selectedTaskIndex = (selectedTaskIndex - 1 + tasks.size) % tasks.size
                refreshUi()
            }
        }
        val taskInfoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(6), 0, dp(6), 0)
        }
        taskNameView = TextView(this).apply {
            text = "加载任务中..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        taskMetaView = TextView(this).apply {
            text = "--"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        taskInfoCol.addView(taskNameView)
        taskInfoCol.addView(taskMetaView)
        val nextBtn = makeMiniStepButton("▶") {
            if (tasks.isNotEmpty()) {
                selectedTaskIndex = (selectedTaskIndex + 1) % tasks.size
                refreshUi()
            }
        }
        switcherRow.addView(prevBtn)
        switcherRow.addView(taskInfoCol)
        switcherRow.addView(nextBtn)

        // Status & Mini Log
        statusTextView = TextView(this).apply {
            text = "就绪"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8), 0, dp(2))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        miniLogView = TextView(this).apply {
            text = "切至目标界面后点击“启动”即可全局识字识图点击"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp(4))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        geminiDiagView = TextView(this).apply {
            text = "✦ Gemini 诊断高亮：待命中"
            setTextColor(Color.parseColor("#C084FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp(8))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        // Control Buttons Row
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        primaryActionBtn = TextView(this).apply {
            text = "▶ 启动"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#10B981"))
            }
            setOnClickListener { handlePrimaryAction() }
        }
        stopActionBtn = TextView(this).apply {
            text = "■ 终止"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EF4444"))
            }
            setOnClickListener {
                if (ManualConfirmationBus.pendingRequest.value != null) {
                    ManualConfirmationBus.resolve(confirmed = false)
                    TaskNotificationManager.cancelManualConfirmationNotification(this@FloatingControlService)
                } else {
                    RootAutomationService.stopRootTask(this@FloatingControlService)
                }
            }
        }
        controlsRow.addView(primaryActionBtn)
        controlsRow.addView(stopActionBtn)

        expandedCardView.addView(headerRow)
        expandedCardView.addView(switcherRow)
        expandedCardView.addView(statusTextView)
        expandedCardView.addView(miniLogView)
        expandedCardView.addView(geminiDiagView)
        expandedCardView.addView(controlsRow)

        container.addView(collapsedPillView)
        container.addView(expandedCardView)

        rootOverlayView = container
        windowManager.addView(container, layoutParams)
        FloatingWindowBus.setOverlayVisible(true)
        refreshUi()
    }

    private fun handlePrimaryAction() {
        val pendingConfirm = ManualConfirmationBus.pendingRequest.value
        if (pendingConfirm != null) {
            ManualConfirmationBus.resolve(confirmed = true, requestId = pendingConfirm.requestId)
            TaskNotificationManager.cancelManualConfirmationNotification(this)
            return
        }
        val runner = RootExecutionBus.state.value
        if (runner.isRunning) {
            if (runner.isPaused) {
                RootAutomationService.resumeRootTask(this)
            } else {
                RootAutomationService.pauseRootTask(this)
            }
        } else {
            val active = tasks.getOrNull(selectedTaskIndex) ?: return
            RootAutomationService.startRootTask(this, active.task.id, countdownSec = 1)
        }
    }

    private fun refreshUi() {
        if (rootOverlayView == null) return
        val runner = RootExecutionBus.state.value
        val pendingConfirm = ManualConfirmationBus.pendingRequest.value
        val active = tasks.getOrNull(selectedTaskIndex)

        collapsedPillView.visibility = if (isCollapsed) View.VISIBLE else View.GONE
        expandedCardView.visibility = if (isCollapsed) View.GONE else View.VISIBLE

        val dotColor = when {
            pendingConfirm != null -> Color.parseColor("#F59E0B")
            runner.isRunning && runner.isPaused -> Color.parseColor("#F59E0B")
            runner.isRunning -> Color.parseColor("#10B981")
            else -> Color.parseColor("#22D3EE")
        }
        (pillDotView.background as? GradientDrawable)?.setColor(dotColor)
        (statusDotView.background as? GradientDrawable)?.setColor(dotColor)

        pillLabelView.text = when {
            pendingConfirm != null -> "待确认"
            runner.isRunning && runner.isPaused -> "已暂停"
            runner.isRunning -> "运行中"
            else -> active?.task?.name?.take(6) ?: "智点悬浮"
        }

        if (active != null) {
            taskNameView.text = active.task.name
            taskMetaView.text = "${active.sortedSteps.size} 步 · ${selectedTaskIndex + 1}/${tasks.size}"
        } else {
            taskNameView.text = "暂无可用任务"
            taskMetaView.text = "请先在应用内创建任务"
        }

        statusTextView.text = when {
            pendingConfirm != null -> "⚠️ 待人工确认：${pendingConfirm.reason}"
            runner.isRunning -> runner.statusMessage
            else -> "就绪（点击启动 Root 全局执行）"
        }
        statusTextView.setTextColor(dotColor)

        val latestLog = runner.logs.lastOrNull()
        if (!latestLog.isNullOrBlank()) {
            miniLogView.text = latestLog
        }
        val diag = DiagnosticOverlayBus.state.value
        geminiDiagView.text = when {
            !diag.isEnabled -> "✦ Gemini 诊断高亮已关闭"
            diag.isAnalyzing -> "✦ Gemini 正在识别定位「${diag.stepTitle}」..."
            !diag.errorMessage.isNullOrBlank() -> "✦ Gemini 诊断异常：${diag.errorMessage}"
            diag.isVisible && diag.elementFound -> {
                val pct = (diag.confidence * 100).toInt()
                "✦ Gemini 高亮：[${diag.elementType}] ${diag.elementLabel} (${pct}%)"
            }
            diag.isVisible -> "✦ Gemini 诊断：${diag.diagnosticNote}"
            else -> "✦ Gemini 诊断高亮就绪（自动标注目标元素）"
        }

        if (pendingConfirm != null) {
            stopActionBtn.visibility = View.VISIBLE
            stopActionBtn.text = "■ 终止"
            primaryActionBtn.isEnabled = true
            primaryActionBtn.text = "✅ 确认继续"
            (primaryActionBtn.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
        } else if (runner.isRunning) {
            stopActionBtn.visibility = View.VISIBLE
            stopActionBtn.text = "■ 终止"
            primaryActionBtn.isEnabled = true
            if (runner.isPaused) {
                primaryActionBtn.text = "▶ 继续"
                (primaryActionBtn.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
            } else {
                primaryActionBtn.text = "⏸ 暂停"
                (primaryActionBtn.background as? GradientDrawable)?.setColor(Color.parseColor("#F59E0B"))
            }
        } else {
            stopActionBtn.visibility = View.GONE
            primaryActionBtn.text = "▶ 启动任务"
            val canStart = active != null && active.sortedSteps.isNotEmpty()
            primaryActionBtn.isEnabled = canStart
            (primaryActionBtn.background as? GradientDrawable)?.setColor(
                if (canStart) Color.parseColor("#10B981") else Color.parseColor("#475569")
            )
        }
    }

    private fun attachDragBehavior(target: View, onTap: (() -> Unit)?) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        target.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    rootOverlayView?.let { v ->
                        runCatching { windowManager.updateViewLayout(v, layoutParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap?.invoke()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun makeHeaderIconButton(label: String, textColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { onClick() }
        }
    }

    private fun makeMiniStepButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { onClick() }
        }
    }

    private fun startForegroundControlNotification() {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val closeIntent = Intent(this, FloatingControlService::class.java).apply {
            action = ACTION_CLOSE
        }
        val closePending = PendingIntent.getService(
            this,
            11,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("智点任务 · 悬浮控制台已开启")
            .setContentText("可在目标应用上方直接启动、暂停或终止任务")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮窗", closePending)
            .build()

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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "系统悬浮控制窗",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun cleanupOverlay() {
        diagnosticHighlightView?.let { v ->
            runCatching { windowManager.removeViewImmediate(v) }
        }
        diagnosticHighlightView = null
        rootOverlayView?.let { v ->
            runCatching { windowManager.removeViewImmediate(v) }
        }
        rootOverlayView = null
        FloatingWindowBus.setOverlayVisible(false)
    }

    override fun onDestroy() {
        cleanupOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private class DiagnosticHighlightOverlayView(context: Context) : View(context) {
        private var overlayState: DiagnosticOverlayState = DiagnosticOverlayState()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#2A9333EA")
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#C084FC")
            pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#F59E0B")
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#E61E1B4B")
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FDE68A")
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                12f,
                context.resources.displayMetrics
            )
            isFakeBoldText = true
        }

        fun updateState(state: DiagnosticOverlayState) {
            overlayState = state
            visibility = if (state.isEnabled && state.isVisible && state.executionMode == "ROOT" && state.highlightRect != null) {
                VISIBLE
            } else {
                GONE
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = overlayState.highlightRect ?: return
            if (overlayState.viewportWidthPx <= 0 || overlayState.viewportHeightPx <= 0 || width <= 0 || height <= 0) {
                return
            }
            val sx = width.toFloat() / overlayState.viewportWidthPx.toFloat()
            val sy = height.toFloat() / overlayState.viewportHeightPx.toFloat()
            val mapped = RectF(
                rect.left * sx,
                rect.top * sy,
                rect.right * sx,
                rect.bottom * sy
            )
            canvas.drawRoundRect(mapped, 14f, 14f, fillPaint)
            canvas.drawRoundRect(mapped, 14f, 14f, strokePaint)

            val cornerLen = (minOf(mapped.width(), mapped.height()) * 0.24f).coerceIn(18f, 44f)
            // Top-left corner
            canvas.drawLine(mapped.left, mapped.top, mapped.left + cornerLen, mapped.top, cornerPaint)
            canvas.drawLine(mapped.left, mapped.top, mapped.left, mapped.top + cornerLen, cornerPaint)
            // Top-right corner
            canvas.drawLine(mapped.right - cornerLen, mapped.top, mapped.right, mapped.top, cornerPaint)
            canvas.drawLine(mapped.right, mapped.top, mapped.right, mapped.top + cornerLen, cornerPaint)
            // Bottom-left corner
            canvas.drawLine(mapped.left, mapped.bottom - cornerLen, mapped.left, mapped.bottom, cornerPaint)
            canvas.drawLine(mapped.left, mapped.bottom, mapped.left + cornerLen, mapped.bottom, cornerPaint)
            // Bottom-right corner
            canvas.drawLine(mapped.right - cornerLen, mapped.bottom, mapped.right, mapped.bottom, cornerPaint)
            canvas.drawLine(mapped.right, mapped.bottom - cornerLen, mapped.right, mapped.bottom, cornerPaint)

            val pct = (overlayState.confidence * 100).toInt()
            val label = "✦ Gemini · ${overlayState.elementLabel} ($pct%)"
            val textW = badgeTextPaint.measureText(label)
            val padH = 16f
            val badgeH = 44f
            val badgeLeft = mapped.left.coerceIn(12f, (width - textW - padH * 2 - 12f).coerceAtLeast(12f))
            val badgeTop = if (mapped.top > badgeH + 16f) mapped.top - badgeH - 8f else (mapped.bottom + 8f).coerceAtMost(height - badgeH - 12f)
            val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + textW + padH * 2, badgeTop + badgeH)
            canvas.drawRoundRect(badgeRect, 14f, 14f, badgeBgPaint)
            canvas.drawText(label, badgeRect.left + padH, badgeRect.bottom - 14f, badgeTextPaint)
        }
    }
}
