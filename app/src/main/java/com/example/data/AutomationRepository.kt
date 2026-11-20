package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AutomationRepository(
    private val context: Context,
    private val dao: AutomationDao
) {
    val tasksWithSteps: Flow<List<TaskWithSteps>> = dao.observeTasksWithSteps()
    val executionLogsWithEvents: Flow<List<ExecutionLogWithEvents>> = dao.observeExecutionLogsWithEvents()
    val recentClickEvents: Flow<List<ExecutionClickEventEntity>> = dao.observeRecentClickEvents()

    fun observeTask(taskId: Long): Flow<TaskWithSteps?> = dao.observeTaskWithSteps(taskId)

    suspend fun getTaskWithSteps(taskId: Long): TaskWithSteps? = dao.getTaskWithSteps(taskId)

    suspend fun saveTask(task: AutomationTaskEntity, steps: List<AutomationStepEntity>): Long {
        return dao.saveTaskWithSteps(task, steps)
    }

    suspend fun deleteTask(taskId: Long) = dao.deleteTaskById(taskId)

    suspend fun setTaskEnabled(taskId: Long, enabled: Boolean) = dao.setTaskEnabled(taskId, enabled)

    suspend fun recordRun(
        taskId: Long,
        taskName: String,
        executionMode: String = "SANDBOX",
        startedAt: Long,
        durationMs: Long,
        matchedSteps: Int,
        totalSteps: Int,
        clicksTriggered: Int,
        success: Boolean,
        summary: String,
        events: List<ExecutionClickEventEntity> = emptyList()
    ) {
        dao.recordTaskRun(taskId, startedAt, success, clicksTriggered)
        dao.insertExecutionLogWithEvents(
            log = ExecutionLogEntity(
                taskId = taskId,
                taskName = taskName,
                executionMode = executionMode,
                startedAt = startedAt,
                durationMs = durationMs,
                matchedSteps = matchedSteps,
                totalSteps = totalSteps,
                clicksTriggered = clicksTriggered,
                success = success,
                summary = summary
            ),
            events = events
        )
    }

    suspend fun clearLogs() = dao.clearAllLogs()

    suspend fun exportTasksAsJson(taskId: Long? = null): Result<ExportJsonPayload> =
        withContext(Dispatchers.IO) {
            runCatching {
                val targetTasks = if (taskId != null) {
                    listOfNotNull(dao.getTaskWithSteps(taskId))
                } else {
                    dao.getAllTasksWithSteps()
                }
                if (targetTasks.isEmpty()) {
                    throw IllegalStateException("当前没有可导出的自动化任务")
                }
                WorkflowJsonSerializer.exportTasks(targetTasks)
            }
        }

    suspend fun importTasksFromJson(
        rawJson: String,
        conflictPolicy: ImportConflictPolicy
    ): Result<ImportResultSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val preview = WorkflowJsonSerializer.parseImportJson(rawJson).getOrThrow()
            val (entities, restoredTemplates) = WorkflowJsonSerializer.restoreTemplatesAndBuildEntities(
                context = context,
                bundle = preview.bundle
            )

            val existingTasks = dao.getAllTasks().toMutableList()
            var insertedCount = 0
            var updatedCount = 0
            var totalStepCount = 0
            val importedNames = mutableListOf<String>()

            for ((task, steps) in entities) {
                val existingMatch = existingTasks.firstOrNull { it.name == task.name }
                if (conflictPolicy == ImportConflictPolicy.OVERWRITE_BY_NAME && existingMatch != null) {
                    val mergedTask = existingMatch.copy(
                        description = task.description,
                        sceneType = task.sceneType,
                        loopMode = task.loopMode,
                        repeatCount = task.repeatCount,
                        loopIntervalMs = task.loopIntervalMs,
                        stopConditionType = task.stopConditionType,
                        stopConditionText = task.stopConditionText,
                        stopConditionImagePath = task.stopConditionImagePath,
                        stopConditionThreshold = task.stopConditionThreshold,
                        maxDurationMinutes = task.maxDurationMinutes,
                        isEnabled = task.isEnabled
                    )
                    dao.saveTaskWithSteps(mergedTask, steps)
                    updatedCount++
                    importedNames.add(mergedTask.name)
                } else {
                    val finalName = if (existingMatch != null) {
                        var candidate = "${task.name} (导入)"
                        var suffix = 2
                        while (existingTasks.any { it.name == candidate }) {
                            candidate = "${task.name} (导入$suffix)"
                            suffix++
                        }
                        candidate
                    } else {
                        task.name
                    }
                    val newTask = task.copy(id = 0, name = finalName)
                    val savedId = dao.saveTaskWithSteps(newTask, steps)
                    existingTasks.add(newTask.copy(id = savedId))
                    insertedCount++
                    importedNames.add(finalName)
                }
                totalStepCount += steps.size
            }

            ImportResultSummary(
                insertedCount = insertedCount,
                updatedCount = updatedCount,
                stepCount = totalStepCount,
                restoredTemplatesCount = restoredTemplates,
                importedTaskNames = importedNames
            )
        }
    }

    suspend fun appendQuickOcrStepToTask(
        taskId: Long?,
        targetText: String,
        title: String = "OCR 点击「$targetText」",
        textMatchMode: String = TextMatchMode.CONTAINS.name,
        roiEnabled: Boolean = false,
        roiLeft: Float = 0f,
        roiTop: Float = 0f,
        roiRight: Float = 1f,
        roiBottom: Float = 1f
    ): Long = withContext(Dispatchers.IO) {
        if (taskId != null) {
            val existingCount = dao.getStepCountForTask(taskId)
            dao.insertStep(
                AutomationStepEntity(
                    taskId = taskId,
                    stepOrder = existingCount,
                    title = title,
                    stepType = StepType.OCR_TEXT_TAP.name,
                    targetText = targetText,
                    textMatchMode = textMatchMode,
                    roiEnabled = roiEnabled,
                    roiLeft = roiLeft,
                    roiTop = roiTop,
                    roiRight = roiRight,
                    roiBottom = roiBottom
                )
            )
            taskId
        } else {
            val newTaskId = dao.insertTask(
                AutomationTaskEntity(
                    name = "OCR 任务：$targetText",
                    description = "从视觉区域选择器生成的文字识别点击任务。",
                    sceneType = TargetSceneType.CUSTOM_IMAGE_CANVAS.name
                )
            )
            dao.insertStep(
                AutomationStepEntity(
                    taskId = newTaskId,
                    stepOrder = 0,
                    title = title,
                    stepType = StepType.OCR_TEXT_TAP.name,
                    targetText = targetText,
                    textMatchMode = textMatchMode,
                    roiEnabled = roiEnabled,
                    roiLeft = roiLeft,
                    roiTop = roiTop,
                    roiRight = roiRight,
                    roiBottom = roiBottom
                )
            )
            newTaskId
        }
    }

    suspend fun appendQuickImageStepToTask(
        taskId: Long?,
        templatePath: String,
        threshold: Float,
        title: String = "图像识别点击",
        roiEnabled: Boolean = false,
        roiLeft: Float = 0f,
        roiTop: Float = 0f,
        roiRight: Float = 1f,
        roiBottom: Float = 1f
    ): Long = withContext(Dispatchers.IO) {
        if (taskId != null) {
            val existingCount = dao.getStepCountForTask(taskId)
            dao.insertStep(
                AutomationStepEntity(
                    taskId = taskId,
                    stepOrder = existingCount,
                    title = title,
                    stepType = StepType.IMAGE_TEMPLATE_TAP.name,
                    templateImagePath = templatePath,
                    matchThreshold = threshold,
                    roiEnabled = roiEnabled,
                    roiLeft = roiLeft,
                    roiTop = roiTop,
                    roiRight = roiRight,
                    roiBottom = roiBottom
                )
            )
            taskId
        } else {
            val newTaskId = dao.insertTask(
                AutomationTaskEntity(
                    name = "图像识别自动化任务",
                    description = "从视觉区域选择器裁剪模板并创建的识图点击任务。",
                    sceneType = TargetSceneType.CUSTOM_IMAGE_CANVAS.name
                )
            )
            dao.insertStep(
                AutomationStepEntity(
                    taskId = newTaskId,
                    stepOrder = 0,
                    title = title,
                    stepType = StepType.IMAGE_TEMPLATE_TAP.name,
                    templateImagePath = templatePath,
                    matchThreshold = threshold,
                    roiEnabled = roiEnabled,
                    roiLeft = roiLeft,
                    roiTop = roiTop,
                    roiRight = roiRight,
                    roiBottom = roiBottom
                )
            )
            newTaskId
        }
    }

    suspend fun saveTemplateBitmap(bitmap: Bitmap, prefix: String = "tpl"): String =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "templates").apply { mkdirs() }
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }

    suspend fun savePickedUriAsTemplate(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream) ?: return@withContext null
                val scaled = scaleBitmapDown(bmp, 360)
                saveTemplateBitmap(scaled, "picked")
            }
        }.getOrNull()
    }

    suspend fun decodeUriBitmap(uri: Uri, maxDim: Int = 1440): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val raw = BitmapFactory.decodeStream(stream) ?: return@withContext null
                    scaleBitmapDown(raw, maxDim)
                }
            }.getOrNull()
        }

    fun loadTemplateBitmap(pathOrKey: String): Bitmap? {
        if (pathOrKey.isBlank()) return null
        return when (pathOrKey) {
            "drawable:img_reward_emblem" -> renderVectorDrawableBitmap(R.drawable.img_reward_emblem, 144)
            "drawable:img_coupon_badge" -> renderVectorDrawableBitmap(R.drawable.img_coupon_badge, 144)
            else -> {
                val file = File(pathOrKey)
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            }
        }
    }

    fun listSavedTemplateFiles(): List<File> {
        val dir = File(context.filesDir, "templates")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun renderVectorDrawableBitmap(drawableRes: Int, sizePx: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    private fun scaleBitmapDown(src: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDimension) return src
        val ratio = maxDimension.toFloat() / maxSide.toFloat()
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
