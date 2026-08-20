package com.example.parking.ui.scan

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "CameraStreamController"

class CameraStreamController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val cameraExecutor: Executor = Executors.newSingleThreadExecutor()
    private val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var isCameraOpen = false
    private var isStreaming = false

    fun getPreviewView(): PreviewView = previewView

    fun openCamera(
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isCameraOpen) {
            onReady()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysis = CameraFrameStreamer.buildImageAnalysis(cameraExecutor)
                imageAnalysis = analysis

                val cameraSelector = selectCamera(provider) ?: run {
                    onError("未找到可用摄像头")
                    return@addListener
                }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analysis,
                )
                isCameraOpen = true
                Log.i(TAG, "摄像头已开启 (Preview + ImageAnalysis)")
                onReady()
            } catch (exception: Exception) {
                Log.e(TAG, "摄像头开启失败", exception)
                onError(exception.message ?: "摄像头开启失败")
            }
        }, mainExecutor)
    }

    fun startStreaming() {
        if (!isCameraOpen) {
            Log.w(TAG, "摄像头未就绪，无法推流")
            return
        }
        if (isStreaming) {
            return
        }
        isStreaming = true
        CameraFrameStreamer.start()
    }

    fun stopStreaming() {
        if (!isStreaming) {
            return
        }
        isStreaming = false
        CameraFrameStreamer.stop()
    }

    fun release() {
        stopStreaming()
        imageAnalysis = null
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        isCameraOpen = false
        cameraProvider = null
        Log.i(TAG, "摄像头已释放")
    }

    private fun selectCamera(provider: ProcessCameraProvider): CameraSelector? {
        val selectors = listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA,
        )
        for (selector in selectors) {
            if (provider.hasCamera(selector)) {
                return selector
            }
        }
        return null
    }
}
