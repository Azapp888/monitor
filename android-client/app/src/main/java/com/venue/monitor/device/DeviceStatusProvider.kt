package com.venue.monitor.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import java.net.NetworkInterface

/**
 * 设备状态提供者 - 一次性读取电量与网络状态
 * 注意：电量/网络的实时变化由 MonitorForegroundService 中的 BroadcastReceiver 监听
 */
class DeviceStatusProvider(private val context: Context) {

    data class BatteryInfo(
        val level: Int,            // 0-100
        val charging: Boolean,
        val temperature: Float,    // 摄氏度
        val technology: String?
    )

    data class NetworkInfo(
        val type: String,          // wifi / mobile / none / ethernet / bluetooth
        val strength: Int?,        // 0-4（信号格数，移动网络/移动数据时有效）
        val ssid: String?,
        val ipAddress: String?
    )

    /** 读取当前电量信息 */
    fun getBatteryInfo(): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return BatteryInfo(0, false, 0f, null)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f
        val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

        return BatteryInfo(pct, charging, temp, tech)
    }

    /** 读取当前网络信息 */
    @Suppress("DEPRECATION")
    fun getNetworkInfo(): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ip = getLocalIpAddress()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null) {
                return NetworkInfo("none", null, null, ip)
            }
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val strength = caps.signalStrength.takeIf { it in 0..100 }?.let { it / 25 }
                    val ssid = readWifiSsid()
                    NetworkInfo("wifi", strength, ssid, ip)
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    NetworkInfo("mobile", caps.signalStrength.takeIf { it in 0..100 }?.let { it / 25 }, null, ip)
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    NetworkInfo("ethernet", null, null, ip)
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> {
                    NetworkInfo("bluetooth", null, null, ip)
                }
                else -> NetworkInfo("other", null, null, ip)
            }
        } else {
            val info = cm.activeNetworkInfo
            if (info == null || !info.isConnected) {
                return NetworkInfo("none", null, null, ip)
            }
            val type = when (info.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "mobile"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                ConnectivityManager.TYPE_BLUETOOTH -> "bluetooth"
                else -> "other"
            }
            val ssid = if (type == "wifi") readWifiSsid() else null
            return NetworkInfo(type, null, ssid, ip)
        }
    }

    private fun readWifiSsid(): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.connectionInfo?.ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (e: Exception) {
            null
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress != null) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
