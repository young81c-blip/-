package com.example.engine

import android.graphics.Bitmap
import android.graphics.Rect

data class CroppedRegionFrame(
    val bitmap: Bitmap,
    val offsetX: Int,
    val offsetY: Int,
    val cropRect: Rect,
    val ownsBitmap: Boolean,
    val areaRatio: Float
)

object RegionCropHelper {

    fun cropForRegion(
        source: Bitmap,
        enabled: Boolean,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float
    ): CroppedRegionFrame {
        val fullRect = Rect(0, 0, source.width, source.height)
        if (!enabled || source.width < 32 || source.height < 32) {
            return CroppedRegionFrame(
                bitmap = source,
                offsetX = 0,
                offsetY = 0,
                cropRect = fullRect,
                ownsBitmap = false,
                areaRatio = 1f
            )
        }

        val l = normLeft.coerceIn(0f, 0.94f)
        val t = normTop.coerceIn(0f, 0.94f)
        val r = normRight.coerceIn(l + 0.05f, 1f)
        val b = normBottom.coerceIn(t + 0.05f, 1f)

        if (l <= 0.01f && t <= 0.01f && r >= 0.99f && b >= 0.99f) {
            return CroppedRegionFrame(
                bitmap = source,
                offsetX = 0,
                offsetY = 0,
                cropRect = fullRect,
                ownsBitmap = false,
                areaRatio = 1f
            )
        }

        val pxLeft = (l * source.width).toInt().coerceIn(0, source.width - 24)
        val pxTop = (t * source.height).toInt().coerceIn(0, source.height - 24)
        val pxRight = (r * source.width).toInt().coerceIn(pxLeft + 24, source.width)
        val pxBottom = (b * source.height).toInt().coerceIn(pxTop + 24, source.height)

        val cropW = (pxRight - pxLeft).coerceAtLeast(24)
        val cropH = (pxBottom - pxTop).coerceAtLeast(24)
        val cropped = Bitmap.createBitmap(source, pxLeft, pxTop, cropW, cropH)
        val ratio = (cropW.toFloat() * cropH.toFloat()) / (source.width.toFloat() * source.height.toFloat()).coerceAtLeast(1f)

        return CroppedRegionFrame(
            bitmap = cropped,
            offsetX = pxLeft,
            offsetY = pxTop,
            cropRect = Rect(pxLeft, pxTop, pxLeft + cropW, pxTop + cropH),
            ownsBitmap = true,
            areaRatio = ratio.coerceIn(0.01f, 1f)
        )
    }

    fun mapRectToFullScreen(localRect: Rect, offsetX: Int, offsetY: Int): Rect {
        return Rect(
            localRect.left + offsetX,
            localRect.top + offsetY,
            localRect.right + offsetX,
            localRect.bottom + offsetY
        )
    }
}
