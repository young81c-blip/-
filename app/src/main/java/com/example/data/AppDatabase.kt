package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        AutomationTaskEntity::class,
        AutomationStepEntity::class,
        ExecutionLogEntity::class,
        ExecutionClickEventEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN loopMode TEXT NOT NULL DEFAULT 'FIXED_COUNT'")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN loopIntervalMs INTEGER NOT NULL DEFAULT 800")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN stopConditionType TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN stopConditionText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN stopConditionImagePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN stopConditionThreshold REAL NOT NULL DEFAULT 0.76")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN maxDurationMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE execution_logs ADD COLUMN executionMode TEXT NOT NULL DEFAULT 'SANDBOX'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_click_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        logId INTEGER NOT NULL,
                        taskId INTEGER NOT NULL,
                        taskName TEXT NOT NULL,
                        roundIndex INTEGER NOT NULL,
                        stepOrder INTEGER NOT NULL,
                        stepTitle TEXT NOT NULL,
                        executionMode TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        recognizedTarget TEXT NOT NULL,
                        tapX INTEGER,
                        tapY INTEGER,
                        offsetDeltaX INTEGER NOT NULL,
                        offsetDeltaY INTEGER NOT NULL,
                        success INTEGER NOT NULL,
                        failureReason TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(logId) REFERENCES execution_logs(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_execution_click_events_logId ON execution_click_events(logId)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN delayBeforeMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN touchPressure REAL NOT NULL DEFAULT 0.72")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN pressDurationMs INTEGER NOT NULL DEFAULT 55")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN roiEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN roiLeft REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN roiTop REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN roiRight REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN roiBottom REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN maxRetryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN retryIntervalMs INTEGER NOT NULL DEFAULT 1000")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN groupId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN notifyOnComplete INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN notifyOnFailure INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN notifyOnManualConfirm INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN longRunThresholdSec INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE automation_tasks ADD COLUMN customNotifyNote TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN branchConditionType TEXT NOT NULL DEFAULT 'ALWAYS_PAUSE'")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN preScanDelayMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automation_steps ADD COLUMN scanIntervalMs INTEGER NOT NULL DEFAULT 350")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE execution_click_events ADD COLUMN normX REAL")
                db.execSQL("ALTER TABLE execution_click_events ADD COLUMN normY REAL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): AppDatabase {
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                "smart_tap_flow.db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedStarterWorkflows(getInstance(appContext).automationDao())
                        }
                    }
                })
                .build()
        }

        suspend fun seedStarterWorkflows(dao: AutomationDao) {
            if (dao.getAllTasks().isNotEmpty()) return

            // 1. OCR Text Recognition + Auto-Click Workflow
            val checkinTaskId = dao.insertTask(
                AutomationTaskEntity(
                    name = "每日签到与弹窗清理",
                    description = "自动识别“关闭广告”关闭营销弹窗，随后识别“立即签到”与“领取积分”依次完成点击。",
                    sceneType = TargetSceneType.DAILY_CHECKIN.name,
                    loopMode = LoopMode.FIXED_COUNT.name,
                    repeatCount = 1,
                    stopConditionType = StopConditionType.STOP_ON_STEP_MISS.name,
                    isEnabled = true
                )
            )
            dao.insertSteps(
                listOf(
                    AutomationStepEntity(
                        taskId = checkinTaskId,
                        stepOrder = 0,
                        title = "识别并关闭营销弹窗",
                        stepType = StepType.OCR_TEXT_TAP.name,
                        targetText = "关闭广告",
                        textMatchMode = TextMatchMode.CONTAINS.name,
                        delayAfterMs = 650L
                    ),
                    AutomationStepEntity(
                        taskId = checkinTaskId,
                        stepOrder = 1,
                        title = "识别并点击立即签到",
                        stepType = StepType.OCR_TEXT_TAP.name,
                        targetText = "立即签到",
                        textMatchMode = TextMatchMode.CONTAINS.name,
                        delayAfterMs = 650L
                    ),
                    AutomationStepEntity(
                        taskId = checkinTaskId,
                        stepOrder = 2,
                        title = "识别并点击领取积分",
                        stepType = StepType.OCR_TEXT_TAP.name,
                        targetText = "领取积分",
                        textMatchMode = TextMatchMode.CONTAINS.name,
                        delayAfterMs = 500L
                    )
                )
            )

            // 2. Image Template Matching + OCR Hybrid Workflow
            val rewardTaskId = dao.insertTask(
                AutomationTaskEntity(
                    name = "奖励徽章图像识图领取",
                    description = "通过像素模板匹配定位金色星标徽章中心坐标并自动点击，再通过 OCR 确认领取。",
                    sceneType = TargetSceneType.REWARD_CENTER.name,
                    loopMode = LoopMode.FIXED_COUNT.name,
                    repeatCount = 1,
                    stopConditionType = StopConditionType.NONE.name,
                    isEnabled = true
                )
            )
            dao.insertSteps(
                listOf(
                    AutomationStepEntity(
                        taskId = rewardTaskId,
                        stepOrder = 0,
                        title = "图像识别奖励徽章图标",
                        stepType = StepType.IMAGE_TEMPLATE_TAP.name,
                        templateImagePath = "drawable:img_reward_emblem",
                        matchThreshold = 0.72f,
                        delayAfterMs = 700L
                    ),
                    AutomationStepEntity(
                        taskId = rewardTaskId,
                        stepOrder = 1,
                        title = "识别并点击确认领取",
                        stepType = StepType.OCR_TEXT_TAP.name,
                        targetText = "确认领取",
                        textMatchMode = TextMatchMode.CONTAINS.name,
                        delayAfterMs = 500L
                    )
                )
            )

            // 3. Conditional Loop Multi-Step Workflow
            val couponTaskId = dao.insertTask(
                AutomationTaskEntity(
                    name = "限时闪购券条件循环连击",
                    description = "持续循环识别闪电券图标与“立即抢券”，当屏幕出现“已抢到 2 张”时自动停止循环。",
                    sceneType = TargetSceneType.FLASH_COUPON.name,
                    loopMode = LoopMode.UNTIL_CONDITION.name,
                    repeatCount = 1,
                    loopIntervalMs = 700L,
                    stopConditionType = StopConditionType.STOP_WHEN_TEXT_APPEARS.name,
                    stopConditionText = "已抢到 2 张",
                    isEnabled = true
                )
            )
            dao.insertSteps(
                listOf(
                    AutomationStepEntity(
                        taskId = couponTaskId,
                        stepOrder = 0,
                        title = "图像匹配闪电券徽章",
                        stepType = StepType.IMAGE_TEMPLATE_TAP.name,
                        templateImagePath = "drawable:img_coupon_badge",
                        matchThreshold = 0.72f,
                        delayAfterMs = 500L
                    ),
                    AutomationStepEntity(
                        taskId = couponTaskId,
                        stepOrder = 1,
                        title = "等待卡片展开",
                        stepType = StepType.WAIT_DELAY.name,
                        delayAfterMs = 400L
                    ),
                    AutomationStepEntity(
                        taskId = couponTaskId,
                        stepOrder = 2,
                        title = "OCR 识别并点击立即抢券",
                        stepType = StepType.OCR_TEXT_TAP.name,
                        targetText = "立即抢券",
                        textMatchMode = TextMatchMode.CONTAINS.name,
                        delayAfterMs = 500L
                    )
                )
            )
        }
    }
}
