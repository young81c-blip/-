package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.data.AutomationRepository
import com.example.data.AutomationStepEntity
import com.example.data.AutomationTaskEntity
import com.example.data.BranchConditionType
import com.example.data.LoopMode
import com.example.data.StepType
import com.example.data.StopConditionType
import com.example.data.TargetSceneType
import com.example.data.TaskWithSteps
import com.example.data.TextMatchMode
import com.example.engine.SmartDetectionResult
import com.example.ui.CropBoxNorm
import com.example.ui.components.CreateOrRenameContainerDialog
import com.example.ui.components.EditorFlowBlock
import com.example.ui.components.FlowAlignmentActiveOverlayCanvas
import com.example.ui.components.FlowAlignmentBackdropCanvas
import com.example.ui.components.FlowDragAlignmentHud
import com.example.ui.components.NodeContainerCard
import com.example.ui.components.NodeContainerUtils
import com.example.ui.components.SmartRegionDetectDialog
import com.example.ui.components.StepNodeCard
import com.example.ui.components.rememberFlowBlockDragState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    existingTask: TaskWithSteps?,
    repository: AutomationRepository,
    hasRoot: Boolean,
    initialDetectionBitmap: Bitmap?,
    initialDetectionCropBox: CropBoxNorm,
    onCaptureRootBitmap: suspend () -> Result<Bitmap>,
    onCreateSampleBitmap: suspend () -> Bitmap,
    onAnalyzeRegion: suspend (Bitmap, CropBoxNorm) -> SmartDetectionResult,
    onBuildSmartTemplateStep: suspend (Bitmap, CropBoxNorm, Float, String, Boolean) -> AutomationStepEntity,
    onSave: (AutomationTaskEntity, List<AutomationStepEntity>) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var name by remember(existingTask) {
        mutableStateOf(existingTask?.task?.name ?: "新自动化任务")
    }
    var description by remember(existingTask) {
        mutableStateOf(existingTask?.task?.description ?: "")
    }
    var sceneType by remember(existingTask) {
        mutableStateOf(
            runCatching { TargetSceneType.valueOf(existingTask?.task?.sceneType ?: "") }
                .getOrDefault(TargetSceneType.DAILY_CHECKIN)
        )
    }
    var loopMode by remember(existingTask) {
        mutableStateOf(
            runCatching { LoopMode.valueOf(existingTask?.task?.loopMode ?: "") }
                .getOrDefault(LoopMode.FIXED_COUNT)
        )
    }
    var repeatCount by remember(existingTask) {
        mutableIntStateOf(existingTask?.task?.repeatCount ?: 1)
    }
    var loopIntervalMs by remember(existingTask) {
        mutableFloatStateOf((existingTask?.task?.loopIntervalMs ?: 800L).toFloat())
    }
    var loopIntervalText by remember(existingTask) {
        mutableStateOf((existingTask?.task?.loopIntervalMs ?: 800L).toString())
    }
    var stopConditionType by remember(existingTask) {
        mutableStateOf(
            runCatching { StopConditionType.valueOf(existingTask?.task?.stopConditionType ?: "") }
                .getOrDefault(StopConditionType.NONE)
        )
    }
    var stopConditionText by remember(existingTask) {
        mutableStateOf(existingTask?.task?.stopConditionText ?: "")
    }
    var stopConditionImagePath by remember(existingTask) {
        mutableStateOf(existingTask?.task?.stopConditionImagePath ?: "drawable:img_reward_emblem")
    }
    var stopConditionThreshold by remember(existingTask) {
        mutableFloatStateOf(existingTask?.task?.stopConditionThreshold ?: 0.76f)
    }
    var maxDurationMinutes by remember(existingTask) {
        mutableIntStateOf(existingTask?.task?.maxDurationMinutes ?: 0)
    }
    var taskNotifyOnComplete by remember(existingTask) {
        mutableStateOf(existingTask?.task?.notifyOnComplete ?: true)
    }
    var taskNotifyOnFailure by remember(existingTask) {
        mutableStateOf(existingTask?.task?.notifyOnFailure ?: true)
    }
    var taskNotifyOnManualConfirm by remember(existingTask) {
        mutableStateOf(existingTask?.task?.notifyOnManualConfirm ?: true)
    }
    var taskLongRunThresholdSec by remember(existingTask) {
        mutableIntStateOf(existingTask?.task?.longRunThresholdSec ?: -1)
    }
    var taskLongRunThresholdText by remember(existingTask) {
        val initVal = existingTask?.task?.longRunThresholdSec ?: -1
        mutableStateOf(if (initVal >= 0) initVal.toString() else "")
    }
    var customNotifyNote by remember(existingTask) {
        mutableStateOf(existingTask?.task?.customNotifyNote ?: "")
    }
    var nextTempStepId by remember(existingTask) { mutableLongStateOf(-1L) }
    fun allocateTempStepId(): Long {
        val id = nextTempStepId
        nextTempStepId -= 1L
        return id
    }
    fun normalizeEditorSteps(raw: List<AutomationStepEntity>): List<AutomationStepEntity> {
        val seen = HashSet<Long>(raw.size)
        return raw.map { s ->
            if (s.id != 0L && seen.add(s.id)) {
                s
            } else {
                val uid = allocateTempStepId()
                seen.add(uid)
                s.copy(id = uid)
            }
        }
    }

    var steps by remember(existingTask) {
        mutableStateOf(normalizeEditorSteps(existingTask?.sortedSteps ?: emptyList()))
    }

    val editorScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val headerItemCount = 6
    val dragState = rememberFlowBlockDragState(
        lazyListState = lazyListState,
        headerItemCount = headerItemCount,
        haptic = haptic
    )
    var showAlignmentGuides by remember { mutableStateOf(true) }
    var editingStepIndex by remember { mutableStateOf<Int?>(null) }
    var showStepDialog by remember { mutableStateOf(false) }
    var showSmartDetectDialog by remember { mutableStateOf(false) }
    var prefilledStepTemplate by remember { mutableStateOf<AutomationStepEntity?>(null) }
    var targetContainerInsert by remember { mutableStateOf<Triple<String, String, Int>?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf(emptySet<Int>()) }
    var showCreateContainerDialog by remember { mutableStateOf(false) }
    var renamingContainer by remember { mutableStateOf<EditorFlowBlock.NodeContainer?>(null) }

    val flowBlocks = remember(steps) { NodeContainerUtils.buildBlocks(steps) }
    val latestSteps by rememberUpdatedState(steps)
    val latestFlowBlocks by rememberUpdatedState(flowBlocks)
    val containerCount = remember(flowBlocks) {
        flowBlocks.count { it is EditorFlowBlock.NodeContainer }
    }

    val stopPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            editorScope.launch {
                val path = repository.savePickedUriAsTemplate(uri)
                if (path != null) stopConditionImagePath = path
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingTask == null) "新建自动化任务" else "编辑任务流程") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val cleanName = name.trim().ifBlank { "未命名自动化任务" }
                            val baseTask = existingTask?.task?.copy(
                                name = cleanName,
                                description = description.trim(),
                                sceneType = sceneType.name,
                                loopMode = loopMode.name,
                                repeatCount = repeatCount.coerceIn(1, 99),
                                loopIntervalMs = loopIntervalMs.roundToInt().toLong(),
                                stopConditionType = stopConditionType.name,
                                stopConditionText = stopConditionText.trim(),
                                stopConditionImagePath = stopConditionImagePath,
                                stopConditionThreshold = stopConditionThreshold,
                                maxDurationMinutes = maxDurationMinutes,
                                notifyOnComplete = taskNotifyOnComplete,
                                notifyOnFailure = taskNotifyOnFailure,
                                notifyOnManualConfirm = taskNotifyOnManualConfirm,
                                longRunThresholdSec = taskLongRunThresholdSec,
                                customNotifyNote = customNotifyNote.trim()
                            ) ?: AutomationTaskEntity(
                                name = cleanName,
                                description = description.trim(),
                                sceneType = sceneType.name,
                                loopMode = loopMode.name,
                                repeatCount = repeatCount.coerceIn(1, 99),
                                loopIntervalMs = loopIntervalMs.roundToInt().toLong(),
                                stopConditionType = stopConditionType.name,
                                stopConditionText = stopConditionText.trim(),
                                stopConditionImagePath = stopConditionImagePath,
                                stopConditionThreshold = stopConditionThreshold,
                                maxDurationMinutes = maxDurationMinutes,
                                notifyOnComplete = taskNotifyOnComplete,
                                notifyOnFailure = taskNotifyOnFailure,
                                notifyOnManualConfirm = taskNotifyOnManualConfirm,
                                longRunThresholdSec = taskLongRunThresholdSec,
                                customNotifyNote = customNotifyNote.trim()
                            )
                            onSave(baseTask, steps)
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("editor_save_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("保存")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .align(Alignment.TopCenter)
            ) {
                FlowAlignmentBackdropCanvas(
                    lazyListState = lazyListState,
                    headerItemCount = headerItemCount,
                    showGuides = showAlignmentGuides,
                    hasBlocks = flowBlocks.isNotEmpty()
                )

                LazyColumn(
                    state = lazyListState,
                    userScrollEnabled = !dragState.isDragging,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("任务名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("editor_name_input")
                    )
                }
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("任务说明（选填）") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("默认沙盒目标场景", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TargetSceneType.entries.forEach { s ->
                                FilterChip(
                                    selected = sceneType == s,
                                    onClick = { sceneType = s },
                                    label = { Text(s.label) }
                                )
                            }
                        }
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("循环与停止控制", style = MaterialTheme.typography.titleMedium)

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LoopMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = loopMode == mode,
                                        onClick = { loopMode = mode },
                                        label = { Text(mode.label) },
                                        modifier = Modifier.testTag("loop_mode_${mode.name}")
                                    )
                                }
                            }
                            Text(
                                text = loopMode.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (loopMode == LoopMode.FIXED_COUNT) {
                                Text(
                                    text = "执行次数：$repeatCount 次",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(1, 3, 5, 10, 20, 50).forEach { preset ->
                                        FilterChip(
                                            selected = repeatCount == preset,
                                            onClick = { repeatCount = preset },
                                            label = { Text("${preset}次", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                                Slider(
                                    value = repeatCount.toFloat(),
                                    onValueChange = { repeatCount = it.roundToInt().coerceIn(1, 50) },
                                    valueRange = 1f..50f
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = loopIntervalText,
                                    onValueChange = { raw ->
                                        val digits = raw.filter { it.isDigit() }.take(5)
                                        loopIntervalText = digits
                                        digits.toLongOrNull()?.let { v ->
                                            loopIntervalMs = v.coerceIn(100L, 60000L).toFloat()
                                        }
                                    },
                                    label = { Text("每轮间隔延时 (ms)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("loop_interval_ms_input")
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "滑块调节：${loopIntervalMs.roundToInt()} ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = loopIntervalMs.coerceIn(100f, 5000f),
                                        onValueChange = {
                                            val v = it.roundToInt()
                                            loopIntervalMs = v.toFloat()
                                            loopIntervalText = v.toString()
                                        },
                                        valueRange = 100f..5000f
                                    )
                                }
                            }

                            Text("条件停止机制", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StopConditionType.entries.forEach { cond ->
                                    FilterChip(
                                        selected = stopConditionType == cond,
                                        onClick = { stopConditionType = cond },
                                        label = { Text(cond.label, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("stop_cond_${cond.name}")
                                    )
                                }
                            }

                            if (stopConditionType == StopConditionType.STOP_WHEN_TEXT_APPEARS ||
                                stopConditionType == StopConditionType.STOP_WHEN_TEXT_MISSING
                            ) {
                                OutlinedTextField(
                                    value = stopConditionText,
                                    onValueChange = { stopConditionText = it },
                                    label = { Text("停止判断目标文字（如：已签到、已抢到 2 张）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("stop_condition_text_input")
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("已签到", "已抢到 2 张", "已完成", "确认领取 (2)").forEach { suggestion ->
                                        FilterChip(
                                            selected = stopConditionText == suggestion,
                                            onClick = { stopConditionText = suggestion },
                                            label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }

                            if (stopConditionType == StopConditionType.STOP_WHEN_IMAGE_APPEARS) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PresetTemplateThumb(
                                        selected = stopConditionImagePath == "drawable:img_reward_emblem",
                                        drawableRes = R.drawable.img_reward_emblem,
                                        label = "星标徽章",
                                        onClick = { stopConditionImagePath = "drawable:img_reward_emblem" }
                                    )
                                    PresetTemplateThumb(
                                        selected = stopConditionImagePath == "drawable:img_coupon_badge",
                                        drawableRes = R.drawable.img_coupon_badge,
                                        label = "闪购神券",
                                        onClick = { stopConditionImagePath = "drawable:img_coupon_badge" }
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            stopPhotoPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("相册选图")
                                    }
                                }
                                Text(
                                    text = "停止图像置信度：${(stopConditionThreshold * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(
                                    value = stopConditionThreshold,
                                    onValueChange = { stopConditionThreshold = it },
                                    valueRange = 0.55f..0.95f
                                )
                            }

                            Text(
                                text = if (maxDurationMinutes == 0) "最长运行保护：不限时"
                                else "最长运行保护：$maxDurationMinutes 分钟后自动停止",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = maxDurationMinutes.toFloat(),
                                onValueChange = { maxDurationMinutes = it.roundToInt() },
                                valueRange = 0f..60f
                            )
                        }
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("本任务自定义通知推送规则", style = MaterialTheme.typography.titleMedium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("完成时推送状态栏提醒", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = taskNotifyOnComplete,
                                    onCheckedChange = { taskNotifyOnComplete = it },
                                    modifier = Modifier.testTag("task_notify_complete_switch")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("执行失败/异常立即告警", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = taskNotifyOnFailure,
                                    onCheckedChange = { taskNotifyOnFailure = it },
                                    modifier = Modifier.testTag("task_notify_failure_switch")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("遇人工确认逻辑分支推送提醒", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = taskNotifyOnManualConfirm,
                                    onCheckedChange = { taskNotifyOnManualConfirm = it },
                                    modifier = Modifier.testTag("task_notify_manual_switch")
                                )
                            }
                            Text(
                                text = "完成提醒长任务阈值：" + when {
                                    taskLongRunThresholdSec < 0 -> "跟随全局设置"
                                    taskLongRunThresholdSec == 0 -> "每次完成均通知 (0s)"
                                    else -> "运行超过 ${taskLongRunThresholdSec}s 才通知"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    -1 to "跟随全局",
                                    0 to "0s 每次通知",
                                    15 to "15s",
                                    30 to "30s",
                                    60 to "60s"
                                ).forEach { (sec, label) ->
                                    FilterChip(
                                        selected = taskLongRunThresholdSec == sec,
                                        onClick = {
                                            taskLongRunThresholdSec = sec
                                            taskLongRunThresholdText = if (sec >= 0) sec.toString() else ""
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            if (taskLongRunThresholdSec >= 0) {
                                OutlinedTextField(
                                    value = taskLongRunThresholdText,
                                    onValueChange = { raw ->
                                        val digits = raw.filter(Char::isDigit).take(4)
                                        taskLongRunThresholdText = digits
                                        taskLongRunThresholdSec = digits.toIntOrNull()?.coerceIn(0, 3600) ?: 0
                                    },
                                    label = { Text("自定义本任务长任务阈值 (秒)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = customNotifyNote,
                                onValueChange = { customNotifyNote = it.take(80) },
                                label = { Text("自定义附带提醒备注（如：抢券结果请查收）") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("task_notify_note_input")
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "执行步骤 (${steps.size})",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (containerCount > 0) {
                                        Text(
                                            text = "已归类 $containerCount 个节点容器",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    text = "长按步骤节点或容器标题可拖拽调序，并自动显示对齐辅助线",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        editingStepIndex = null
                                        prefilledStepTemplate = null
                                        targetContainerInsert = null
                                        showSmartDetectDialog = true
                                    },
                                    modifier = Modifier.testTag("editor_smart_detect_btn")
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("智能检测")
                                }
                                FilterChip(
                                    selected = showAlignmentGuides,
                                    onClick = { showAlignmentGuides = !showAlignmentGuides },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.AlignHorizontalCenter,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("对齐辅助线") },
                                    modifier = Modifier.testTag("editor_alignment_guides_chip")
                                )
                                OutlinedButton(
                                    onClick = {
                                        isSelectionMode = !isSelectionMode
                                        selectedIndices = emptySet()
                                    },
                                    enabled = steps.isNotEmpty(),
                                    modifier = Modifier.testTag("editor_toggle_group_mode_btn")
                                ) {
                                    Icon(
                                        imageVector = if (isSelectionMode) Icons.Default.Close else Icons.Default.AccountTree,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isSelectionMode) "取消框选" else "归类分组")
                                }
                                OutlinedButton(
                                    onClick = {
                                        editingStepIndex = null
                                        prefilledStepTemplate = null
                                        targetContainerInsert = null
                                        showStepDialog = true
                                    },
                                    modifier = Modifier.testTag("editor_add_step_btn")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加步骤")
                                }
                            }
                        }

                        if (isSelectionMode) {
                            val sortedSel = selectedIndices.sorted()
                            val isContiguous = sortedSel.isNotEmpty() &&
                                (sortedSel.last() - sortedSel.first() + 1 == sortedSel.size)

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = when {
                                            sortedSel.isEmpty() -> "请勾选需要归类为同一“节点容器”的连续步骤"
                                            isContiguous -> "已选中连续节点 #${sortedSel.first() + 1} – #${sortedSel.last() + 1}（共 ${sortedSel.size} 个）"
                                            else -> "当前所选节点不连续，请只选择连续节点或点击下方自动连选"
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (!isContiguous && sortedSel.size >= 2) {
                                            OutlinedButton(
                                                onClick = {
                                                    selectedIndices = (sortedSel.first()..sortedSel.last()).toSet()
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    text = "连选 #${sortedSel.first() + 1}–#${sortedSel.last() + 1}",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { showCreateContainerDialog = true },
                                            enabled = isContiguous && sortedSel.isNotEmpty(),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("confirm_group_selection_btn")
                                        ) {
                                            Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("打包为节点容器")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = flowBlocks,
                    key = { _, block ->
                        when (block) {
                            is EditorFlowBlock.NodeContainer -> "group_${block.groupId}"
                            is EditorFlowBlock.SingleStep -> "step_${block.step.id}"
                        }
                    }
                ) { blockIdx, block ->
                    val blockKey = when (block) {
                        is EditorFlowBlock.NodeContainer -> "group_${block.groupId}"
                        is EditorFlowBlock.SingleStep -> "step_${block.step.id}"
                    }
                    val blockLabel = when (block) {
                        is EditorFlowBlock.NodeContainer -> "容器「${block.groupName}」"
                        is EditorFlowBlock.SingleStep -> block.step.title
                    }
                    val isBlockDragging = dragState.draggingKey == blockKey
                    val blockDragModifier = if (!isSelectionMode && flowBlocks.size > 1) {
                        Modifier.pointerInput(blockKey, isSelectionMode) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    val liveIdx = latestFlowBlocks.indexOfFirst {
                                        val k = when (it) {
                                            is EditorFlowBlock.NodeContainer -> "group_${it.groupId}"
                                            is EditorFlowBlock.SingleStep -> "step_${it.step.id}"
                                        }
                                        k == blockKey
                                    }.coerceAtLeast(0)
                                    dragState.startDrag(blockKey, blockLabel, liveIdx)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragState.onDrag(dragAmount) { fromIdx, delta ->
                                        steps = NodeContainerUtils.moveBlock(
                                            latestSteps,
                                            latestFlowBlocks,
                                            fromIdx,
                                            delta
                                        )
                                    }
                                },
                                onDragEnd = { dragState.endDrag() },
                                onDragCancel = { dragState.endDrag() }
                            )
                        }
                    } else {
                        Modifier
                    }

                    val dragTransformModifier = Modifier
                        .zIndex(if (isBlockDragging) 10f else 0f)
                        .graphicsLayer {
                            if (isBlockDragging) {
                                translationY = dragState.dragOffsetY
                                translationX = dragState.snappedOffsetX
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }

                    when (block) {
                        is EditorFlowBlock.NodeContainer -> {
                            NodeContainerCard(
                                container = block,
                                blockIndex = blockIdx,
                                totalBlocks = flowBlocks.size,
                                selectionMode = isSelectionMode,
                                selectedIndices = selectedIndices,
                                isDragging = isBlockDragging,
                                isAligned = dragState.isAlignedToRail,
                                showAlignmentGuides = showAlignmentGuides,
                                containerModifier = dragTransformModifier,
                                headerDragModifier = blockDragModifier,
                                onToggleSelectStep = { idx ->
                                    selectedIndices = if (idx in selectedIndices) {
                                        selectedIndices - idx
                                    } else {
                                        selectedIndices + idx
                                    }
                                },
                                onMoveContainerUp = {
                                    steps = NodeContainerUtils.moveBlock(steps, flowBlocks, blockIdx, -1)
                                },
                                onMoveContainerDown = {
                                    steps = NodeContainerUtils.moveBlock(steps, flowBlocks, blockIdx, +1)
                                },
                                onDuplicateContainer = {
                                    steps = normalizeEditorSteps(
                                        NodeContainerUtils.duplicateContainer(
                                            steps,
                                            block.startIndex,
                                            block.endIndex
                                        )
                                    )
                                },
                                onToggleContainerEnabled = { enabled ->
                                    steps = NodeContainerUtils.setContainerEnabled(
                                        steps,
                                        block.startIndex,
                                        block.endIndex,
                                        enabled
                                    )
                                },
                                onRenameContainer = { renamingContainer = block },
                                onUngroupContainer = {
                                    steps = NodeContainerUtils.ungroupContainer(
                                        steps,
                                        block.startIndex,
                                        block.endIndex
                                    )
                                },
                                onAddStepToContainer = {
                                    editingStepIndex = null
                                    targetContainerInsert = Triple(
                                        block.groupId,
                                        block.groupName,
                                        block.endIndex + 1
                                    )
                                    showStepDialog = true
                                },
                                onEditStep = { globalIdx ->
                                    editingStepIndex = globalIdx
                                    targetContainerInsert = null
                                    showStepDialog = true
                                },
                                onMoveStepUpInContainer = { globalIdx ->
                                    steps = NodeContainerUtils.swapAdjacentInRange(
                                        steps,
                                        globalIdx,
                                        -1,
                                        block.startIndex,
                                        block.endIndex
                                    )
                                },
                                onMoveStepDownInContainer = { globalIdx ->
                                    steps = NodeContainerUtils.swapAdjacentInRange(
                                        steps,
                                        globalIdx,
                                        +1,
                                        block.startIndex,
                                        block.endIndex
                                    )
                                },
                                onDeleteStep = { globalIdx ->
                                    steps = steps.filterIndexed { i, _ -> i != globalIdx }
                                }
                            )
                        }

                        is EditorFlowBlock.SingleStep -> {
                            StepNodeCard(
                                globalIndex = block.index,
                                step = block.step,
                                selectionMode = isSelectionMode,
                                isSelected = block.index in selectedIndices,
                                canMoveUp = blockIdx > 0,
                                canMoveDown = blockIdx < flowBlocks.lastIndex,
                                insideContainer = false,
                                isDragging = isBlockDragging,
                                isAligned = dragState.isAlignedToRail,
                                cardModifier = dragTransformModifier.then(blockDragModifier),
                                onToggleSelect = {
                                    selectedIndices = if (block.index in selectedIndices) {
                                        selectedIndices - block.index
                                    } else {
                                        selectedIndices + block.index
                                    }
                                },
                                onClickEdit = {
                                    editingStepIndex = block.index
                                    targetContainerInsert = null
                                    showStepDialog = true
                                },
                                onMoveUp = {
                                    steps = NodeContainerUtils.moveBlock(steps, flowBlocks, blockIdx, -1)
                                },
                                onMoveDown = {
                                    steps = NodeContainerUtils.moveBlock(steps, flowBlocks, blockIdx, +1)
                                },
                                onDelete = {
                                    steps = steps.filterIndexed { i, _ -> i != block.index }
                                }
                            )
                        }
                    }
                }
            }

            FlowAlignmentActiveOverlayCanvas(
                dragState = dragState,
                headerItemCount = headerItemCount
            )

            FlowDragAlignmentHud(
                isDragging = dragState.isDragging,
                label = dragState.draggingLabel,
                slotNumber = dragState.activeBlockIndex?.plus(1),
                isAligned = dragState.isAlignedToRail,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
    }

    if (showStepDialog) {
        val initialStep = editingStepIndex?.let { steps.getOrNull(it) } ?: prefilledStepTemplate
        val containerTarget = targetContainerInsert
        StepBuilderDialog(
            initialStep = initialStep,
            defaultGroupId = initialStep?.groupId ?: containerTarget?.first ?: "",
            defaultGroupName = initialStep?.groupName ?: containerTarget?.second ?: "",
            repository = repository,
            onOpenSmartDetect = if (editingStepIndex == null) {
                {
                    showStepDialog = false
                    showSmartDetectDialog = true
                }
            } else null,
            onDismiss = {
                showStepDialog = false
                prefilledStepTemplate = null
                targetContainerInsert = null
            },
            onConfirm = { newStep ->
                val idx = editingStepIndex
                val stepWithId = if (newStep.id == 0L) newStep.copy(id = allocateTempStepId()) else newStep
                steps = when {
                    idx != null -> steps.mapIndexed { i, old -> if (i == idx) stepWithId else old }
                    containerTarget != null -> {
                        val mutable = steps.toMutableList()
                        val insertAt = containerTarget.third.coerceIn(0, mutable.size)
                        mutable.add(insertAt, stepWithId)
                        mutable
                    }
                    else -> steps + stepWithId
                }
                showStepDialog = false
                prefilledStepTemplate = null
                targetContainerInsert = null
            }
        )
    }

    if (showSmartDetectDialog) {
        val containerTarget = targetContainerInsert
        SmartRegionDetectDialog(
            initialBitmap = initialDetectionBitmap,
            initialCropBox = initialDetectionCropBox,
            hasRoot = hasRoot,
            onDecodeUri = { uri -> repository.decodeUriBitmap(uri, 1280) },
            onCaptureRoot = onCaptureRootBitmap,
            onLoadSample = onCreateSampleBitmap,
            onAnalyzeRegion = onAnalyzeRegion,
            onBuildImageTemplateStep = onBuildSmartTemplateStep,
            onDismiss = { showSmartDetectDialog = false },
            onStepReady = { detectedStep, editBeforeAdding ->
                val baseStep = if (detectedStep.id == 0L) detectedStep.copy(id = allocateTempStepId()) else detectedStep
                val stepWithGroup = if (containerTarget != null) {
                    baseStep.copy(
                        groupId = containerTarget.first,
                        groupName = containerTarget.second
                    )
                } else {
                    baseStep
                }
                showSmartDetectDialog = false
                if (editBeforeAdding) {
                    editingStepIndex = null
                    prefilledStepTemplate = stepWithGroup
                    showStepDialog = true
                } else {
                    steps = if (containerTarget != null) {
                        val mutable = steps.toMutableList()
                        val insertAt = containerTarget.third.coerceIn(0, mutable.size)
                        mutable.add(insertAt, stepWithGroup)
                        mutable
                    } else {
                        steps + stepWithGroup
                    }
                    prefilledStepTemplate = null
                    targetContainerInsert = null
                }
            }
        )
    }

    if (showCreateContainerDialog) {
        val sortedSel = selectedIndices.sorted()
        val first = sortedSel.firstOrNull() ?: 0
        val last = sortedSel.lastOrNull() ?: 0
        CreateOrRenameContainerDialog(
            title = "创建节点容器",
            initialName = "节点容器 ${containerCount + 1}",
            subtitle = "将步骤 #${first + 1} 至 #${last + 1}（共 ${last - first + 1} 个节点）归类为同一分组容器，可整体复制、移动或禁用。",
            confirmLabel = "创建容器",
            onDismiss = { showCreateContainerDialog = false },
            onConfirm = { groupName ->
                steps = NodeContainerUtils.groupContiguousRange(steps, first, last, groupName)
                showCreateContainerDialog = false
                isSelectionMode = false
                selectedIndices = emptySet()
            }
        )
    }

    renamingContainer?.let { target ->
        CreateOrRenameContainerDialog(
            title = "重命名节点容器",
            initialName = target.groupName,
            subtitle = "修改该节点容器的显示名称（包含内部 ${target.steps.size} 个步骤）。",
            confirmLabel = "保存名称",
            onDismiss = { renamingContainer = null },
            onConfirm = { newName ->
                steps = NodeContainerUtils.renameContainer(
                    steps,
                    target.startIndex,
                    target.endIndex,
                    newName
                )
                renamingContainer = null
            }
        )
    }
}

@Composable
private fun StepBuilderDialog(
    initialStep: AutomationStepEntity?,
    defaultGroupId: String,
    defaultGroupName: String,
    repository: AutomationRepository,
    onOpenSmartDetect: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (AutomationStepEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    var stepType by remember {
        mutableStateOf(
            runCatching { StepType.valueOf(initialStep?.stepType ?: "") }
                .getOrDefault(StepType.OCR_TEXT_TAP)
        )
    }
    var title by remember { mutableStateOf(initialStep?.title ?: "") }
    var targetText by remember { mutableStateOf(initialStep?.targetText ?: "") }
    var matchMode by remember {
        mutableStateOf(
            runCatching { TextMatchMode.valueOf(initialStep?.textMatchMode ?: "") }
                .getOrDefault(TextMatchMode.CONTAINS)
        )
    }
    var templatePath by remember {
        mutableStateOf(initialStep?.templateImagePath ?: "drawable:img_reward_emblem")
    }
    var matchThreshold by remember { mutableFloatStateOf(initialStep?.matchThreshold ?: 0.75f) }
    var normX by remember { mutableFloatStateOf(initialStep?.normalizedX ?: 0.5f) }
    var normY by remember { mutableFloatStateOf(initialStep?.normalizedY ?: 0.5f) }
    var randomOffsetEnabled by remember { mutableStateOf(initialStep?.randomOffsetEnabled ?: true) }
    var randomOffsetRadius by remember { mutableFloatStateOf((initialStep?.randomOffsetRadiusPx ?: 12).toFloat()) }
    var randomJitterMs by remember { mutableFloatStateOf((initialStep?.randomDelayJitterMs ?: 150L).toFloat()) }

    var preScanDelayText by remember { mutableStateOf((initialStep?.preScanDelayMs ?: 0L).toString()) }
    var delayBeforeText by remember { mutableStateOf((initialStep?.delayBeforeMs ?: 0L).toString()) }
    var delayAfterText by remember { mutableStateOf((initialStep?.delayAfterMs ?: 600L).toString()) }
    var scanIntervalText by remember { mutableStateOf((initialStep?.scanIntervalMs ?: 350L).toString()) }
    var timeoutText by remember { mutableStateOf((initialStep?.timeoutMs ?: 4000L).toString()) }
    var touchPressure by remember { mutableFloatStateOf(initialStep?.touchPressure ?: 0.72f) }
    var pressurePercentText by remember {
        mutableStateOf(((initialStep?.touchPressure ?: 0.72f) * 100).roundToInt().toString())
    }
    var pressDurationText by remember { mutableStateOf((initialStep?.pressDurationMs ?: 55L).toString()) }
    var roiEnabled by remember { mutableStateOf(initialStep?.roiEnabled ?: false) }
    val roiLeft = initialStep?.roiLeft ?: 0f
    val roiTop = initialStep?.roiTop ?: 0f
    val roiRight = initialStep?.roiRight ?: 1f
    val roiBottom = initialStep?.roiBottom ?: 1f
    var maxRetryCountText by remember { mutableStateOf((initialStep?.maxRetryCount ?: 0).toString()) }
    var retryIntervalText by remember { mutableStateOf((initialStep?.retryIntervalMs ?: 1000L).toString()) }
    var branchConditionType by remember {
        mutableStateOf(
            runCatching { BranchConditionType.valueOf(initialStep?.branchConditionType ?: "") }
                .getOrDefault(BranchConditionType.ALWAYS_PAUSE)
        )
    }
    var stepEnabled by remember { mutableStateOf(initialStep?.isEnabled ?: true) }

    val savedTemplates = remember { repository.listSavedTemplateFiles() }
    var previewBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(templatePath) {
        previewBmp = if (templatePath.startsWith("drawable:")) null
        else repository.loadTemplateBitmap(templatePath)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val path = repository.savePickedUriAsTemplate(uri)
                if (path != null) {
                    templatePath = path
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialStep == null) "添加自动化步骤" else "编辑步骤") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (onOpenSmartDetect != null) {
                    FilledTonalButton(
                        onClick = onOpenSmartDetect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("step_dialog_smart_detect_btn")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("智能检测截图区域并自动推荐算法", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StepType.entries.forEach { t ->
                        FilterChip(
                            selected = stepType == t,
                            onClick = { stepType = t },
                            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("步骤标题（留空将自动生成）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                when (stepType) {
                    StepType.OCR_TEXT_TAP -> {
                        OutlinedTextField(
                            value = targetText,
                            onValueChange = { targetText = it },
                            label = { Text("识别目标文字（如：立即签到、关闭广告）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("step_ocr_text_input")
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TextMatchMode.entries.forEach { m ->
                                FilterChip(
                                    selected = matchMode == m,
                                    onClick = { matchMode = m },
                                    label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    StepType.IMAGE_TEMPLATE_TAP -> {
                        Text("选择目标图像模板：", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PresetTemplateThumb(
                                selected = templatePath == "drawable:img_reward_emblem",
                                drawableRes = R.drawable.img_reward_emblem,
                                label = "星标徽章",
                                onClick = { templatePath = "drawable:img_reward_emblem" }
                            )
                            PresetTemplateThumb(
                                selected = templatePath == "drawable:img_coupon_badge",
                                drawableRes = R.drawable.img_coupon_badge,
                                label = "闪购神券",
                                onClick = { templatePath = "drawable:img_coupon_badge" }
                            )
                            savedTemplates.take(4).forEach { file ->
                                val isSel = templatePath == file.absolutePath
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        2.dp,
                                        if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    ),
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { templatePath = file.absolutePath }
                                ) {
                                    val bmp = remember(file.absolutePath) {
                                        repository.loadTemplateBitmap(file.absolutePath)
                                    }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "已保存模板",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("相册导入")
                            }
                        }
                        if (previewBmp != null && !templatePath.startsWith("drawable:")) {
                            Image(
                                bitmap = previewBmp!!.asImageBitmap(),
                                contentDescription = "当前模板预览",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                            )
                        }
                        Text(
                            text = "匹配相似度阈值：${(matchThreshold * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = matchThreshold,
                            onValueChange = { matchThreshold = it },
                            valueRange = 0.55f..0.95f
                        )
                    }

                    StepType.COORDINATE_TAP -> {
                        Text("横向坐标 X：${(normX * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        Slider(value = normX, onValueChange = { normX = it }, valueRange = 0.05f..0.95f)
                        Text("纵向坐标 Y：${(normY * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        Slider(value = normY, onValueChange = { normY = it }, valueRange = 0.05f..0.95f)
                    }

                    StepType.WAIT_DELAY -> Unit

                    StepType.MANUAL_CONFIRM_BRANCH -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.PanTool,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("人工确认逻辑分支触发条件", style = MaterialTheme.typography.labelLarge)
                                }
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    BranchConditionType.entries.forEach { cond ->
                                        FilterChip(
                                            selected = branchConditionType == cond,
                                            onClick = { branchConditionType = cond },
                                            label = { Text(cond.label, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.testTag("branch_cond_${cond.name}")
                                        )
                                    }
                                }
                                Text(
                                    text = branchConditionType.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (branchConditionType != BranchConditionType.ALWAYS_PAUSE) {
                                    OutlinedTextField(
                                        value = targetText,
                                        onValueChange = { targetText = it },
                                        label = { Text("触发确认的屏幕关键词（如：验证码、支付确认）") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("branch_target_text_input")
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        TextMatchMode.entries.forEach { m ->
                                            FilterChip(
                                                selected = matchMode == m,
                                                onClick = { matchMode = m },
                                                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("验证码", "支付确认", "风控验证", "提交订单").forEach { kw ->
                                            FilterChip(
                                                selected = targetText == kw,
                                                onClick = { targetText = kw },
                                                label = { Text(kw, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if ((stepType == StepType.OCR_TEXT_TAP || stepType == StepType.IMAGE_TEMPLATE_TAP) &&
                    (roiEnabled || (roiRight - roiLeft) < 0.99f || (roiBottom - roiTop) < 0.99f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("区域限定识别 (ROI)", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = "范围 (${(roiLeft * 100).roundToInt()}%, ${(roiTop * 100).roundToInt()}%) → (${(roiRight * 100).roundToInt()}%, ${(roiBottom * 100).roundToInt()}%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = roiEnabled,
                                onCheckedChange = { roiEnabled = it },
                                modifier = Modifier.testTag("step_roi_enabled_switch")
                            )
                        }
                    }
                }

                // --- Millisecond Delay & App Response Speed Tuning ---
                if (stepType != StepType.MANUAL_CONFIRM_BRANCH) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("延迟与响应速度参数（毫秒级）", style = MaterialTheme.typography.labelLarge)

                            if (stepType != StepType.WAIT_DELAY) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = delayBeforeText,
                                        onValueChange = { delayBeforeText = it.filter(Char::isDigit).take(5) },
                                        label = { Text("点击前延迟 (ms)") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("step_delay_before_input")
                                    )
                                    OutlinedTextField(
                                        value = delayAfterText,
                                        onValueChange = { delayAfterText = it.filter(Char::isDigit).take(5) },
                                        label = { Text("点击后延迟 (ms)") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("step_delay_after_input")
                                    )
                                }
                                OutlinedTextField(
                                    value = preScanDelayText,
                                    onValueChange = { preScanDelayText = it.filter(Char::isDigit).take(5) },
                                    label = { Text("页面加载等待/识别前延时 (ms)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("step_pre_scan_delay_input")
                                )

                                if (stepType == StepType.OCR_TEXT_TAP || stepType == StepType.IMAGE_TEMPLATE_TAP) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = scanIntervalText,
                                            onValueChange = { scanIntervalText = it.filter(Char::isDigit).take(5) },
                                            label = { Text("OCR/识图循环间隔 (ms)") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("step_scan_interval_input")
                                        )
                                        OutlinedTextField(
                                            value = timeoutText,
                                            onValueChange = { timeoutText = it.filter(Char::isDigit).take(5) },
                                            label = { Text("识别超时上限 (ms)") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("step_timeout_input")
                                        )
                                    }
                                    Text(
                                        text = "慢页面加载预设（页面等待 / 轮询间隔 / 超时）：",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            Triple(0L, 200L, 3000L) to "极速 0/200ms",
                                            Triple(300L, 350L, 5000L) to "常规 300/350ms",
                                            Triple(1500L, 800L, 12000L) to "慢加载 1500/800ms",
                                            Triple(3000L, 1200L, 25000L) to "弱网超慢 3000/1200ms"
                                        ).forEach { (cfg, label) ->
                                            val (preMs, intervalMs, toMs) = cfg
                                            FilterChip(
                                                selected = preScanDelayText == preMs.toString() &&
                                                    scanIntervalText == intervalMs.toString(),
                                                onClick = {
                                                    preScanDelayText = preMs.toString()
                                                    scanIntervalText = intervalMs.toString()
                                                    timeoutText = toMs.toString()
                                                    if (preMs >= 1500L && (delayAfterText.toLongOrNull() ?: 0L) < 1000L) {
                                                        delayAfterText = "1000"
                                                    }
                                                },
                                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(0L to "极速 0ms", 150L to "150ms", 600L to "标准 600ms", 1500L to "慢加载 1500ms").forEach { (ms, label) ->
                                            FilterChip(
                                                selected = delayAfterText == ms.toString(),
                                                onClick = { delayAfterText = ms.toString() },
                                                label = { Text("后延 $label", style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = delayAfterText,
                                    onValueChange = { delayAfterText = it.filter(Char::isDigit).take(5) },
                                    label = { Text("等待延时时长 (ms)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("step_delay_after_input")
                                )
                            }
                        }
                    }
                }

                // --- Pressure Sensitivity & Hold Duration ---
                if (stepType != StepType.WAIT_DELAY && stepType != StepType.MANUAL_CONFIRM_BRANCH) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("触控压力敏感度与按压控制", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = pressurePercentText,
                                    onValueChange = { raw ->
                                        val digits = raw.filter(Char::isDigit).take(3)
                                        pressurePercentText = digits
                                        digits.toIntOrNull()?.let { pct ->
                                            touchPressure = (pct.coerceIn(10, 100) / 100f)
                                        }
                                    },
                                    label = { Text("压力敏感度 (%)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("step_pressure_input")
                                )
                                OutlinedTextField(
                                    value = pressDurationText,
                                    onValueChange = { pressDurationText = it.filter(Char::isDigit).take(4) },
                                    label = { Text("按压驻留 (ms)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("step_press_duration_input")
                                )
                            }
                            Slider(
                                value = touchPressure,
                                onValueChange = {
                                    touchPressure = it
                                    pressurePercentText = (it * 100).roundToInt().toString()
                                },
                                valueRange = 0.10f..1.0f,
                                modifier = Modifier.testTag("step_pressure_slider")
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    Triple(0.35f, 35L, "轻触灵敏 (35%/35ms)"),
                                    Triple(0.72f, 55L, "标准点按 (72%/55ms)"),
                                    Triple(0.95f, 180L, "重按深触 (95%/180ms)")
                                ).forEach { (p, d, label) ->
                                    FilterChip(
                                        selected = (touchPressure * 100).roundToInt() == (p * 100).roundToInt() &&
                                            pressDurationText == d.toString(),
                                        onClick = {
                                            touchPressure = p
                                            pressurePercentText = (p * 100).roundToInt().toString()
                                            pressDurationText = d.toString()
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }

                    // --- Automatic Failure Retry Policy ---
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val currentRetries = maxRetryCountText.toIntOrNull()?.coerceIn(0, 10) ?: 0
                            val currentRetryInterval = retryIntervalText.toLongOrNull()?.coerceIn(100L, 30000L) ?: 1000L
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("失败自动重试策略", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = if (currentRetries == 0) "不重试" else "最多 $currentRetries 次 · ${currentRetryInterval}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (currentRetries > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = maxRetryCountText,
                                    onValueChange = { maxRetryCountText = it.filter(Char::isDigit).take(2) },
                                    label = { Text("最大重试次数 (0-10)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("step_max_retry_input")
                                )
                                OutlinedTextField(
                                    value = retryIntervalText,
                                    onValueChange = { retryIntervalText = it.filter(Char::isDigit).take(5) },
                                    label = { Text("重试间隔 (ms)") },
                                    singleLine = true,
                                    enabled = currentRetries > 0,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("step_retry_interval_input")
                                )
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    Triple(0, 1000L, "不重试"),
                                    Triple(2, 800L, "轻度 (2次/800ms)"),
                                    Triple(3, 1500L, "标准 (3次/1500ms)"),
                                    Triple(5, 2500L, "强力 (5次/2500ms)")
                                ).forEach { (count, interval, label) ->
                                    FilterChip(
                                        selected = currentRetries == count && (count == 0 || currentRetryInterval == interval),
                                        onClick = {
                                            maxRetryCountText = count.toString()
                                            retryIntervalText = interval.toString()
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("模拟真人轻微随机偏移", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "在目标识别框内微调落点并加入轻微间隔抖动",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = randomOffsetEnabled,
                            onCheckedChange = { randomOffsetEnabled = it },
                            modifier = Modifier.testTag("step_random_offset_switch")
                        )
                    }
                    if (randomOffsetEnabled) {
                        Text(
                            text = "随机偏移半径：±${randomOffsetRadius.roundToInt()} px",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = randomOffsetRadius,
                            onValueChange = { randomOffsetRadius = it },
                            valueRange = 2f..36f
                        )
                        Text(
                            text = "随机延时抖动：±${randomJitterMs.roundToInt()} ms",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = randomJitterMs,
                            onValueChange = { randomJitterMs = it },
                            valueRange = 0f..500f
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用此节点", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = if (defaultGroupName.isNotBlank()) "所属容器：$defaultGroupName" else "关闭后执行流程时将跳过此步骤",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = stepEnabled,
                        onCheckedChange = { stepEnabled = it },
                        modifier = Modifier.testTag("step_enabled_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedPreScanDelay = preScanDelayText.toLongOrNull()?.coerceIn(0L, 60000L) ?: 0L
                    val parsedDelayBefore = delayBeforeText.toLongOrNull()?.coerceIn(0L, 30000L) ?: 0L
                    val parsedDelayAfter = delayAfterText.toLongOrNull()?.coerceIn(0L, 60000L) ?: 600L
                    val parsedScanInterval = scanIntervalText.toLongOrNull()?.coerceIn(100L, 10000L) ?: 350L
                    val parsedTimeout = timeoutText.toLongOrNull()?.coerceIn(500L, 60000L) ?: 4000L
                    val parsedPressure = (pressurePercentText.toIntOrNull()?.coerceIn(10, 100)?.div(100f))
                        ?: touchPressure.coerceIn(0.10f, 1.0f)
                    val parsedPressDuration = pressDurationText.toLongOrNull()?.coerceIn(15L, 2000L) ?: 55L
                    val parsedMaxRetry = maxRetryCountText.toIntOrNull()?.coerceIn(0, 10) ?: 0
                    val parsedRetryInterval = retryIntervalText.toLongOrNull()?.coerceIn(100L, 30000L) ?: 1000L

                    val autoTitle = title.trim().ifBlank {
                        when (stepType) {
                            StepType.OCR_TEXT_TAP -> "识别点击「${targetText.trim().ifBlank { "文字" }}」"
                            StepType.IMAGE_TEMPLATE_TAP -> "图像识别点击"
                            StepType.COORDINATE_TAP -> "点击坐标 (${(normX * 100).toInt()}%, ${(normY * 100).toInt()}%)"
                            StepType.WAIT_DELAY -> "等待 ${parsedDelayAfter}ms"
                            StepType.MANUAL_CONFIRM_BRANCH -> {
                                if (branchConditionType == BranchConditionType.ALWAYS_PAUSE) {
                                    "人工确认分支"
                                } else {
                                    "分支确认「${targetText.trim().ifBlank { "关键状态" }}」"
                                }
                            }
                        }
                    }
                    onConfirm(
                        AutomationStepEntity(
                            taskId = initialStep?.taskId ?: 0L,
                            stepOrder = initialStep?.stepOrder ?: 0,
                            title = autoTitle,
                            stepType = stepType.name,
                            targetText = targetText.trim(),
                            textMatchMode = matchMode.name,
                            templateImagePath = templatePath,
                            matchThreshold = matchThreshold,
                            normalizedX = normX,
                            normalizedY = normY,
                            randomOffsetEnabled = randomOffsetEnabled,
                            randomOffsetRadiusPx = randomOffsetRadius.roundToInt(),
                            randomDelayJitterMs = randomJitterMs.roundToInt().toLong(),
                            preScanDelayMs = parsedPreScanDelay,
                            delayBeforeMs = parsedDelayBefore,
                            delayAfterMs = parsedDelayAfter,
                            scanIntervalMs = parsedScanInterval,
                            timeoutMs = parsedTimeout,
                            touchPressure = parsedPressure,
                            pressDurationMs = parsedPressDuration,
                            roiEnabled = roiEnabled,
                            roiLeft = roiLeft,
                            roiTop = roiTop,
                            roiRight = roiRight,
                            roiBottom = roiBottom,
                            maxRetryCount = parsedMaxRetry,
                            retryIntervalMs = parsedRetryInterval,
                            isEnabled = stepEnabled,
                            groupId = defaultGroupId,
                            groupName = defaultGroupName,
                            branchConditionType = branchConditionType.name
                        )
                    )
                },
                modifier = Modifier.testTag("step_dialog_confirm_btn")
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PresetTemplateThumb(
    selected: Boolean,
    drawableRes: Int,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = label,
            modifier = Modifier.size(44.dp)
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
