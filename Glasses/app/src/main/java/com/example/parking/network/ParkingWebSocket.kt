package com.example.parking.network

import android.content.Context
import android.util.Log
import com.example.parking.config.AddressConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class ServerConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

data class ServerConnectionState(
    val status: ServerConnectionStatus = ServerConnectionStatus.Disconnected,
    val errorMessage: String? = null,
)

object ParkingWebSocket {
    private const val TAG = "ParkingWebSocket"
    private const val CONNECT_TIMEOUT_MS = 10_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _connectionState = MutableStateFlow(ServerConnectionState())
    val connectionState: StateFlow<ServerConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isOpen: Boolean = false

    private val sentFrameCount = AtomicLong(0L)

    private fun updateConnectionState(status: ServerConnectionStatus, errorMessage: String? = null) {
        _connectionState.value = ServerConnectionState(status, errorMessage)
    }

    suspend fun connect(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (isOpen) {
            updateConnectionState(ServerConnectionStatus.Connected)
            return@withContext Result.success(Unit)
        }

        updateConnectionState(ServerConnectionStatus.Connecting)

        val url = AddressConfig.getWebSocketUrl(context)
        Log.i(TAG, "连接 WebSocket: $url")
        val deferred = CompletableDeferred<Result<Unit>>()
        val request = Request.Builder()
            .url(url)
            .build()

        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isOpen = true
                    updateConnectionState(ServerConnectionStatus.Connected)
                    Log.i(TAG, "WebSocket 已连接")
                    webSocket.send(
                        JSONObject()
                            .put("type", "hello")
                            .put("role", "glasses")
                            .toString(),
                    )
                    deferred.complete(Result.success(Unit))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 连接失败", t)
                    isOpen = false
                    this@ParkingWebSocket.webSocket = null
                    updateConnectionState(
                        ServerConnectionStatus.Error,
                        t.message ?: "连接失败",
                    )
                    if (!deferred.isCompleted) {
                        deferred.complete(Result.failure(t))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket 已关闭 code=$code reason=$reason")
                    isOpen = false
                    this@ParkingWebSocket.webSocket = null
                    updateConnectionState(ServerConnectionStatus.Disconnected)
                }
            },
        )
        webSocket = socket

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (exception: Exception) {
            disconnect()
            updateConnectionState(
                ServerConnectionStatus.Error,
                exception.message ?: "连接超时",
            )
            Result.failure(exception)
        }
    }

    fun sendScanBegin(): Boolean {
        sentFrameCount.set(0L)
        return sendTextMessage(
            JSONObject()
                .put("type", "scan_begin")
                .put("fps", 15)
                .put("resolution", "480p"),
        )
    }

    fun sendScanEnd(): Boolean {
        return sendTextMessage(
            JSONObject()
                .put("type", "scan_end")
                .put("frames_sent", sentFrameCount.get()),
        )
    }

    fun sendJpegFrame(jpegBytes: ByteArray): Boolean {
        if (!isOpen || jpegBytes.isEmpty()) {
            return false
        }
        val sent = webSocket?.send(jpegBytes.toByteString(0, jpegBytes.size)) ?: false
        if (sent) {
            val count = sentFrameCount.incrementAndGet()
            if (count == 1L || count % 15L == 0L) {
                Log.d(TAG, "JPEG 已发送: ${jpegBytes.size} bytes (累计 $count 帧)")
            }
        }
        return sent
    }

    private fun sendTextMessage(payload: JSONObject): Boolean {
        return webSocket?.send(payload.toString()) ?: false
    }

    fun disconnect() {
        webSocket?.close(1000, "app closed")
        webSocket = null
        isOpen = false
        updateConnectionState(ServerConnectionStatus.Disconnected)
    }
}
