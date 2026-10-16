package com.lei.accounting.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 本地中文 OCR（ML Kit 内置模型，离线、不上传图片） */
object OcrHelper {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** 返回按从上到下、从左到右排好序的文字行 */
    suspend fun recognize(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                cont.resume(lines)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }
}
