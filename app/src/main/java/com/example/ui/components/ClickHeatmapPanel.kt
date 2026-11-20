package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.ExecutionClickEventEntity
import com.example.data.TaskWithSteps
import com.example.engine.ClickHeatmapAggregator
import com.example.engine.HeatmapCellStats
import com.example.engine.HeatmapMetricMode
import com.example.engine.HeatmapModeFilter
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CyanBright
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.RoseAlert
import org.json.JSONObject
import kotlin.math.roundToInt

private class HeatmapJsBridge(
    private val onSelectCell: (Int, Int) -> Unit
) {
    @JavascriptInterface
    fun onCellSelected(col: Int, row: Int) {
        onSelectCell(col, row)
    }
}

@Composable
fun TaskMonitoringHeatmapCard(
    clickEvents: List<ExecutionClickEventEntity>,
    tasks: List<TaskWithSteps>,
    modifier: Modifier = Modifier
) {
    var metricMode by remember { mutableStateOf(HeatmapMetricMode.SUCCESS_RATE) }
    var modeFilter by remember { mutableStateOf(HeatmapModeFilter.ALL) }
    var showScatterPoints by remember { mutableStateOf(true) }
    var selectedTaskId by remember { mutableStateOf<Long?>(null) }
    var selectedCellCoord by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val availableTaskOptions = remember(clickEvents, tasks) {
        val fromEvents = clickEvents.map { it.taskId to it.taskName }.distinctBy { it.first }
        val fromTasks = tasks.map { it.task.id to it.task.name }
        (fromEvents + fromTasks).distinctBy { it.first }
    }

    val summary = remember(clickEvents, tasks, selectedTaskId, modeFilter) {
        ClickHeatmapAggregator.aggregate(
            events = clickEvents,
            tasks = tasks,
            selectedTaskId = selectedTaskId,
            modeFilter = modeFilter
        )
    }

    val activeSelectedCell: HeatmapCellStats? = remember(summary, selectedCellCoord) {
        val coord = selectedCellCoord
        if (coord != null) {
            summary.cells.firstOrNull { it.col == coord.first && it.row == coord.second }
        } else {
            summary.weakestZone?.takeIf { it.failureCount > 0 } ?: summary.hottestZone
        }
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .testTag("monitoring_d3_heatmap_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "点击坐标分布热力图（D3.js）",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (summary.totalEvents > 0) {
                                "已定位 ${summary.locatedEvents}/${summary.totalEvents} 个坐标点 · 总体成功率 ${(summary.overallSuccessRate * 100).roundToInt()}%"
                            } else {
                                "按 5×6 屏幕区域与实际坐标落点聚合成功率及触发频率"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Metric & Mode Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeatmapMetricMode.entries.forEach { metric ->
                    FilterChip(
                        selected = metricMode == metric,
                        onClick = { metricMode = metric },
                        label = { Text(metric.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("heatmap_metric_${metric.id}")
                    )
                }
                FilterChip(
                    selected = showScatterPoints,
                    onClick = { showScatterPoints = !showScatterPoints },
                    label = { Text("坐标落点", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("heatmap_toggle_scatter")
                )
                HeatmapModeFilter.entries.forEach { mode ->
                    FilterChip(
                        selected = modeFilter == mode,
                        onClick = { modeFilter = mode },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (availableTaskOptions.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedTaskId == null,
                        onClick = {
                            selectedTaskId = null
                            selectedCellCoord = null
                        },
                        label = { Text("全部任务", style = MaterialTheme.typography.labelSmall) }
                    )
                    availableTaskOptions.forEach { (id, taskName) ->
                        FilterChip(
                            selected = selectedTaskId == id,
                            onClick = {
                                selectedTaskId = id
                                selectedCellCoord = null
                            },
                            label = {
                                Text(
                                    text = taskName,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            // D3.js Heatmap Canvas via WebView
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .testTag("d3_heatmap_webview_box")
            ) {
                D3HeatmapWebView(
                    jsonPayload = summary.jsonPayload,
                    metricMode = metricMode,
                    selectedCol = activeSelectedCell?.col ?: -1,
                    selectedRow = activeSelectedCell?.row ?: -1,
                    showScatter = showScatterPoints,
                    onCellSelected = { col, row ->
                        selectedCellCoord = col to row
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (summary.totalEvents == 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "暂无真实点击样本数据，前往“执行台”或“任务”运行一次流程后，热力图将实时渲染各屏幕区域的成功率和触发频率。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Quick Insight Chips for Hottest & Weakest Regions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    summary.hottestZone?.let { hot ->
                        HeatmapQuickInsightChip(
                            title = "最高频区 · ${hot.zoneCode}",
                            value = "${hot.totalCount} 次 (${(hot.successRate * 100).roundToInt()}%)",
                            accent = CyanBright,
                            selected = activeSelectedCell?.key == hot.key,
                            onClick = { selectedCellCoord = hot.col to hot.row },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    summary.weakestZone?.let { weak ->
                        val weakColor = if (weak.successRate < 0.7f) RoseAlert
                        else if (weak.successRate < 0.92f) AmberSignal
                        else EmeraldPulse
                        HeatmapQuickInsightChip(
                            title = if (weak.failureCount > 0) "待优化区 · ${weak.zoneCode}" else "最稳区域 · ${weak.zoneCode}",
                            value = "成功率 ${(weak.successRate * 100).roundToInt()}% (${weak.failureCount}败)",
                            accent = weakColor,
                            selected = activeSelectedCell?.key == weak.key,
                            onClick = { selectedCellCoord = weak.col to weak.row },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Selected Cell Optimization Detail Card
                activeSelectedCell?.let { cell ->
                    val statusColor = when {
                        cell.totalCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        cell.successRate >= 0.9f -> EmeraldPulse
                        cell.successRate >= 0.7f -> AmberSignal
                        else -> RoseAlert
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("heatmap_selected_cell_detail")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${cell.zoneLabel} (${cell.rangeLabel})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (cell.totalCount > 0) {
                                        "成功率 ${(cell.successRate * 100).roundToInt()}%"
                                    } else {
                                        "无触发"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = statusColor
                                )
                            }
                            if (cell.totalCount > 0) {
                                Text(
                                    text = "触发 ${cell.totalCount} 次（占比 ${(cell.frequencyShare * 100).roundToInt()}%）· 成功 ${cell.successCount} / 失败 ${cell.failureCount} · ${cell.actionMix}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "高频节点：${cell.topStepTitle}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (cell.topFailureReason.isNotBlank()) {
                                    Text(
                                        text = "典型失败：${cell.topFailureReason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RoseAlert,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    tint = AmberSignal,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(15.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = cell.optimizationHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (summary.unlocatedFailedEvents > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AmberSignal.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AmberSignal.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = AmberSignal,
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            val reasonHint = if (summary.topUnlocatedFailure.isNotBlank()) {
                                "（常见原因：${summary.topUnlocatedFailure}）"
                            } else ""
                            Text(
                                text = "另有 ${summary.unlocatedFailedEvents} 次全屏识别超时失败因未命中坐标未计入网格$reasonHint，建议为对应步骤开启区域限定识别 (ROI) 以纳入区域监控。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapQuickInsightChip(
    title: String,
    value: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun D3HeatmapWebView(
    jsonPayload: String,
    metricMode: HeatmapMetricMode,
    selectedCol: Int,
    selectedRow: Int,
    showScatter: Boolean,
    onCellSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val htmlTemplate = remember(context) {
        context.assets.open("d3_heatmap.html").bufferedReader().use { it.readText() }
    }

    val latestOnSelect by rememberUpdatedState(onCellSelected)
    val latestJson by rememberUpdatedState(jsonPayload)
    val latestMetric by rememberUpdatedState(metricMode.id)
    val latestCol by rememberUpdatedState(selectedCol)
    val latestRow by rememberUpdatedState(selectedRow)
    val latestShowScatter by rememberUpdatedState(showScatter)

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    fun pushToD3(view: WebView) {
        val quotedJson = JSONObject.quote(latestJson)
        val quotedMetric = JSONObject.quote(latestMetric)
        view.evaluateJavascript(
            "window.renderClickHeatmap($quotedJson, $quotedMetric, $latestCol, $latestRow, $latestShowScatter);",
            null
        )
    }

    LaunchedEffect(pageLoaded, jsonPayload, metricMode, selectedCol, selectedRow, showScatter) {
        val view = webViewRef ?: return@LaunchedEffect
        if (pageLoaded) {
            pushToD3(view)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                removeJavascriptInterface("AndroidHeatmapBridge")
                destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.parseColor("#0B1220"))
                isNestedScrollingEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    builtInZoomControls = false
                    displayZoomControls = false
                }
                addJavascriptInterface(
                    HeatmapJsBridge { col, row ->
                        post { latestOnSelect(col, row) }
                    },
                    "AndroidHeatmapBridge"
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        pageLoaded = true
                        pushToD3(view)
                    }
                }
                loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/",
                    htmlTemplate,
                    "text/html",
                    "UTF-8",
                    null
                )
                webViewRef = this
            }
        },
        modifier = modifier
    )
}
