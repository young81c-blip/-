package com.example.engine

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.data.TextMatchMode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecognizedTextElement(
    val text: String,
    val boundingBox: Rect,
    val centerX: Float,
    val centerY: Float,
    val isLine: Boolean
)

class OcrRecognitionEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun warmUp(): Long {
        val start = System.currentTimeMillis()
        val dummy = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            recognizeText(dummy)
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            System.currentTimeMillis() - start
        } finally {
            dummy.recycle()
        }
    }

    suspend fun recognizeText(bitmap: Bitmap): List<RecognizedTextElement> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val elements = mutableListOf<RecognizedTextElement>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox
                            if (box != null && line.text.isNotBlank()) {
                                elements.add(
                                    RecognizedTextElement(
                                        text = line.text.trim(),
                                        boundingBox = box,
                                        centerX = box.exactCenterX(),
                                        centerY = box.exactCenterY(),
                                        isLine = true
                                    )
                                )
                            }
                            for (element in line.elements) {
                                val elBox = element.boundingBox
                                if (elBox != null && element.text.isNotBlank() && line.elements.size > 1) {
                                    elements.add(
                                        RecognizedTextElement(
                                            text = element.text.trim(),
                                            boundingBox = elBox,
                                            centerX = elBox.exactCenterX(),
                                            centerY = elBox.exactCenterY(),
                                            isLine = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                    cont.resume(elements)
                }
                .addOnFailureListener { error ->
                    cont.resumeWithException(error)
                }
        }
    }

    fun findBestMatch(
        elements: List<RecognizedTextElement>,
        query: String,
        mode: TextMatchMode
    ): RecognizedTextElement? {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return null
        val compactQuery = trimmedQuery.replace(" ", "")

        val matches = elements.filter { el ->
            val candidate = el.text.trim()
            val compactCandidate = candidate.replace(" ", "")
            when (mode) {
                TextMatchMode.EXACT ->
                    candidate.equals(trimmedQuery, ignoreCase = true) ||
                        compactCandidate.equals(compactQuery, ignoreCase = true)

                TextMatchMode.CONTAINS ->
                    candidate.contains(trimmedQuery, ignoreCase = true) ||
                        compactCandidate.contains(compactQuery, ignoreCase = true)

                TextMatchMode.REGEX -> runCatching {
                    Regex(trimmedQuery, RegexOption.IGNORE_CASE).containsMatchIn(candidate)
                }.getOrDefault(false)
            }
        }

        // Prefer shorter exact-fitting line or element bounding boxes so tap hits the button directly
        return matches.minByOrNull { el ->
            val area = el.boundingBox.width().toLong() * el.boundingBox.height().toLong()
            val exactBonus = if (el.text.replace(" ", "").equals(compactQuery, ignoreCase = true)) 0L else 1_000_000L
            exactBonus + area
        }
    }
}
