package com.example.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class StepType(val label: String) {
    OCR_TEXT_TAP("文字识别点击"),
    IMAGE_TEMPLATE_TAP("图像模板点击"),
    COORDINATE_TAP("固定坐标点击"),
    WAIT_DELAY("等待延时"),
    MANUAL_CONFIRM_BRANCH("人工确认分支")
}

enum class BranchConditionType(val label: String, val description: String) {
    ALWAYS_PAUSE("执行到此节点直接暂停待确认", "到达该节点立即弹窗与推通知，等待人工确认后继续"),
    WHEN_TEXT_APPEARS("检测到指定文字时触发确认", "例如出现“验证码”“风控校验”“支付确认”时推送提醒并暂停，未出现则自动跳过"),
    WHEN_TEXT_MISSING("缺少指定文字时触发确认", "例如页面未出现预期文字时推送提醒并等待人工排查")
}

enum class TextMatchMode(val label: String) {
    CONTAINS("包含匹配"),
    EXACT("精确匹配"),
    REGEX("正则匹配")
}

enum class LoopMode(val label: String, val description: String) {
    FIXED_COUNT("固定次数", "按指定次数重复执行任务流程"),
    INFINITE("无限循环", "持续循环执行，直至手动停止或超时保护"),
    UNTIL_CONDITION("条件循环", "持续循环复用流程，直到满足停止条件")
}

enum class StopConditionType(val label: String) {
    NONE("无附加条件"),
    STOP_WHEN_TEXT_APPEARS("出现指定文字停止"),
    STOP_WHEN_TEXT_MISSING("目标文字消失停止"),
    STOP_WHEN_IMAGE_APPEARS("出现指定图像停止"),
    STOP_ON_STEP_MISS("步骤未命中即停止"),
    STOP_AFTER_DURATION("达到时长上限停止")
}

enum class TargetSceneType(val label: String, val subtitle: String) {
    DAILY_CHECKIN("签到与弹窗清理", "模拟营销弹窗拦截 + 签到领积分页面"),
    REWARD_CENTER("徽章奖励中心", "包含视觉图标徽章与多状态领取按钮"),
    FLASH_COUPON("限时闪购抢券", "包含高对比度闪购券图标与抢券交互"),
    CUSTOM_IMAGE_CANVAS("自定义截图画布", "从系统相册导入真实截图进行识图识字点击")
}

@Entity(tableName = "automation_tasks")
data class AutomationTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
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
    val lastRunTimestamp: Long? = null,
    val lastRunSuccess: Boolean? = null,
    val runCount: Int = 0,
    val totalClicksExecuted: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "automation_steps",
    foreignKeys = [
        ForeignKey(
            entity = AutomationTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId")]
)
data class AutomationStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val stepOrder: Int,
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

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val taskName: String,
    val executionMode: String = "SANDBOX",
    val startedAt: Long,
    val durationMs: Long,
    val matchedSteps: Int,
    val totalSteps: Int,
    val clicksTriggered: Int,
    val success: Boolean,
    val summary: String
)

@Entity(
    tableName = "execution_click_events",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["logId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("logId")]
)
data class ExecutionClickEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long = 0,
    val taskId: Long,
    val taskName: String,
    val roundIndex: Int,
    val stepOrder: Int,
    val stepTitle: String,
    val executionMode: String,
    val actionType: String,
    val recognizedTarget: String,
    val tapX: Int? = null,
    val tapY: Int? = null,
    val normX: Float? = null,
    val normY: Float? = null,
    val offsetDeltaX: Int = 0,
    val offsetDeltaY: Int = 0,
    val success: Boolean,
    val failureReason: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class ExecutionLogWithEvents(
    @Embedded val log: ExecutionLogEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "logId"
    )
    val events: List<ExecutionClickEventEntity>
) {
    val sortedEvents: List<ExecutionClickEventEntity>
        get() = events.sortedBy { it.timestamp }
}

data class TaskWithSteps(
    @Embedded val task: AutomationTaskEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "taskId"
    )
    val steps: List<AutomationStepEntity>
) {
    val sortedSteps: List<AutomationStepEntity>
        get() = steps.sortedBy { it.stepOrder }
}
