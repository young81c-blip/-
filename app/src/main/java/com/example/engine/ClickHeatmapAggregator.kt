package com.example.engine

import com.example.data.AutomationStepEntity
import com.example.data.ExecutionClickEventEntity
import com.example.data.StepType
import com.example.data.TaskWithSteps
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

enum class HeatmapMetricMode(val id: String, val label: String) {
    SUCCESS_RATE("success_rate", "成功率热力"),
    FREQUENCY("frequency", "触发频率热力")
}

enum class HeatmapModeFilter(val label: String) {
    ALL("全部模式"),
    SANDBOX("仅沙盒"),
    ROOT("仅 Root")
}

data class HeatmapCellStats(
    val row: Int,
    val col: Int,
    val zoneCode: String,
    val zoneLabel: String,
    val rangeLabel: String,
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val successRate: Float,
    val frequencyShare: Float,
    val topStepTitle: String,
    val topFailureReason: String,
    val actionMix: String,
    val optimizationHint: String
) {
    val key: String get() = "${col}_$row"
}

data class HeatmapSummary(
    val cols: Int = 5,
    val rows: Int = 6,
    val totalEvents: Int = 0,
    val locatedEvents: Int = 0,
    val unlocatedEvents: Int = 0,
    val unlocatedFailedEvents: Int = 0,
    val overallSuccessRate: Float = 0f,
    val maxCellCount: Int = 0,
    val cells: List<HeatmapCellStats> = emptyList(),
    val hottestZone: HeatmapCellStats? = null,
    val weakestZone: HeatmapCellStats? = null,
    val topUnlocatedFailure: String = "",
    val jsonPayload: String = "{}"
)

object ClickHeatmapAggregator {

    private const val COLS = 5
    private const val ROWS = 6

    private val colLetters = listOf("A", "B", "C", "D", "E")
    private val colNames = listOf("最左", "偏左", "中轴", "偏右", "最右")
    private val rowNames = listOf("顶部", "上区", "中上", "中下", "下区", "底部")

    fun aggregate(
        events: List<ExecutionClickEventEntity>,
        tasks: List<TaskWithSteps>,
        selectedTaskId: Long? = null,
        modeFilter: HeatmapModeFilter = HeatmapModeFilter.ALL
    ): HeatmapSummary {
        val filtered = events.filter { ev ->
            val taskMatches = selectedTaskId == null || ev.taskId == selectedTaskId
            val modeMatches = when (modeFilter) {
                HeatmapModeFilter.ALL -> true
                HeatmapModeFilter.SANDBOX -> ev.executionMode == "SANDBOX"
                HeatmapModeFilter.ROOT -> ev.executionMode == "ROOT"
            }
            taskMatches && modeMatches
        }

        if (filtered.isEmpty()) {
            return HeatmapSummary(
                cols = COLS,
                rows = ROWS,
                cells = buildEmptyGrid(),
                jsonPayload = encodeToJson(COLS, ROWS, emptyList(), emptyList(), 0)
            )
        }

        val stepLookup: Map<Pair<Long, Int>, AutomationStepEntity> = tasks
            .flatMap { t -> t.steps.map { s -> (t.task.id to s.stepOrder) to s } }
            .toMap()

        // Fallback pixel bounds for historical events recorded before normX/normY were stored.
        val sandboxMaxX = max(
            900f,
            filtered.filter { it.executionMode == "SANDBOX" }.mapNotNull { it.tapX?.toFloat() }.maxOrNull() ?: 900f
        )
        val sandboxMaxY = max(
            750f,
            filtered.filter { it.executionMode == "SANDBOX" }.mapNotNull { it.tapY?.toFloat() }.maxOrNull() ?: 750f
        )
        val rootMaxX = max(
            1080f,
            filtered.filter { it.executionMode == "ROOT" }.mapNotNull { it.tapX?.toFloat() }.maxOrNull() ?: 1080f
        )
        val rootMaxY = max(
            2400f,
            filtered.filter { it.executionMode == "ROOT" }.mapNotNull { it.tapY?.toFloat() }.maxOrNull() ?: 2400f
        )

        val buckets = Array(ROWS) { Array(COLS) { mutableListOf<ExecutionClickEventEntity>() } }
        val unlocatedList = mutableListOf<ExecutionClickEventEntity>()
        val scatterPoints = mutableListOf<JSONObject>()

        for (ev in filtered) {
            val resolvedNorm = resolveNormalizedPoint(
                ev = ev,
                stepLookup = stepLookup,
                sandboxMaxX = sandboxMaxX,
                sandboxMaxY = sandboxMaxY,
                rootMaxX = rootMaxX,
                rootMaxY = rootMaxY
            )
            if (resolvedNorm == null) {
                unlocatedList.add(ev)
            } else {
                val col = (resolvedNorm.first * COLS).toInt().coerceIn(0, COLS - 1)
                val row = (resolvedNorm.second * ROWS).toInt().coerceIn(0, ROWS - 1)
                buckets[row][col].add(ev)
                if (scatterPoints.size < 240) {
                    scatterPoints.add(
                        JSONObject().apply {
                            put("nx", resolvedNorm.first.toDouble())
                            put("ny", resolvedNorm.second.toDouble())
                            put("col", col)
                            put("row", row)
                            put("success", ev.success)
                            put("stepTitle", ev.stepTitle.ifBlank { ev.actionType })
                            put("mode", ev.executionMode)
                        }
                    )
                }
            }
        }

        var maxCellCount = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                if (buckets[r][c].size > maxCellCount) {
                    maxCellCount = buckets[r][c].size
                }
            }
        }

        val locatedEvents = filtered.size - unlocatedList.size
        val cells = mutableListOf<HeatmapCellStats>()

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val list = buckets[r][c]
                val total = list.size
                val succ = list.count { it.success }
                val fail = total - succ
                val rate = if (total > 0) succ.toFloat() / total.toFloat() else 0f
                val share = if (locatedEvents > 0) total.toFloat() / locatedEvents.toFloat() else 0f

                val topStep = list.groupingBy { it.stepTitle.ifBlank { it.actionType } }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key ?: "暂无步骤"
                val topFailure = list.asSequence()
                    .filter { !it.success && it.failureReason.isNotBlank() }
                    .groupingBy { it.failureReason }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key ?: ""

                val actionCounts = list.groupingBy { ev ->
                    when (ev.actionType) {
                        StepType.OCR_TEXT_TAP.name -> "OCR"
                        StepType.IMAGE_TEMPLATE_TAP.name -> "图像"
                        StepType.COORDINATE_TAP.name -> "坐标"
                        else -> "其他"
                    }
                }.eachCount()
                val actionMix = if (actionCounts.isEmpty()) {
                    "暂无触发"
                } else {
                    actionCounts.entries.sortedByDescending { it.value }
                        .joinToString(" · ") { "${it.key} ${it.value}" }
                }

                val zoneCode = "${colLetters[c]}${r + 1}"
                val zoneLabel = "$zoneCode · ${rowNames[r]}${colNames[c]}"
                val xStartPct = (c * 100f / COLS).roundToInt()
                val xEndPct = ((c + 1) * 100f / COLS).roundToInt()
                val yStartPct = (r * 100f / ROWS).roundToInt()
                val yEndPct = ((r + 1) * 100f / ROWS).roundToInt()
                val rangeLabel = "X $xStartPct%–$xEndPct% · Y $yStartPct%–$yEndPct%"

                cells.add(
                    HeatmapCellStats(
                        row = r,
                        col = c,
                        zoneCode = zoneCode,
                        zoneLabel = zoneLabel,
                        rangeLabel = rangeLabel,
                        totalCount = total,
                        successCount = succ,
                        failureCount = fail,
                        successRate = rate,
                        frequencyShare = share,
                        topStepTitle = topStep,
                        topFailureReason = topFailure,
                        actionMix = actionMix,
                        optimizationHint = buildOptimizationHint(
                            total = total,
                            successRate = rate,
                            share = share,
                            topFailure = topFailure,
                            actionCounts = actionCounts
                        )
                    )
                )
            }
        }

        val activeCells = cells.filter { it.totalCount > 0 }
        val hottestZone = activeCells.maxByOrNull { it.totalCount }
        val weakestZone = activeCells
            .filter { it.failureCount > 0 }
            .minWithOrNull(compareBy<HeatmapCellStats> { it.successRate }.thenByDescending { it.failureCount })
            ?: activeCells.minByOrNull { it.successRate }

        val totalSuccess = filtered.count { it.success }
        val overallRate = totalSuccess.toFloat() / filtered.size.toFloat()
        val unlocatedFailed = unlocatedList.filter { !it.success }
        val topUnlocatedFailure = unlocatedFailed
            .map { it.failureReason }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: ""

        return HeatmapSummary(
            cols = COLS,
            rows = ROWS,
            totalEvents = filtered.size,
            locatedEvents = locatedEvents,
            unlocatedEvents = unlocatedList.size,
            unlocatedFailedEvents = unlocatedFailed.size,
            overallSuccessRate = overallRate,
            maxCellCount = maxCellCount,
            cells = cells,
            hottestZone = hottestZone,
            weakestZone = weakestZone,
            topUnlocatedFailure = topUnlocatedFailure,
            jsonPayload = encodeToJson(COLS, ROWS, cells, scatterPoints, maxCellCount)
        )
    }

    private fun resolveNormalizedPoint(
        ev: ExecutionClickEventEntity,
        stepLookup: Map<Pair<Long, Int>, AutomationStepEntity>,
        sandboxMaxX: Float,
        sandboxMaxY: Float,
        rootMaxX: Float,
        rootMaxY: Float
    ): Pair<Float, Float>? {
        val nx = ev.normX
        val ny = ev.normY
        if (nx != null && ny != null) {
            return nx.coerceIn(0f, 0.999f) to ny.coerceIn(0f, 0.999f)
        }
        val px = ev.tapX
        val py = ev.tapY
        if (px != null && py != null) {
            val (w, h) = if (ev.executionMode == "ROOT") {
                rootMaxX to rootMaxY
            } else {
                sandboxMaxX to sandboxMaxY
            }
            return (px.toFloat() / w).coerceIn(0f, 0.999f) to (py.toFloat() / h).coerceIn(0f, 0.999f)
        }
        val step = stepLookup[ev.taskId to ev.stepOrder]
        if (step != null) {
            if (step.roiEnabled) {
                return ((step.roiLeft + step.roiRight) / 2f).coerceIn(0f, 0.999f) to
                    ((step.roiTop + step.roiBottom) / 2f).coerceIn(0f, 0.999f)
            }
            if (step.stepType == StepType.COORDINATE_TAP.name) {
                return step.normalizedX.coerceIn(0f, 0.999f) to step.normalizedY.coerceIn(0f, 0.999f)
            }
        }
        return null
    }

    private fun buildOptimizationHint(
        total: Int,
        successRate: Float,
        share: Float,
        topFailure: String,
        actionCounts: Map<String, Int>
    ): String {
        if (total == 0) return "该区域尚无点击记录。"
        val pct = (successRate * 100).roundToInt()
        return when {
            successRate < 0.7f -> {
                val base = "该区域成功率偏低（$pct%），建议在步骤编排中增加「页面加载等待延时 (preScanDelayMs)」或配置「失败自动重试」。"
                if (topFailure.contains("OCR", ignoreCase = true) || (actionCounts["OCR"] ?: 0) > 0) {
                    "$base 若弹窗/文字易偏移，建议开启区域限定识别(ROI)提高准确率。"
                } else {
                    "$base 可适当调低图像相似度阈值或扩大识别超时上限。"
                }
            }
            successRate < 0.92f -> {
                "该区域存在偶发未命中（成功率 $pct%），建议检查是否存在动态弹窗遮挡，或微调「点击前延迟」与「拟人偏移半径」。"
            }
            share >= 0.25f -> {
                "高频稳定核心区（占比 ${(share * 100).roundToInt()}%，成功率 $pct%）：可适当缩短 OCR/识图轮询间隔 (scanIntervalMs) 以提升任务整体吞吐效率。"
            }
            else -> {
                "该区域命中表现稳定（成功率 $pct%），当前节点参数匹配良好。"
            }
        }
    }

    private fun buildEmptyGrid(): List<HeatmapCellStats> {
        val list = mutableListOf<HeatmapCellStats>()
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val zoneCode = "${colLetters[c]}${r + 1}"
                val xStartPct = (c * 100f / COLS).roundToInt()
                val xEndPct = ((c + 1) * 100f / COLS).roundToInt()
                val yStartPct = (r * 100f / ROWS).roundToInt()
                val yEndPct = ((r + 1) * 100f / ROWS).roundToInt()
                list.add(
                    HeatmapCellStats(
                        row = r,
                        col = c,
                        zoneCode = zoneCode,
                        zoneLabel = "$zoneCode · ${rowNames[r]}${colNames[c]}",
                        rangeLabel = "X $xStartPct%–$xEndPct% · Y $yStartPct%–$yEndPct%",
                        totalCount = 0,
                        successCount = 0,
                        failureCount = 0,
                        successRate = 0f,
                        frequencyShare = 0f,
                        topStepTitle = "暂无",
                        topFailureReason = "",
                        actionMix = "暂无触发",
                        optimizationHint = "运行任务后即可采集该区域的点击成功率与触发频率。"
                    )
                )
            }
        }
        return list
    }

    private fun encodeToJson(
        cols: Int,
        rows: Int,
        cells: List<HeatmapCellStats>,
        scatterPoints: List<JSONObject>,
        maxCellCount: Int
    ): String {
        val root = JSONObject()
        root.put("cols", cols)
        root.put("rows", rows)
        root.put("maxCount", maxCellCount)
        root.put("colLabels", JSONArray(colLetters.mapIndexed { idx, letter -> "$letter(${colNames[idx]})" }))
        root.put("rowLabels", JSONArray(rowNames.mapIndexed { idx, name -> "${idx + 1}$name" }))
        val arr = JSONArray()
        cells.forEach { cell ->
            val obj = JSONObject()
            obj.put("row", cell.row)
            obj.put("col", cell.col)
            obj.put("zoneCode", cell.zoneCode)
            obj.put("zoneLabel", cell.zoneLabel)
            obj.put("totalCount", cell.totalCount)
            obj.put("successCount", cell.successCount)
            obj.put("failureCount", cell.failureCount)
            obj.put("successRate", cell.successRate.toDouble())
            obj.put("frequencyShare", cell.frequencyShare.toDouble())
            obj.put("topStepTitle", cell.topStepTitle)
            arr.put(obj)
        }
        root.put("cells", arr)
        root.put("points", JSONArray(scatterPoints))
        return root.toString()
    }
}
