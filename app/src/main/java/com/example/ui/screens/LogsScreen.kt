package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ExecutionClickEventEntity
import com.example.data.ExecutionLogWithEvents
import com.example.data.StepType
import com.example.data.TaskWithSteps
import com.example.engine.RootShellExecutor
import com.example.engine.RootStatus
import com.example.ui.components.TaskMonitoringHeatmapCard
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.RoseAlert
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogPanelTab(val label: String) {
    CLICK_EVENTS("点击明细流"),
    RUN_BATCHES("按任务批次")
}

private enum class LogStatusFilter(val label: String) {
    ALL("全部"),
    FAILED("仅看失败"),
    SUCCESS("仅看成功")
}

@Composable
fun LogsScreen(
    logsWithEvents: List<ExecutionLogWithEvents>,
    recentClickEvents: List<ExecutionClickEventEntity>,
    tasks: List<TaskWithSteps> = emptyList(),
    rootStatus: RootStatus?,
    onRefreshRoot: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val preciseTimeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    val rootShell = remember { RootShellExecutor() }

    var testTapCount by remember { mutableIntStateOf(0) }
    var testButtonCenter by remember { mutableStateOf(Offset.Zero) }
    var testTapStatus by remember { mutableStateOf<String?>(null) }

    var selectedTab by remember { mutableStateOf(LogPanelTab.CLICK_EVENTS) }
    var selectedFilter by remember { mutableStateOf(LogStatusFilter.ALL) }
    var expandedRunIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showArchitectureDetails by remember { mutableStateOf(false) }

    val failedEventCount = remember(recentClickEvents) { recentClickEvents.count { !it.success } }
    val filteredEvents = remember(recentClickEvents, selectedFilter) {
        when (selectedFilter) {
            LogStatusFilter.ALL -> recentClickEvents
            LogStatusFilter.FAILED -> recentClickEvents.filter { !it.success }
            LogStatusFilter.SUCCESS -> recentClickEvents.filter { it.success }
        }
    }
    val filteredRuns = remember(logsWithEvents, selectedFilter) {
        when (selectedFilter) {
            LogStatusFilter.ALL -> logsWithEvents
            LogStatusFilter.FAILED -> logsWithEvents.filter { !it.log.success || it.events.any { e -> !e.success } }
            LogStatusFilter.SUCCESS -> logsWithEvents.filter { it.log.success }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 760.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Root 直连诊断（免无障碍服务）", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = rootStatus?.message ?: "正在检测 su 权限...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = onRefreshRoot) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("刷新状态")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    val x = testButtonCenter.x.toInt()
                                    val y = testButtonCenter.y.toInt()
                                    val res = rootShell.tap(x, y)
                                    testTapStatus = if (res.isSuccess) {
                                        "已执行 su input tap $x $y"
                                    } else {
                                        "Root 点击失败：${res.exceptionOrNull()?.message}"
                                    }
                                }
                            },
                            enabled = rootStatus?.hasRootAccess == true
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("测试 su 点击")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        onClick = { testTapCount++ },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                testButtonCenter = Offset(
                                    pos.x + coords.size.width / 2f,
                                    pos.y + coords.size.height / 2f
                                )
                            }
                    ) {
                        Text(
                            text = "Root 测试接收区 · 已收到点击 $testTapCount 次",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    if (!testTapStatus.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = testTapStatus!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showArchitectureDetails = !showArchitectureDetails }
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = EmeraldPulse,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("架构与权限说明（免无障碍）", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            imageVector = if (showArchitectureDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "展开或收起说明"
                        )
                    }
                    AnimatedVisibility(visible = showArchitectureDetails) {
                        Text(
                            text = "本应用严格不声明 BIND_ACCESSIBILITY_SERVICE 无障碍权限，也不申请存储读写权限。全局自动点击通过 Root (su screencap + su input tap) 完成；可选开启的系统悬浮控制台（SYSTEM_ALERT_WINDOW）用于在目标应用上方直接启动、暂停或终止任务。未 Root 设备可在“执行台”沙盒内完成同等精度的 OCR 与图像模板识别点击验证。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("任务监控面板与执行排障", style = MaterialTheme.typography.titleLarge)
                    if (logsWithEvents.isNotEmpty() || recentClickEvents.isNotEmpty()) {
                        TextButton(
                            onClick = onClearLogs,
                            modifier = Modifier.testTag("clear_logs_btn")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空日志")
                        }
                    }
                }

                // Summary Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LogStatChip(
                        label = "任务批次",
                        value = "${logsWithEvents.size}",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    LogStatChip(
                        label = "点击/动作记录",
                        value = "${recentClickEvents.size}",
                        color = EmeraldPulse,
                        modifier = Modifier.weight(1f)
                    )
                    LogStatChip(
                        label = "失败待排查",
                        value = "$failedEventCount",
                        color = if (failedEventCount > 0) RoseAlert else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }

                TaskMonitoringHeatmapCard(
                    clickEvents = recentClickEvents,
                    tasks = tasks
                )

                // View Tab Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == LogPanelTab.CLICK_EVENTS,
                        onClick = { selectedTab = LogPanelTab.CLICK_EVENTS },
                        label = { Text("${LogPanelTab.CLICK_EVENTS.label} (${filteredEvents.size})") },
                        modifier = Modifier.testTag("log_view_events_tab")
                    )
                    FilterChip(
                        selected = selectedTab == LogPanelTab.RUN_BATCHES,
                        onClick = { selectedTab = LogPanelTab.RUN_BATCHES },
                        label = { Text("${LogPanelTab.RUN_BATCHES.label} (${filteredRuns.size})") },
                        modifier = Modifier.testTag("log_view_runs_tab")
                    )
                }

                // Status Filter Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LogStatusFilter.entries.forEach { filter ->
                        val tag = when (filter) {
                            LogStatusFilter.ALL -> "log_filter_all"
                            LogStatusFilter.FAILED -> "log_filter_failed"
                            LogStatusFilter.SUCCESS -> "log_filter_success"
                        }
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = {
                                val label = if (filter == LogStatusFilter.FAILED && failedEventCount > 0) {
                                    "${filter.label} ($failedEventCount)"
                                } else {
                                    filter.label
                                }
                                Text(label)
                            },
                            modifier = Modifier.testTag(tag)
                        )
                    }
                }
            }
        }

        if (selectedTab == LogPanelTab.CLICK_EVENTS) {
            if (filteredEvents.isEmpty()) {
                item {
                    EmptyLogsCard(
                        message = when (selectedFilter) {
                            LogStatusFilter.FAILED -> "当前没有失败的点击或识别记录。"
                            LogStatusFilter.SUCCESS -> "当前没有成功的点击记录。"
                            LogStatusFilter.ALL -> "暂无点击明细，去“任务”或“执行台”运行一次任务即可记录每次点击时间、操作类型与识别目标。"
                        }
                    )
                }
            } else {
                items(filteredEvents, key = { "ev_${it.id}" }) { event ->
                    ClickEventDetailCard(
                        event = event,
                        timeText = preciseTimeFormat.format(Date(event.timestamp))
                    )
                }
            }
        } else {
            if (filteredRuns.isEmpty()) {
                item {
                    EmptyLogsCard(
                        message = when (selectedFilter) {
                            LogStatusFilter.FAILED -> "当前没有执行失败的任务批次。"
                            LogStatusFilter.SUCCESS -> "当前没有成功的任务批次。"
                            LogStatusFilter.ALL -> "暂无任务执行批次，运行任务后将在此按批次归档并展示逐步详情。"
                        }
                    )
                }
            } else {
                items(filteredRuns, key = { "run_${it.log.id}" }) { runWithEvents ->
                    val isExpanded = expandedRunIds.contains(runWithEvents.log.id) ||
                        (!runWithEvents.log.success && expandedRunIds.isEmpty() && filteredRuns.firstOrNull()?.log?.id == runWithEvents.log.id)
                    RunBatchCard(
                        runWithEvents = runWithEvents,
                        dateText = dateFormat.format(Date(runWithEvents.log.startedAt)),
                        preciseTimeFormat = preciseTimeFormat,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedRunIds = if (expandedRunIds.contains(runWithEvents.log.id)) {
                                expandedRunIds - runWithEvents.log.id
                            } else {
                                expandedRunIds + runWithEvents.log.id
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogStatChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = color
            )
        }
    }
}

@Composable
private fun ClickEventDetailCard(
    event: ExecutionClickEventEntity,
    timeText: String
) {
    val actionLabel = runCatching { StepType.valueOf(event.actionType).label }.getOrDefault(event.actionType)
    val actionIcon = when (event.actionType) {
        StepType.OCR_TEXT_TAP.name -> Icons.Default.TextFields
        StepType.IMAGE_TEMPLATE_TAP.name -> Icons.Default.ImageSearch
        StepType.WAIT_DELAY.name -> Icons.Default.Schedule
        else -> Icons.Default.TouchApp
    }
    val modeLabel = if (event.executionMode == "ROOT") "Root" else "沙盒"
    val borderColor = if (event.success) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    } else {
        RoseAlert.copy(alpha = 0.55f)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("click_event_card_${event.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                        imageVector = if (event.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = if (event.success) "成功" else "失败",
                        tint = if (event.success) EmeraldPulse else RoseAlert,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = event.stepTitle.ifBlank { actionLabel },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            actionIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Surface(
                    color = if (event.executionMode == "ROOT") AmberSignal.copy(alpha = 0.16f) else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = modeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (event.executionMode == "ROOT") AmberSignal else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = if (event.roundIndex > 0) "${event.taskName} · 第${event.roundIndex}轮" else event.taskName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "识别目标：${event.recognizedTarget}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (event.tapX != null && event.tapY != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "点击坐标：(${event.tapX}, ${event.tapY}) · 拟人偏移 (${event.offsetDeltaX}, ${event.offsetDeltaY}) px",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!event.success && event.failureReason.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = RoseAlert.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, RoseAlert.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = "失败原因",
                            tint = RoseAlert,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "失败排查：${event.failureReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = RoseAlert
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunBatchCard(
    runWithEvents: ExecutionLogWithEvents,
    dateText: String,
    preciseTimeFormat: SimpleDateFormat,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val log = runWithEvents.log
    val events = runWithEvents.sortedEvents
    val failedInRun = events.count { !it.success }
    val modeLabel = if (log.executionMode == "ROOT") "Root" else "沙盒"

    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (log.success) MaterialTheme.colorScheme.outline else RoseAlert.copy(alpha = 0.55f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .testTag("run_batch_card_${log.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                        imageVector = if (log.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (log.success) EmeraldPulse else RoseAlert,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = log.taskName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$modeLabel · $dateText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "展开详情",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(text = log.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "耗时 ${log.durationMs}ms · 命中 ${log.matchedSteps}/${log.totalSteps} 步 · 点击 ${log.clicksTriggered} 次" +
                    if (failedInRun > 0) " · 异常 $failedInRun 项" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (failedInRun > 0) RoseAlert else MaterialTheme.colorScheme.primary
            )

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider()
                    if (events.isEmpty()) {
                        Text(
                            text = "该批次无逐步点击明细记录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        events.forEach { ev ->
                            val actionLabel = runCatching { StepType.valueOf(ev.actionType).label }.getOrDefault(ev.actionType)
                            Surface(
                                color = if (ev.success) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                } else {
                                    RoseAlert.copy(alpha = 0.08f)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "第${ev.roundIndex}轮 · $actionLabel · ${ev.stepTitle}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (ev.success) MaterialTheme.colorScheme.onSurface else RoseAlert,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = preciseTimeFormat.format(Date(ev.timestamp)).substringAfter(" "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = "目标：${ev.recognizedTarget}" +
                                            if (ev.tapX != null && ev.tapY != null) {
                                                " · 点击(${ev.tapX}, ${ev.tapY}) 偏移(${ev.offsetDeltaX},${ev.offsetDeltaY})"
                                            } else {
                                                ""
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!ev.success && ev.failureReason.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = "原因：${ev.failureReason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = RoseAlert
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
}

@Composable
private fun EmptyLogsCard(message: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp)
        )
    }
}
