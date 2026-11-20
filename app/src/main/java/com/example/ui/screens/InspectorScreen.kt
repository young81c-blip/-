package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.TaskWithSteps
import com.example.engine.RecognizedTextElement
import com.example.engine.RecommendedAlgorithm
import com.example.engine.SmartDetectionResult
import com.example.ui.CropBoxNorm
import com.example.ui.InspectorUiState
import com.example.ui.components.SmartDetectionRecommendationCard
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CyanBright
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NavyDeep
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class CropDragHandle {
    MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

@Composable
fun InspectorScreen(
    state: InspectorUiState,
    tasks: List<TaskWithSteps>,
    hasRoot: Boolean,
    onLoadSample: () -> Unit,
    onPickImage: (Uri) -> Unit,
    onRootScreencap: () -> Unit,
    onRunOcr: (regionOnly: Boolean) -> Unit,
    onSelectTextElement: (RecognizedTextElement) -> Unit,
    onUpdateCropBox: (CropBoxNorm) -> Unit,
    onApplySearchRoi: () -> Unit,
    onClearSearchRoi: () -> Unit,
    onCropAndTest: () -> Unit,
    onSaveOcrStep: (Long?, String, Boolean) -> Unit,
    onSaveImageStep: (Long?, String, Float, Boolean) -> Unit,
    onRunSmartDetect: () -> Unit,
    onApplySmartRecommendation: (Long?, SmartDetectionResult, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableIntStateOf(0) } // 0 = OCR Region/Full, 1 = Image Template & ROI
    var targetTaskId by remember { mutableStateOf<Long?>(tasks.firstOrNull()?.task?.id) }
    var useRegionRoiForOcr by remember { mutableStateOf(true) }
    var useRegionRoiForImage by remember { mutableStateOf(true) }

    val latestCropBox by rememberUpdatedState(state.cropBox)
    val latestTexts by rememberUpdatedState(state.recognizedTexts)
    val latestMode by rememberUpdatedState(mode)

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickImage(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Source toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.testTag("inspector_pick_photo_btn")
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("相册选图")
                }

                OutlinedButton(
                    onClick = onRootScreencap,
                    enabled = hasRoot,
                    modifier = Modifier.testTag("inspector_root_cap_btn")
                ) {
                    Icon(Icons.Default.Screenshot, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasRoot) "Root 截屏" else "Root 截屏(需授权)")
                }

                OutlinedButton(
                    onClick = onLoadSample,
                    modifier = Modifier.testTag("inspector_sample_btn")
                ) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("示例界面")
                }
            }

            // Mode switch
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == 0,
                    onClick = { mode = 0 },
                    leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("区域 / 全屏 OCR") }
                )
                FilterChip(
                    selected = mode == 1,
                    onClick = { mode = 1 },
                    leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("模板裁剪 & 搜索区") }
                )
            }

            // Status Banner
            if (!state.feedbackMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.isAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = state.feedbackMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Image Inspection Canvas with Visual Region Selector
            val bmp = state.sourceBitmap
            if (bmp == null) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Crop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "加载手机截图或点击“示例界面”，拖动四角选框即可限定识别区域以提升准确率与响应速度",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // Region Metrics & Quick Presets Bar
                val cropW = ((state.cropBox.right - state.cropBox.left) * bmp.width).roundToInt().coerceAtLeast(1)
                val cropH = ((state.cropBox.bottom - state.cropBox.top) * bmp.height).roundToInt().coerceAtLeast(1)
                val areaPct = (((state.cropBox.right - state.cropBox.left) * (state.cropBox.bottom - state.cropBox.top)) * 100f)
                    .roundToInt().coerceIn(1, 100)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "框选区域：${cropW}×${cropH}px（占全屏 $areaPct%）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                    TextButton(
                        onClick = { onUpdateCropBox(CropBoxNorm(0.20f, 0.25f, 0.80f, 0.65f)) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("中部选区", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { onUpdateCropBox(CropBoxNorm(0.05f, 0.55f, 0.95f, 0.95f)) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("下半屏", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { onUpdateCropBox(CropBoxNorm(0.02f, 0.02f, 0.98f, 0.98f)) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全屏范围", style = MaterialTheme.typography.labelSmall)
                    }
                }

                val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.45f, 1.8f)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    color = NavyDeep,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                    ) {
                        val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                        val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "视觉区域选择画布",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Unified interactive visual crop selector + OCR tap hit-testing
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(bmp) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val startPos = down.position
                                        val nx = startPos.x / boxW
                                        val ny = startPos.y / boxH
                                        val c = latestCropBox
                                        val tol = 0.085f
                                        val activeHandle = when {
                                            abs(nx - c.left) < tol && abs(ny - c.top) < tol -> CropDragHandle.TOP_LEFT
                                            abs(nx - c.right) < tol && abs(ny - c.top) < tol -> CropDragHandle.TOP_RIGHT
                                            abs(nx - c.left) < tol && abs(ny - c.bottom) < tol -> CropDragHandle.BOTTOM_LEFT
                                            abs(nx - c.right) < tol && abs(ny - c.bottom) < tol -> CropDragHandle.BOTTOM_RIGHT
                                            else -> CropDragHandle.MOVE
                                        }

                                        var totalMovedPx = 0f
                                        var prevPos = startPos
                                        var isDragging = false

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!change.pressed) {
                                                break
                                            }
                                            val delta = change.position - prevPos
                                            prevPos = change.position
                                            totalMovedPx += hypot(delta.x, delta.y)
                                            if (totalMovedPx > viewConfiguration.touchSlop) {
                                                isDragging = true
                                            }
                                            if (isDragging) {
                                                change.consume()
                                                val dx = delta.x / boxW
                                                val dy = delta.y / boxH
                                                val cur = latestCropBox
                                                val minSpan = 0.06f
                                                val updated = when (activeHandle) {
                                                    CropDragHandle.TOP_LEFT -> cur.copy(
                                                        left = (cur.left + dx).coerceIn(0.0f, cur.right - minSpan),
                                                        top = (cur.top + dy).coerceIn(0.0f, cur.bottom - minSpan)
                                                    )
                                                    CropDragHandle.TOP_RIGHT -> cur.copy(
                                                        right = (cur.right + dx).coerceIn(cur.left + minSpan, 1.0f),
                                                        top = (cur.top + dy).coerceIn(0.0f, cur.bottom - minSpan)
                                                    )
                                                    CropDragHandle.BOTTOM_LEFT -> cur.copy(
                                                        left = (cur.left + dx).coerceIn(0.0f, cur.right - minSpan),
                                                        bottom = (cur.bottom + dy).coerceIn(cur.top + minSpan, 1.0f)
                                                    )
                                                    CropDragHandle.BOTTOM_RIGHT -> cur.copy(
                                                        right = (cur.right + dx).coerceIn(cur.left + minSpan, 1.0f),
                                                        bottom = (cur.bottom + dy).coerceIn(cur.top + minSpan, 1.0f)
                                                    )
                                                    CropDragHandle.MOVE -> {
                                                        val w = cur.right - cur.left
                                                        val h = cur.bottom - cur.top
                                                        val nl = (cur.left + dx).coerceIn(0.0f, 1.0f - w)
                                                        val nt = (cur.top + dy).coerceIn(0.0f, 1.0f - h)
                                                        CropBoxNorm(nl, nt, nl + w, nt + h)
                                                    }
                                                }
                                                onUpdateCropBox(updated)
                                            }
                                        }

                                        if (!isDragging && latestMode == 0) {
                                            val imgX = (startPos.x / boxW) * bmp.width
                                            val imgY = (startPos.y / boxH) * bmp.height
                                            val hit = latestTexts.firstOrNull {
                                                it.boundingBox.contains(imgX.toInt(), imgY.toInt())
                                            }
                                            if (hit != null) {
                                                onSelectTextElement(hit)
                                            }
                                        }
                                    }
                                }
                        ) {
                            val l = state.cropBox.left * size.width
                            val t = state.cropBox.top * size.height
                            val r = state.cropBox.right * size.width
                            val b = state.cropBox.bottom * size.height

                            // Darkroom mask outside the selected region
                            val maskColor = Color.Black.copy(alpha = 0.42f)
                            if (t > 0f) {
                                drawRect(maskColor, Offset(0f, 0f), Size(size.width, t))
                            }
                            if (b < size.height) {
                                drawRect(maskColor, Offset(0f, b), Size(size.width, size.height - b))
                            }
                            if (l > 0f && b > t) {
                                drawRect(maskColor, Offset(0f, t), Size(l, b - t))
                            }
                            if (r < size.width && b > t) {
                                drawRect(maskColor, Offset(r, t), Size(size.width - r, b - t))
                            }

                            // Optional locked Search ROI box in Cyan
                            state.searchRoi?.let { sRoi ->
                                val sl = sRoi.left * size.width
                                val st = sRoi.top * size.height
                                val sr = sRoi.right * size.width
                                val sb = sRoi.bottom * size.height
                                drawRect(
                                    color = CyanBright,
                                    topLeft = Offset(sl, st),
                                    size = Size(sr - sl, sb - st),
                                    style = Stroke(
                                        width = 2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                    )
                                )
                            }

                            // Active crop selector frame + rule-of-thirds guides
                            drawRect(
                                color = AmberSignal,
                                topLeft = Offset(l, t),
                                size = Size(r - l, b - t),
                                style = Stroke(width = 2.5.dp.toPx())
                            )
                            val thirdW = (r - l) / 3f
                            val thirdH = (b - t) / 3f
                            drawLine(
                                color = Color.White.copy(alpha = 0.25f),
                                start = Offset(l + thirdW, t),
                                end = Offset(l + thirdW, b),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.25f),
                                start = Offset(l + thirdW * 2f, t),
                                end = Offset(l + thirdW * 2f, b),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.25f),
                                start = Offset(l, t + thirdH),
                                end = Offset(r, t + thirdH),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.25f),
                                start = Offset(l, t + thirdH * 2f),
                                end = Offset(r, t + thirdH * 2f),
                                strokeWidth = 1f
                            )

                            // 4 Corner Handles
                            val handleR = 8.dp.toPx()
                            drawCircle(AmberSignal, radius = handleR, center = Offset(l, t))
                            drawCircle(AmberSignal, radius = handleR, center = Offset(r, t))
                            drawCircle(AmberSignal, radius = handleR, center = Offset(l, b))
                            drawCircle(AmberSignal, radius = handleR, center = Offset(r, b))

                            if (mode == 0) {
                                val sx = size.width / bmp.width.toFloat()
                                val sy = size.height / bmp.height.toFloat()
                                state.recognizedTexts.forEach { el ->
                                    val isSel = el == state.selectedTextElement
                                    val er = el.boundingBox
                                    val col = if (isSel) EmeraldPulse else CyanBright
                                    drawRect(
                                        color = col.copy(alpha = if (isSel) 0.28f else 0.12f),
                                        topLeft = Offset(er.left * sx, er.top * sy),
                                        size = Size(er.width() * sx, er.height() * sy)
                                    )
                                    drawRect(
                                        color = col,
                                        topLeft = Offset(er.left * sx, er.top * sy),
                                        size = Size(er.width() * sx, er.height() * sy),
                                        style = Stroke(width = if (isSel) 3.dp.toPx() else 1.5.dp.toPx())
                                    )
                                }
                            } else {
                                state.testMatchResult?.takeIf { it.found }?.let { match ->
                                    val sx = size.width / bmp.width.toFloat()
                                    val sy = size.height / bmp.height.toFloat()
                                    val mr = match.boundingBox
                                    drawRect(
                                        color = EmeraldPulse,
                                        topLeft = Offset(mr.left * sx, mr.top * sy),
                                        size = Size(mr.width() * sx, mr.height() * sy),
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }
                            }
                        }
                    }
                }

                // Smart Region Feature Detection & Algorithm Recommendation Card
                SmartDetectionRecommendationCard(
                    result = state.smartDetection,
                    isDetecting = state.isSmartDetecting,
                    applyButtonLabel = if (state.smartDetection?.recommendedAlgorithm == RecommendedAlgorithm.OCR_TEXT) {
                        "采纳推荐：使用 OCR 生成步骤"
                    } else {
                        "采纳推荐：裁剪模板并生成步骤"
                    },
                    onReanalyze = onRunSmartDetect,
                    onApplyRecommendation = { res ->
                        val isOcr = res.recommendedAlgorithm == RecommendedAlgorithm.OCR_TEXT
                        mode = if (isOcr) 0 else 1
                        val bindRoi = if (isOcr) useRegionRoiForOcr else useRegionRoiForImage
                        onApplySmartRecommendation(targetTaskId, res, bindRoi)
                    }
                )

                // Target Task Selector for one-click step saving
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("添加到任务：", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = targetTaskId == null,
                        onClick = { targetTaskId = null },
                        label = { Text("新建独立任务") }
                    )
                    tasks.forEach { t ->
                        FilterChip(
                            selected = targetTaskId == t.task.id,
                            onClick = { targetTaskId = t.task.id },
                            label = { Text(t.task.name) }
                        )
                    }
                }

                if (mode == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                useRegionRoiForOcr = true
                                onRunOcr(true)
                            },
                            enabled = !state.isAnalyzing,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inspector_run_region_ocr_btn")
                        ) {
                            Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("区域 OCR 识别")
                        }
                        OutlinedButton(
                            onClick = {
                                useRegionRoiForOcr = false
                                onRunOcr(false)
                            },
                            enabled = !state.isAnalyzing,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inspector_run_ocr_btn")
                        ) {
                            Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("全屏 OCR")
                        }
                    }

                    if (state.recognizedTexts.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            state.recognizedTexts.forEach { el ->
                                FilterChip(
                                    selected = el == state.selectedTextElement,
                                    onClick = { onSelectTextElement(el) },
                                    label = { Text(el.text, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    state.selectedTextElement?.let { el ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, EmeraldPulse.copy(alpha = 0.6f)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "选中文字：「${el.text}」",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        val elapsedText = state.ocrElapsedMs?.let { " · 耗时 ${it}ms" } ?: ""
                                        Text(
                                            text = "全屏坐标 (${el.centerX.toInt()}, ${el.centerY.toInt()}) · ${el.boundingBox.width()}×${el.boundingBox.height()}$elapsedText",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Button(
                                        onClick = { onSaveOcrStep(targetTaskId, el.text, useRegionRoiForOcr) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        modifier = Modifier.testTag("inspector_save_ocr_step_btn")
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("生成步骤")
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "仅在当前框选区域内识别（减少干扰与耗时）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Switch(
                                        checked = useRegionRoiForOcr,
                                        onCheckedChange = { useRegionRoiForOcr = it },
                                        modifier = Modifier.testTag("inspector_ocr_roi_switch")
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onCropAndTest,
                            enabled = !state.isAnalyzing,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inspector_crop_test_btn")
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("裁剪模板并回测")
                        }
                        OutlinedButton(
                            onClick = onApplySearchRoi,
                            enabled = !state.isAnalyzing,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inspector_set_roi_btn")
                        ) {
                            Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("设为搜索区域")
                        }
                    }

                    if (state.searchRoi != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已锁定青色虚线搜索区域 (ROI)，匹配仅在该区域执行",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanBright
                            )
                            TextButton(onClick = onClearSearchRoi) {
                                Text("恢复全屏")
                            }
                        }
                    }

                    val preview = state.croppedTemplatePreview
                    val savedPath = state.savedTemplatePath
                    val testRes = state.testMatchResult
                    if (preview != null && savedPath != null) {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Image(
                                        bitmap = preview.asImageBitmap(),
                                        contentDescription = "裁剪出的模板图片",
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.White)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "模板尺寸 ${preview.width}×${preview.height}",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        if (testRes != null) {
                                            Text(
                                                text = "回测相似度 ${(testRes.similarity * 100).toInt()}% · 耗时 ${testRes.elapsedMs}ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = EmeraldPulse
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = { onSaveImageStep(targetTaskId, savedPath, 0.75f, useRegionRoiForImage) },
                                        modifier = Modifier.testTag("inspector_save_img_step_btn")
                                    ) {
                                        Text("生成步骤")
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (state.searchRoi != null) {
                                            "保存步骤时绑定已设定的搜索区域 (ROI)"
                                        } else {
                                            "保存步骤时仅在当前框选区域附近匹配"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Switch(
                                        checked = useRegionRoiForImage,
                                        onCheckedChange = { useRegionRoiForImage = it },
                                        modifier = Modifier.testTag("inspector_img_roi_switch")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
