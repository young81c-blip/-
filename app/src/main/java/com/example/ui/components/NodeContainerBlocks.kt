package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.data.AutomationStepEntity
import com.example.data.BranchConditionType
import com.example.data.StepType
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CyanBright
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.RoseAlert
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface EditorFlowBlock {
    val startIndex: Int
    val endIndex: Int

    data class SingleStep(
        val index: Int,
        val step: AutomationStepEntity
    ) : EditorFlowBlock {
        override val startIndex: Int = index
        override val endIndex: Int = index
    }

    data class NodeContainer(
        val groupId: String,
        val groupName: String,
        override val startIndex: Int,
        override val endIndex: Int,
        val steps: List<AutomationStepEntity>
    ) : EditorFlowBlock {
        val allDisabled: Boolean get() = steps.none { it.isEnabled }
        val anyEnabled: Boolean get() = steps.any { it.isEnabled }
    }
}

object NodeContainerUtils {

    fun buildBlocks(steps: List<AutomationStepEntity>): List<EditorFlowBlock> {
        if (steps.isEmpty()) return emptyList()
        val blocks = mutableListOf<EditorFlowBlock>()
        var i = 0
        while (i < steps.size) {
            val current = steps[i]
            if (current.groupId.isBlank()) {
                blocks.add(EditorFlowBlock.SingleStep(i, current))
                i++
            } else {
                val gid = current.groupId
                var end = i
                while (end + 1 < steps.size && steps[end + 1].groupId == gid) {
                    end++
                }
                val slice = steps.subList(i, end + 1)
                val label = slice.firstOrNull { it.groupName.isNotBlank() }?.groupName ?: "节点容器"
                blocks.add(
                    EditorFlowBlock.NodeContainer(
                        groupId = gid,
                        groupName = label,
                        startIndex = i,
                        endIndex = end,
                        steps = slice
                    )
                )
                i = end + 1
            }
        }
        return blocks
    }

    fun moveBlock(
        steps: List<AutomationStepEntity>,
        blocks: List<EditorFlowBlock>,
        blockIndex: Int,
        delta: Int
    ): List<AutomationStepEntity> {
        val targetIndex = blockIndex + delta
        if (blockIndex !in blocks.indices || targetIndex !in blocks.indices) return steps
        val firstIdx = minOf(blockIndex, targetIndex)
        val secondIdx = maxOf(blockIndex, targetIndex)
        val firstBlock = blocks[firstIdx]
        val secondBlock = blocks[secondIdx]

        val result = mutableListOf<AutomationStepEntity>()
        if (firstBlock.startIndex > 0) {
            result.addAll(steps.subList(0, firstBlock.startIndex))
        }
        result.addAll(steps.subList(secondBlock.startIndex, secondBlock.endIndex + 1))
        result.addAll(steps.subList(firstBlock.startIndex, firstBlock.endIndex + 1))
        if (secondBlock.endIndex + 1 < steps.size) {
            result.addAll(steps.subList(secondBlock.endIndex + 1, steps.size))
        }
        return result
    }

    fun duplicateContainer(
        steps: List<AutomationStepEntity>,
        startIndex: Int,
        endIndex: Int
    ): List<AutomationStepEntity> {
        if (startIndex < 0 || endIndex >= steps.size || startIndex > endIndex) return steps
        val newGroupId = "grp_${UUID.randomUUID().toString().take(8)}"
        val originalSlice = steps.subList(startIndex, endIndex + 1)
        val baseName = originalSlice.firstOrNull { it.groupName.isNotBlank() }?.groupName ?: "节点容器"
        val copiedName = if (baseName.endsWith("副本")) baseName else "$baseName 副本"
        val copiedSlice = originalSlice.map { step ->
            step.copy(
                id = 0,
                groupId = newGroupId,
                groupName = copiedName
            )
        }
        val result = steps.toMutableList()
        result.addAll(endIndex + 1, copiedSlice)
        return result
    }

    fun setContainerEnabled(
        steps: List<AutomationStepEntity>,
        startIndex: Int,
        endIndex: Int,
        enabled: Boolean
    ): List<AutomationStepEntity> {
        return steps.mapIndexed { idx, step ->
            if (idx in startIndex..endIndex) step.copy(isEnabled = enabled) else step
        }
    }

    fun renameContainer(
        steps: List<AutomationStepEntity>,
        startIndex: Int,
        endIndex: Int,
        newName: String
    ): List<AutomationStepEntity> {
        val clean = newName.trim().ifBlank { "节点容器" }
        return steps.mapIndexed { idx, step ->
            if (idx in startIndex..endIndex) step.copy(groupName = clean) else step
        }
    }

    fun ungroupContainer(
        steps: List<AutomationStepEntity>,
        startIndex: Int,
        endIndex: Int
    ): List<AutomationStepEntity> {
        return steps.mapIndexed { idx, step ->
            if (idx in startIndex..endIndex) step.copy(groupId = "", groupName = "") else step
        }
    }

    fun deleteContainer(
        steps: List<AutomationStepEntity>,
        startIndex: Int,
        endIndex: Int
    ): List<AutomationStepEntity> {
        return steps.filterIndexed { idx, _ -> idx !in startIndex..endIndex }
    }

    fun groupContiguousRange(
        steps: List<AutomationStepEntity>,
        startIndex: Int,
        endIndex: Int,
        groupName: String
    ): List<AutomationStepEntity> {
        val cleanStart = startIndex.coerceIn(0, steps.lastIndex)
        val cleanEnd = endIndex.coerceIn(cleanStart, steps.lastIndex)
        val newGroupId = "grp_${UUID.randomUUID().toString().take(8)}"
        val cleanName = groupName.trim().ifBlank { "节点容器" }
        return steps.mapIndexed { idx, step ->
            if (idx in cleanStart..cleanEnd) {
                step.copy(groupId = newGroupId, groupName = cleanName)
            } else {
                step
            }
        }
    }

    fun swapAdjacentInRange(
        steps: List<AutomationStepEntity>,
        index: Int,
        delta: Int,
        rangeStart: Int,
        rangeEnd: Int
    ): List<AutomationStepEntity> {
        val target = index + delta
        if (index !in rangeStart..rangeEnd || target !in rangeStart..rangeEnd) return steps
        val mutable = steps.toMutableList()
        val tmp = mutable[target]
        mutable[target] = mutable[index]
        mutable[index] = tmp
        return mutable
    }
}

@Composable
fun NodeContainerCard(
    container: EditorFlowBlock.NodeContainer,
    blockIndex: Int,
    totalBlocks: Int,
    selectionMode: Boolean,
    selectedIndices: Set<Int>,
    isDragging: Boolean = false,
    isAligned: Boolean = true,
    showAlignmentGuides: Boolean = true,
    containerModifier: Modifier = Modifier,
    headerDragModifier: Modifier = Modifier,
    onToggleSelectStep: (Int) -> Unit,
    onMoveContainerUp: () -> Unit,
    onMoveContainerDown: () -> Unit,
    onDuplicateContainer: () -> Unit,
    onToggleContainerEnabled: (Boolean) -> Unit,
    onRenameContainer: () -> Unit,
    onUngroupContainer: () -> Unit,
    onAddStepToContainer: () -> Unit,
    onEditStep: (Int) -> Unit,
    onMoveStepUpInContainer: (Int) -> Unit,
    onMoveStepDownInContainer: (Int) -> Unit,
    onDeleteStep: (Int) -> Unit
) {
    var expanded by remember(container.groupId) { mutableStateOf(true) }
    val isEnabled = container.anyEnabled
    val haptic = LocalHapticFeedback.current
    val childSpacingPx = with(LocalDensity.current) { 8.dp.toPx() }
    val latestContainer by rememberUpdatedState(container)
    val childLayouts = remember(container.groupId) { mutableStateMapOf<Long, Pair<Float, Float>>() }
    var draggingChildId by remember(container.groupId) { mutableStateOf<Long?>(null) }
    var childDragOffsetY by remember(container.groupId) { mutableFloatStateOf(0f) }
    var childRawDragX by remember(container.groupId) { mutableFloatStateOf(0f) }
    val childAligned = abs(childRawDragX) <= 20f
    val childSnappedX = if (childAligned) 0f else childRawDragX.coerceIn(-36f, 36f)
    val primaryColor = MaterialTheme.colorScheme.primary

    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = if (isDragging) 2.2.dp else 1.5.dp,
            color = when {
                isDragging -> if (isAligned) EmeraldPulse else CyanBright
                isEnabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 10.dp else 0.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                isEnabled -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        ),
        modifier = containerModifier
            .fillMaxWidth()
            .alpha(if (isEnabled || isDragging) 1f else 0.72f)
            .testTag("node_container_${container.groupId}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Container Header (long-press here drags the entire container block)
            Row(
                modifier = headerDragModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!selectionMode) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = "长按拖拽整组排序",
                        tint = if (isDragging) EmeraldPulse else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("drag_handle_container_${container.groupId}")
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = if (isEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = container.groupName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "${container.steps.size} 节点",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                        if (!isEnabled) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = "已禁用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "序号 #${container.startIndex + 1} – #${container.endIndex + 1} · 容器内节点按序连续执行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRenameContainer,
                    modifier = Modifier.testTag("rename_container_btn_${container.groupId}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "重命名容器", modifier = Modifier.size(18.dp))
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggleContainerEnabled,
                    modifier = Modifier.testTag("toggle_container_switch_${container.groupId}")
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("expand_container_btn_${container.groupId}")
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起容器" else "展开容器"
                    )
                }
            }

            // Batch Container Actions Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onMoveContainerUp,
                    enabled = blockIndex > 0,
                    modifier = Modifier.testTag("move_up_container_${container.groupId}")
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("上移整组", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onMoveContainerDown,
                    enabled = blockIndex < totalBlocks - 1,
                    modifier = Modifier.testTag("move_down_container_${container.groupId}")
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下移整组", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onDuplicateContainer,
                    modifier = Modifier.testTag("duplicate_container_${container.groupId}")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制整组", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onAddStepToContainer,
                    modifier = Modifier.testTag("add_step_container_${container.groupId}")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("组内添步", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onUngroupContainer,
                    modifier = Modifier.testTag("ungroup_container_${container.groupId}")
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("解散分组", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Nested Steps inside Container (with indent alignment rails + nested long-press drag sorting)
            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            val dragId = draggingChildId
                            if (showAlignmentGuides || dragId != null) {
                                val railX = if (showAlignmentGuides) 5.dp.toPx() else 0f
                                val indentDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)
                                if (showAlignmentGuides && container.steps.isNotEmpty()) {
                                    val childYs = container.steps.mapNotNull { childLayouts[it.id] }
                                    if (childYs.isNotEmpty()) {
                                        val firstCenter = childYs.first().first + childYs.first().second / 2f
                                        val lastCenter = childYs.last().first + childYs.last().second / 2f
                                        drawLine(
                                            color = primaryColor.copy(alpha = 0.42f),
                                            start = Offset(railX, 0f),
                                            end = Offset(railX, lastCenter.coerceAtLeast(firstCenter)),
                                            strokeWidth = 1.4.dp.toPx(),
                                            pathEffect = indentDash
                                        )
                                        childYs.forEach { (y, h) ->
                                            val cy = y + h / 2f
                                            drawLine(
                                                color = primaryColor.copy(alpha = 0.48f),
                                                start = Offset(railX, cy),
                                                end = Offset(12.dp.toPx(), cy),
                                                strokeWidth = 1.3.dp.toPx()
                                            )
                                            drawCircle(
                                                color = primaryColor.copy(alpha = 0.72f),
                                                radius = 2.6.dp.toPx(),
                                                center = Offset(railX, cy)
                                            )
                                        }
                                    }
                                }
                                if (dragId != null) {
                                    val activeLayout = childLayouts[dragId]
                                    if (activeLayout != null) {
                                        val leftX = if (showAlignmentGuides) 12.dp.toPx() else 0f
                                        val rightX = size.width
                                        val centerX = (leftX + rightX) / 2f
                                        val snapColor = if (childAligned) EmeraldPulse else CyanBright
                                        val railDash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 0f)
                                        listOf(leftX, centerX, rightX).forEach { rx ->
                                            drawLine(
                                                color = snapColor.copy(alpha = if (childAligned) 0.68f else 0.35f),
                                                start = Offset(rx, 0f),
                                                end = Offset(rx, size.height),
                                                strokeWidth = 1.3.dp.toPx(),
                                                pathEffect = railDash
                                            )
                                        }
                                        if (!childAligned) {
                                            drawLine(
                                                color = AmberSignal.copy(alpha = 0.75f),
                                                start = Offset(centerX + childSnappedX, 0f),
                                                end = Offset(centerX + childSnappedX, size.height),
                                                strokeWidth = 1.3.dp.toPx(),
                                                pathEffect = railDash
                                            )
                                        }
                                        val slotY = if (childDragOffsetY > 4.dp.toPx()) {
                                            activeLayout.first + activeLayout.second
                                        } else {
                                            activeLayout.first
                                        }.coerceIn(0f, size.height)
                                        drawLine(
                                            color = snapColor,
                                            start = Offset(leftX, slotY),
                                            end = Offset(rightX, slotY),
                                            strokeWidth = 2.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                        drawCircle(color = snapColor, radius = 3.5.dp.toPx(), center = Offset(leftX, slotY))
                                        drawCircle(color = snapColor, radius = 3.5.dp.toPx(), center = Offset(rightX, slotY))
                                    }
                                }
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(start = if (showAlignmentGuides) 12.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        container.steps.forEachIndexed { localIdx, step ->
                            val globalIdx = container.startIndex + localIdx
                            val isChildDragging = draggingChildId == step.id
                            val childDragModifier = if (!selectionMode && container.steps.size > 1) {
                                Modifier.pointerInput(step.id, selectionMode) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingChildId = step.id
                                            childDragOffsetY = 0f
                                            childRawDragX = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val wasAligned = abs(childRawDragX) <= 20f
                                            childDragOffsetY += dragAmount.y
                                            childRawDragX = (childRawDragX + dragAmount.x).coerceIn(-36f, 36f)
                                            if (!wasAligned && abs(childRawDragX) <= 20f) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            val curContainer = latestContainer
                                            val curIdx = curContainer.steps.indexOfFirst { it.id == step.id }
                                            if (curIdx < 0) return@detectDragGesturesAfterLongPress
                                            val curLayout = childLayouts[step.id] ?: return@detectDragGesturesAfterLongPress
                                            val draggedCenter = curLayout.first + childDragOffsetY + curLayout.second / 2f

                                            val nextStep = curContainer.steps.getOrNull(curIdx + 1)
                                            if (nextStep != null) {
                                                val nextLayout = childLayouts[nextStep.id]
                                                if (nextLayout != null && draggedCenter > nextLayout.first + nextLayout.second * 0.48f) {
                                                    onMoveStepDownInContainer(curContainer.startIndex + curIdx)
                                                    childDragOffsetY -= (nextLayout.second + childSpacingPx)
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                            }
                                            val prevStep = curContainer.steps.getOrNull(curIdx - 1)
                                            if (prevStep != null) {
                                                val prevLayout = childLayouts[prevStep.id]
                                                if (prevLayout != null && draggedCenter < prevLayout.first + prevLayout.second * 0.52f) {
                                                    onMoveStepUpInContainer(curContainer.startIndex + curIdx)
                                                    childDragOffsetY += (prevLayout.second + childSpacingPx)
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggingChildId = null
                                            childDragOffsetY = 0f
                                            childRawDragX = 0f
                                        },
                                        onDragCancel = {
                                            draggingChildId = null
                                            childDragOffsetY = 0f
                                            childRawDragX = 0f
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }

                            StepNodeCard(
                                globalIndex = globalIdx,
                                step = step,
                                selectionMode = selectionMode,
                                isSelected = globalIdx in selectedIndices,
                                canMoveUp = localIdx > 0,
                                canMoveDown = localIdx < container.steps.lastIndex,
                                insideContainer = true,
                                isDragging = isChildDragging,
                                isAligned = childAligned,
                                cardModifier = Modifier
                                    .zIndex(if (isChildDragging) 2f else 0f)
                                    .onGloballyPositioned { coords ->
                                        childLayouts[step.id] = coords.positionInParent().y to coords.size.height.toFloat()
                                    }
                                    .graphicsLayer {
                                        if (isChildDragging) {
                                            translationY = childDragOffsetY
                                            translationX = childSnappedX
                                            scaleX = 1.02f
                                            scaleY = 1.02f
                                        }
                                    }
                                    .then(childDragModifier),
                                onToggleSelect = { onToggleSelectStep(globalIdx) },
                                onClickEdit = { onEditStep(globalIdx) },
                                onMoveUp = { onMoveStepUpInContainer(globalIdx) },
                                onMoveDown = { onMoveStepDownInContainer(globalIdx) },
                                onDelete = { onDeleteStep(globalIdx) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StepNodeCard(
    globalIndex: Int,
    step: AutomationStepEntity,
    selectionMode: Boolean,
    isSelected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    insideContainer: Boolean,
    isDragging: Boolean = false,
    isAligned: Boolean = true,
    cardModifier: Modifier = Modifier,
    onToggleSelect: () -> Unit,
    onClickEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val typeLabel = runCatching { StepType.valueOf(step.stepType).label }
        .getOrDefault(step.stepType)
    val enabled = step.isEnabled

    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = when {
                isDragging -> 2.2.dp
                isSelected -> 2.dp
                else -> 1.dp
            },
            color = when {
                isDragging -> if (isAligned) EmeraldPulse else CyanBright
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline.copy(alpha = if (insideContainer) 0.55f else 0.85f)
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 9.dp else 0.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        modifier = cardModifier
            .fillMaxWidth()
            .alpha(if (enabled || isDragging) 1f else 0.6f)
            .clickable {
                if (selectionMode) onToggleSelect() else onClickEdit()
            }
            .testTag("step_card_$globalIndex")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!selectionMode) {
                Icon(
                    imageVector = Icons.Default.DragIndicator,
                    contentDescription = "长按拖拽排序",
                    tint = if (isDragging) {
                        if (isAligned) EmeraldPulse else CyanBright
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
                    },
                    modifier = Modifier
                        .size(18.dp)
                        .testTag("drag_handle_step_$globalIndex")
                )
                Spacer(Modifier.width(4.dp))
            }
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.testTag("step_select_checkbox_$globalIndex")
                )
                Spacer(Modifier.width(4.dp))
            }

            Surface(
                color = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${globalIndex + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!enabled) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                val roiSuffix = if (
                    step.roiEnabled &&
                    (step.stepType == StepType.OCR_TEXT_TAP.name || step.stepType == StepType.IMAGE_TEMPLATE_TAP.name)
                ) {
                    val wPct = ((step.roiRight - step.roiLeft) * 100f).roundToInt().coerceIn(1, 100)
                    val hPct = ((step.roiBottom - step.roiTop) * 100f).roundToInt().coerceIn(1, 100)
                    " · 区域限定(${wPct}%×${hPct}%)"
                } else ""
                val isTapStep = step.stepType != StepType.WAIT_DELAY.name &&
                    step.stepType != StepType.MANUAL_CONFIRM_BRANCH.name
                val offsetSuffix = if (isTapStep && step.randomOffsetEnabled) {
                    " · 偏移±${step.randomOffsetRadiusPx}px"
                } else ""
                val retrySuffix = if (isTapStep && step.maxRetryCount > 0) {
                    " · 失败重试${step.maxRetryCount}次(${step.retryIntervalMs}ms)"
                } else ""
                val timingSuffix = if (isTapStep) {
                    val pageWait = if (step.preScanDelayMs > 0L) "页面等${step.preScanDelayMs}ms · " else ""
                    val poll = if ((step.stepType == StepType.OCR_TEXT_TAP.name || step.stepType == StepType.IMAGE_TEMPLATE_TAP.name) &&
                        step.scanIntervalMs != 350L
                    ) {
                        "轮询${step.scanIntervalMs}ms · "
                    } else ""
                    val pre = if (step.delayBeforeMs > 0L) "前延${step.delayBeforeMs}ms · " else ""
                    val pressurePct = (step.touchPressure * 100).roundToInt()
                    " · $pageWait$poll${pre}后延${step.delayAfterMs}ms · 压感${pressurePct}%(${step.pressDurationMs}ms)"
                } else ""
                val detail = when (step.stepType) {
                    StepType.OCR_TEXT_TAP.name -> "$typeLabel · 「${step.targetText}」$roiSuffix$retrySuffix$offsetSuffix$timingSuffix"
                    StepType.IMAGE_TEMPLATE_TAP.name ->
                        "$typeLabel · 阈值 ${(step.matchThreshold * 100).toInt()}%$roiSuffix$retrySuffix$offsetSuffix$timingSuffix"
                    StepType.COORDINATE_TAP.name ->
                        "$typeLabel · (${(step.normalizedX * 100).toInt()}%, ${(step.normalizedY * 100).toInt()}%)$retrySuffix$offsetSuffix$timingSuffix"
                    StepType.MANUAL_CONFIRM_BRANCH.name -> {
                        val cond = runCatching { BranchConditionType.valueOf(step.branchConditionType) }
                            .getOrDefault(BranchConditionType.ALWAYS_PAUSE)
                        if (cond == BranchConditionType.ALWAYS_PAUSE) {
                            "$typeLabel · ${cond.label}"
                        } else {
                            "$typeLabel · ${cond.label}「${step.targetText}」"
                        }
                    }
                    else -> "$typeLabel · ${step.delayAfterMs}ms"
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!selectionMode) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "上移")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "下移")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除步骤", tint = RoseAlert)
                }
            }
        }
    }
}

@Composable
fun CreateOrRenameContainerDialog(
    title: String,
    initialName: String,
    subtitle: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("节点容器名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("container_name_input")
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("弹窗清理组", "领奖连击容器", "登录前置流程", "容错重试组").forEach { preset ->
                        FilterChip(
                            selected = name == preset,
                            onClick = { name = preset },
                            label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim().ifBlank { "节点容器" }) },
                modifier = Modifier.testTag("container_dialog_confirm_btn")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
