package com.example.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class RootStatus(
    val isSuBinaryPresent: Boolean,
    val hasRootAccess: Boolean,
    val uidOutput: String,
    val message: String
)

class RootShellExecutor {

    private fun hasSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    suspend fun checkRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        val suExists = hasSuBinary()
        val result = execSu("id", timeoutSeconds = 3L)
        if (result.isSuccess) {
            val out = result.getOrNull().orEmpty().trim()
            val granted = out.contains("uid=0")
            RootStatus(
                isSuBinaryPresent = true,
                hasRootAccess = granted,
                uidOutput = out,
                message = if (granted) {
                    "已获取 Root 权限 ($out)，可直接执行全局 screencap 与 input tap"
                } else {
                    "su 命令返回非 root 身份：$out"
                }
            )
        } else {
            val err = result.exceptionOrNull()?.message ?: "无法执行 su"
            RootStatus(
                isSuBinaryPresent = suExists,
                hasRootAccess = false,
                uidOutput = "",
                message = if (suExists) {
                    "检测到 su 文件，但尚未授予 Root 权限：$err"
                } else {
                    "当前设备未 Root（未检测到 su）。您仍可使用“应用内沙盒引擎”实时测试 OCR 与图像点击。"
                }
            )
        }
    }

    suspend fun captureScreenBitmap(outputFile: File): Result<Bitmap> = withContext(Dispatchers.IO) {
        val path = outputFile.absolutePath
        val cmdResult = execSu("screencap -p '$path' && chmod 644 '$path'", timeoutSeconds = 5L)
        if (cmdResult.isFailure) {
            return@withContext Result.failure(
                cmdResult.exceptionOrNull() ?: IllegalStateException("Root screencap 失败")
            )
        }
        if (!outputFile.exists() || outputFile.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("截图文件为空，请检查 Root 授权"))
        }
        val bitmap = BitmapFactory.decodeFile(path)
            ?: return@withContext Result.failure(IllegalStateException("无法解析屏幕截图 Bitmap"))
        Result.success(bitmap)
    }

    suspend fun tap(
        x: Int,
        y: Int,
        pressDurationMs: Long = 55L,
        touchPressure: Float = 0.72f
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val effectiveDuration = maxOf(
            pressDurationMs.coerceIn(15L, 2000L),
            if (touchPressure >= 0.85f) (touchPressure * 120f).toLong() else 0L
        )
        val cmd = if (effectiveDuration > 85L) {
            "input swipe $x $y $x $y $effectiveDuration"
        } else {
            "input tap $x $y"
        }
        execSu(cmd, timeoutSeconds = 4L).map { }
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Result<Unit> =
        withContext(Dispatchers.IO) {
            execSu("input swipe $x1 $y1 $x2 $y2 $durationMs", timeoutSeconds = 4L).map { }
        }

    private fun execSu(command: String, timeoutSeconds: Long): Result<String> {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                error("Root 命令超时 (${timeoutSeconds}s)")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                error("退出码 $exitCode: ${output.ifBlank { "Root 授权被拒绝" }}")
            }
            output
        }
    }
}
