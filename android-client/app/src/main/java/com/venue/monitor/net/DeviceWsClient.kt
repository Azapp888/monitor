package com.venue.monitor.net

import android.util.Log
import com.venue.monitor.MonitorApplication
import com.venue.monitor.data.PrefsManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 设备端 WebSocket 长连接客户端
 *
 * - 通过 device_code + device_token 鉴权连入 /ws?type=device
 * - 接收管理端经服务端转发的 audio_control 指令（start/stop）
 * - 提供回调机制把指令派发给上层（如 AudioRecorderService）
 * - 内置断线重连（指数退避，最大 30s）
 *
 * 由于 PrefsManager.serverUrl 是形如 "http://192.168.1.100:3000" 的 HTTP 地址，
 * WS 地址需转换为 ws:// 或 wss://。
 */
class DeviceWsClient(
    private val prefs: PrefsManager,
    private val listener: Listener
) {

    interface Listener {
        /** 收到 audio_control 指令 */
        fun onAudioControl(action: String, sessionId: String?, reason: String?)
        /** 连接成功 */
        fun onConnected()
        /** 连接断开 */
        fun onDisconnected(reason: String)
        /** 收到其他消息 */
        fun onOtherMessage(msg: JSONObject) {}
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // 长连接不超时
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // 心跳保活
            .build()
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var shouldRun = false
    @Volatile private var reconnectDelayMs = 2000L
    private val reconnectThread = Thread { reconnectLoop() }.apply {
        isDaemon = true
        name = "DeviceWsClient-Reconnect"
    }

    /** 启动连接 */
    fun start() {
        if (shouldRun) return
        shouldRun = true
        reconnectDelayMs = 2000L
        if (!reconnectThread.isAlive) reconnectThread.start()
    }

    /** 停止连接 */
    fun stop() {
        shouldRun = false
        try { ws?.close(1000, "client stop") } catch (_: Throwable) {}
        ws = null
    }

    /** 主动发送消息 */
    fun send(json: JSONObject): Boolean {
        val s = ws ?: return false
        return try { s.send(json.toString()) } catch (e: Throwable) { false }
    }

    private fun reconnectLoop() {
        while (shouldRun) {
            if (ws == null && prefs.isActivated) {
                try {
                    connectOnce()
                } catch (e: Throwable) {
                    Log.w(TAG, "connect failed: ${e.message}")
                }
            }
            // 等待下一次检查
            try {
                Thread.sleep(reconnectDelayMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun connectOnce() {
        val url = buildWsUrl() ?: run {
            Log.w(TAG, "server url invalid, skip")
            return
        }
        val req = Request.Builder().url(url).build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS connected: $url")
                ws = webSocket
                reconnectDelayMs = 2000L // 重置退避
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleText(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed: $code / $reason")
                ws = null
                listener.onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}")
                ws = null
                listener.onDisconnected(t.message ?: "failure")
                // 指数退避
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
            }
        })
        ws = socket
    }

    private fun handleText(text: String) {
        val msg = try { JSONObject(text) } catch (e: Throwable) { return }
        val type = msg.optString("type", "")
        when (type) {
            "audio_control" -> {
                val action = msg.optString("action", "")
                val sessionId = msg.optStringOrNull("session_id")
                val reason = msg.optStringOrNull("reason")
                listener.onAudioControl(action, sessionId, reason)
            }
            "connected" -> { /* 服务端确认连接 */ }
            else -> listener.onOtherMessage(msg)
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, null) else null

    /** 把 http(s):// 转成 ws(s):// 并附加 query */
    private fun buildWsUrl(): String? {
        val base = prefs.serverUrl
        if (base.isBlank()) return null
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.substring(8)
            base.startsWith("http://") -> "ws://" + base.substring(7)
            base.startsWith("ws://") || base.startsWith("wss://") -> base
            else -> "ws://$base"
        }
        val sep = if (wsBase.contains("?")) "&" else "?"
        return "$wsBase$sep" +
            "type=device&" +
            "device_code=${prefs.deviceCode}&" +
            "device_token=${prefs.deviceToken}"
    }

    companion object {
        private const val TAG = "DeviceWsClient"
    }
}
