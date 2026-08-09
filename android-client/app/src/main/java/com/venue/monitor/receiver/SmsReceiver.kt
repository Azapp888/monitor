package com.venue.monitor.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import com.venue.monitor.MonitorApplication
import com.venue.monitor.MonitorState
import com.venue.monitor.api.ApiClient
import com.venue.monitor.data.SmsItem
import com.venue.monitor.data.SmsReportRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 新短信接收广播 - 实时捕获新收到的短信并立即上报服务器
 *
 * 触发条件：
 *   - 静态注册：android.provider.Telephony.SMS_RECEIVED
 *   - 权限要求：android.permission.RECEIVE_SMS
 *
 * 注意：Android 10+ 默认短信应用才能看到 SMS_DELIVER，普通 app 用 SMS_RECEIVED 即可拿到内容。
 *       对于发出的短信，SMS_RECEIVED 不触发，由定时全量同步兜底。
 */
class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // 1. 权限检查
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) return
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) return

        // 2. 从 intent 解析出完整 SmsMessage 列表（长短信会被拆成多段）
        val messages = extractSmsMessages(intent)
        if (messages.isEmpty()) return

        // 3. 同一条长短信的各段共享同一个时间戳和号码，合并正文
        val grouped = messages.groupBy { Pair(it.originatingAddress, it.timestampMillis) }
        val items = mutableListOf<SmsItem>()
        for ((key, segs) in grouped) {
            val address = key.first
            val timestamp = key.second
            // 按 index 顺序拼接正文
            val sortedSegs = segs.sortedBy {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.indexOnIcc else 0
            }
            val body = sortedSegs.joinToString(separator = "") { it.messageBody ?: "" }

            items += SmsItem(
                smsId = timestamp, // 实时场景用时间戳作为临时 id，服务端会去重
                address = address,
                body = body,
                type = 1, // 1 = 收到
                personName = null,
                receivedAt = msToIso(timestamp),
                read = 0,
                serviceCenter = segs.firstOrNull()?.serviceCenterAddress
            )
        }

        if (items.isEmpty()) return

        // 4. 上报服务器（增量标记）
        MonitorState.setLastReport("新短信：来自 ${items.first().address ?: "未知号码"}")
        reportIncremental(items)
    }

    /** 从 intent 中提取 SmsMessage 列表（兼容旧的 pdu 数组方式） */
    @Suppress("DEPRECATION")
    private fun extractSmsMessages(intent: Intent): List<SmsMessage> {
        return try {
            // Android 19+ 官方 API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)?.toList().orEmpty()
            } else {
                // 旧版兼容：手动从 pdus 解析
                val pdus = intent.getSerializableExtra("pdus") as? Array<*> ?: return emptyList()
                val format = intent.getStringExtra("format")
                pdus.mapNotNull { pdu ->
                    try {
                        val bytes = pdu as? ByteArray ?: return@mapNotNull null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            SmsMessage.createFromPdu(bytes, format)
                        } else {
                            SmsMessage.createFromPdu(bytes)
                        }
                    } catch (_: Throwable) { null }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "解析短信 PDU 失败: ${e.message}")
            emptyList()
        }
    }

    /** 增量上报新短信 */
    private fun reportIncremental(items: List<SmsItem>) {
        val prefs = MonitorApplication.prefs
        scope.launch {
            try {
                ApiClient.rebuild()
                val resp = ApiClient.get().reportSms(
                    SmsReportRequest(
                        deviceCode = prefs.deviceCode,
                        deviceToken = prefs.deviceToken,
                        items = items,
                        syncType = "incremental",
                        reportedAt = nowIso8601()
                    )
                )
                if (resp.ok) {
                    Log.i(TAG, "新短信增量上报成功 count=${items.size}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "新短信增量上报失败: ${e.message}")
            }
        }
    }

    private fun nowIso8601(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun msToIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
