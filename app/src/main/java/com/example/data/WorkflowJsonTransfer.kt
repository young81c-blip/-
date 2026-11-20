package com.example.data

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class WorkflowExportBundle(
    val schemaVersion: Int = 1,
    val appName: String = "SmartTapFlow",
    val exportedAt: Long = System.currentTimeMillis(),
    val tasks: List<ExportedTaskDto> = emptyList(),
    val embeddedTemplates: Map<String, String> = emptyMap()
)

@Serializable
data class ExportedTaskDto(
    val name: String,
    val description: String = "",
    val sceneType: String = TargetSceneType.DAILY_CHECKIN.name,
    val loopMode: String = LoopMode.FIXED_COUNT.name,
    val repeatCount: Int = 1,
    val loopIntervalMs: Long = 800L,
    val stopConditionType: String = StopConditionType.NONE.name,
    val stopConditionText: String = "",
    val stopConditionImagePath: String = "",
    val stopConditionThreshold: Float = 0.76f,
    val maxDurationMinutes: Int = 0,
    val notifyOnComplete: Boolean = true,
    val notifyOnFailure: Boolean = true,
    val notifyOnManualConfirm: Boolean = true,
    val longRunThresholdSec: Int = -1,
    val customNotifyNote: String = "",
    val isEnabled: Boolean = true,
    val steps: List<ExportedStepDto> = emptyList()
)

@Serializable
data class ExportedStepDto(
    val stepOrder: Int = 0,
    val title: String,
    val stepType: String,
    val targetText: String = "",
    val textMatchMode: String = TextMatchMode.CONTAINS.name,
    val templateImagePath: String = "",
    val matchThreshold: Float = 0.76f,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val randomOffsetEnabled: Boolean = true,
    val randomOffsetRadiusPx: Int = 12,
    val randomDelayJitterMs: Long = 150L,
    val delayBeforeMs: Long = 0L,
    val delayAfterMs: Long = 650L,
    val timeoutMs: Long = 4000L,
    val preScanDelayMs: Long = 0L,
    val scanIntervalMs: Long = 350L,
    val touchPressure: Float = 0.72f,
    val pressDurationMs: Long = 55L,
    val roiEnabled: Boolean = false,
    val roiLeft: Float = 0f,
    val roiTop: Float = 0f,
    val roiRight: Float = 1f,
    val roiBottom: Float = 1f,
    val maxRetryCount: Int = 0,
    val retryIntervalMs: Long = 1000L,
    val isEnabled: Boolean = true,
    val groupId: String = "",
    val groupName: String = "",
    val branchConditionType: String = BranchConditionType.ALWAYS_PAUSE.name
)

enum class ImportConflictPolicy(val label: String, val description: String) {
    KEEP_BOTH("保留副本", "若存在同名任务，则自动加后缀并新增导入"),
    OVERWRITE_BY_NAME("同名覆盖", "若存在同名任务，则替换其流程与参数")
}

data class ExportJsonPayload(
    val json: String,
    val taskCount: Int,
    val stepCount: Int,
    val embeddedTemplateCount: Int,
    val suggestedFileName: String
)

data class ParsedImportPreview(
    val bundle: WorkflowExportBundle,
    val taskCount: Int,
    val stepCount: Int,
    val templateCount: Int,
    val taskNames: List<String>
)

data class ImportResultSummary(
    val insertedCount: Int,
    val updatedCount: Int,
    val stepCount: Int,
    val restoredTemplatesCount: Int,
    val importedTaskNames: List<String>
)

object WorkflowJsonSerializer {

    private const val EMBEDDED_PREFIX = "embedded:"

    private val jsonFormatter = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun exportTasks(tasks: List<TaskWithSteps>): ExportJsonPayload {
        val templateMap = linkedMapOf<String, String>()
        val pathToKey = linkedMapOf<String, String>()
        var templateCounter = 0

        fun encodeTemplateRef(rawPath: String): String {
            if (rawPath.isBlank() || rawPath.startsWith("drawable:")) {
                return rawPath
            }
            pathToKey[rawPath]?.let { return "$EMBEDDED_PREFIX$it" }
            val file = File(rawPath)
            if (file.exists() && file.isFile && file.length() in 1L..2_097_152L) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    templateCounter++
                    val key = "tpl_$templateCounter"
                    templateMap[key] = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    pathToKey[rawPath] = key
                    return "$EMBEDDED_PREFIX$key"
                }
            }
            return rawPath
        }

        val exportedTasks = tasks.map { item ->
            val t = item.task
            val exportedSteps = item.sortedSteps.mapIndexed { idx, s ->
                ExportedStepDto(
                    stepOrder = idx,
                    title = s.title,
                    stepType = s.stepType,
                    targetText = s.targetText,
                    textMatchMode = s.textMatchMode,
                    templateImagePath = encodeTemplateRef(s.templateImagePath),
                    matchThreshold = s.matchThreshold,
                    normalizedX = s.normalizedX,
                    normalizedY = s.normalizedY,
                    randomOffsetEnabled = s.randomOffsetEnabled,
                    randomOffsetRadiusPx = s.randomOffsetRadiusPx,
                    randomDelayJitterMs = s.randomDelayJitterMs,
                    delayBeforeMs = s.delayBeforeMs,
                    delayAfterMs = s.delayAfterMs,
                    timeoutMs = s.timeoutMs,
                    preScanDelayMs = s.preScanDelayMs,
                    scanIntervalMs = s.scanIntervalMs,
                    touchPressure = s.touchPressure,
                    pressDurationMs = s.pressDurationMs,
                    roiEnabled = s.roiEnabled,
                    roiLeft = s.roiLeft,
                    roiTop = s.roiTop,
                    roiRight = s.roiRight,
                    roiBottom = s.roiBottom,
                    maxRetryCount = s.maxRetryCount,
                    retryIntervalMs = s.retryIntervalMs,
                    isEnabled = s.isEnabled,
                    groupId = s.groupId,
                    groupName = s.groupName,
                    branchConditionType = s.branchConditionType
                )
            }
            ExportedTaskDto(
                name = t.name,
                description = t.description,
                sceneType = t.sceneType,
                loopMode = t.loopMode,
                repeatCount = t.repeatCount,
                loopIntervalMs = t.loopIntervalMs,
                stopConditionType = t.stopConditionType,
                stopConditionText = t.stopConditionText,
                stopConditionImagePath = encodeTemplateRef(t.stopConditionImagePath),
                stopConditionThreshold = t.stopConditionThreshold,
                maxDurationMinutes = t.maxDurationMinutes,
                notifyOnComplete = t.notifyOnComplete,
                notifyOnFailure = t.notifyOnFailure,
                notifyOnManualConfirm = t.notifyOnManualConfirm,
                longRunThresholdSec = t.longRunThresholdSec,
                customNotifyNote = t.customNotifyNote,
                isEnabled = t.isEnabled,
                steps = exportedSteps
            )
        }

        val bundle = WorkflowExportBundle(
            schemaVersion = 1,
            appName = "SmartTapFlow",
            exportedAt = System.currentTimeMillis(),
            tasks = exportedTasks,
            embeddedTemplates = templateMap
        )

        val serialized = jsonFormatter.encodeToString(bundle)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = if (tasks.size == 1) {
            val safe = tasks.first().task.name
                .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_")
                .take(20)
                .ifBlank { "task" }
            "smarttap_${safe}_$stamp.json"
        } else {
            "smarttap_workflows_$stamp.json"
        }

        return ExportJsonPayload(
            json = serialized,
            taskCount = exportedTasks.size,
            stepCount = exportedTasks.sumOf { it.steps.size },
            embeddedTemplateCount = templateMap.size,
            suggestedFileName = baseName
        )
    }

    fun parseImportJson(rawJson: String): Result<ParsedImportPreview> {
        val cleaned = rawJson.trim().removePrefix("\uFEFF")
        if (cleaned.isBlank()) {
            return Result.failure(IllegalArgumentException("JSON 内容为空，请粘贴或选择导出的配置文件"))
        }

        val bundleResult = runCatching {
            val parsed = jsonFormatter.decodeFromString<WorkflowExportBundle>(cleaned)
            if (parsed.tasks.isEmpty()) {
                throw IllegalArgumentException("配置中未包含任何任务（tasks 列表为空）")
            }
            parsed
        }

        val bundle = if (bundleResult.isSuccess) {
            bundleResult.getOrThrow()
        } else {
            // Fallback: also accept a single ExportedTaskDto object
            val singleTask = runCatching {
                jsonFormatter.decodeFromString<ExportedTaskDto>(cleaned)
            }.getOrElse {
                val rootCause = bundleResult.exceptionOrNull()?.message ?: it.message ?: "无法识别的 JSON 结构"
                return Result.failure(IllegalArgumentException("JSON 解析失败：$rootCause"))
            }
            WorkflowExportBundle(
                schemaVersion = 1,
                tasks = listOf(singleTask)
            )
        }

        val sanitizedTasks = bundle.tasks.mapIndexed { idx, task ->
            val safeScene = runCatching { TargetSceneType.valueOf(task.sceneType).name }
                .getOrDefault(TargetSceneType.DAILY_CHECKIN.name)
            val safeLoop = runCatching { LoopMode.valueOf(task.loopMode).name }
                .getOrDefault(LoopMode.FIXED_COUNT.name)
            val safeStop = runCatching { StopConditionType.valueOf(task.stopConditionType).name }
                .getOrDefault(StopConditionType.NONE.name)

            val safeSteps = task.steps.mapIndexed { sIdx, step ->
                val safeStepType = runCatching { StepType.valueOf(step.stepType).name }
                    .getOrDefault(StepType.WAIT_DELAY.name)
                val safeMatchMode = runCatching { TextMatchMode.valueOf(step.textMatchMode).name }
                    .getOrDefault(TextMatchMode.CONTAINS.name)
                val roiL = step.roiLeft.coerceIn(0f, 0.95f)
                val roiT = step.roiTop.coerceIn(0f, 0.95f)
                val roiR = step.roiRight.coerceIn(roiL + 0.04f, 1f)
                val roiB = step.roiBottom.coerceIn(roiT + 0.04f, 1f)

                step.copy(
                    stepOrder = sIdx,
                    title = step.title.trim().ifBlank { "导入步骤 ${sIdx + 1}" },
                    stepType = safeStepType,
                    textMatchMode = safeMatchMode,
                    matchThreshold = step.matchThreshold.coerceIn(0.45f, 0.98f),
                    normalizedX = step.normalizedX.coerceIn(0.01f, 0.99f),
                    normalizedY = step.normalizedY.coerceIn(0.01f, 0.99f),
                    randomOffsetRadiusPx = step.randomOffsetRadiusPx.coerceIn(0, 80),
                    randomDelayJitterMs = step.randomDelayJitterMs.coerceIn(0L, 2000L),
                    delayBeforeMs = step.delayBeforeMs.coerceIn(0L, 30000L),
                    delayAfterMs = step.delayAfterMs.coerceIn(0L, 60000L),
                    timeoutMs = step.timeoutMs.coerceIn(500L, 60000L),
                    preScanDelayMs = step.preScanDelayMs.coerceIn(0L, 60000L),
                    scanIntervalMs = step.scanIntervalMs.coerceIn(100L, 10000L),
                    touchPressure = step.touchPressure.coerceIn(0.10f, 1.0f),
                    pressDurationMs = step.pressDurationMs.coerceIn(15L, 2000L),
                    roiLeft = roiL,
                    roiTop = roiT,
                    roiRight = roiR,
                    roiBottom = roiB,
                    maxRetryCount = step.maxRetryCount.coerceIn(0, 10),
                    retryIntervalMs = step.retryIntervalMs.coerceIn(100L, 30000L),
                    isEnabled = step.isEnabled,
                    groupId = step.groupId.trim().take(64),
                    groupName = step.groupName.trim().take(40),
                    branchConditionType = runCatching {
                        BranchConditionType.valueOf(step.branchConditionType).name
                    }.getOrDefault(BranchConditionType.ALWAYS_PAUSE.name)
                )
            }

            task.copy(
                name = task.name.trim().ifBlank { "导入任务 ${idx + 1}" },
                sceneType = safeScene,
                loopMode = safeLoop,
                repeatCount = task.repeatCount.coerceIn(1, 99),
                loopIntervalMs = task.loopIntervalMs.coerceIn(100L, 60000L),
                stopConditionType = safeStop,
                stopConditionThreshold = task.stopConditionThreshold.coerceIn(0.45f, 0.98f),
                maxDurationMinutes = task.maxDurationMinutes.coerceIn(0, 240),
                longRunThresholdSec = task.longRunThresholdSec.coerceIn(-1, 3600),
                customNotifyNote = task.customNotifyNote.trim().take(120),
                steps = safeSteps
            )
        }

        val normalizedBundle = bundle.copy(tasks = sanitizedTasks)
        return Result.success(
            ParsedImportPreview(
                bundle = normalizedBundle,
                taskCount = sanitizedTasks.size,
                stepCount = sanitizedTasks.sumOf { it.steps.size },
                templateCount = normalizedBundle.embeddedTemplates.size,
                taskNames = sanitizedTasks.map { it.name }
            )
        )
    }

    fun restoreTemplatesAndBuildEntities(
        context: Context,
        bundle: WorkflowExportBundle
    ): Pair<List<Pair<AutomationTaskEntity, List<AutomationStepEntity>>>, Int> {
        val restoredPaths = mutableMapOf<String, String>()
        if (bundle.embeddedTemplates.isNotEmpty()) {
            val dir = File(context.filesDir, "templates").apply { mkdirs() }
            val batchTs = System.currentTimeMillis()
            var index = 0
            bundle.embeddedTemplates.forEach { (key, base64) ->
                index++
                runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        val safeKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
                        val outFile = File(dir, "imp_${batchTs}_${index}_$safeKey.png")
                        outFile.writeBytes(bytes)
                        restoredPaths[key] = outFile.absolutePath
                    }
                }
            }
        }

        fun resolvePath(raw: String): String {
            if (!raw.startsWith(EMBEDDED_PREFIX)) return raw
            val key = raw.removePrefix(EMBEDDED_PREFIX)
            return restoredPaths[key] ?: ""
        }

        val baseTime = System.currentTimeMillis()
        val pairs = bundle.tasks.mapIndexed { idx, dto ->
            val taskEntity = AutomationTaskEntity(
                id = 0,
                name = dto.name,
                description = dto.description,
                sceneType = dto.sceneType,
                loopMode = dto.loopMode,
                repeatCount = dto.repeatCount,
                loopIntervalMs = dto.loopIntervalMs,
                stopConditionType = dto.stopConditionType,
                stopConditionText = dto.stopConditionText,
                stopConditionImagePath = resolvePath(dto.stopConditionImagePath),
                stopConditionThreshold = dto.stopConditionThreshold,
                maxDurationMinutes = dto.maxDurationMinutes,
                notifyOnComplete = dto.notifyOnComplete,
                notifyOnFailure = dto.notifyOnFailure,
                notifyOnManualConfirm = dto.notifyOnManualConfirm,
                longRunThresholdSec = dto.longRunThresholdSec,
                customNotifyNote = dto.customNotifyNote,
                isEnabled = dto.isEnabled,
                createdAt = baseTime + idx
            )
            val stepEntities = dto.steps.mapIndexed { sIdx, s ->
                AutomationStepEntity(
                    id = 0,
                    taskId = 0,
                    stepOrder = sIdx,
                    title = s.title,
                    stepType = s.stepType,
                    targetText = s.targetText,
                    textMatchMode = s.textMatchMode,
                    templateImagePath = resolvePath(s.templateImagePath),
                    matchThreshold = s.matchThreshold,
                    normalizedX = s.normalizedX,
                    normalizedY = s.normalizedY,
                    randomOffsetEnabled = s.randomOffsetEnabled,
                    randomOffsetRadiusPx = s.randomOffsetRadiusPx,
                    randomDelayJitterMs = s.randomDelayJitterMs,
                    delayBeforeMs = s.delayBeforeMs,
                    delayAfterMs = s.delayAfterMs,
                    timeoutMs = s.timeoutMs,
                    preScanDelayMs = s.preScanDelayMs,
                    scanIntervalMs = s.scanIntervalMs,
                    touchPressure = s.touchPressure,
                    pressDurationMs = s.pressDurationMs,
                    roiEnabled = s.roiEnabled,
                    roiLeft = s.roiLeft,
                    roiTop = s.roiTop,
                    roiRight = s.roiRight,
                    roiBottom = s.roiBottom,
                    maxRetryCount = s.maxRetryCount,
                    retryIntervalMs = s.retryIntervalMs,
                    isEnabled = s.isEnabled,
                    groupId = s.groupId,
                    groupName = s.groupName,
                    branchConditionType = s.branchConditionType
                )
            }
            taskEntity to stepEntities
        }

        return pairs to restoredPaths.size
    }
}
