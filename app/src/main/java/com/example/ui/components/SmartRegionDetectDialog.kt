package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.AutomationStepEntity
import com.example.data.StepType
import com.example.data.TextMatchMode
import com.example.engine.DetectedFeatureKind
import com.example.engine.RecommendedAlgorithm
import com.example.engine.SmartDetectionResult
import com.example.ui.CropBoxNorm
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NavyDeep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun SmartDetectionRecommendationCard(
    result: SmartDetectionResult?,
    isDetecting: Boolean,
    applyButtonLabel: String,
    onReanalyze: () -> Unit,
    onApplyRecommendation: (SmartDetectionResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("smart_detection_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "智能特征检测与算法推荐",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                if (isDetecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(
                        onClick = onReanalyze,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("smart_detect_refresh_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "重新智能检测",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (result == null) {
                Text(
                    text = if (isDetecting) "正在分析框选区域文字密度、边缘盒框与色彩特征..." else "拖动选框后将自动分析区域特征并推荐 OCR 或模板匹配",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val isOcr = result.recommendedAlgorithm == RecommendedAlgorithm.OCR_TEXT
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "区域特征：${result.featureKind.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOcr) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isOcr) Icons.Default.TextFields else Icons.Default.ImageSearch,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "推荐：${result.recommendedAlgorithm.label} (${result.confidenceScore}%)",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Text(
                        text = "${result.elapsedMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = result.reasonSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    result.featureMetrics.forEach { metric ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = metric,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = { onApplyRecommendation(result) },
                    enabled = !isDetecting,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("smart_detect_apply_btn")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(applyButtonLabel)
                }
            }
        }
    }
}

private enum class SmartDialogCropHandle {
    MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

@Composable
fun SmartRegionDetectDialog(
    initialBitmap: Bitmap?,
    initialCropBox: CropBoxNorm,
    hasRoot: Boolean,
    onDecodeUri: suspend (Uri) -> Bitmap?,
    onCaptureRoot: suspend () -> Result<Bitmap>,
    onLoadSample: suspend () -> Bitmap,
    onAnalyzeRegion: suspend (Bitmap, CropBoxNorm) -> SmartDetectionResult,
    onBuildImageTemplateStep: suspend (
        bitmap: Bitmap,
        cropBox: CropBoxNorm,
        threshold: Float,
        title: String,
        bindRoi: Boolean
    ) -> AutomationStepEntity,
    onDismiss: () -> Unit,
    onStepReady: (AutomationStepEntity, editBeforeAdding: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var activeBitmap by remember { mutableStateOf(initialBitmap) }
    var cropBox by remember { mutableStateOf(initialCropBox) }
    var detectionResult by remember { mutableStateOf<SmartDetectionResult?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var bindRoi by remember { mutableStateOf(true) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var overrideAlgorithm by remember { mutableStateOf<RecommendedAlgorithm?>(null) }

    val latestCropBox by rememberUpdatedState(cropBox)

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = onDecodeUri(uri)
            if (decoded != null) {
                activeBitmap = decoded
                overrideAlgorithm = null
                statusError = null
            } else {
                statusError = "图片解析失败，请重新选择"
            }
        }
    }

    // Automatically re-analyze region features when bitmap loads or cropBox settles
    LaunchedEffect(activeBitmap, cropBox) {
        val bmp = activeBitmap ?: return@LaunchedEffect
        delay(260L)
        isDetecting = true
        overrideAlgorithm = null
        val analyzed = runCatching { onAnalyzeRegion(bmp, cropBox) }
        analyzed.onSuccess {
            detectionResult = it
            statusError = null
        }.onFailure { err ->
            statusError = "智能检测异常：${err.message}"
        }
        isDetecting = false
    }

    fun applyResult(result: SmartDetectionResult, editBeforeAdding: Boolean) {
        val bmp = activeBitmap ?: return
        val chosenAlg = overrideAlgorithm ?: result.recommendedAlgorithm
        scope.launch {
            if (chosenAlg == RecommendedAlgorithm.OCR_TEXT && !result.detectedText.isNullOrBlank()) {
                val step = AutomationStepEntity(
                    taskId = 0L,
                    stepOrder = 0,
                    title = result.suggestedStepTitle,
                    stepType = StepType.OCR_TEXT_TAP.name,
                    targetText = result.detectedText,
                    textMatchMode = result.suggestedTextMatchMode.name,
                    roiEnabled = bindRoi,
                    roiLeft = cropBox.left,
                    roiTop = cropBox.top,
                    roiRight = cropBox.right,
                    roiBottom = cropBox.bottom
                )
                onStepReady(step, editBeforeAdding)
            } else {
                val step = onBuildImageTemplateStep(
                    bmp,
                    cropBox,
                    result.suggestedTemplateThreshold,
                    result.suggestedStepTitle,
                    bindRoi
                )
                onStepReady(step, editBeforeAdding)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("区域智能检测配参") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("相册截图", style = MaterialTheme.typography.labelSmall)
                    }
                    if (hasRoot) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isDetecting = true
                                    onCaptureRoot()
                                        .onSuccess {
                                            activeBitmap = it
                                            statusError = null
                                        }
                                        .onFailure {
                                            statusError = "Root 截屏失败：${it.message}"
                                        }
                                    isDetecting = false
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Screenshot, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Root 截屏", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                activeBitmap = onLoadSample()
                                statusError = null
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("示例界面", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (statusError != null) {
                    Text(
                        text = statusError!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val bmp = activeBitmap
                if (bmp == null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "请先从相册、Root 截屏或示例界面加载画面，再拖动裁剪框截取按钮、输入框或图标区域",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.6f, 1.8f)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = NavyDeep,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                                contentDescription = "智能检测裁剪画面",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )

                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(bmp) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val nx = down.position.x / boxW
                                            val ny = down.position.y / boxH
                                            val c = latestCropBox
                                            val tol = 0.09f
                                            val handle = when {
                                                abs(nx - c.left) < tol && abs(ny - c.top) < tol -> SmartDialogCropHandle.TOP_LEFT
                                                abs(nx - c.right) < tol && abs(ny - c.top) < tol -> SmartDialogCropHandle.TOP_RIGHT
                                                abs(nx - c.left) < tol && abs(ny - c.bottom) < tol -> SmartDialogCropHandle.BOTTOM_LEFT
                                                abs(nx - c.right) < tol && abs(ny - c.bottom) < tol -> SmartDialogCropHandle.BOTTOM_RIGHT
                                                else -> SmartDialogCropHandle.MOVE
                                            }
                                            var prevPos = down.position
                                            var moved = 0f
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val ch = ev.changes.firstOrNull() ?: break
                                                if (!ch.pressed) break
                                                val delta = ch.position - prevPos
                                                prevPos = ch.position
                                                moved += hypot(delta.x, delta.y)
                                                if (moved > viewConfiguration.touchSlop) {
                                                    ch.consume()
                                                    val dx = delta.x / boxW
                                                    val dy = delta.y / boxH
                                                    val cur = latestCropBox
                                                    val minSpan = 0.06f
                                                    cropBox = when (handle) {
                                                        SmartDialogCropHandle.TOP_LEFT -> cur.copy(
                                                            left = (cur.left + dx).coerceIn(0f, cur.right - minSpan),
                                                            top = (cur.top + dy).coerceIn(0f, cur.bottom - minSpan)
                                                        )
                                                        SmartDialogCropHandle.TOP_RIGHT -> cur.copy(
                                                            right = (cur.right + dx).coerceIn(cur.left + minSpan, 1f),
                                                            top = (cur.top + dy).coerceIn(0f, cur.bottom - minSpan)
                                                        )
                                                        SmartDialogCropHandle.BOTTOM_LEFT -> cur.copy(
                                                            left = (cur.left + dx).coerceIn(0f, cur.right - minSpan),
                                                            bottom = (cur.bottom + dy).coerceIn(cur.top + minSpan, 1f)
                                                        )
                                                        SmartDialogCropHandle.BOTTOM_RIGHT -> cur.copy(
                                                            right = (cur.right + dx).coerceIn(cur.left + minSpan, 1f),
                                                            bottom = (cur.bottom + dy).coerceIn(cur.top + minSpan, 1f)
                                                        )
                                                        SmartDialogCropHandle.MOVE -> {
                                                            val w = cur.right - cur.left
                                                            val h = cur.bottom - cur.top
                                                            val nl = (cur.left + dx).coerceIn(0f, 1f - w)
                                                            val nt = (cur.top + dy).coerceIn(0f, 1f - h)
                                                            CropBoxNorm(nl, nt, nl + w, nt + h)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                val l = cropBox.left * size.width
                                val t = cropBox.top * size.height
                                val r = cropBox.right * size.width
                                val b = cropBox.bottom * size.height
                                val mask = Color.Black.copy(alpha = 0.42f)
                                if (t > 0f) drawRect(mask, Offset.Zero, Size(size.width, t))
                                if (b < size.height) drawRect(mask, Offset(0f, b), Size(size.width, size.height - b))
                                if (l > 0f && b > t) drawRect(mask, Offset(0f, t), Size(l, b - t))
                                if (r < size.width && b > t) drawRect(mask, Offset(r, t), Size(size.width - r, b - t))

                                val boxColor = when (detectionResult?.featureKind) {
                                    DetectedFeatureKind.INTERACTIVE_BUTTON -> EmeraldPulse
                                    DetectedFeatureKind.INPUT_FIELD -> AmberSignal
                                    else -> AmberSignal
                                }
                                drawRect(
                                    color = boxColor,
                                    topLeft = Offset(l, t),
                                    size = Size(r - l, b - t),
                                    style = Stroke(width = 2.5.dp.toPx())
                                )
                                val hr = 7.dp.toPx()
                                drawCircle(boxColor, radius = hr, center = Offset(l, t))
                                drawCircle(boxColor, radius = hr, center = Offset(r, t))
                                drawCircle(boxColor, radius = hr, center = Offset(l, b))
                                drawCircle(boxColor, radius = hr, center = Offset(r, b))
                            }
                        }
                    }

                    SmartDetectionRecommendationCard(
                        result = detectionResult,
                        isDetecting = isDetecting,
                        applyButtonLabel = "采纳推荐并加入步骤",
                        onReanalyze = {
                            scope.launch {
                                isDetecting = true
                                runCatching { onAnalyzeRegion(bmp, cropBox) }
                                    .onSuccess { detectionResult = it }
                                isDetecting = false
                            }
                        },
                        onApplyRecommendation = { res -> applyResult(res, editBeforeAdding = false) }
                    )

                    detectionResult?.let { res ->
                        val activeAlg = overrideAlgorithm ?: res.recommendedAlgorithm
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("算法切换：", style = MaterialTheme.typography.labelSmall)
                            FilterChip(
                                selected = activeAlg == RecommendedAlgorithm.OCR_TEXT,
                                enabled = !res.detectedText.isNullOrBlank(),
                                onClick = { overrideAlgorithm = RecommendedAlgorithm.OCR_TEXT },
                                label = { Text("OCR 识别", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = activeAlg == RecommendedAlgorithm.TEMPLATE_MATCH,
                                onClick = { overrideAlgorithm = RecommendedAlgorithm.TEMPLATE_MATCH },
                                label = { Text("图像模板", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "同步绑定当前框选区域为 ROI（提速防误触）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = bindRoi,
                            onCheckedChange = { bindRoi = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            val res = detectionResult
            if (res != null) {
                Button(
                    onClick = { applyResult(res, editBeforeAdding = true) },
                    enabled = !isDetecting,
                    modifier = Modifier.testTag("smart_dialog_fill_edit_btn")
                ) {
                    Text("填入步骤微调")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
