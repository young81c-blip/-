package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.TargetSceneType
import com.example.data.TaskWithSteps
import com.example.engine.DiagnosticOverlayState
import com.example.engine.PendingConfirmationRequest
import com.example.engine.PreloadEngineState
import com.example.engine.ReplayVerificationMode
import com.example.engine.RootRunnerState
import com.example.engine.TouchDispatcher
import com.example.engine.VirtualSandboxUiState
import com.example.engine.VirtualStepMatchResult
import com.example.ui.SandboxSceneState
import com.example.ui.SandboxUiState
import com.example.ui.components.VirtualTestSandboxPanel
import com.example.ui.theme.CyanBright
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.RoseAlert
import com.example.ui.theme.SmartTapFlowTheme
import kotlin.math.roundToInt

private enum class SandboxWorkspaceMode(val label: String) {
    LIVE_EXECUTION("真实执行台"),
    VIRTUAL_DRY_RUN("虚拟测试沙盒 (Dry-Run)")
}

@Composable
fun SandboxScreen(
    tasks: List<TaskWithSteps>,
    sandboxState: SandboxUiState,
    rootRunnerState: RootRunnerState,
    pendingConfirmation: PendingConfirmationRequest? = null,
    diagnosticOverlayState: DiagnosticOverlayState = DiagnosticOverlayState(),
    preloadState: PreloadEngineState = PreloadEngineState(),
    virtualSandboxState: VirtualSandboxUiState = VirtualSandboxUiState(),
    onSelectTask: (TaskWithSteps) -> Unit,
    onSelectScene: (TargetSceneType) -> Unit,
    onPickCustomScreenshot: (android.net.Uri) -> Unit,
    onResetScene: () -> Unit,
    onRunSandbox: (TaskWithSteps, suspend () -> Bitmap?, suspend (Float, Float, Long, Float) -> Boolean) -> Unit,
    onStopSandbox: () -> Unit,
    onRunRootTask: (Long) -> Unit,
    onStopRootTask: () -> Unit,
    onResolveConfirmation: (Boolean) -> Unit = {},
    onToggleGeminiDiagnostic: (Boolean) -> Unit = {},
    onRunManualGeminiDiagnostic: (TaskWithSteps, suspend () -> Bitmap?) -> Unit = { _, _ -> },
    onClearGeminiDiagnostic: () -> Unit = {},
    onTogglePreloadEngine: (Boolean) -> Unit = {},
    onVirtualImportVideo: (android.net.Uri) -> Unit = {},
    onVirtualAppendImages: (List<android.net.Uri>) -> Unit = {},
    onVirtualLoadSample: (TargetSceneType) -> Unit = {},
    onVirtualSelectFrame: (Int) -> Unit = {},
    onVirtualRemoveFrame: (Int) -> Unit = {},
    onVirtualMoveFrame: (Int, Int) -> Unit = { _, _ -> },
    onVirtualClearFrames: () -> Unit = {},
    onVirtualSetMode: (ReplayVerificationMode) -> Unit = {},
    onVirtualSetInterval: (Long) -> Unit = {},
    onVirtualRunVerify: (TaskWithSteps) -> Unit = {},
    onVirtualStopVerify: () -> Unit = {},
    onVirtualInspectStep: (VirtualStepMatchResult) -> Unit = {},
    onDismissAdDialog: () -> Unit,
    onPerformCheckin: () -> Unit,
    onClaimPoints: () -> Unit,
    onActivateRewardBadge: () -> Unit,
    onConfirmRewardClaim: () -> Unit,
    onActivateCouponBadge: () -> Unit,
    onClaimFlashCoupon: () -> Unit,
    onRecordCustomCanvasTap: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeTask = remember(tasks, sandboxState.selectedTaskId) {
        tasks.firstOrNull { it.task.id == sandboxState.selectedTaskId } ?: tasks.firstOrNull()
    }

    var workspaceMode by rememberSaveable { mutableStateOf(SandboxWorkspaceMode.LIVE_EXECUTION) }
    var viewportView by remember { mutableStateOf<View?>(null) }
    var pendingSandboxStart by remember { mutableStateOf(false) }

    fun executeActiveInSandbox() {
        val task = activeTask ?: return
        val view = viewportView ?: return
        onRunSandbox(
            task,
            { TouchDispatcher.captureViewBitmap(view) },
            { x, y, pressMs, pressure ->
                TouchDispatcher.dispatchTapOnView(view, x, y, pressMs, pressure)
            }
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (pendingSandboxStart) {
            pendingSandboxStart = false
            executeActiveInSandbox()
        }
    }

    fun startSandboxWithNotificationCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingSandboxStart = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        executeActiveInSandbox()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickCustomScreenshot(uri)
    }

    Column(modifier = modifier.fillMaxSize()) {
        SandboxWorkspaceSwitcher(
            selectedMode = workspaceMode,
            hasVirtualReport = virtualSandboxState.report != null,
            onSelectMode = { workspaceMode = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
        )

        if (workspaceMode == SandboxWorkspaceMode.VIRTUAL_DRY_RUN) {
            VirtualTestSandboxPanel(
                tasks = tasks,
                activeTask = activeTask,
                virtualState = virtualSandboxState,
                onSelectTask = onSelectTask,
                onImportVideo = onVirtualImportVideo,
                onAppendImages = onVirtualAppendImages,
                onLoadSampleSequence = onVirtualLoadSample,
                onSelectFrame = onVirtualSelectFrame,
                onRemoveFrame = onVirtualRemoveFrame,
                onMoveFrame = onVirtualMoveFrame,
                onClearFrames = onVirtualClearFrames,
                onSetVerificationMode = onVirtualSetMode,
                onSetVideoInterval = onVirtualSetInterval,
                onRunVerification = onVirtualRunVerify,
                onStopVerification = onVirtualStopVerify,
                onInspectStepResult = onVirtualInspectStep,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            return@Column
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isWide = maxWidth >= 840.dp

            if (isWide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                Column(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                    SandboxTaskSelectorRow(
                        tasks = tasks,
                        activeTask = activeTask,
                        selectedScene = sandboxState.selectedScene,
                        onSelectTask = onSelectTask,
                        onSelectScene = onSelectScene,
                        onPickImage = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onResetScene = onResetScene
                    )
                    Spacer(Modifier.height(10.dp))
                    TargetViewportBox(
                        sandboxState = sandboxState,
                        diagnosticOverlayState = diagnosticOverlayState,
                        onClearGeminiDiagnostic = onClearGeminiDiagnostic,
                        onViewportReady = { viewportView = it },
                        onDismissAdDialog = onDismissAdDialog,
                        onPerformCheckin = onPerformCheckin,
                        onClaimPoints = onClaimPoints,
                        onActivateRewardBadge = onActivateRewardBadge,
                        onConfirmRewardClaim = onConfirmRewardClaim,
                        onActivateCouponBadge = onActivateCouponBadge,
                        onClaimFlashCoupon = onClaimFlashCoupon,
                        onRecordCustomCanvasTap = onRecordCustomCanvasTap,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }

                SandboxConsolePane(
                    activeTask = activeTask,
                    sandboxState = sandboxState,
                    rootRunnerState = rootRunnerState,
                    pendingConfirmation = pendingConfirmation,
                    diagnosticOverlayState = diagnosticOverlayState,
                    preloadState = preloadState,
                    onRunSandbox = { startSandboxWithNotificationCheck() },
                    onStopSandbox = onStopSandbox,
                    onRunRootTask = { activeTask?.let { onRunRootTask(it.task.id) } },
                    onStopRootTask = onStopRootTask,
                    onResolveConfirmation = onResolveConfirmation,
                    onToggleGeminiDiagnostic = onToggleGeminiDiagnostic,
                    onRunManualGeminiDiagnostic = {
                        val task = activeTask
                        val view = viewportView
                        if (task != null && view != null) {
                            onRunManualGeminiDiagnostic(task) { TouchDispatcher.captureViewBitmap(view) }
                        }
                    },
                    onTogglePreloadEngine = onTogglePreloadEngine,
                    modifier = Modifier.weight(0.9f).fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SandboxTaskSelectorRow(
                    tasks = tasks,
                    activeTask = activeTask,
                    selectedScene = sandboxState.selectedScene,
                    onSelectTask = onSelectTask,
                    onSelectScene = onSelectScene,
                    onPickImage = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onResetScene = onResetScene
                )

                TargetViewportBox(
                    sandboxState = sandboxState,
                    diagnosticOverlayState = diagnosticOverlayState,
                    onClearGeminiDiagnostic = onClearGeminiDiagnostic,
                    onViewportReady = { viewportView = it },
                    onDismissAdDialog = onDismissAdDialog,
                    onPerformCheckin = onPerformCheckin,
                    onClaimPoints = onClaimPoints,
                    onActivateRewardBadge = onActivateRewardBadge,
                    onConfirmRewardClaim = onConfirmRewardClaim,
                    onActivateCouponBadge = onActivateCouponBadge,
                    onClaimFlashCoupon = onClaimFlashCoupon,
                    onRecordCustomCanvasTap = onRecordCustomCanvasTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(330.dp)
                )

                SandboxConsolePane(
                    activeTask = activeTask,
                    sandboxState = sandboxState,
                    rootRunnerState = rootRunnerState,
                    pendingConfirmation = pendingConfirmation,
                    diagnosticOverlayState = diagnosticOverlayState,
                    preloadState = preloadState,
                    onRunSandbox = { startSandboxWithNotificationCheck() },
                    onStopSandbox = onStopSandbox,
                    onRunRootTask = { activeTask?.let { onRunRootTask(it.task.id) } },
                    onStopRootTask = onStopRootTask,
                    onResolveConfirmation = onResolveConfirmation,
                    onToggleGeminiDiagnostic = onToggleGeminiDiagnostic,
                    onRunManualGeminiDiagnostic = {
                        val task = activeTask
                        val view = viewportView
                        if (task != null && view != null) {
                            onRunManualGeminiDiagnostic(task) { TouchDispatcher.captureViewBitmap(view) }
                        }
                    },
                    onTogglePreloadEngine = onTogglePreloadEngine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
        }
    }
}

@Composable
private fun SandboxWorkspaceSwitcher(
    selectedMode: SandboxWorkspaceMode,
    hasVirtualReport: Boolean,
    onSelectMode: (SandboxWorkspaceMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SandboxWorkspaceMode.entries.forEach { mode ->
                val isSelected = selectedMode == mode
                val tag = if (mode == SandboxWorkspaceMode.LIVE_EXECUTION) {
                    "sandbox_mode_live_tab"
                } else {
                    "sandbox_mode_virtual_tab"
                }
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelectMode(mode) }
                        .testTag(tag)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (mode == SandboxWorkspaceMode.LIVE_EXECUTION) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.Science
                            },
                            contentDescription = null,
                            tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (mode == SandboxWorkspaceMode.VIRTUAL_DRY_RUN && hasVirtualReport && !isSelected) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldPulse)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxTaskSelectorRow(
    tasks: List<TaskWithSteps>,
    activeTask: TaskWithSteps?,
    selectedScene: TargetSceneType,
    onSelectTask: (TaskWithSteps) -> Unit,
    onSelectScene: (TargetSceneType) -> Unit,
    onPickImage: () -> Unit,
    onResetScene: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tasks.forEach { item ->
                FilterChip(
                    selected = activeTask?.task?.id == item.task.id,
                    onClick = { onSelectTask(item) },
                    label = { Text(item.task.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TargetSceneType.entries.forEach { scene ->
                FilterChip(
                    selected = selectedScene == scene,
                    onClick = { onSelectScene(scene) },
                    label = { Text(scene.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
            OutlinedButton(
                onClick = onPickImage,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("sandbox_pick_image_btn")
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入截图", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(
                onClick = onResetScene,
                modifier = Modifier.testTag("sandbox_reset_scene_btn")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "重置场景")
            }
        }
    }
}

@Composable
private fun TargetViewportBox(
    sandboxState: SandboxUiState,
    diagnosticOverlayState: DiagnosticOverlayState,
    onClearGeminiDiagnostic: () -> Unit,
    onViewportReady: (View) -> Unit,
    onDismissAdDialog: () -> Unit,
    onPerformCheckin: () -> Unit,
    onClaimPoints: () -> Unit,
    onActivateRewardBadge: () -> Unit,
    onConfirmRewardClaim: () -> Unit,
    onActivateCouponBadge: () -> Unit,
    onClaimFlashCoupon: () -> Unit,
    onRecordCustomCanvasTap: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Real Android View wrapping the target Compose UI so captureViewBitmap & dispatchTapOnView act on genuine pixels and Input events!
            AndroidView(
                factory = { ctx ->
                    ComposeView(ctx).also { cv ->
                        onViewportReady(cv)
                    }
                },
                update = { composeView ->
                    onViewportReady(composeView)
                    composeView.setContent {
                        SmartTapFlowTheme(darkTheme = false) {
                            SandboxTargetSceneContent(
                                selectedScene = sandboxState.selectedScene,
                                customScreenshot = sandboxState.customScreenshot,
                                sceneState = sandboxState.sceneState,
                                onDismissAdDialog = onDismissAdDialog,
                                onPerformCheckin = onPerformCheckin,
                                onClaimPoints = onClaimPoints,
                                onActivateRewardBadge = onActivateRewardBadge,
                                onConfirmRewardClaim = onConfirmRewardClaim,
                                onActivateCouponBadge = onActivateCouponBadge,
                                onClaimFlashCoupon = onClaimFlashCoupon,
                                onRecordCustomCanvasTap = onRecordCustomCanvasTap
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Non-blocking Laser Scanner & Tap Ripple Overlay
            LaserScannerOverlay(
                isRunning = sandboxState.isRunning,
                detectedRect = sandboxState.detectedRect,
                viewportWidthPx = sandboxState.viewportWidthPx,
                viewportHeightPx = sandboxState.viewportHeightPx,
                tapPoint = sandboxState.tapPoint,
                matchLabel = sandboxState.matchLabel,
                modifier = Modifier.fillMaxSize()
            )

            // Gemini Diagnostic UI Element Highlight Overlay
            GeminiDiagnosticOverlay(
                diagnosticState = diagnosticOverlayState,
                onDismissOverlay = onClearGeminiDiagnostic,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun SandboxTargetSceneContent(
    selectedScene: TargetSceneType,
    customScreenshot: Bitmap?,
    sceneState: SandboxSceneState,
    onDismissAdDialog: () -> Unit,
    onPerformCheckin: () -> Unit,
    onClaimPoints: () -> Unit,
    onActivateRewardBadge: () -> Unit,
    onConfirmRewardClaim: () -> Unit,
    onActivateCouponBadge: () -> Unit,
    onClaimFlashCoupon: () -> Unit,
    onRecordCustomCanvasTap: (Offset) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F9FC))
            .padding(14.dp)
    ) {
        when (selectedScene) {
            TargetSceneType.DAILY_CHECKIN -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "每日签到中心",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (sceneState.checkedInToday) "今日已完成签到 · 积分 +${sceneState.pointsClaimedCount * 50}"
                            else "连续签到可领取双倍积分奖励",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF475569)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onPerformCheckin,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sceneState.checkedInToday) EmeraldPulse else Color(0xFF0099C6)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (sceneState.checkedInToday) "已签到" else "立即签到",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = onClaimPoints,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "领取积分 (${sceneState.pointsClaimedCount})",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFFEAF2FA),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("当前可用积分", color = Color(0xFF334155))
                            Text(
                                text = "${120 + sceneState.pointsClaimedCount * 50} 分",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF0099C6)
                            )
                        }
                    }
                }

                if (sceneState.adDialogVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x990F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .padding(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "限时活动提醒",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF0F172A)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "测试弹出广告拦截识别效果，点击下方按钮关闭",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF64748B)
                                )
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = onDismissAdDialog,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("关闭广告", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            TargetSceneType.REWARD_CENTER -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "徽章视觉识图领奖",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF0F172A)
                    )

                    Surface(
                        color = if (sceneState.rewardBadgeActivated) Color(0xFFD1FAE5) else Color.White,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(
                            2.dp,
                            if (sceneState.rewardBadgeActivated) EmeraldPulse else Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(onClick = onActivateRewardBadge)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_reward_emblem),
                                contentDescription = "金色奖励徽章图标",
                                modifier = Modifier.size(84.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (sceneState.rewardBadgeActivated) "徽章已激活" else "点击星标徽章",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF1E293B)
                            )
                        }
                    }

                    Button(
                        onClick = onConfirmRewardClaim,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sceneState.rewardBadgeActivated) EmeraldPulse else Color(0xFF0099C6)
                        ),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        Icon(Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("确认领取 (${sceneState.rewardConfirmedCount})", fontWeight = FontWeight.Bold)
                    }
                }
            }

            TargetSceneType.FLASH_COUPON -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "闪购神券双模抢购",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF0F172A)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (sceneState.couponActivated) Color(0xFFFFEDD5) else Color.White,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(2.dp, Color(0xFFFB923C)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(onClick = onActivateCouponBadge)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_coupon_badge),
                                contentDescription = "闪购神券图标",
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(82.dp)
                            )
                        }

                        Column {
                            Text(
                                text = if (sceneState.couponActivated) "神券已解锁" else "请先识别左侧图标",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                text = "已抢到 ${sceneState.couponClaimedCount} 张",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF64748B)
                            )
                        }
                    }

                    Button(
                        onClick = onClaimFlashCoupon,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Icon(Icons.Default.LocalOffer, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("立即抢券", fontWeight = FontWeight.Bold)
                    }
                }
            }

            TargetSceneType.CUSTOM_IMAGE_CANVAS -> {
                if (customScreenshot != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    onRecordCustomCanvasTap(offset)
                                }
                            }
                    ) {
                        Image(
                            bitmap = customScreenshot.asImageBitmap(),
                            contentDescription = "用户导入的目标截图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            sceneState.customCanvasClicks.forEach { pt ->
                                drawCircle(
                                    color = EmeraldPulse.copy(alpha = 0.35f),
                                    radius = 22.dp.toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = EmeraldPulse,
                                    radius = 6.dp.toPx(),
                                    center = pt
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "请点击上方“导入截图”从手机相册选择一张界面图片",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF475569)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaserScannerOverlay(
    isRunning: Boolean,
    detectedRect: android.graphics.Rect?,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    tapPoint: Offset?,
    matchLabel: String?,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanY"
    )
    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (isRunning) {
                val y = size.height * scanProgress
                drawLine(
                    color = CyanBright.copy(alpha = 0.75f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 3.dp.toPx()
                )
            }

            if (detectedRect != null && viewportWidthPx > 0 && viewportHeightPx > 0) {
                val sx = size.width / viewportWidthPx.toFloat()
                val sy = size.height / viewportHeightPx.toFloat()
                val left = detectedRect.left * sx
                val top = detectedRect.top * sy
                val w = detectedRect.width() * sx
                val h = detectedRect.height() * sy

                drawRect(
                    color = EmeraldPulse.copy(alpha = 0.18f),
                    topLeft = Offset(left, top),
                    size = Size(w, h)
                )
                drawRect(
                    color = EmeraldPulse,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            if (tapPoint != null && viewportWidthPx > 0 && viewportHeightPx > 0) {
                val sx = size.width / viewportWidthPx.toFloat()
                val sy = size.height / viewportHeightPx.toFloat()
                val cx = tapPoint.x * sx
                val cy = tapPoint.y * sy
                drawCircle(
                    color = CyanBright.copy(alpha = 0.45f),
                    radius = 22.dp.toPx() * rippleScale,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = EmeraldPulse,
                    radius = 6.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }
        }

        if (!matchLabel.isNullOrBlank()) {
            Surface(
                color = NavyDeep.copy(alpha = 0.88f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            ) {
                Text(
                    text = matchLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanBright,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun GeminiDiagnosticOverlay(
    diagnosticState: DiagnosticOverlayState,
    onDismissOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!diagnosticState.isEnabled || !diagnosticState.isVisible) return

    val violetHighlight = Color(0xFFA855F7)
    val violetBorder = Color(0xFFC084FC)
    val amberAccent = Color(0xFFF59E0B)

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = diagnosticState.highlightRect
            if (rect != null && diagnosticState.viewportWidthPx > 0 && diagnosticState.viewportHeightPx > 0) {
                val sx = size.width / diagnosticState.viewportWidthPx.toFloat()
                val sy = size.height / diagnosticState.viewportHeightPx.toFloat()
                val left = rect.left * sx
                val top = rect.top * sy
                val right = rect.right * sx
                val bottom = rect.bottom * sy
                val w = (right - left).coerceAtLeast(8f)
                val h = (bottom - top).coerceAtLeast(8f)

                drawRect(
                    color = violetHighlight.copy(alpha = 0.16f),
                    topLeft = Offset(left, top),
                    size = Size(w, h)
                )
                drawRect(
                    color = violetBorder,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 8f), 0f)
                    )
                )

                val bracket = minOf(16.dp.toPx(), w * 0.35f, h * 0.35f)
                val bracketStroke = 3.5.dp.toPx()
                // Top-left
                drawLine(amberAccent, Offset(left, top), Offset(left + bracket, top), bracketStroke, StrokeCap.Round)
                drawLine(amberAccent, Offset(left, top), Offset(left, top + bracket), bracketStroke, StrokeCap.Round)
                // Top-right
                drawLine(amberAccent, Offset(right - bracket, top), Offset(right, top), bracketStroke, StrokeCap.Round)
                drawLine(amberAccent, Offset(right, top), Offset(right, top + bracket), bracketStroke, StrokeCap.Round)
                // Bottom-left
                drawLine(amberAccent, Offset(left, bottom), Offset(left + bracket, bottom), bracketStroke, StrokeCap.Round)
                drawLine(amberAccent, Offset(left, bottom - bracket), Offset(left, bottom), bracketStroke, StrokeCap.Round)
                // Bottom-right
                drawLine(amberAccent, Offset(right - bracket, bottom), Offset(right, bottom), bracketStroke, StrokeCap.Round)
                drawLine(amberAccent, Offset(right, bottom - bracket), Offset(right, bottom), bracketStroke, StrokeCap.Round)
            }
        }

        Surface(
            color = NavyDeep.copy(alpha = 0.92f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (diagnosticState.errorMessage != null) RoseAlert else violetBorder
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .testTag("sandbox_gemini_overlay_badge")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (diagnosticState.isAnalyzing) {
                    CircularProgressIndicator(
                        color = violetBorder,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (diagnosticState.errorMessage != null) RoseAlert else amberAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Column(modifier = Modifier.width(210.dp)) {
                    val headerText = when {
                        diagnosticState.isAnalyzing -> "Gemini 诊断中 · ${diagnosticState.stepTitle}"
                        diagnosticState.errorMessage != null -> "Gemini 诊断异常"
                        diagnosticState.elementFound -> {
                            val pct = (diagnosticState.confidence * 100f).roundToInt()
                            "[${diagnosticState.elementType}] ${diagnosticState.elementLabel} ($pct%)"
                        }
                        else -> "未定位目标 · ${diagnosticState.stepTitle}"
                    }
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diagnosticState.errorMessage != null) RoseAlert else Color(0xFFF3E8FF),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subText = diagnosticState.errorMessage ?: diagnosticState.diagnosticNote
                    if (subText.isNotBlank()) {
                        Text(
                            text = subText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDismissOverlay,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭诊断叠加层",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SandboxConsolePane(
    activeTask: TaskWithSteps?,
    sandboxState: SandboxUiState,
    rootRunnerState: RootRunnerState,
    pendingConfirmation: PendingConfirmationRequest?,
    diagnosticOverlayState: DiagnosticOverlayState,
    preloadState: PreloadEngineState,
    onRunSandbox: () -> Unit,
    onStopSandbox: () -> Unit,
    onRunRootTask: () -> Unit,
    onStopRootTask: () -> Unit,
    onResolveConfirmation: (Boolean) -> Unit,
    onToggleGeminiDiagnostic: (Boolean) -> Unit,
    onRunManualGeminiDiagnostic: () -> Unit,
    onTogglePreloadEngine: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sandboxState.isRunning) {
                    Button(
                        onClick = onStopSandbox,
                        colors = ButtonDefaults.buttonColors(containerColor = RoseAlert),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sandbox_stop_btn")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止沙盒")
                    }
                } else {
                    Button(
                        onClick = onRunSandbox,
                        enabled = activeTask != null && activeTask.sortedSteps.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sandbox_start_btn")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("运行沙盒识别点击")
                    }
                }

                if (rootRunnerState.isRunning) {
                    OutlinedButton(onClick = onStopRootTask) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止 Root")
                    }
                } else {
                    OutlinedButton(
                        onClick = onRunRootTask,
                        enabled = activeTask != null && activeTask.sortedSteps.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Root 全局")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sandboxState.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (sandboxState.loopBadgeText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = sandboxState.loopBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Gemini 诊断高亮叠加层",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        OutlinedButton(
                            onClick = onRunManualGeminiDiagnostic,
                            enabled = activeTask != null && !diagnosticOverlayState.isAnalyzing,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("sandbox_gemini_diag_btn")
                        ) {
                            Text(
                                text = if (diagnosticOverlayState.isAnalyzing) "分析中..." else "诊断当前画面",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = diagnosticOverlayState.isEnabled,
                            onCheckedChange = onToggleGeminiDiagnostic,
                            modifier = Modifier.testTag("sandbox_gemini_diag_switch")
                        )
                    }
                    val diagSummary = when {
                        diagnosticOverlayState.errorMessage != null -> "错误：${diagnosticOverlayState.errorMessage}"
                        diagnosticOverlayState.diagnosticNote.isNotBlank() -> {
                            val latency = diagnosticOverlayState.elapsedMs?.let { " · ${it}ms" } ?: ""
                            "${diagnosticOverlayState.diagnosticNote}$latency"
                        }
                        diagnosticOverlayState.isEnabled -> "执行 OCR/模板匹配时将自动调用 Gemini 高亮框选当前目标 UI 元素"
                        else -> "已关闭 Gemini 执行诊断高亮叠加层"
                    }
                    Text(
                        text = diagSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diagnosticOverlayState.errorMessage != null) RoseAlert
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = EmeraldPulse,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "任务预加载引擎",
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (preloadState.isPreloading) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "后台预匹配中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanBright,
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (preloadState.cacheHits > 0) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "命中 ${preloadState.cacheHits} 次 · -${preloadState.totalSavedLatencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldPulse,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Switch(
                            checked = preloadState.isEnabled,
                            onCheckedChange = onTogglePreloadEngine,
                            modifier = Modifier.testTag("sandbox_preload_switch")
                        )
                    }
                    Text(
                        text = preloadState.statusSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (pendingConfirmation != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sandbox_pending_confirm_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PanTool,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "人工确认等待中：${pendingConfirmation.stepTitle}",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Text(
                            text = pendingConfirmation.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onResolveConfirmation(true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("确认继续")
                            }
                            OutlinedButton(
                                onClick = { onResolveConfirmation(false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("终止", color = RoseAlert)
                            }
                        }
                    }
                }
            }

            // Step progress & live logs
            val displayLogs = if (rootRunnerState.isRunning || rootRunnerState.logs.isNotEmpty()) {
                sandboxState.logs + rootRunnerState.logs
            } else {
                sandboxState.logs
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NavyDeep)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeTask != null) {
                    itemsIndexed(activeTask.sortedSteps) { index, step ->
                        val isCurrent = index == sandboxState.currentStepIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isCurrent) EmeraldPulse else Color(0xFF475569))
                            )
                            Text(
                                text = "${index + 1}. ${step.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCurrent) EmeraldPulse else Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
                if (displayLogs.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                    }
                    itemsIndexed(displayLogs.reversed()) { _, line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = CyanBright,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(12.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }
    }
}
