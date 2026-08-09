package com.venue.monitor.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.venue.monitor.device.admin.VenueDeviceAdminReceiver

/**
 * 设备策略管理器封装 - 统一管理 Device Admin / Device Owner 能力
 *
 * 三层防护体系：
 * 1. Device Admin（设备管理员）：用户主动激活，卸载前必须先取消激活，[onDisableRequested] 给出警告
 * 2. Device Owner（设备所有者）：通过 ADB 命令设置，可调用 [setUninstallBlocked] 硬性禁止卸载
 * 3. 应用锁（可选）：通过 [setApplicationHidden] 隐藏应用，配合 kiosk 模式
 */
class DevicePolicyHelper(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent: ComponentName =
        VenueDeviceAdminReceiver.getComponentName(context)

    /** 是否已激活设备管理员 */
    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    /** 当前应用是否为 Device Owner（设备所有者，最强权限） */
    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /** 获取激活设备管理员的 Intent，供 Activity startActivityForResult 使用 */
    fun getEnableAdminIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活设备管理员以启用防卸载和后台保活能力。"
            )
        }
    }

    /**
     * 设置应用是否允许卸载（仅 Device Owner 可用）
     *
     * 注意：成为 Device Owner 的前提条件之一是设备上没有已添加的 Google 账号，
     * 通常需要在设备首次开机或恢复出厂设置后，通过以下 ADB 命令设置：
     *
     *   adb shell dpm set-device-owner com.venue.monitor/.device.admin.VenueDeviceAdminReceiver
     *
     * 设置成功后即可调用本方法实现真正的"禁止卸载"。
     *
     * @param blocked true=禁止卸载，false=允许卸载
     * @return 是否设置成功
     */
    fun setUninstallBlocked(blocked: Boolean): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, blocked)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 设置应用是否允许被用户强制停止（仅 Device Owner 可用）
     * @param blocked true=禁止强制停止
     */
    fun setForceStopBlocked(blocked: Boolean): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setUninstallBlocked(adminComponent, context.packageName, blocked)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 启用全部可用防护：禁止卸载 + 禁止强制停止（需 Device Owner）
     * @return 返回是否为 Device Owner 并成功设置
     */
    fun enableFullProtection(): Boolean {
        if (!isDeviceOwner()) return false
        setUninstallBlocked(true)
        return true
    }

    /**
     * 获取当前防护等级描述，用于 UI 展示
     */
    fun getProtectionLevelText(): String {
        return when {
            isDeviceOwner() -> "设备所有者（最强防护，已禁止卸载）"
            isAdminActive() -> "设备管理员（卸载前需取消激活）"
            else -> "未激活防护"
        }
    }

    /**
     * 尝试移除 Device Owner（用于测试或解除绑定）
     * 仅在设备所有者模式下可用，且会清除所有设备所有者配置
     */
    fun clearDeviceOwner(): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.clearDeviceOwnerApp(context.packageName)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 跳转到应用详情页（用于引导用户查看权限/卸载入口）
     */
    fun openAppDetailSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
