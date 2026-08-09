package com.venue.monitor.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 自启动权限引导工具 - 针对国内手机厂商（小米/华为/OPPO/vivo/魅族等）的"自启动管理"
 *
 * 由于 Android 原生并未限制后台自启，但各厂商 ROM 都加入了自启管理白名单机制，
 * 用户需要手动将本应用加入白名单，才能在重启或被杀后自动恢复运行。
 *
 * 本工具根据设备厂商自动跳转到对应的自启动管理页面。
 */
object AutoStartHelper {

    /** 当前设备厂商 */
    val manufacturer: String
        get() = (Build.MANUFACTURER ?: "").lowercase()

    /** 是否为国内厂商（需引导自启动权限） */
    fun isChineseManufacturer(): Boolean {
        val m = manufacturer
        return m in setOf("xiaomi", "redmi", "honor", "huawei", "oppo", "vivo", "meizu", "samsung", "letv", "oneplus", "realme")
    }

    /** 厂商友好名称 */
    fun manufacturerName(): String = when (manufacturer) {
        "xiaomi", "redmi" -> "小米"
        "huawei", "honor" -> "华为"
        "oppo", "realme" -> "OPPO"
        "vivo" -> "vivo"
        "meizu" -> "魅族"
        "samsung" -> "三星"
        "oneplus" -> "一加"
        "letv" -> "乐视"
        else -> Build.MANUFACTURER ?: "未知"
    }

    /**
     * 跳转到厂商自启动管理页面
     * @return true 表示成功跳转；false 表示未识别到对应页面（可回退到应用详情）
     */
    fun jumpToAutoStartSettings(context: Context): Boolean {
        val intents = when (manufacturer) {
            "xiaomi", "redmi" -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            )
            "huawei", "honor" -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity"))
            )
            "oppo", "realme" -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            )
            "vivo" -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            )
            "meizu" -> listOf(
                Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")),
                Intent().setComponent(ComponentName("com.meizu.safe", ".permission.SmartBGActivity"))
            )
            "samsung" -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"))
            )
            "oneplus" -> listOf(
                Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            )
            "letv" -> listOf(
                Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"))
            )
            else -> emptyList()
        }

        for (intent in intents) {
            if (tryStartActivity(context, intent)) return true
        }

        // 回退：打开应用详情页
        return tryStartActivity(context, appDetailIntent(context))
    }

    /**
     * 跳转到电池优化设置页（请求加入白名单）
     */
    fun jumpToBatteryOptimizationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStartActivity(context, intent)
    }

    private fun appDetailIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            // 先校验是否存在可处理该 Intent 的 Activity
            val pm = context.packageManager
            if (intent.resolveActivity(pm) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
