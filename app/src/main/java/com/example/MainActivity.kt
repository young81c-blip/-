package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.engine.configureAppCheck
import com.example.ui.AutomationViewModel
import com.example.ui.components.ManualConfirmationAlertDialog
import com.example.ui.screens.InspectorScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.SandboxScreen
import com.example.ui.screens.TaskEditorScreen
import com.example.ui.screens.TasksScreen
import com.example.ui.theme.SmartTapFlowTheme
import kotlinx.serialization.Serializable

@Serializable
data object TasksRoute

@Serializable
data object SandboxRoute

@Serializable
data object InspectorRoute

@Serializable
data object LogsRoute

@Serializable
data class TaskEditorRoute(val taskId: Long? = null)

private data class TopLevelNavItem(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            SmartTapFlowTheme {
                SmartTapFlowApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartTapFlowApp(
    viewModel: AutomationViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val tasks by viewModel.tasksWithSteps.collectAsStateWithLifecycle()
    val logsWithEvents by viewModel.executionLogsWithEvents.collectAsStateWithLifecycle()
    val recentClickEvents by viewModel.recentClickEvents.collectAsStateWithLifecycle()
    val rootStatus by viewModel.rootStatus.collectAsStateWithLifecycle()
    val rootRunnerState by viewModel.rootRunnerState.collectAsStateWithLifecycle()
    val isFloatingVisible by viewModel.isFloatingWindowVisible.collectAsStateWithLifecycle()
    val sandboxState by viewModel.sandboxState.collectAsStateWithLifecycle()
    val inspectorState by viewModel.inspectorState.collectAsStateWithLifecycle()
    val jsonTransferState by viewModel.jsonTransferState.collectAsStateWithLifecycle()
    val notificationConfig by viewModel.notificationConfig.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val diagnosticOverlayState by viewModel.diagnosticOverlayState.collectAsStateWithLifecycle()
    val preloadState by viewModel.preloadState.collectAsStateWithLifecycle()
    val virtualSandboxState by viewModel.virtualSandboxState.collectAsStateWithLifecycle()

    val topLevelItems = listOf(
        TopLevelNavItem(
            route = TasksRoute,
            label = "任务",
            selectedIcon = Icons.Filled.SmartButton,
            unselectedIcon = Icons.Outlined.SmartButton,
            testTag = "nav_tasks"
        ),
        TopLevelNavItem(
            route = SandboxRoute,
            label = "执行台",
            selectedIcon = Icons.Filled.PlayCircle,
            unselectedIcon = Icons.Outlined.PlayCircle,
            testTag = "nav_sandbox"
        ),
        TopLevelNavItem(
            route = InspectorRoute,
            label = "识别拾取",
            selectedIcon = Icons.Filled.DocumentScanner,
            unselectedIcon = Icons.Outlined.DocumentScanner,
            testTag = "nav_inspector"
        ),
        TopLevelNavItem(
            route = LogsRoute,
            label = "日志",
            selectedIcon = Icons.Filled.History,
            unselectedIcon = Icons.Outlined.History,
            testTag = "nav_logs"
        )
    )

    val isTopLevelScreen = currentDestination?.let { dest ->
        dest.hasRoute<TasksRoute>() ||
            dest.hasRoute<SandboxRoute>() ||
            dest.hasRoute<InspectorRoute>() ||
            dest.hasRoute<LogsRoute>()
    } ?: true

    val topBarTitle = when {
        currentDestination?.hasRoute<SandboxRoute>() == true -> "自动化执行台"
        currentDestination?.hasRoute<InspectorRoute>() == true -> "OCR 与图像拾取"
        currentDestination?.hasRoute<LogsRoute>() == true -> "任务监控与 Root 诊断"
        else -> "智点任务"
    }

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (isTopLevelScreen) {
                    TopAppBar(
                        title = {
                            Text(
                                text = topBarTitle,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    )
                }
            },
            bottomBar = {
                if (isTopLevelScreen && !useRail) {
                    NavigationBar {
                        topLevelItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isTopLevelScreen && useRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        topLevelItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = TasksRoute
                    ) {
                        composable<TasksRoute> {
                            TasksScreen(
                                tasks = tasks,
                                rootStatus = rootStatus,
                                rootRunnerState = rootRunnerState,
                                isFloatingWindowVisible = isFloatingVisible,
                                jsonTransferState = jsonTransferState,
                                notificationConfig = notificationConfig,
                                onRefreshRoot = { viewModel.refreshRootStatus() },
                                onCreateTask = {
                                    navController.navigate(TaskEditorRoute(taskId = null))
                                },
                                onEditTask = { taskId ->
                                    navController.navigate(TaskEditorRoute(taskId = taskId))
                                },
                                onRunInSandbox = { taskItem ->
                                    viewModel.selectTaskForSandbox(taskItem)
                                    navigateTopLevel(SandboxRoute)
                                },
                                onStartRootTask = { taskId ->
                                    viewModel.startRootTask(taskId)
                                },
                                onPauseRootTask = {
                                    viewModel.pauseRootTask()
                                },
                                onResumeRootTask = {
                                    viewModel.resumeRootTask()
                                },
                                onStopRootTask = {
                                    viewModel.stopRootTask()
                                },
                                onStartFloatingWindow = { taskId ->
                                    viewModel.startFloatingControl(taskId)
                                },
                                onStopFloatingWindow = {
                                    viewModel.stopFloatingControl()
                                },
                                onToggleEnabled = { taskId, enabled ->
                                    viewModel.toggleTaskEnabled(taskId, enabled)
                                },
                                onDeleteTask = { taskId ->
                                    viewModel.deleteTask(taskId)
                                },
                                onExportAllTasks = { viewModel.requestExportTasks(null) },
                                onExportSingleTask = { taskId -> viewModel.requestExportTasks(taskId) },
                                onOpenImportDialog = { viewModel.openImportDialog() },
                                onDismissExportDialog = { viewModel.dismissExportDialog() },
                                onDismissImportDialog = { viewModel.dismissImportDialog() },
                                onConfirmImportJson = { rawJson, policy ->
                                    viewModel.confirmImportJson(rawJson, policy)
                                },
                                onNotifyTransferStatus = { message, isError ->
                                    viewModel.notifyTransferStatus(message, isError)
                                },
                                onClearTransferBanner = { viewModel.clearTransferBanner() },
                                onUpdateNotificationConfig = { cfg ->
                                    viewModel.updateNotificationConfig(cfg)
                                },
                                onSendTestNotification = { viewModel.sendTestNotification() }
                            )
                        }

                        composable<SandboxRoute> {
                            SandboxScreen(
                                tasks = tasks,
                                sandboxState = sandboxState,
                                rootRunnerState = rootRunnerState,
                                pendingConfirmation = pendingConfirmation,
                                diagnosticOverlayState = diagnosticOverlayState,
                                preloadState = preloadState,
                                virtualSandboxState = virtualSandboxState,
                                onSelectTask = { viewModel.selectTaskForSandbox(it) },
                                onSelectScene = { viewModel.selectSandboxScene(it) },
                                onPickCustomScreenshot = { viewModel.onCustomSceneImagePicked(it) },
                                onResetScene = { viewModel.resetSandboxScene() },
                                onRunSandbox = { task, cap, tap ->
                                    viewModel.runTaskInSandbox(task, cap, tap)
                                },
                                onStopSandbox = { viewModel.stopSandboxExecution() },
                                onRunRootTask = { taskId -> viewModel.startRootTask(taskId) },
                                onStopRootTask = { viewModel.stopRootTask() },
                                onResolveConfirmation = { confirmed ->
                                    viewModel.resolvePendingConfirmation(confirmed)
                                },
                                onToggleGeminiDiagnostic = { enabled ->
                                    viewModel.setGeminiDiagnosticEnabled(enabled)
                                },
                                onRunManualGeminiDiagnostic = { task, cap ->
                                    viewModel.runManualGeminiDiagnostic(task, cap)
                                },
                                onClearGeminiDiagnostic = {
                                    viewModel.clearGeminiDiagnosticOverlay()
                                },
                                onTogglePreloadEngine = { enabled ->
                                    viewModel.setPreloadEngineEnabled(enabled)
                                },
                                onVirtualImportVideo = { viewModel.importVirtualTestVideo(it) },
                                onVirtualAppendImages = { viewModel.appendVirtualTestImages(it) },
                                onVirtualLoadSample = { viewModel.loadSampleVirtualTestSequence(it) },
                                onVirtualSelectFrame = { viewModel.selectVirtualTestFrame(it) },
                                onVirtualRemoveFrame = { viewModel.removeVirtualTestFrame(it) },
                                onVirtualMoveFrame = { from, to -> viewModel.moveVirtualTestFrame(from, to) },
                                onVirtualClearFrames = { viewModel.clearVirtualTestFrames() },
                                onVirtualSetMode = { viewModel.setVirtualVerificationMode(it) },
                                onVirtualSetInterval = { viewModel.setVirtualVideoSampleInterval(it) },
                                onVirtualRunVerify = { viewModel.runVirtualScriptVerification(it) },
                                onVirtualStopVerify = { viewModel.stopVirtualVerification() },
                                onVirtualInspectStep = { viewModel.inspectVirtualStepResult(it) },
                                onDismissAdDialog = { viewModel.dismissSandboxAdDialog() },
                                onPerformCheckin = { viewModel.performSandboxCheckin() },
                                onClaimPoints = { viewModel.claimSandboxPoints() },
                                onActivateRewardBadge = { viewModel.activateRewardBadge() },
                                onConfirmRewardClaim = { viewModel.confirmRewardClaim() },
                                onActivateCouponBadge = { viewModel.activateCouponBadge() },
                                onClaimFlashCoupon = { viewModel.claimFlashCoupon() },
                                onRecordCustomCanvasTap = { viewModel.recordCustomCanvasTap(it) }
                            )
                        }

                        composable<InspectorRoute> {
                            InspectorScreen(
                                state = inspectorState,
                                tasks = tasks,
                                hasRoot = rootStatus?.hasRootAccess == true,
                                onLoadSample = { viewModel.loadSampleRewardIntoInspector() },
                                onPickImage = { viewModel.loadImageIntoInspector(it) },
                                onRootScreencap = { viewModel.captureRootScreenIntoInspector() },
                                onRunOcr = { regionOnly -> viewModel.runInspectorOcr(regionOnly) },
                                onSelectTextElement = { viewModel.selectInspectorTextElement(it) },
                                onUpdateCropBox = { viewModel.updateCropBox(it) },
                                onApplySearchRoi = { viewModel.applyCropBoxAsSearchRoi() },
                                onClearSearchRoi = { viewModel.clearSearchRoi() },
                                onCropAndTest = { viewModel.cropAndSaveTemplateAndTest() },
                                onSaveOcrStep = { taskId, text, useRoi ->
                                    viewModel.saveOcrStepFromInspector(taskId, text, useRoi)
                                },
                                onSaveImageStep = { taskId, path, threshold, useRoi ->
                                    viewModel.saveImageStepFromInspector(taskId, path, threshold, useRoi)
                                },
                                onRunSmartDetect = { viewModel.runSmartDetectionOnInspectorRegion() },
                                onApplySmartRecommendation = { taskId, res, bindRoi ->
                                    viewModel.applySmartDetectionRecommendation(taskId, res, bindRoi)
                                }
                            )
                        }

                        composable<LogsRoute> {
                            LogsScreen(
                                logsWithEvents = logsWithEvents,
                                recentClickEvents = recentClickEvents,
                                tasks = tasks,
                                rootStatus = rootStatus,
                                onRefreshRoot = { viewModel.refreshRootStatus() },
                                onClearLogs = { viewModel.clearExecutionLogs() }
                            )
                        }

                        composable<TaskEditorRoute> { entry ->
                            val route = entry.toRoute<TaskEditorRoute>()
                            val existing = tasks.firstOrNull { it.task.id == route.taskId }
                            TaskEditorScreen(
                                existingTask = existing,
                                repository = viewModel.repository,
                                hasRoot = rootStatus?.hasRootAccess == true,
                                initialDetectionBitmap = inspectorState.sourceBitmap,
                                initialDetectionCropBox = inspectorState.cropBox,
                                onCaptureRootBitmap = { viewModel.captureRootBitmapForDetection() },
                                onCreateSampleBitmap = { viewModel.buildSampleInspectorBitmap() },
                                onAnalyzeRegion = { bmp, box -> viewModel.analyzeRegionForDetection(bmp, box) },
                                onBuildSmartTemplateStep = { bmp, box, threshold, title, bindRoi ->
                                    viewModel.buildSmartTemplateStep(bmp, box, threshold, title, bindRoi)
                                },
                                onSave = { task, steps ->
                                    viewModel.saveEditedTask(task, steps) {
                                        navController.popBackStack()
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }

        pendingConfirmation?.let { request ->
            ManualConfirmationAlertDialog(
                request = request,
                onConfirmContinue = { viewModel.resolvePendingConfirmation(true) },
                onAbortTask = { viewModel.resolvePendingConfirmation(false) }
            )
        }
    }
}
