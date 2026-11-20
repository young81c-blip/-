package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.ImportConflictPolicy
import com.example.data.LoopMode
import com.example.data.StepType
import com.example.data.StopConditionType
import com.example.data.TargetSceneType
import com.example.data.TaskWithSteps
import com.example.engine.NotificationConfig
import com.example.engine.RootRunnerState
import com.example.engine.RootStatus
import com.example.engine.TaskNotificationManager
import com.example.ui.JsonTransferUiState
import com.example.ui.components.ExportJsonDialog
import com.example.ui.components.ImportJsonDialog
import com.example.ui.components.NotificationSettingsDialog
import com.example.ui.theme.CyanBright
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavySurface
import com.example.ui.theme.RoseAlert

@Composable
fun TasksScreen(
    tasks: List<TaskWithSteps>,
    rootStatus: RootStatus?,
    rootRunnerState: RootRunnerState,
    isFloatingWindowVisible: Boolean,
    jsonTransferState: JsonTransferUiState,
    notificationConfig: NotificationConfig,
    onRefreshRoot: () -> Unit,
    onCreateTask: () -> Unit,
    onEditTask: (Long) -> Unit,
    onRunInSandbox: (TaskWithSteps) -> Unit,
    onStartRootTask: (Long) -> Unit,
    onPauseRootTask: () -> Unit,
    onResumeRootTask: () -> Unit,
    onStopRootTask: () -> Unit,
    onStartFloatingWindow: (Long?) -> Unit,
    onStopFloatingWindow: () -> Unit,
    onToggleEnabled: (Long, Boolean) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onExportAllTasks: () -> Unit,
    onExportSingleTask: (Long) -> Unit,
    onOpenImportDialog: () -> Unit,
    onDismissExportDialog: () -> Unit,
    onDismissImportDialog: () -> Unit,
    onConfirmImportJson: (String, ImportConflictPolicy) -> Unit,
    onNotifyTransferStatus: (String, Boolean) -> Unit,
    onClearTransferBanner: () -> Unit,
    onUpdateNotificationConfig: (NotificationConfig) -> Unit,
    onSendTestNotification: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingRootTaskId by remember { mutableStateOf<Long?>(null) }
    var pendingFloatingOpen by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    var hasNotifyPermission by remember {
        mutableStateOf(TaskNotificationManager.hasNotificationPermission(context))
    }
    var taskToDelete by remember { mutableStateOf<TaskWithSteps?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifyPermission = granted || TaskNotificationManager.hasNotificationPermission(context)
        pendingRootTaskId?.let { id ->
            onStartRootTask(id)
            pendingRootTaskId = null
        }
        if (pendingFloatingOpen) {
            pendingFloatingOpen = false
            onStartFloatingWindow(tasks.firstOrNull()?.task?.id)
        }
    }

    fun launchRootSafely(taskId: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                onStartRootTask(taskId)
            } else {
                pendingRootTaskId = taskId
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            onStartRootTask(taskId)
        }
    }

    fun toggleFloatingControl() {
        if (isFloatingWindowVisible) {
            onStopFloatingWindow()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            showOverlayPermissionDialog = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingFloatingOpen = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        onStartFloatingWindow(tasks.firstOrNull()?.task?.id)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HeroStatusCard(
                    tasks = tasks,
                    rootStatus = rootStatus,
                    rootRunnerState = rootRunnerState,
                    isFloatingWindowVisible = isFloatingWindowVisible,
                    onRefreshRoot = onRefreshRoot,
                    onToggleFloatingWindow = { toggleFloatingControl() },
                    onPauseRootTask = onPauseRootTask,
                    onResumeRootTask = onResumeRootTask,
                    onStopRootTask = onStopRootTask
                )
            }

            item {
                NotificationStatusCard(
                    config = notificationConfig,
                    hasPermission = hasNotifyPermission,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenSettings = {
                        hasNotifyPermission = TaskNotificationManager.hasNotificationPermission(context)
                        showNotificationDialog = true
                    },
                    onSendTest = {
                        hasNotifyPermission = TaskNotificationManager.hasNotificationPermission(context)
                        if (!hasNotifyPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val ok = onSendTestNotification()
                            onNotifyTransferStatus(
                                if (ok) "已发送自定义测试通知到系统状态栏" else "通知发送受限，请先检查通知权限",
                                !ok
                            )
                        }
                    }
                )
            }

            item {
                WorkflowTransferCard(
                    hasTasks = tasks.isNotEmpty(),
                    isBusy = jsonTransferState.isBusy,
                    statusBanner = jsonTransferState.statusBanner,
                    isErrorBanner = jsonTransferState.isErrorBanner,
                    onExportAll = onExportAllTasks,
                    onOpenImport = onOpenImportDialog,
                    onDismissBanner = onClearTransferBanner
                )
            }

            if (tasks.isEmpty()) {
                item {
                    EmptyTasksCard(
                        onCreateTask = onCreateTask,
                        onImportJson = onOpenImportDialog
                    )
                }
            } else {
                items(tasks, key = { it.task.id }) { item ->
                    TaskWorkflowCard(
                        item = item,
                        hasRoot = rootStatus?.hasRootAccess == true,
                        isRootRunningThis = rootRunnerState.isRunning && rootRunnerState.taskId == item.task.id,
                        onRunSandbox = { onRunInSandbox(item) },
                        onRunRoot = { launchRootSafely(item.task.id) },
                        onStopRoot = onStopRootTask,
                        onEdit = { onEditTask(item.task.id) },
                        onExportJson = { onExportSingleTask(item.task.id) },
                        onToggleEnabled = { enabled -> onToggleEnabled(item.task.id, enabled) },
                        onDelete = { taskToDelete = item }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onCreateTask,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("create_task_fab")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建任务")
                Spacer(Modifier.width(6.dp))
                Text("新建任务", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("需要系统悬浮窗权限") },
            text = {
                Text("开启“显示在其他应用上层”权限后，即可在目标应用上方悬浮展示控制台，随时启动、暂停或终止自动化任务。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                ) {
                    Text("前往授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    taskToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text("删除自动化任务") },
            text = { Text("确定删除「${target.task.name}」及其所有步骤？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTask(target.task.id)
                        taskToDelete = null
                    }
                ) {
                    Text("删除", color = RoseAlert)
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    jsonTransferState.activeExportPayload?.let { payload ->
        ExportJsonDialog(
            payload = payload,
            onDismiss = onDismissExportDialog,
            onNotify = onNotifyTransferStatus
        )
    }

    if (jsonTransferState.showImportDialog) {
        ImportJsonDialog(
            isBusy = jsonTransferState.isBusy,
            onDismiss = onDismissImportDialog,
            onConfirmImport = onConfirmImportJson
        )
    }

    if (showNotificationDialog) {
        NotificationSettingsDialog(
            config = notificationConfig,
            hasPermission = hasNotifyPermission,
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onSaveConfig = onUpdateNotificationConfig,
            onSendTestNotification = onSendTestNotification,
            onDismiss = { showNotificationDialog = false }
        )
    }
}

@Composable
private fun NotificationStatusCard(
    config: NotificationConfig,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendTest: () -> Unit
) {
    val activeRules = buildList {
        if (config.notifyOnComplete) {
            add(if (config.minLongRunSeconds > 0) "完成(长任务≥${config.minLongRunSeconds}s)" else "完成")
        }
        if (config.notifyOnFailure) add("失败")
        if (config.notifyOnManualConfirm) add("人工确认")
    }.joinToString("/")

    val statusText = when {
        !hasPermission -> "需授予系统通知权限才能弹窗提醒任务完成、失败或人工确认分支"
        !config.masterEnabled -> "全局状态栏通知推送已关闭 · 可随时重新开启"
        activeRules.isBlank() -> "通知通道已启用（未勾选触发条件）"
        else -> "实时提醒已就绪 · 触发规则：$activeRules"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (!hasPermission) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("notification_status_card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (hasPermission && config.masterEnabled) {
                        Icons.Default.NotificationsActive
                    } else {
                        Icons.Default.NotificationsOff
                    },
                    contentDescription = null,
                    tint = if (!hasPermission) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("任务结果与分支确认推送", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!hasPermission) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!hasPermission) {
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("card_grant_notify_btn")
                    ) {
                        Text("授权通知权限")
                    }
                } else {
                    OutlinedButton(
                        onClick = onSendTest,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("card_test_notify_btn")
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("测试推送")
                    }
                }
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_notify_settings_btn")
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("自定义规则")
                }
            }
        }
    }
}

@Composable
private fun WorkflowTransferCard(
    hasTasks: Boolean,
    isBusy: Boolean,
    statusBanner: String?,
    isErrorBanner: Boolean,
    onExportAll: () -> Unit,
    onOpenImport: () -> Unit,
    onDismissBanner: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        imageVector = Icons.Default.DataObject,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "跨设备配置迁移 (JSON)",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "导出或导入完整流程参数、ROI 区域与内嵌模板",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onExportAll,
                    enabled = hasTasks && !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("export_all_json_btn")
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出全部 JSON")
                }
                FilledTonalButton(
                    onClick = onOpenImport,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_json_btn")
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入 JSON")
                }
            }

            AnimatedVisibility(visible = !statusBanner.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isErrorBanner) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusBanner.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isErrorBanner) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissBanner,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭提示",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStatusCard(
    tasks: List<TaskWithSteps>,
    rootStatus: RootStatus?,
    rootRunnerState: RootRunnerState,
    isFloatingWindowVisible: Boolean,
    onRefreshRoot: () -> Unit,
    onToggleFloatingWindow: () -> Unit,
    onPauseRootTask: () -> Unit,
    onResumeRootTask: () -> Unit,
    onStopRootTask: () -> Unit
) {
    val totalClicks = remember(tasks) { tasks.sumOf { it.task.totalClicksExecuted } }
    val enabledCount = remember(tasks) { tasks.count { it.task.isEnabled } }
    val hasRoot = rootStatus?.hasRootAccess == true

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = NavyDeep,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(NavyDeep, NavySurface, Color(0xFF0E2942))
                    )
                )
        ) {
            // Subtle technical blueprint grid
            Canvas(modifier = Modifier.matchParentSize()) {
                val gridStep = 28.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Color(0x1422D3EE),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += gridStep
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        color = Color(0x1422D3EE),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += gridStep
                }
            }

            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (hasRoot) EmeraldPulse.copy(alpha = 0.18f) else CyanBright.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(
                            1.dp,
                            if (hasRoot) EmeraldPulse.copy(alpha = 0.55f) else CyanBright.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (hasRoot) EmeraldPulse else CyanBright)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (hasRoot) "Root 直连已就绪 · 无障碍零依赖" else "免无障碍引擎 · 沙盒 & Root 双模",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasRoot) EmeraldPulse else CyanBright
                            )
                        }
                    }

                    IconButton(
                        onClick = onRefreshRoot,
                        modifier = Modifier.testTag("refresh_root_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新 Root 状态",
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "智点自动化引擎",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "集成中英文 OCR 识别与多尺度图像模板匹配，不依赖无障碍服务，支持 Root 全局执行与沙盒验证。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB8C7DC)
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroMetricPill(
                        label = "启用任务",
                        value = "$enabledCount/${tasks.size}",
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetricPill(
                        label = "累计点击",
                        value = "$totalClicks",
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetricPill(
                        label = "Root 通道",
                        value = if (hasRoot) "uid=0" else "未授权",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))

                FilledTonalButton(
                    onClick = onToggleFloatingWindow,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isFloatingWindowVisible) EmeraldPulse else Color.White.copy(alpha = 0.14f),
                        contentColor = if (isFloatingWindowVisible) NavyDeep else Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("toggle_floating_window_btn")
                ) {
                    Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isFloatingWindowVisible) "悬浮控制台已开启（点击关闭）" else "开启系统悬浮控制台",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                AnimatedVisibility(visible = rootRunnerState.isRunning) {
                    Surface(
                        color = EmeraldPulse.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, EmeraldPulse.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Root 任务运行中：${rootRunnerState.taskName}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                            Text(
                                text = rootRunnerState.statusMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldPulse,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (rootRunnerState.isPaused) {
                                    FilledTonalButton(onClick = onResumeRootTask, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("继续")
                                    }
                                } else {
                                    FilledTonalButton(onClick = onPauseRootTask, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("暂停")
                                    }
                                }
                                FilledTonalButton(
                                    onClick = onStopRootTask,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = RoseAlert,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("终止")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
    }
}

@Composable
private fun TaskWorkflowCard(
    item: TaskWithSteps,
    hasRoot: Boolean,
    isRootRunningThis: Boolean,
    onRunSandbox: () -> Unit,
    onRunRoot: () -> Unit,
    onStopRoot: () -> Unit,
    onEdit: () -> Unit,
    onExportJson: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val task = item.task
    val steps = item.sortedSteps
    val ocrCount = steps.count { it.stepType == StepType.OCR_TEXT_TAP.name }
    val imgCount = steps.count { it.stepType == StepType.IMAGE_TEMPLATE_TAP.name }
    val sceneLabel = runCatching { TargetSceneType.valueOf(task.sceneType).label }
        .getOrDefault("默认场景")
    val loopMode = runCatching { LoopMode.valueOf(task.loopMode) }.getOrDefault(LoopMode.FIXED_COUNT)
    val stopCond = runCatching { StopConditionType.valueOf(task.stopConditionType) }
        .getOrDefault(StopConditionType.NONE)
    val loopSummary = when (loopMode) {
        LoopMode.FIXED_COUNT -> "固定 ${task.repeatCount} 次"
        LoopMode.INFINITE -> "无限循环"
        LoopMode.UNTIL_CONDITION -> "条件循环"
    }
    val stopSummary = when (stopCond) {
        StopConditionType.NONE -> ""
        StopConditionType.STOP_WHEN_TEXT_APPEARS -> " · 见「${task.stopConditionText}」停"
        StopConditionType.STOP_WHEN_TEXT_MISSING -> " · 失「${task.stopConditionText}」停"
        else -> " · ${stopCond.label}"
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$sceneLabel · ${steps.size} 步 · $loopSummary$stopSummary",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = task.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("task_switch_${task.id}")
                )
            }

            if (task.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val containerCount = remember(steps) {
                    steps.mapNotNull { it.groupId.takeIf(String::isNotBlank) }.distinct().size
                }
                if (containerCount > 0) {
                    StepCapabilityBadge(
                        icon = Icons.Default.AccountTree,
                        text = "容器 ×$containerCount",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (ocrCount > 0) {
                    StepCapabilityBadge(
                        icon = Icons.Default.TextFields,
                        text = "OCR ×$ocrCount",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (imgCount > 0) {
                    StepCapabilityBadge(
                        icon = Icons.Default.ImageSearch,
                        text = "识图 ×$imgCount",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                val manualBranchCount = remember(steps) {
                    steps.count { it.stepType == StepType.MANUAL_CONFIRM_BRANCH.name }
                }
                if (manualBranchCount > 0) {
                    StepCapabilityBadge(
                        icon = Icons.Default.PanTool,
                        text = "人工分支 ×$manualBranchCount",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (task.longRunThresholdSec >= 0) {
                    StepCapabilityBadge(
                        icon = Icons.Default.NotificationsActive,
                        text = if (task.longRunThresholdSec == 0) "即时通知" else "长任务≥${task.longRunThresholdSec}s通知",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (task.lastRunSuccess != null) {
                    StepCapabilityBadge(
                        icon = if (task.lastRunSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        text = if (task.lastRunSuccess) "上次成功" else "上次未全命中",
                        container = if (task.lastRunSuccess) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        content = if (task.lastRunSuccess) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRunSandbox,
                    enabled = task.isEnabled && steps.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("run_sandbox_btn_${task.id}")
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("沙盒运行", style = MaterialTheme.typography.labelLarge)
                }

                if (isRootRunningThis) {
                    FilledTonalButton(
                        onClick = onStopRoot,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = RoseAlert,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止 Root")
                    }
                } else {
                    OutlinedButton(
                        onClick = onRunRoot,
                        enabled = task.isEnabled && steps.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("run_root_btn_${task.id}")
                    ) {
                        Icon(
                            imageVector = if (hasRoot) Icons.Default.PlayArrow else Icons.Default.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Root 运行", style = MaterialTheme.typography.labelLarge)
                    }
                }

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_task_btn_${task.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑任务")
                }
                IconButton(
                    onClick = onExportJson,
                    modifier = Modifier.testTag("export_task_btn_${task.id}")
                ) {
                    Icon(
                        Icons.Default.DataObject,
                        contentDescription = "导出任务 JSON",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_task_btn_${task.id}")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除任务",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCapabilityBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    container: Color,
    content: Color
) {
    Surface(
        color = container,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

@Composable
private fun EmptyTasksCard(
    onCreateTask: () -> Unit,
    onImportJson: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("暂无自动化任务", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "点击“新建任务”编排 OCR 文字点击或图像模板点击流程，也可从 JSON 配置文件导入跨设备任务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCreateTask) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("创建任务")
                }
                OutlinedButton(onClick = onImportJson) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("导入 JSON")
                }
            }
        }
    }
}
