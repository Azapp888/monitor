package com.venue.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.venue.monitor.MonitorApplication
import com.venue.monitor.service.MonitorForegroundService

/**
 * 开机自启 - 在系统启动完成后自动启动系统服务（仅当用户此前已开启服务时）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (MonitorApplication.prefs.serviceEnabled &&
                    MonitorApplication.prefs.isActivated) {
                    MonitorForegroundService.start(context)
                }
            }
        }
    }
}
