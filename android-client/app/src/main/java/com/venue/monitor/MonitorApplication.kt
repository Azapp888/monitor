package com.venue.monitor

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.venue.monitor.data.PrefsManager
import com.venue.monitor.net.DeviceWsClient
import com.venue.monitor.service.MonitorForegroundService

class MonitorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PrefsManager(this)
        createNotificationChannels()
        // 注册 Activity 生命周期回调：每次回到前台时检查并拉起系统服务
        registerActivityLifecycleCallbacks(ServiceKeeper)
    }

    /**
     * 保活守卫：当用户操作 App（任意 Activity 进入前台）时，
     * 若系统服务开关已开启但服务未运行，则自动拉起服务。
     * 这样即便服务被系统杀死，用户下次打开 App 也会立即恢复。
     */
    private object ServiceKeeper : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (prefs.serviceEnabled && prefs.isActivated &&
                !MonitorState.serviceRunning.value
            ) {
                MonitorForegroundService.start(activity)
            }
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // 前台服务常驻通知渠道
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "系统服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持系统服务在后台运行"
                setShowBadge(false)
            }
            nm.createNotificationChannel(serviceChannel)

            // 上报结果通知渠道
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "通知提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "数据上报成功/失败提醒"
            }
            nm.createNotificationChannel(alertChannel)

            // 实时录音通知渠道
            val audioChannel = NotificationChannel(
                CHANNEL_AUDIO,
                "实时录音",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "管理端远程开启录音时使用"
                setShowBadge(false)
            }
            nm.createNotificationChannel(audioChannel)
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "monitor_service"
        const val CHANNEL_ALERT = "monitor_alert"
        const val CHANNEL_AUDIO = "monitor_audio"

        lateinit var instance: MonitorApplication
            private set
        lateinit var prefs: PrefsManager
            private set

        /** 设备端 WS 客户端（由 MonitorForegroundService 负责启动/停止） */
        @Volatile var wsClient: DeviceWsClient? = null
    }
}
