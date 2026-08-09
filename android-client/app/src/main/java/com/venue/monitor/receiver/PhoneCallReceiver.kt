package com.venue.monitor.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.venue.monitor.MonitorApplication
import com.venue.monitor.MonitorState
import com.venue.monitor.api.ApiClient
import com.venue.monitor.data.PhoneCallReportRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 来电状态监听 - 通过 android.intent.action.PHONE_STATE 广播触发
 *
 * 状态机：
 *   IDLE -> RINGING : 新来电开始响铃，上报 ringing
 *   RINGING -> OFFHOOK : 用户接听，上报 answered
 *   OFFHOOK -> IDLE : 通话结束，上报 ended（含 duration_seconds）
 *   RINGING -> IDLE : 未接来电，上报 missed
 *
 * 上报内容：来电号码（如可获取）、状态、各时间点
 * 注意：Android 12+ 静态注册的 PHONE_STATE 广播需 READ_PHONE_STATE 权限，
 *      且系统对静态 receiver 有限制，但电话状态广播属于豁免范围。
 */
class PhoneCallReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        // 权限检查
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val nowIso = nowIso8601()
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) return // 未激活则忽略

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // 新来电开始响铃
                lastRingingMs = System.currentTimeMillis()
                lastRingingNumber = incomingNumber
                lastCallAnswered = false
                report(
                    PhoneCallReportRequest(
                        deviceCode = prefs.deviceCode,
                        deviceToken = prefs.deviceToken,
                        phoneNumber = incomingNumber,
                        callState = "ringing",
                        ringStartedAt = nowIso,
                        extra = buildExtra("ringing", incomingNumber)
                    )
                )
                MonitorState.setLastReport("来电响铃：${incomingNumber ?: "未知号码"}")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // 接听（也可能是去电，但本 receiver 只在来电时触发响铃）
                if (lastRingingMs != null) {
                    lastCallAnswered = true
                    lastAnsweredIso = nowIso
                    report(
                        PhoneCallReportRequest(
                            deviceCode = prefs.deviceCode,
                            deviceToken = prefs.deviceToken,
                            phoneNumber = lastRingingNumber,
                            callState = "answered",
                            ringStartedAt = lastRingingMs?.let { msToIso(it) },
                            answeredAt = nowIso,
                            extra = buildExtra("answered", lastRingingNumber)
                        )
                    )
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // 通话结束或未接
                val ringMs = lastRingingMs
                if (ringMs != null) {
                    val durationSeconds = if (lastCallAnswered) {
                        ((System.currentTimeMillis() - ringMs) / 1000).toInt()
                    } else 0
                    val callState = if (lastCallAnswered) "ended" else "missed"
                    report(
                        PhoneCallReportRequest(
                            deviceCode = prefs.deviceCode,
                            deviceToken = prefs.deviceToken,
                            phoneNumber = lastRingingNumber,
                            callState = callState,
                            ringStartedAt = msToIso(ringMs),
                            answeredAt = if (lastCallAnswered) lastAnsweredIso else null,
                            endedAt = nowIso,
                            durationSeconds = durationSeconds,
                            extra = buildExtra(callState, lastRingingNumber)
                        )
                    )
                    MonitorState.setLastReport(
                        if (lastCallAnswered) "通话结束：${lastRingingNumber ?: "未知"} 时长 ${durationSeconds}s"
                        else "未接来电：${lastRingingNumber ?: "未知号码"}"
                    )
                }
                // 重置状态
                lastRingingMs = null
                lastRingingNumber = null
                lastCallAnswered = false
                lastAnsweredIso = null
            }
        }
    }

    private fun buildExtra(state: String, number: String?): Map<String, Any?> = mapOf(
        "device_model" to Build.MODEL,
        "state" to state,
        "has_number" to (number != null)
    )

    private fun report(req: PhoneCallReportRequest) {
        scope.launch {
            try {
                ApiClient.rebuild()
                ApiClient.get().reportPhoneCall(req)
                Log.i(TAG, "来电上报成功 state=${req.callState} number=${req.phoneNumber}")
            } catch (e: Throwable) {
                Log.w(TAG, "来电上报失败：${e.message}")
            }
        }
    }

    private fun nowIso8601(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun msToIso(ms: Long): String = nowIso8601().let {
        // 简化：直接用当时时间，ms 仅用于算 duration
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(Date(ms))
    }

    companion object {
        private const val TAG = "PhoneCallReceiver"

        // 静态状态：跨多次 onReceive 维护一次通话的状态机
        @Volatile private var lastRingingMs: Long? = null
        @Volatile private var lastRingingNumber: String? = null
        @Volatile private var lastCallAnswered: Boolean = false
        @Volatile private var lastAnsweredIso: String? = null
    }
}
