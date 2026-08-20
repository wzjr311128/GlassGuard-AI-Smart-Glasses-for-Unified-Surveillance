package com.example.parking.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.parking.R
import com.example.parking.network.ParkingWebSocket
import com.example.parking.network.ServerConnectionStatus
import kotlinx.coroutines.launch

private const val TAG = "ScanScreen"

private enum class CameraStatus {
    Opening,
    Ready,
    Streaming,
    PermissionDenied,
    Error,
}

@Composable
fun ScanScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraController = remember { CameraStreamController(context, lifecycleOwner) }

    var isScanning by remember { mutableStateOf(false) }
    var cameraStatus by remember { mutableStateOf(CameraStatus.Opening) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cameraOpenRequested by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val serverConnection by ParkingWebSocket.connectionState.collectAsStateWithLifecycle()

    fun openCameraIfPermitted() {
        if (!hasCameraPermission) {
            cameraStatus = CameraStatus.PermissionDenied
            return
        }
        cameraStatus = CameraStatus.Opening
        cameraController.openCamera(
            onReady = {
                if (!isScanning) {
                    cameraStatus = CameraStatus.Ready
                }
            },
            onError = { message ->
                errorMessage = message
                cameraStatus = CameraStatus.Error
            },
        )
    }

    LaunchedEffect(Unit) {
        ParkingWebSocket.connect(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController.release()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            cameraStatus = CameraStatus.PermissionDenied
        }
    }

    fun stopScanning() {
        if (!isScanning) {
            return
        }
        ParkingWebSocket.sendScanEnd()
        cameraController.stopStreaming()
        isScanning = false
        cameraStatus = CameraStatus.Ready
        Log.i(TAG, "停止扫描推流")
    }

    fun startScanning() {
        if (cameraStatus != CameraStatus.Ready || isScanning) {
            return
        }
        scope.launch {
            if (serverConnection.status != ServerConnectionStatus.Connected) {
                val connectResult = ParkingWebSocket.connect(context)
                if (connectResult.isFailure) {
                    errorMessage = connectResult.exceptionOrNull()?.message ?: "WebSocket 连接失败"
                    return@launch
                }
            }

            errorMessage = null
            isScanning = true
            cameraStatus = CameraStatus.Streaming
            ParkingWebSocket.sendScanBegin()
            cameraController.startStreaming()
            Log.i(TAG, "开始扫描推流")
        }
    }

    val statusText = when (cameraStatus) {
        CameraStatus.Opening -> stringResource(R.string.camera_status_opening)
        CameraStatus.Ready -> stringResource(R.string.camera_status_active)
        CameraStatus.Streaming -> stringResource(R.string.camera_status_streaming)
        CameraStatus.PermissionDenied -> stringResource(R.string.camera_status_permission_denied)
        CameraStatus.Error -> stringResource(
            R.string.camera_status_error,
            errorMessage ?: "未知错误",
        )
    }

    val serverStatusText = when (serverConnection.status) {
        ServerConnectionStatus.Disconnected -> stringResource(R.string.server_status_disconnected)
        ServerConnectionStatus.Connecting -> stringResource(R.string.server_status_connecting)
        ServerConnectionStatus.Connected -> stringResource(R.string.server_status_connected)
        ServerConnectionStatus.Error -> stringResource(
            R.string.server_status_error,
            serverConnection.errorMessage ?: "未知错误",
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { cameraController.getPreviewView() },
            modifier = Modifier
                .fillMaxSize()
                .alpha(0f),
            update = { previewView ->
                if (hasCameraPermission && !cameraOpenRequested) {
                    cameraOpenRequested = true
                    previewView.post { openCameraIfPermitted() }
                }
            },
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.padding(top = 24.dp),
            )

            Text(
                text = serverStatusText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (cameraStatus == CameraStatus.Streaming) {
                Spacer(modifier = Modifier.height(8.dp))

                ScanFrameOverlay(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .weight(1f)
                        .padding(bottom = 12.dp),
                    isAnimating = true,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            OutlinedButton(
                onClick = {
                    when {
                        isScanning || cameraStatus == CameraStatus.Streaming ->
                            stopScanning()
                        cameraStatus == CameraStatus.Ready ->
                            startScanning()
                        cameraStatus == CameraStatus.PermissionDenied ->
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                enabled = when {
                    isScanning || cameraStatus == CameraStatus.Streaming -> true
                    cameraStatus == CameraStatus.Ready -> true
                    cameraStatus == CameraStatus.PermissionDenied -> true
                    else -> false
                },
                modifier = Modifier.padding(bottom = 48.dp),
                border = BorderStroke(1.dp, Color.White),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    containerColor = Color.Transparent,
                    disabledContentColor = Color.White.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    text = if (isScanning || cameraStatus == CameraStatus.Streaming) {
                        stringResource(R.string.stop_scan)
                    } else {
                        stringResource(R.string.start_scan)
                    },
                    color = Color.White,
                )
            }
        }
    }
}
