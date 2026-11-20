package com.example.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationConfig(
    val masterEnabled: Boolean = true,
    val notifyOnComplete: Boolean = true,
    val notifyOnFailure: Boolean = true,
    val notifyOnManualConfirm: Boolean = true,
    val minLongRunSeconds: Int = 0,
    val customTitlePrefix: String = "智点任务",
    val highPriorityBanner: Boolean = true,
    val vibrationEnabled: Boolean = true
)

object TaskNotificationManager {

    private const val PREFS_NAME = "smart_tap_notification_prefs"
    private const val KEY_MASTER = "master_enabled"
    private const val KEY_COMPLETE = "notify_complete"
    private const val KEY_FAILURE = "notify_failure"
    private const val KEY_CONFIRM = "notify_manual_confirm"
    private const val KEY_MIN_SEC = "min_long_run_sec"
    private const val KEY_PREFIX = "custom_title_prefix"
    private const val KEY_HIGH_PRIORITY = "high_priority_banner"
    private const val KEY_VIBRATION = "vibration_enabled"

    private const val CHANNEL_ALERTS_HIGH = "task_alerts_high_v1"
    private const val CHANNEL_ALERTS_QUIET = "task_alerts_quiet_v1"

    private const val BASE_RESULT_NOTIFY_ID = 5200
    const val MANUAL_CONFIRM_NOTIFY_ID = 5399
    private const val TEST_NOTIFY_ID = 5401

    private val _configFlow = MutableStateFlow<NotificationConfig?>(null)

    fun configFlow(context: Context): StateFlow<NotificationConfig> {
        if (_configFlow.value == null) {
            _configFlow.value = loadConfig(context)
        }
        @Suppress("UNCHECKED_CAST")
        return _configFlow as StateFlow<NotificationConfig>
    }

    fun loadConfig(context: Context): NotificationConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val loaded = NotificationConfig(
            masterEnabled = prefs.getBoolean(KEY_MASTER, true),
            notifyOnComplete = prefs.getBoolean(KEY_COMPLETE, true),
            notifyOnFailure = prefs.getBoolean(KEY_FAILURE, true),
            notifyOnManualConfirm = prefs.getBoolean(KEY_CONFIRM, true),
            minLongRunSeconds = prefs.getInt(KEY_MIN_SEC, 0).coerceIn(0, 3600),
            customTitlePrefix = prefs.getString(KEY_PREFIX, "智点任务")?.ifBlank { "智点任务" } ?: "智点任务",
            highPriorityBanner = prefs.getBoolean(KEY_HIGH_PRIORITY, true),
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true)
        )
        _configFlow.value = loaded
        return loaded
    }

    fun saveConfig(context: Context, config: NotificationConfig) {
        val sanitized = config.copy(
            minLongRunSeconds = config.minLongRunSeconds.coerceIn(0, 3600),
            customTitlePrefix = config.customTitlePrefix.trim().take(24).ifBlank { "智点任务" }
        )
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MASTER, sanitized.masterEnabled)
            .putBoolean(KEY_COMPLETE, sanitized.notifyOnComplete)
            .putBoolean(KEY_FAILURE, sanitized.notifyOnFailure)
            .putBoolean(KEY_CONFIRM, sanitized.notifyOnManualConfirm)
            .putInt(KEY_MIN_SEC, sanitized.minLongRunSeconds)
            .putString(KEY_PREFIX, sanitized.customTitlePrefix)
            .putBoolean(KEY_HIGH_PRIORITY, sanitized.highPriorityBanner)
            .putBoolean(KEY_VIBRATION, sanitized.vibrationEnabled)
            .apply()
        _configFlow.value = sanitized
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val highChannel = NotificationChannel(
            CHANNEL_ALERTS_HIGH,
            "任务状态与人工确认强提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "任务完成、执行失败或遇到人工确认分支时弹出系统横幅提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 220L, 120L, 220L)
        }

        val quietChannel = NotificationChannel(
            CHANNEL_ALERTS_QUIET,
            "任务状态静音通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "仅在状态栏安静显示任务执行结果提醒"
            enableVibration(false)
            setSound(null, null)
        }

        manager.createNotificationChannel(highChannel)
        manager.createNotificationChannel(quietChannel)
    }

    fun notifyTaskCompleted(
        context: Context,
        taskId: Long,
        taskName: String,
        executionMode: String,
        durationMs: Long,
        summary: String,
        taskNotifyEnabled: Boolean,
        taskLongRunThresholdSec: Int,
        taskCustomNote: String
    ) {
        val cfg = loadConfig(context)
        if (!cfg.masterEnabled || !cfg.notifyOnComplete || !taskNotifyEnabled) return
        if (!hasNotificationPermission(context)) return

        val effectiveThresholdSec = if (taskLongRunThresholdSec >= 0) {
            taskLongRunThresholdSec
        } else {
            cfg.minLongRunSeconds
        }
        if (durationMs < effectiveThresholdSec * 1000L) return

        ensureChannels(context)
        val channelId = if (cfg.highPriorityBanner) CHANNEL_ALERTS_HIGH else CHANNEL_ALERTS_QUIET
        val durationLabel = formatDuration(durationMs)
        val modeLabel = if (executionMode == "ROOT") "Root 引擎" else "沙盒演练"
        val title = "${cfg.customTitlePrefix} · 任务执行完成"
        val noteLine = if (taskCustomNote.isNotBlank()) "\n备注：${taskCustomNote.trim()}" else ""
        val body = "[$modeLabel]「$taskName」已运行结束（耗时 $durationLabel）\n$summary$noteLine"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("「$taskName」已完成（耗时 $durationLabel）")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (cfg.highPriorityBanner) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(context, 100 + (taskId % 50).toInt()))
            .apply {
                if (cfg.vibrationEnabled && cfg.highPriorityBanner) {
                    setVibrate(longArrayOf(0L, 180L, 90L, 180L))
                } else if (!cfg.vibrationEnabled) {
                    setSilent(true)
                }
            }
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(BASE_RESULT_NOTIFY_ID + (taskId % 100).toInt(), notification)
    }

    fun notifyTaskFailed(
        context: Context,
        taskId: Long,
        taskName: String,
        executionMode: String,
        reason: String,
        taskNotifyEnabled: Boolean,
        taskCustomNote: String
    ) {
        val cfg = loadConfig(context)
        if (!cfg.masterEnabled || !cfg.notifyOnFailure || !taskNotifyEnabled) return
        if (!hasNotificationPermission(context)) return

        ensureChannels(context)
        val channelId = if (cfg.highPriorityBanner) CHANNEL_ALERTS_HIGH else CHANNEL_ALERTS_QUIET
        val modeLabel = if (executionMode == "ROOT") "Root 引擎" else "沙盒演练"
        val title = "${cfg.customTitlePrefix} · 任务执行失败"
        val noteLine = if (taskCustomNote.isNotBlank()) "\n备注：${taskCustomNote.trim()}" else ""
        val body = "[$modeLabel]「$taskName」未能完成：$reason$noteLine"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("「$taskName」执行异常：$reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (cfg.highPriorityBanner) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(context, 200 + (taskId % 50).toInt()))
            .apply {
                if (cfg.vibrationEnabled && cfg.highPriorityBanner) {
                    setVibrate(longArrayOf(0L, 260L, 120L, 260L))
                } else if (!cfg.vibrationEnabled) {
                    setSilent(true)
                }
            }
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(BASE_RESULT_NOTIFY_ID + (taskId % 100).toInt(), notification)
    }

    fun notifyManualConfirmationRequired(
        context: Context,
        request: PendingConfirmationRequest,
        taskNotifyEnabled: Boolean,
        taskCustomNote: String
    ) {
        val cfg = loadConfig(context)
        if (!cfg.masterEnabled || !cfg.notifyOnManualConfirm || !taskNotifyEnabled) return
        if (!hasNotificationPermission(context)) return

        ensureChannels(context)
        val channelId = if (cfg.highPriorityBanner) CHANNEL_ALERTS_HIGH else CHANNEL_ALERTS_QUIET
        val modeLabel = if (request.executionMode == "ROOT") "Root 引擎" else "沙盒演练"
        val title = "${cfg.customTitlePrefix} · 需要人工确认"
        val noteLine = if (taskCustomNote.isNotBlank()) "\n备注：${taskCustomNote.trim()}" else ""
        val body = "[$modeLabel]「${request.taskName}」在节点「${request.stepTitle}」暂停等待：\n${request.reason}$noteLine"

        val confirmIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CONFIRM_CONTINUE
            putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, request.requestId)
        }
        val confirmPending = PendingIntent.getBroadcast(
            context,
            301,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val abortIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ABORT_TASK
            putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, request.requestId)
        }
        val abortPending = PendingIntent.getBroadcast(
            context,
            302,
            abortIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("「${request.stepTitle}」等待您确认后继续执行")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(buildOpenAppIntent(context, 300))
            .addAction(android.R.drawable.ic_media_play, "确认继续", confirmPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "终止任务", abortPending)
            .apply {
                if (cfg.vibrationEnabled) {
                    setVibrate(longArrayOf(0L, 280L, 140L, 280L))
                } else {
                    setSilent(true)
                }
            }
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(MANUAL_CONFIRM_NOTIFY_ID, notification)
    }

    fun cancelManualConfirmationNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(MANUAL_CONFIRM_NOTIFY_ID)
    }

    fun sendTestNotification(context: Context): Boolean {
        if (!hasNotificationPermission(context)) return false
        val cfg = loadConfig(context)
        ensureChannels(context)
        val channelId = if (cfg.highPriorityBanner) CHANNEL_ALERTS_HIGH else CHANNEL_ALERTS_QUIET
        val title = "${cfg.customTitlePrefix} · 自定义通知测试"
        val thresholdDesc = if (cfg.minLongRunSeconds <= 0) "不限耗时（全部触发）" else "仅长任务 >= ${cfg.minLongRunSeconds}秒"
        val body = "系统通知栏通道已就绪！当前规则：$thresholdDesc · 震动${if (cfg.vibrationEnabled) "开启" else "关闭"}。"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (cfg.highPriorityBanner) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(context, 401))
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.notify(TEST_NOTIFY_ID, notification)
        return true
    }

    private fun buildOpenAppIntent(context: Context, requestCode: Int): PendingIntent {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        return when {
            totalSec < 60L -> "${durationMs / 1000.0} 秒".let {
                if (durationMs < 1000L) "${durationMs}ms" else "${totalSec} 秒"
            }
            else -> {
                val min = totalSec / 60L
                val sec = totalSec % 60L
                "${min} 分 ${sec} 秒"
            }
        }
    }
}
