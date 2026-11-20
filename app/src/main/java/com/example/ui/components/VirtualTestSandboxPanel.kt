package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TargetSceneType
import com.example.data.TaskWithSteps
import com.example.engine.MediaSequenceSourceType
import com.example.engine.ReplayVerificationMode
import com.example.engine.VirtualSandboxUiState
import com.example.engine.VirtualStepMatchResult
import com.example.engine.VirtualTestFrame
import com.example.engine.VirtualTestReport
import com.example.ui.theme.CyanBright
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.RoseAlert
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun VirtualTestSandboxPanel(
    tasks: List<TaskWithSteps>,
    activeTask: TaskWithSteps?,
    virtualState: VirtualSandboxUiState,
    onSelectTask: (TaskWithSteps) -> Unit,
    onImportVideo: (android.net.Uri) -> Unit,
    onAppendImages: (List<android.net.Uri>) -> Unit,
    onLoadSampleSequence: (TargetSceneType) -> Unit,
    onSelectFrame: (Int) -> Unit,
    onRemoveFrame: (Int) -> Unit,
    onMoveFrame: (Int, Int) -> Unit,
    onClearFrames: () -> Unit,
    onSetVerificationMode: (ReplayVerificationMode) -> Unit,
    onSetVideoInterval: (Long) -> Unit,
    onRunVerification: (TaskWithSteps) -> Unit,
    onStopVerification: () -> Unit,
    onInspectStepResult: (VirtualStepMatchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImportVideo(uri)
    }

    val imageSequencePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) onAppendImages(uris)
    }

    val activeSceneHint = remember(activeTask) {
        runCatching {
            TargetSceneType.valueOf(activeTask?.task?.sceneType ?: TargetSceneType.DAILY_CHECKIN.name)
        }.getOrDefault(TargetSceneType.DAILY_CHECKIN)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.08f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VirtualMediaToolbar(
                        tasks = tasks,
                        activeTask = activeTask,
                        virtualState = virtualState,
                        onSelectTask = onSelectTask,
                        onPickVideo = {
                            videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onPickImages = {
                            imageSequencePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onLoadSample = { onLoadSampleSequence(activeSceneHint) },
                        onClearFrames = onClearFrames,
                        onSetVideoInterval = onSetVideoInterval
                    )

                    VirtualReplayViewport(
                        virtualState = virtualState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    VirtualFrameStrip(
                        virtualState = virtualState,
                        onSelectFrame = onSelectFrame,
                        onMoveFrame = onMoveFrame,
                        onRemoveFrame = onRemoveFrame
                    )
                }

                VirtualVerificationReportPane(
                    activeTask = activeTask,
                    virtualState = virtualState,
                    onSetVerificationMode = onSetVerificationMode,
                    onRunVerification = { activeTask?.let(onRunVerification) },
                    onStopVerification = onStopVerification,
                    onInspectStepResult = onInspectStepResult,
                    modifier = Modifier
                        .weight(0.92f)
                        .fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VirtualMediaToolbar(
                    tasks = tasks,
                    activeTask = activeTask,
                    virtualState = virtualState,
                    onSelectTask = onSelectTask,
                    onPickVideo = {
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onPickImages = {
                        imageSequencePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onLoadSample = { onLoadSampleSequence(activeSceneHint) },
                    onClearFrames = onClearFrames,
                    onSetVideoInterval = onSetVideoInterval
                )

                VirtualReplayViewport(
                    virtualState = virtualState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(225.dp)
                )

                if (virtualState.frames.isNotEmpty()) {
                    VirtualFrameStrip(
                        virtualState = virtualState,
                        onSelectFrame = onSelectFrame,
                        onMoveFrame = onMoveFrame,
                        onRemoveFrame = onRemoveFrame
                    )
                }

                VirtualVerificationReportPane(
                    activeTask = activeTask,
                    virtualState = virtualState,
                    onSetVerificationMode = onSetVerificationMode,
                    onRunVerification = { activeTask?.let(onRunVerification) },
                    onStopVerification = onStopVerification,
                    onInspectStepResult = onInspectStepResult,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun VirtualMediaToolbar(
    tasks: List<TaskWithSteps>,
    activeTask: TaskWithSteps?,
    virtualState: VirtualSandboxUiState,
    onSelectTask: (TaskWithSteps) -> Unit,
    onPickVideo: () -> Unit,
    onPickImages: () -> Unit,
    onLoadSample: () -> Unit,
    onClearFrames: () -> Unit,
    onSetVideoInterval: (Long) -> Unit
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
            OutlinedButton(
                onClick = onPickVideo,
                enabled = !virtualState.isLoadingMedia && !virtualState.isVerifying,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("virtual_pick_video_btn")
            ) {
                Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("上传模拟录屏", style = MaterialTheme.typography.labelSmall)
            }

            OutlinedButton(
                onClick = onPickImages,
                enabled = !virtualState.isLoadingMedia && !virtualState.isVerifying,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("virtual_pick_images_btn")
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入截图序列", style = MaterialTheme.typography.labelSmall)
            }

            OutlinedButton(
                onClick = onLoadSample,
                enabled = !virtualState.isLoadingMedia && !virtualState.isVerifying,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("virtual_load_sample_btn")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("示例序列", style = MaterialTheme.typography.labelSmall)
            }

            if (virtualState.sourceType == MediaSequenceSourceType.VIDEO_RECORDING || virtualState.frames.isEmpty()) {
                listOf(500L to "0.5s采样", 1000L to "1.0s采样", 1500L to "1.5s采样").forEach { (ms, label) ->
                    FilterChip(
                        selected = virtualState.videoSampleIntervalMs == ms,
                        onClick = { onSetVideoInterval(ms) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (virtualState.frames.isNotEmpty()) {
                IconButton(
                    onClick = onClearFrames,
                    enabled = !virtualState.isVerifying,
                    modifier = Modifier.testTag("virtual_clear_frames_btn")
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "清空测试帧",
                        tint = RoseAlert
                    )
                }
            }
        }
    }
}

@Composable
private fun VirtualReplayViewport(
    virtualState: VirtualSandboxUiState,
    modifier: Modifier = Modifier
) {
    val activeFrame = virtualState.activeFrame
    val matchedOnActiveFrame = remember(virtualState.report, virtualState.selectedFrameIndex) {
        virtualState.report?.results?.filter {
            !it.isSkippedWait && it.matchedFrameIndex == virtualState.selectedFrameIndex
        }.orEmpty()
    }
    val focusedResult = remember(virtualState.report, virtualState.selectedResultStepOrder) {
        virtualState.report?.results?.firstOrNull { it.stepOrder == virtualState.selectedResultStepOrder }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        color = NavyDeep,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (virtualState.isLoadingMedia) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(color = CyanBright, modifier = Modifier.size(30.dp))
                    Text(
                        text = virtualState.statusBanner,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFE2E8F0)
                    )
                }
            } else if (activeFrame == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = CyanBright,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "开发测试虚拟沙盒（Dry-Run 模式）",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "请上传模拟录屏（MP4）或导入多张截图序列，系统将逐帧校验 OCR 与图像模板命中位置，不实际执行点击且不污染执行日志。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            } else {
                val bmp = activeFrame.bitmap
                val bmpRatio = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()).coerceIn(0.20f, 5.0f)

                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
                    val fitModifier = if (bmpRatio > containerRatio) {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(bmpRatio, matchHeightConstraintsFirst = false)
                    } else {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(bmpRatio, matchHeightConstraintsFirst = true)
                    }

                    Box(modifier = fitModifier) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = activeFrame.label,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val sx = size.width / bmp.width.coerceAtLeast(1).toFloat()
                            val sy = size.height / bmp.height.coerceAtLeast(1).toFloat()

                            // Draw ROI outline of focused result if enabled
                            if (focusedResult != null && focusedResult.roiEnabled) {
                                val rLeft = focusedResult.roiLeft * size.width
                                val rTop = focusedResult.roiTop * size.height
                                val rW = (focusedResult.roiRight - focusedResult.roiLeft).coerceAtLeast(0.02f) * size.width
                                val rH = (focusedResult.roiBottom - focusedResult.roiTop).coerceAtLeast(0.02f) * size.height
                                drawRect(
                                    color = CyanBright.copy(alpha = 0.85f),
                                    topLeft = Offset(rLeft, rTop),
                                    size = Size(rW, rH),
                                    style = Stroke(
                                        width = 2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                    )
                                )
                            }

                            // Draw all matched/candidate boxes located on this frame
                            matchedOnActiveFrame.forEach { res ->
                                val isSelected = res.stepOrder == virtualState.selectedResultStepOrder
                                val boxColor = when {
                                    res.matched && isSelected -> EmeraldPulse
                                    res.matched -> CyanBright
                                    else -> RoseAlert
                                }
                                res.detectedRect?.let { rect ->
                                    val left = rect.left * sx
                                    val top = rect.top * sy
                                    val w = rect.width() * sx
                                    val h = rect.height() * sy
                                    drawRect(
                                        color = boxColor.copy(alpha = if (isSelected) 0.22f else 0.12f),
                                        topLeft = Offset(left, top),
                                        size = Size(w, h)
                                    )
                                    drawRect(
                                        color = boxColor,
                                        topLeft = Offset(left, top),
                                        size = Size(w, h),
                                        style = Stroke(width = if (isSelected) 2.5.dp.toPx() else 1.5.dp.toPx())
                                    )
                                }

                                res.virtualTapPoint?.let { pt ->
                                    val cx = pt.x * sx
                                    val cy = pt.y * sy
                                    val arm = 10.dp.toPx()
                                    drawCircle(
                                        color = boxColor.copy(alpha = 0.35f),
                                        radius = 12.dp.toPx(),
                                        center = Offset(cx, cy)
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(cx - arm, cy),
                                        end = Offset(cx + arm, cy),
                                        strokeWidth = 2.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(cx, cy - arm),
                                        end = Offset(cx, cy + arm),
                                        strokeWidth = 2.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                    drawCircle(
                                        color = boxColor,
                                        radius = 4.5.dp.toPx(),
                                        center = Offset(cx, cy)
                                    )
                                }
                            }
                        }
                    }
                }

                // Frame metadata chip
                Surface(
                    color = NavyDeep.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${activeFrame.label} · ${bmp.width}×${bmp.height}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }

                // Non-destructive dry-run badge
                Surface(
                    color = NavyDeep.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, CyanBright.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (matchedOnActiveFrame.isNotEmpty()) {
                            "本帧命中 ${matchedOnActiveFrame.count { it.matched }} 处 · 仅验证不点击"
                        } else {
                            "Dry-Run · 不实际触控"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanBright,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VirtualFrameStrip(
    virtualState: VirtualSandboxUiState,
    onSelectFrame: (Int) -> Unit,
    onMoveFrame: (Int, Int) -> Unit,
    onRemoveFrame: (Int) -> Unit
) {
    val frames = virtualState.frames
    if (frames.isEmpty()) return

    val hitCountByFrame = remember(virtualState.report) {
        virtualState.report?.results
            ?.filter { it.matched && !it.isSkippedWait && it.matchedFrameIndex != null }
            ?.groupingBy { it.matchedFrameIndex!! }
            ?.eachCount()
            .orEmpty()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (frames.size >= 2) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "帧 ${virtualState.selectedFrameIndex + 1}/${frames.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = virtualState.selectedFrameIndex.toFloat(),
                    onValueChange = { onSelectFrame(it.roundToInt()) },
                    valueRange = 0f..(frames.lastIndex).toFloat(),
                    steps = (frames.size - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(frames, key = { _, f -> f.id }) { idx, frame ->
                val isSelected = idx == virtualState.selectedFrameIndex
                val hits = hitCountByFrame[idx] ?: 0
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) CyanBright else MaterialTheme.colorScheme.outline
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .width(102.dp)
                        .clickable { onSelectFrame(idx) }
                ) {
                    Column(
                        modifier = Modifier.padding(5.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(NavyDeep)
                        ) {
                            Image(
                                bitmap = frame.bitmap.asImageBitmap(),
                                contentDescription = frame.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = NavyDeep.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(2.dp)
                            ) {
                                Text(
                                    text = "#${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            if (hits > 0) {
                                Surface(
                                    color = EmeraldPulse,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                ) {
                                    Text(
                                        text = "$hits",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = frame.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onMoveFrame(idx, idx - 1) },
                                enabled = idx > 0 && !virtualState.isVerifying,
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "前移帧",
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            IconButton(
                                onClick = { onMoveFrame(idx, idx + 1) },
                                enabled = idx < frames.lastIndex && !virtualState.isVerifying,
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "后移帧",
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            IconButton(
                                onClick = { onRemoveFrame(idx) },
                                enabled = !virtualState.isVerifying,
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除该帧",
                                    tint = RoseAlert,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualVerificationReportPane(
    activeTask: TaskWithSteps?,
    virtualState: VirtualSandboxUiState,
    onSetVerificationMode: (ReplayVerificationMode) -> Unit,
    onRunVerification: () -> Unit,
    onStopVerification: () -> Unit,
    onInspectStepResult: (VirtualStepMatchResult) -> Unit,
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReplayVerificationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = virtualState.verificationMode == mode,
                        onClick = { onSetVerificationMode(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (virtualState.isVerifying) {
                    Button(
                        onClick = onStopVerification,
                        colors = ButtonDefaults.buttonColors(containerColor = RoseAlert),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("virtual_stop_verify_btn")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止虚拟核验")
                    }
                } else {
                    Button(
                        onClick = onRunVerification,
                        enabled = activeTask != null &&
                            activeTask.sortedSteps.any { it.isEnabled } &&
                            virtualState.frames.isNotEmpty() &&
                            !virtualState.isLoadingMedia,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("virtual_run_verify_btn")
                    ) {
                        Icon(Icons.Default.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始无害化匹配验证 (Dry-Run)")
                    }
                }
            }

            if (virtualState.isVerifying) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val progress = if (virtualState.verifyProgressTotal > 0) {
                        virtualState.verifyProgressCurrent.toFloat() / virtualState.verifyProgressTotal.toFloat()
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = virtualState.verifyProgressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Text(
                    text = virtualState.statusBanner,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val report = virtualState.report
            if (report != null) {
                VirtualAccuracySummaryCard(report = report)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(report.results) { _, res ->
                        VirtualStepResultCard(
                            result = res,
                            isSelected = virtualState.selectedResultStepOrder == res.stepOrder,
                            onClick = { onInspectStepResult(res) }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = virtualState.verificationMode.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (virtualState.frames.isEmpty()) {
                                "步骤：1. 导入录屏或截图序列（或点击“示例序列”） → 2. 点击上方“开始无害化匹配验证”"
                            } else {
                                "已就绪 ${virtualState.frames.size} 帧 (${virtualState.sourceTitle})，点击上方按钮即可产出匹配准确率报告"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualAccuracySummaryCard(report: VirtualTestReport) {
    val pct = (report.accuracyRate * 100f).roundToInt()
    val accentColor = when {
        pct >= 85 -> EmeraldPulse
        pct >= 50 -> Color(0xFFF59E0B)
        else -> RoseAlert
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("virtual_accuracy_summary_card")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "脚本匹配准确率：$pct%",
                    style = MaterialTheme.typography.titleSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${report.mode.label} · 共 ${report.totalFrames} 帧 · 平均 ${report.avgMatchMs}ms/步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = accentColor
            ) {
                Text(
                    text = "${report.matchedStepCount}/${report.verifiableStepCount} 步命中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun VirtualStepResultCard(
    result: VirtualStepMatchResult,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val statusColor = when {
        !result.isEnabled -> Color(0xFF64748B)
        result.isSkippedWait -> CyanBright
        result.matched -> EmeraldPulse
        else -> RoseAlert
    }
    val statusIcon = when {
        !result.isEnabled || result.isSkippedWait -> Icons.Default.Schedule
        result.matched -> Icons.Default.CheckCircle
        else -> Icons.Default.ErrorOutline
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) CyanBright else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${result.stepOrder + 1}. ${result.stepTitle}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val badgeText = buildString {
                    append(
                        when {
                            !result.isEnabled -> "已禁用"
                            result.isSkippedWait -> "跳过延时"
                            result.matched -> "命中"
                            else -> "未命中"
                        }
                    )
                    if (result.confidenceScore != null && !result.isSkippedWait && result.isEnabled) {
                        append(" ${(result.confidenceScore * 100f).roundToInt()}%")
                    }
                    if (result.elapsedMs > 0L) {
                        append(" · ${result.elapsedMs}ms")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = result.diagnosticMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (result.suggestion.isNotBlank()) {
                Text(
                    text = "优化建议：${result.suggestion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD97706)
                )
            }
        }
    }
}
