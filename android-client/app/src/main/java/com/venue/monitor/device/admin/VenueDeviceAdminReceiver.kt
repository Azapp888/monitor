package com.venue.monitor.device.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * 设备管理员接收器 - 防卸载的第一道门槛
 *
 * 工作机制：
 * 1. 用户激活设备管理员后，系统不允许直接卸载本应用，必须先在系统设置中取消激活
 * 2. 当用户尝试取消激活时，[onDisableRequested] 返回的文案会作为警告展示给用户
 * 3. 若本应用同时是 Device Owner，则可通过 [DevicePolicyManager.setUninstallBlocked]
 *    实现真正的"硬性禁止卸载"（用户即使想卸载也无法操作）
 */
class VenueDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "设备管理员已激活")
        Toast.makeText(context, "设备管控已激活，应用将受到保护", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "设备管理员已禁用")
        Toast.makeText(context, "设备管控已关闭，应用可能被卸载", Toast.LENGTH_LONG).show()
    }

    /**
     * 当用户尝试在系统设置中取消激活设备管理员时，系统会展示此方法返回的文案。
     * 返回非空字符串可作为警告，提示用户取消激活的后果。
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "本应用为设备保护程序，取消激活后将无法保证后台持续运行，且应用可能被卸载。" +
            "确定要取消激活吗？"
    }

    companion object {
        private const val TAG = "VenueAdmin"

        /** 获取本应用设备管理员组件名 */
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, VenueDeviceAdminReceiver::class.java)
    }
}
