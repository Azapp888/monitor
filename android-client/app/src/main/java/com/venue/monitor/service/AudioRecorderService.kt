package com.venue.monitor.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.venue.monitor.MainActivity
import com.venue.monitor.MonitorApplication
import com.venue.monitor.R
import com.venue.monitor.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 录音前台服务 - 接收 WS 下发的 audio_control 指令后启动
 *
 * 工作模式：分段录音 + 实时上传
 * - 每 [SEGMENT_DURATION_MS] 毫秒（默认 15 秒）切换一次录音文件
 * - 切换时立即把上一段上传到 /api/monitor/report/audio
 * - 服务停止时上传最后一段并通知服务端 audio_stopped
 *
 * 录音格式：AAC in M4A 容器（兼容性最好，浏览器可直接播放）
 *
 * 与 MonitorForegroundService 的协作：
 * - MonitorForegroundService 持有 DeviceWsClient，收到 audio_control 后通过 startService(Intent) 调起本服务
 * - 本服务通过 MonitorApplication.wsClient 向服务端反馈状态（audio_stopped/audio_error）
 */
class AudioRecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var currentStartMs: Long = 0L
    private var sessionId: String? = null
    private var chunkIndex: Int = 0
    @Volatile private var isRecording = false

    private val segmentSwitcher = Runnable { switchSegment() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sid = intent.getStringExtra(EXTRA_SESSION_ID) ?: "aud-${System.currentTimeMillis()}"
                startRecordingSession(sid)
            }
            ACTION_STOP -> {
                stopRecordingSession(reason = "service_stop")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecordingSession(sid: String) {
        if (isRecording) {
            // 已在录音，仅更新 session_id（罕见情况：管理端重复 start）
            sessionId = sid
            return
        }
        // 权限检查
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            sendWsMessage(JSONObject().apply {
                put("type", "audio_error")
                put("error", "RECORD_AUDIO 权限未授予")
            })
            stopSelf()
            return
        }

        sessionId = sid
        chunkIndex = 0
        startForeground(NOTIFICATION_ID, buildNotification("实时录音中…"))

        try {
            beginSegment()
            isRecording = true
            Log.i(TAG, "录音会话开始 session=$sid")
        } catch (e: Throwable) {
            Log.e(TAG, "start recording failed: ${e.message}", e)
            sendWsMessage(JSONObject().apply {
                put("type", "audio_error")
                put("error", "录音启动失败：${e.message}")
            })
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopRecordingSession(reason: String) {
        if (!isRecording) return
        isRecording = false
        handler.removeCallbacks(segmentSwitcher)
        // 完成当前段并上传
        val (file, durationMs) = finishCurrentSegment()
        file?.let { uploadSegment(it, durationMs, isLast = true) }

        sessionId?.let { sid ->
            sendWsMessage(JSONObject().apply {
                put("type", "audio_stopped")
                put("session_id", sid)
            })
        }
        stopForegroundCompat()
        Log.i(TAG, "录音会话停止 reason=$reason")
    }

    /** 开始一个新的录音分段 */
    private fun beginSegment() {
        val dir = File(filesDir, "audio_chunks").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val file = File(dir, "chunk_${ts}_$chunkIndex.m4a")
        currentFile = file
        currentStartMs = ts

        val rec = createRecorder(file)
        recorder = rec
        try {
            rec.prepare()
            rec.start()
        } catch (e: Throwable) {
            try { rec.release() } catch (_: Throwable) {}
            recorder = null
            throw e
        }
        // 安排切换下一段
        handler.postDelayed(segmentSwitcher, SEGMENT_DURATION_MS)
    }

    /** 切换到下一段：停止当前 recorder -> 上传 -> 开始新段 */
    private fun switchSegment() {
        if (!isRecording) return
        val (file, durationMs) = finishCurrentSegment()
        file?.let { uploadSegment(it, durationMs, isLast = false) }
        chunkIndex++
        try {
            beginSegment()
        } catch (e: Throwable) {
            Log.e(TAG, "switch segment failed: ${e.message}", e)
            sendWsMessage(JSONObject().apply {
                put("type", "audio_error")
                put("error", "录音分段失败：${e.message}")
            })
            stopRecordingSession(reason = "segment_error")
            stopSelf()
        }
    }

    /** 停止当前 recorder 并返回文件 + 时长，调用方负责上传 */
    private fun finishCurrentSegment(): Pair<File?, Long> {
        val rec = recorder ?: return null to 0L
        val file = currentFile
        val startMs = currentStartMs
        var durationMs = System.currentTimeMillis() - startMs
        try {
            // 在 stop 之前调用 stop，maxDuration 防止后续 onInfo 误触发
            rec.stop()
        } catch (e: Throwable) {
            // MediaRecorder.stop 在录制过短时会抛异常，文件可能无效
            Log.w(TAG, "recorder.stop failed: ${e.message}")
            durationMs = 0L
        }
        try { rec.release() } catch (_: Throwable) {}
        recorder = null
        currentFile = null
        // 若文件过小或无效则删除
        if (file != null && (!file.exists() || file.length() < MIN_VALID_FILE_BYTES)) {
            try { file.delete() } catch (_: Throwable) {}
            return null to 0L
        }
        return file to durationMs
    }

    private fun createRecorder(target: File): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(64_000) // 64 kbps
        rec.setAudioSamplingRate(44_100)
        rec.setOutputFile(target.absolutePath)
        // 设置最大时长作为兜底（防止 Handler 失效）
        rec.setMaxDuration((SEGMENT_DURATION_MS + 2000L).toInt())
        return rec
    }

    /** 上传录音分段到服务器 */
    private fun uploadSegment(file: File, durationMs: Long, isLast: Boolean) {
        val sid = sessionId ?: return
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) {
            try { file.delete() } catch (_: Throwable) {}
            return
        }
        val durationSeconds = if (durationMs > 0) durationMs / 1000.0 else 0.0
        val idx = chunkIndex

        scope.launch {
            try {
                ApiClient.rebuild()
                val api = ApiClient.get()
                val textPlain = "text/plain".toMediaType()
                api.uploadAudio(
                    deviceCode = prefs.deviceCode.toRequestBody(textPlain),
                    deviceToken = prefs.deviceToken.toRequestBody(textPlain),
                    sessionId = sid.toRequestBody(textPlain),
                    chunkIndex = idx.toString().toRequestBody(textPlain),
                    durationSeconds = durationSeconds.toString().toRequestBody(textPlain),
                    mimeType = "audio/mp4".toRequestBody(textPlain),
                    file = file.asRequestBody("audio/mp4".toMediaType()).let {
                        MultipartBody.Part.createFormData("file", file.name, it)
                    }
                )
                Log.i(TAG, "分段上传成功 idx=$idx size=${file.length()} duration=${durationSeconds}s last=$isLast")
            } catch (e: Throwable) {
                Log.w(TAG, "分段上传失败 idx=$idx: ${e.message}")
            } finally {
                // 上传完成后清理本地文件
                try { if (file.exists()) file.delete() } catch (_: Throwable) {}
            }
        }
    }

    /** 通过 WS 客户端向服务端发送消息 */
    private fun sendWsMessage(json: JSONObject) {
        try {
            MonitorApplication.wsClient?.send(json)
        } catch (_: Throwable) {}
    }

    private fun buildNotification(text: String): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )

        return NotificationCompat.Builder(this, MonitorApplication.CHANNEL_AUDIO)
            .setContentTitle("实时录音")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        // 兜底：异常销毁时也确保释放 recorder
        if (isRecording) {
            stopRecordingSession(reason = "onDestroy")
        } else {
            try { recorder?.release() } catch (_: Throwable) {}
        }
        handler.removeCallbacks(segmentSwitcher)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AudioRecorderService"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.venue.monitor.action.AUDIO_START"
        const val ACTION_STOP = "com.venue.monitor.action.AUDIO_STOP"
        const val EXTRA_SESSION_ID = "session_id"

        /** 单段录音时长（毫秒） */
        private const val SEGMENT_DURATION_MS = 15_000L

        /** 文件小于此字节数视为无效 */
        private const val MIN_VALID_FILE_BYTES = 1024L

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, AudioRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioRecorderService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun nowIso8601(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }
}
