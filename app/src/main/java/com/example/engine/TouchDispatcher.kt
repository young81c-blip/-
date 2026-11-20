package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class OffsetTapPoint(
    val x: Float,
    val y: Float,
    val deltaX: Int,
    val deltaY: Int
)

object TouchDispatcher {

    fun applyHumanizedOffset(
        centerX: Float,
        centerY: Float,
        boundingBox: Rect?,
        enabled: Boolean,
        radiusPx: Int
    ): OffsetTapPoint {
        if (!enabled || radiusPx <= 0) {
            return OffsetTapPoint(centerX, centerY, 0, 0)
        }
        // Polar distribution weighted toward center (sqrt(u) -> uniform disc, u -> center-biased finger tap)
        val r = Random.nextFloat() * radiusPx.toFloat()
        val angle = Random.nextFloat() * (2f * PI.toFloat())
        var tx = centerX + r * cos(angle)
        var ty = centerY + r * sin(angle)

        if (boundingBox != null && boundingBox.width() > 6 && boundingBox.height() > 6) {
            val insetX = min(4f, boundingBox.width() * 0.15f)
            val insetY = min(4f, boundingBox.height() * 0.15f)
            tx = tx.coerceIn(boundingBox.left + insetX, boundingBox.right - insetX)
            ty = ty.coerceIn(boundingBox.top + insetY, boundingBox.bottom - insetY)
        }

        return OffsetTapPoint(
            x = tx,
            y = ty,
            deltaX = (tx - centerX).toInt(),
            deltaY = (ty - centerY).toInt()
        )
    }

    fun applyJitterDelay(baseDelayMs: Long, jitterMs: Long): Long {
        if (jitterMs <= 0L) return baseDelayMs
        val delta = Random.nextLong(-jitterMs, jitterMs + 1L)
        return (baseDelayMs + delta).coerceAtLeast(120L)
    }

    suspend fun captureViewBitmap(view: View): Bitmap? = withContext(Dispatchers.Main) {
        if (view.width <= 0 || view.height <= 0) return@withContext null
        runCatching {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            bitmap
        }.getOrNull()
    }

    suspend fun dispatchTapOnView(
        view: View,
        xPx: Float,
        yPx: Float,
        pressDurationMs: Long = 55L,
        touchPressure: Float = 0.72f
    ): Boolean = withContext(Dispatchers.Main) {
        if (view.width <= 0 || view.height <= 0) return@withContext false
        val clampedX = xPx.coerceIn(1f, (view.width - 1).toFloat())
        val clampedY = yPx.coerceIn(1f, (view.height - 1).toFloat())
        val safePressure = touchPressure.coerceIn(0.1f, 1.0f)
        val safeSize = (0.25f + 0.65f * safePressure).coerceIn(0.1f, 1.0f)
        val holdMs = pressDurationMs.coerceIn(15L, 2000L)
        val downTime = SystemClock.uptimeMillis()

        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = clampedX
                y = clampedY
                pressure = safePressure
                size = safeSize
            }
        )

        val downEvent = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        val handledDown = view.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        delay(holdMs)

        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(
            downTime,
            upTime,
            MotionEvent.ACTION_UP,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        val handledUp = view.dispatchTouchEvent(upEvent)
        upEvent.recycle()

        handledDown || handledUp
    }
}
