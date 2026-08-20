package com.example.parking.ui.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.parking.network.ParkingWebSocket
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CameraFrameStreamer"

/** 480p 目标分辨率（宽 x 高） */
private const val TARGET_WIDTH = 854
private const val TARGET_HEIGHT = 480
private const val JPEG_QUALITY = 60
private const val MIN_FRAME_INTERVAL_MS = 67L // 15 fps

object CameraFrameStreamer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastAcceptedMonoNs = AtomicLong(0L)
    private val isEncoding = AtomicBoolean(false)

    @Volatile
    private var streaming = false

    fun start() {
        streaming = true
        lastAcceptedMonoNs.set(0L)
        Log.i(TAG, "视频流推流已开始 (480p @ 15fps, quality=$JPEG_QUALITY)")
    }

    fun stop() {
        streaming = false
        Log.i(TAG, "视频流推流已停止")
    }

    fun buildImageAnalysis(cameraExecutor: Executor): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(TARGET_WIDTH, TARGET_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()

        return ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { image ->
                    onAnalyzerFrame(image)
                }
            }
    }

    private fun onAnalyzerFrame(image: ImageProxy) {
        if (!streaming) {
            image.close()
            return
        }

        val nowNs = System.nanoTime()
        val minNs = MIN_FRAME_INTERVAL_MS * 1_000_000L
        if (nowNs - lastAcceptedMonoNs.get() < minNs) {
            image.close()
            return
        }
        if (!isEncoding.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAcceptedMonoNs.set(nowNs)

        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = try {
            rgbaImageProxyToBitmap(image)
        } catch (exception: Exception) {
            Log.e(TAG, "帧转换失败", exception)
            null
        } finally {
            image.close()
        }
        if (bitmap == null) {
            isEncoding.set(false)
            return
        }

        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    val oriented = applyRotation(bitmap, rotationDegrees)
                    if (oriented !== bitmap) {
                        bitmap.recycle()
                    }
                    val scaled = scaleToTarget(oriented)
                    if (scaled !== oriented) {
                        oriented.recycle()
                    }
                    val bytes = bitmapToJpeg(scaled)
                    scaled.recycle()
                    bytes
                }
                if (jpeg.isNotEmpty()) {
                    ParkingWebSocket.sendJpegFrame(jpeg)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "帧编码或发送失败", exception)
            } finally {
                isEncoding.set(false)
            }
        }
    }

    private fun rgbaImageProxyToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (pixelStride == 4 && rowStride == 4 * width) {
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }
        val rowPixels = IntArray(width)
        val rgba = ByteArray(4)
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val pos = rowStart + x * pixelStride
                buffer.position(pos)
                buffer.get(rgba)
                val r = rgba[0].toInt() and 0xFF
                val g = rgba[1].toInt() and 0xFF
                val b = rgba[2].toInt() and 0xFF
                val a = rgba[3].toInt() and 0xFF
                rowPixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    private fun applyRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return bitmap
        }
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    }

    private fun scaleToTarget(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val scale = minOf(
            TARGET_WIDTH.toFloat() / width,
            TARGET_HEIGHT.toFloat() / height,
            1f,
        )
        if (scale >= 1f) {
            return bitmap
        }
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(newWidth, newHeight)
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(bitmap.width * bitmap.height / 6)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }
}
