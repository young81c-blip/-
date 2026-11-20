package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.engine.NotificationConfig
import com.example.engine.PendingConfirmationRequest
import com.example.ui.theme.RoseAlert

@Composable
fun NotificationSettingsDialog(
    config: NotificationConfig,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSaveConfig: (NotificationConfig) -> Unit,
    onSendTestNotification: () -> Boolean,
    onDismiss: () -> Unit
) {
    var masterEnabled by remember(config) { mutableStateOf(config.masterEnabled) }
    var notifyOnComplete by remember(config) { mutableStateOf(config.notifyOnComplete) }
    var notifyOnFailure by remember(config) { mutableStateOf(config.notifyOnFailure) }
    var notifyOnManualConfirm by remember(config) { mutableStateOf(config.notifyOnManualConfirm) }
    var minLongRunSecondsText by remember(config) { mutableStateOf(config.minLongRunSeconds.toString()) }
    var customTitlePrefix by remember(config) { mutableStateOf(config.customTitlePrefix) }
    var highPriorityBanner by remember(config) { mutableStateOf(config.highPriorityBanner) }
    var vibrationEnabled by remember(config) { mutableStateOf(config.vibrationEnabled) }
    var testStatusMessage by remember { mutableStateOf<String?>(null) }
    var testStatusIsError by remember { mutableStateOf(false) }

    fun buildCurrentConfig(): NotificationConfig {
        val parsedMinSec = minLongRunSecondsText.toIntOrNull()?.coerceIn(0, 3600) ?: 0
        return NotificationConfig(
            masterEnabled = masterEnabled,
            notifyOnComplete = notifyOnComplete,
            notifyOnFailure = notifyOnFailure,
            notifyOnManualConfirm = notifyOnManualConfirm,
            minLongRunSeconds = parsedMinSec,
            customTitlePrefix = customTitlePrefix.trim().take(24).ifBlank { "智点任务" },
            highPriorityBanner = highPriorityBanner,
            vibrationEnabled = vibrationEnabled
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("自定义通知推送设置")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasPermission) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (hasPermission) Icons.Default.CheckCircle else Icons.Default.NotificationsOff,
                            contentDescription = null,
                            tint = if (hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (hasPermission) "系统通知权限已授予" else "尚未授予系统通知权限",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = if (hasPermission) {
                                    "任务结果与人工确认分支均可实时推送到通知栏"
                                } else {
                                    "Android 13+ 需授权通知权限才能在通知栏弹出提醒"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!hasPermission) {
                            Button(
                                onClick = onRequestPermission,
                                modifier = Modifier.testTag("grant_notify_permission_btn")
                            ) {
                                Text("一键授权", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                NotificationToggleRow(
                    title = "启用全局状态栏通知推送",
                    subtitle = "关闭后将暂停所有任务完成、失败及确认分支通知",
                    checked = masterEnabled,
                    onCheckedChange = { masterEnabled = it },
                    testTag = "notify_master_switch"
                )

                NotificationToggleRow(
                    title = "任务执行完成提醒",
                    subtitle = "流程达到结束条件或正常跑完时推送结果摘要",
                    checked = notifyOnComplete,
                    enabled = masterEnabled,
                    onCheckedChange = { notifyOnComplete = it },
                    testTag = "notify_complete_switch"
                )

                NotificationToggleRow(
                    title = "任务执行失败/异常告警",
                    subtitle = "识别未命中、Root 断连或脚本异常中断时立即告警",
                    checked = notifyOnFailure,
                    enabled = masterEnabled,
                    onCheckedChange = { notifyOnFailure = it },
                    testTag = "notify_failure_switch"
                )

                NotificationToggleRow(
                    title = "人工确认分支强提醒",
                    subtitle = "遇到验证码、风控或人工确认节点时附带「确认继续/终止」快捷按钮",
                    checked = notifyOnManualConfirm,
                    enabled = masterEnabled,
                    onCheckedChange = { notifyOnManualConfirm = it },
                    testTag = "notify_manual_switch"
                )

                NotificationToggleRow(
                    title = "高优先级横幅弹窗",
                    subtitle = "使用系统高重要度通道，任务切换后台时也能顶栏横幅提示",
                    checked = highPriorityBanner,
                    enabled = masterEnabled,
                    onCheckedChange = { highPriorityBanner = it },
                    testTag = "notify_banner_switch"
                )

                NotificationToggleRow(
                    title = "震动提示",
                    subtitle = "收到关键状态变化与人工确认分支时伴随短震动反馈",
                    checked = vibrationEnabled,
                    enabled = masterEnabled,
                    onCheckedChange = { vibrationEnabled = it },
                    testTag = "notify_vibration_switch"
                )

                OutlinedTextField(
                    value = customTitlePrefix,
                    onValueChange = { customTitlePrefix = it.take(24) },
                    label = { Text("自定义通知标题前缀") },
                    singleLine = true,
                    enabled = masterEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("notify_prefix_input")
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("长任务完成推送阈值（秒）", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "仅当任务实际运行时长达到该阈值后，才推送完成通知（失败与人工确认不受此限制）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                0 to "0s 每次都通知",
                                10 to "10s",
                                30 to "30s",
                                60 to "60s",
                                180 to "180s"
                            ).forEach { (sec, label) ->
                                FilterChip(
                                    selected = minLongRunSecondsText == sec.toString(),
                                    onClick = { minLongRunSecondsText = sec.toString() },
                                    enabled = masterEnabled,
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = minLongRunSecondsText,
                            onValueChange = { minLongRunSecondsText = it.filter(Char::isDigit).take(4) },
                            label = { Text("自定义长任务触发阈值 (0–3600 秒)") },
                            singleLine = true,
                            enabled = masterEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("notify_min_seconds_input")
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        val current = buildCurrentConfig()
                        onSaveConfig(current)
                        if (!hasPermission) {
                            testStatusMessage = "缺少通知权限，请先点击上方“一键授权”"
                            testStatusIsError = true
                            onRequestPermission()
                        } else {
                            val ok = onSendTestNotification()
                            testStatusMessage = if (ok) {
                                "已发送测试通知到系统状态栏"
                            } else {
                                "发送失败，请检查系统通知开关是否被禁用"
                            }
                            testStatusIsError = !ok
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("send_test_notification_btn")
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存并发送测试通知")
                }

                testStatusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (testStatusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveConfig(buildCurrentConfig())
                    onDismiss()
                },
                modifier = Modifier.testTag("notify_settings_save_btn")
            ) {
                Text("保存规则")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun NotificationToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
fun ManualConfirmationAlertDialog(
    request: PendingConfirmationRequest,
    onConfirmContinue: () -> Unit,
    onAbortTask: () -> Unit
) {
    val modeLabel = if (request.executionMode == "ROOT") "Root 全局引擎" else "沙盒执行台"
    AlertDialog(
        onDismissRequest = { /* Must explicitly confirm or abort */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PanTool,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("人工确认逻辑分支")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${request.taskName} · $modeLabel",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "触发节点：${request.stepTitle}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = request.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "自动化流程已安全挂起。请处理当前界面或确认状态无误后，选择继续执行或立即终止。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmContinue,
                modifier = Modifier.testTag("manual_confirm_continue_btn")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("确认继续执行")
            }
        },
        dismissButton = {
            Button(
                onClick = onAbortTask,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RoseAlert,
                    contentColor = Color.White
                ),
                modifier = Modifier.testTag("manual_confirm_abort_btn")
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("终止任务")
            }
        }
    )
}
