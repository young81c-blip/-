package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CyanBright
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldPulse
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

class FlowBlockDragState(
    val lazyListState: LazyListState,
    private val headerItemCount: Int,
    private val spacingPx: Float,
    private val haptic: HapticFeedback
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var draggingLabel by mutableStateOf("")
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set
    var rawDragOffsetX by mutableFloatStateOf(0f)
        private set
    var activeBlockIndex by mutableStateOf<Int?>(null)
        private set

    private val snapThresholdPx = 22f
    private val maxHorizontalDriftPx = 44f

    val isDragging: Boolean get() = draggingKey != null

    val isAlignedToRail: Boolean
        get() = abs(rawDragOffsetX) <= snapThresholdPx

    val snappedOffsetX: Float
        get() = if (isAlignedToRail) 0f else rawDragOffsetX.coerceIn(-maxHorizontalDriftPx, maxHorizontalDriftPx)

    fun startDrag(key: String, label: String, blockIndex: Int) {
        draggingKey = key
        draggingLabel = label
        activeBlockIndex = blockIndex
        dragOffsetY = 0f
        rawDragOffsetX = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun onDrag(
        delta: Offset,
        onMoveBlock: (fromBlockIndex: Int, delta: Int) -> Unit
    ) {
        val activeKey = draggingKey ?: return
        val wasAligned = isAlignedToRail

        dragOffsetY += delta.y
        rawDragOffsetX = (rawDragOffsetX + delta.x).coerceIn(-maxHorizontalDriftPx, maxHorizontalDriftPx)
        val nowAligned = isAlignedToRail
        if (!wasAligned && nowAligned) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        val visibleFlowItems = lazyListState.layoutInfo.visibleItemsInfo
            .filter { it.index >= headerItemCount }
        val currentInfo = visibleFlowItems.firstOrNull { it.key == activeKey } ?: return
        val currentBlockIdx = currentInfo.index - headerItemCount
        activeBlockIndex = currentBlockIdx

        val draggedTop = currentInfo.offset + dragOffsetY
        val draggedCenter = draggedTop + currentInfo.size / 2f

        val nextInfo = visibleFlowItems.firstOrNull { it.index == currentInfo.index + 1 }
        if (nextInfo != null && draggedCenter > nextInfo.offset + nextInfo.size * 0.48f) {
            onMoveBlock(currentBlockIdx, +1)
            dragOffsetY -= (nextInfo.size + spacingPx)
            activeBlockIndex = currentBlockIdx + 1
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            return
        }

        val prevInfo = visibleFlowItems.firstOrNull {
            it.index == currentInfo.index - 1 && it.index >= headerItemCount
        }
        if (prevInfo != null && draggedCenter < prevInfo.offset + prevInfo.size * 0.52f) {
            onMoveBlock(currentBlockIdx, -1)
            dragOffsetY += (prevInfo.size + spacingPx)
            activeBlockIndex = currentBlockIdx - 1
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun compensateScroll(scrolledPx: Float) {
        if (draggingKey != null) {
            dragOffsetY += scrolledPx
        }
    }

    fun endDrag() {
        draggingKey = null
        draggingLabel = ""
        activeBlockIndex = null
        dragOffsetY = 0f
        rawDragOffsetX = 0f
    }
}

@Composable
fun rememberFlowBlockDragState(
    lazyListState: LazyListState,
    headerItemCount: Int,
    haptic: HapticFeedback
): FlowBlockDragState {
    val spacingPx = with(LocalDensity.current) { 12.dp.toPx() }
    val state = remember(lazyListState, headerItemCount, spacingPx, haptic) {
        FlowBlockDragState(
            lazyListState = lazyListState,
            headerItemCount = headerItemCount,
            spacingPx = spacingPx,
            haptic = haptic
        )
    }

    val edgeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val scrollStepPx = with(LocalDensity.current) { 12.dp.toPx() }

    LaunchedEffect(state.draggingKey) {
        val activeKey = state.draggingKey ?: return@LaunchedEffect
        while (isActive && state.draggingKey == activeKey) {
            val info = state.lazyListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == activeKey }
            if (info != null) {
                val visualTop = info.offset + state.dragOffsetY
                val visualBottom = visualTop + info.size
                val vpTop = state.lazyListState.layoutInfo.viewportStartOffset.toFloat()
                val vpBottom = state.lazyListState.layoutInfo.viewportEndOffset.toFloat()

                var scrollDelta = 0f
                if (visualTop < vpTop + edgeThresholdPx && state.lazyListState.canScrollBackward) {
                    val ratio = ((vpTop + edgeThresholdPx - visualTop) / edgeThresholdPx).coerceIn(0.25f, 1.4f)
                    scrollDelta = -scrollStepPx * ratio
                } else if (visualBottom > vpBottom - edgeThresholdPx && state.lazyListState.canScrollForward) {
                    val ratio = ((visualBottom - (vpBottom - edgeThresholdPx)) / edgeThresholdPx).coerceIn(0.25f, 1.4f)
                    scrollDelta = scrollStepPx * ratio
                }

                if (scrollDelta != 0f) {
                    val consumed = state.lazyListState.scrollBy(scrollDelta)
                    state.compensateScroll(consumed)
                }
            }
            delay(16L)
        }
    }

    return state
}

@Composable
fun FlowAlignmentBackdropCanvas(
    lazyListState: LazyListState,
    headerItemCount: Int,
    showGuides: Boolean,
    hasBlocks: Boolean,
    modifier: Modifier = Modifier
) {
    if (!showGuides || !hasBlocks) return
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline

    Canvas(modifier = modifier.fillMaxSize()) {
        val horizontalPadPx = 16.dp.toPx()
        val leftRailX = horizontalPadPx
        val rightRailX = (size.width - horizontalPadPx).coerceAtLeast(leftRailX + 1f)
        val centerAxisX = size.width / 2f

        val flowItems = lazyListState.layoutInfo.visibleItemsInfo
            .filter { it.index >= headerItemCount }
        if (flowItems.isEmpty()) return@Canvas

        val startY = (flowItems.first().offset.toFloat() - 8.dp.toPx()).coerceIn(0f, size.height)
        val endY = (flowItems.last().offset + flowItems.last().size + 8.dp.toPx()).coerceIn(startY, size.height)
        if (endY <= startY) return@Canvas

        val railDash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f)

        // Subtle left / right margin alignment rails
        drawLine(
            color = outlineColor.copy(alpha = 0.28f),
            start = Offset(leftRailX, startY),
            end = Offset(leftRailX, endY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = railDash
        )
        drawLine(
            color = outlineColor.copy(alpha = 0.28f),
            start = Offset(rightRailX, startY),
            end = Offset(rightRailX, endY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = railDash
        )
        // Center flow axis rail
        drawLine(
            color = primaryColor.copy(alpha = 0.22f),
            start = Offset(centerAxisX, startY),
            end = Offset(centerAxisX, endY),
            strokeWidth = 1.4.dp.toPx(),
            pathEffect = railDash
        )

        // Flow directional connectors in gaps between consecutive blocks
        for (i in 0 until flowItems.lastIndex) {
            val topItem = flowItems[i]
            val bottomItem = flowItems[i + 1]
            val gapTop = (topItem.offset + topItem.size).toFloat()
            val gapBottom = bottomItem.offset.toFloat()
            if (gapBottom - gapTop >= 6.dp.toPx()) {
                val midY = (gapTop + gapBottom) / 2f
                drawLine(
                    color = primaryColor.copy(alpha = 0.50f),
                    start = Offset(centerAxisX, gapTop),
                    end = Offset(centerAxisX, gapBottom),
                    strokeWidth = 1.6.dp.toPx()
                )
                val arrowR = 3.5.dp.toPx()
                val arrowPath = Path().apply {
                    moveTo(centerAxisX - arrowR, midY - arrowR * 0.55f)
                    lineTo(centerAxisX + arrowR, midY - arrowR * 0.55f)
                    lineTo(centerAxisX, midY + arrowR * 0.9f)
                    close()
                }
                drawPath(arrowPath, color = primaryColor.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
fun FlowAlignmentActiveOverlayCanvas(
    dragState: FlowBlockDragState,
    headerItemCount: Int,
    modifier: Modifier = Modifier
) {
    val activeKey = dragState.draggingKey ?: return
    val isAligned = dragState.isAlignedToRail
    val snappedX = dragState.snappedOffsetX

    Canvas(modifier = modifier.fillMaxSize()) {
        val horizontalPadPx = 16.dp.toPx()
        val leftRailX = horizontalPadPx
        val rightRailX = (size.width - horizontalPadPx).coerceAtLeast(leftRailX + 1f)
        val centerAxisX = size.width / 2f

        val visibleFlowItems = dragState.lazyListState.layoutInfo.visibleItemsInfo
            .filter { it.index >= headerItemCount }
        val activeInfo = visibleFlowItems.firstOrNull { it.key == activeKey } ?: return@Canvas

        val headerAnchorItem = dragState.lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == headerItemCount - 1 }
        val guideTopY = if (headerAnchorItem != null) {
            (headerAnchorItem.offset + headerAnchorItem.size + 4.dp.toPx()).coerceIn(0f, size.height)
        } else {
            0f
        }

        val activeTop = activeInfo.offset + dragState.dragOffsetY
        val activeBottom = activeTop + activeInfo.size
        val activeCenterY = (activeTop + activeBottom) / 2f

        val snapColor = if (isAligned) EmeraldPulse else CyanBright
        val railDash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()), 0f)

        // 1. Vertical left, center, and right magnetic alignment rails
        listOf(leftRailX, centerAxisX, rightRailX).forEach { railX ->
            drawLine(
                color = snapColor.copy(alpha = if (isAligned) 0.72f else 0.40f),
                start = Offset(railX, guideTopY),
                end = Offset(railX, size.height),
                strokeWidth = if (isAligned) 1.6.dp.toPx() else 1.2.dp.toPx(),
                pathEffect = railDash
            )
        }

        // 2. If drifted horizontally outside snap window, show live offset guide line in amber
        if (!isAligned) {
            drawLine(
                color = AmberSignal.copy(alpha = 0.78f),
                start = Offset(centerAxisX + snappedX, guideTopY),
                end = Offset(centerAxisX + snappedX, size.height),
                strokeWidth = 1.4.dp.toPx(),
                pathEffect = railDash
            )
        }

        // 3. Horizontal bounding guides following the dragged card edges + corner snap nodes
        val extPad = 10.dp.toPx()
        val hDash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 0f)
        listOf(activeTop, activeBottom).forEach { y ->
            if (y in guideTopY..size.height) {
                drawLine(
                    color = snapColor.copy(alpha = 0.55f),
                    start = Offset((leftRailX - extPad).coerceAtLeast(0f), y),
                    end = Offset((rightRailX + extPad).coerceAtMost(size.width), y),
                    strokeWidth = 1.1.dp.toPx(),
                    pathEffect = hDash
                )
                drawCircle(color = snapColor, radius = 3.2.dp.toPx(), center = Offset(leftRailX + snappedX, y))
                drawCircle(color = snapColor, radius = 3.2.dp.toPx(), center = Offset(rightRailX + snappedX, y))
            }
        }
        if (activeCenterY in guideTopY..size.height) {
            drawCircle(
                color = if (isAligned) EmeraldPulse else AmberSignal,
                radius = 4.5.dp.toPx(),
                center = Offset(centerAxisX + snappedX, activeCenterY)
            )
        }

        // 4. Target Drop-Slot Horizontal Insertion Indicator Bar (●━━━━━━━━●)
        val slotIndicatorY = when {
            dragState.dragOffsetY > 6.dp.toPx() -> activeInfo.offset + activeInfo.size.toFloat()
            else -> activeInfo.offset.toFloat()
        }.coerceIn(guideTopY, size.height)

        drawLine(
            color = CyanElectric.copy(alpha = 0.30f),
            start = Offset(leftRailX, slotIndicatorY),
            end = Offset(rightRailX, slotIndicatorY),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = snapColor,
            start = Offset(leftRailX, slotIndicatorY),
            end = Offset(rightRailX, slotIndicatorY),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = snapColor, radius = 4.dp.toPx(), center = Offset(leftRailX, slotIndicatorY))
        drawCircle(color = snapColor, radius = 4.dp.toPx(), center = Offset(rightRailX, slotIndicatorY))
    }
}

@Composable
fun FlowDragAlignmentHud(
    isDragging: Boolean,
    label: String,
    slotNumber: Int?,
    isAligned: Boolean,
    scopeLabel: String = "主流程",
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isDragging,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier
    ) {
        val accent = if (isAligned) EmeraldPulse else AmberSignal
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = BorderStroke(1.2.dp, accent),
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("drag_alignment_hud")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${scopeLabel}第 #${slotNumber ?: 1} 位 · $label",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accent.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlignHorizontalCenter,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isAligned) "已自动对齐" else "靠近中线吸附",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
