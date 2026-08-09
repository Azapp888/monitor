package com.venue.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.venue.monitor.device.AutoStartHelper
import com.venue.monitor.device.DevicePolicyHelper
import com.venue.monitor.api.ApiClient
import com.venue.monitor.api.DeviceCredentialsBody
import com.venue.monitor.data.RegisterRequest
import com.venue.monitor.data.PrefsManager
import com.venue.monitor.databinding.ActivityConfigBinding
import com.venue.monitor.service.MonitorForegroundService
import com.venue.monitor.work.MonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 系统服务配置页 - 隐藏入口（在主界面标题连续点击 7 次进入）
 *
 * 承载原 MainActivity 的全部系统服务配置逻辑：
 *  - 服务器地址 / 设备编码 / 设备令牌
 *  - 激活、启动/停止系统服务
 *  - 设备管理员激活、自启动、电池优化白名单
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (granted) maybeRequestBackgroundLocation()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不论结果都继续 */ }

    private lateinit var devicePolicy: DevicePolicyHelper

    private val adminActivationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateProtectionUI()
        if (devicePolicy.isAdminActive()) toast("设备管理员已激活")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        devicePolicy = DevicePolicyHelper(this)
        loadConfigToUI()
        observeState()
        setupListeners()
        requestEssentialPermissions()
        scheduleBackupWorker()
        updateProtectionUI()
        updateHideIconSwitch()
    }

    /** 根据 LauncherAlias 当前启用状态刷新开关 UI */
    private fun updateHideIconSwitch() {
        val pm = packageManager
        val enabled = try {
            val state = pm.getComponentEnabledSetting(
                ComponentName(this, "${packageName}.LauncherAlias")
            )
            // 默认或启用状态都算"显示中"
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (_: Exception) { true }
        binding.switchHideIcon.setOnCheckedChangeListener(null)
        binding.switchHideIcon.isChecked = !enabled
        binding.switchHideIcon.setOnCheckedChangeListener { _, isChecked ->
            setLauncherHidden(isChecked)
        }
    }

    /**
     * 隐藏或显示桌面图标
     * @param hidden true=隐藏，false=显示
     */
    private fun setLauncherHidden(hidden: Boolean) {
        try {
            val component = ComponentName(this, "${packageName}.LauncherAlias")
            val newState = if (hidden)
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            packageManager.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
            toast(if (hidden) "桌面图标已隐藏，请通过通知栏进入" else "桌面图标已显示")
        } catch (e: Exception) {
            toast("操作失败：${e.message}")
            // 回滚 UI
            updateHideIconSwitch()
        }
    }

    private fun loadConfigToUI() {
        val prefs = MonitorApplication.prefs
        binding.etServerUrl.setText(prefs.serverUrl)
        binding.etDeviceCode.setText(prefs.deviceCode)
        binding.etDeviceToken.setText(prefs.deviceToken)
        // 毫秒转秒显示，默认 600 秒
        val seconds = (prefs.intervalMillis / 1000).toInt()
        binding.etIntervalSeconds.setText(seconds.toString())
        updateActivationUI()
        updateServiceSwitchUI()
    }

    private fun observeState() {
        lifecycleScope.launch {
            MonitorState.serviceRunning.collectLatest { updateServiceSwitchUI() }
        }
        lifecycleScope.launch {
            MonitorState.lastReport.collectLatest { binding.tvLastReport.text = it ?: "暂无上报记录" }
        }
        lifecycleScope.launch {
            MonitorState.batteryLevel.collectLatest { level ->
                val charging = MonitorState.batteryCharging.value
                binding.tvBattery.text = if (level != null) {
                    "$level% ${if (charging) "充电中" else "未充电"}"
                } else "未知"
            }
        }
        lifecycleScope.launch {
            MonitorState.batteryCharging.collectLatest {
                val level = MonitorState.batteryLevel.value
                binding.tvBattery.text = if (level != null) {
                    "$level% ${if (it) "充电中" else "未充电"}"
                } else "未知"
            }
        }
        lifecycleScope.launch {
            MonitorState.networkType.collectLatest { type ->
                binding.tvNetwork.text = networkTypeText(type)
            }
        }
    }

    private fun setupListeners() {
        binding.btnSaveConfig.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val code = binding.etDeviceCode.text.toString().trim()
            val token = binding.etDeviceToken.text.toString().trim()
            val intervalStr = binding.etIntervalSeconds.text.toString().trim()
            if (url.isBlank()) {
                binding.etServerUrl.error = "请输入服务器地址"
                return@setOnClickListener
            }
            val intervalSeconds = intervalStr.toIntOrNull()
            if (intervalStr.isBlank() || intervalSeconds == null || intervalSeconds < 30) {
                binding.etIntervalSeconds.error = "请输入不小于 30 秒的数字"
                return@setOnClickListener
            }
            val prefs = MonitorApplication.prefs
            prefs.serverUrl = url
            prefs.deviceCode = code
            prefs.deviceToken = token
            prefs.intervalMillis = intervalSeconds.toLong() * 1000L
            ApiClient.rebuild()
            updateActivationUI()
            toast("配置已保存，上报间隔 $intervalSeconds 秒")
        }

        binding.btnActivate.setOnClickListener {
            val prefs = MonitorApplication.prefs
            if (!prefs.isActivated) {
                toast("请先填写设备编码与设备令牌并保存")
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ApiClient.rebuild()
                    val resp = ApiClient.get().register(
                        RegisterRequest(
                            deviceCode = prefs.deviceCode,
                            deviceToken = prefs.deviceToken,
                            model = android.os.Build.MODEL,
                            osVersion = android.os.Build.VERSION.RELEASE,
                            appVersion = packageManager.getPackageInfo(packageName, 0).versionName
                        )
                    )
                    withContext(Dispatchers.Main) {
                        if (resp.ok) {
                            prefs.intervalMillis = resp.intervalSeconds * 1000L
                            toast("设备激活成功，上报间隔 ${resp.intervalSeconds} 秒")
                            updateActivationUI()
                        } else {
                            toast("激活失败")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("激活失败：${e.message}") }
                }
            }
        }

        binding.switchService.setOnClickListener {
            if (binding.switchService.isChecked) {
                if (!MonitorApplication.prefs.isActivated) {
                    binding.switchService.isChecked = false
                    toast("请先激活设备")
                    return@setOnClickListener
                }
                if (!hasLocationPermission()) {
                    binding.switchService.isChecked = false
                    requestEssentialPermissions()
                    return@setOnClickListener
                }
                MonitorApplication.prefs.serviceEnabled = true
                MonitorForegroundService.start(this)
            } else {
                MonitorApplication.prefs.serviceEnabled = false
                MonitorForegroundService.stop(this)
            }
        }

        binding.btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.btnActivateAdmin.setOnClickListener {
            if (devicePolicy.isAdminActive()) {
                toast("设备管理员已激活，如需取消请到系统设置中操作")
                try {
                    startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {}
            } else {
                adminActivationLauncher.launch(devicePolicy.getEnableAdminIntent())
            }
        }

        binding.btnAutoStart.setOnClickListener {
            val ok = AutoStartHelper.jumpToAutoStartSettings(this)
            if (!ok) toast("未识别到自启动管理页面，请手动在系统设置中允许本应用自启动")
        }
    }

    private fun updateProtectionUI() {
        val level = devicePolicy.getProtectionLevelText()
        binding.tvProtectionLevel.text = level
        binding.tvProtectionLevel.setTextColor(
            if (devicePolicy.isDeviceOwner() || devicePolicy.isAdminActive())
                getColorValue(R.color.online_green)
            else getColorValue(R.color.offline_gray)
        )
        binding.btnActivateAdmin.text = if (devicePolicy.isAdminActive()) {
            "设备管理员已激活（点击管理）"
        } else {
            "激活设备管理员（防卸载）"
        }

        if (devicePolicy.isDeviceOwner()) {
            devicePolicy.enableFullProtection()
            binding.tvDeviceOwnerTip.visibility = View.GONE
        } else {
            binding.tvDeviceOwnerTip.visibility =
                if (devicePolicy.isAdminActive()) View.VISIBLE else View.GONE
        }

        binding.btnAutoStart.visibility =
            if (AutoStartHelper.isChineseManufacturer()) View.VISIBLE else View.GONE
    }

    private fun getColorValue(resId: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(resId)
        else @Suppress("DEPRECATION") resources.getColor(resId)

    override fun onResume() {
        super.onResume()
        if (::devicePolicy.isInitialized) updateProtectionUI()
    }

    private fun updateActivationUI() {
        val activated = MonitorApplication.prefs.isActivated
        binding.tvActivation.text = if (activated) "已激活" else "未激活"
        binding.btnActivate.isEnabled = activated
        binding.btnActivate.text = if (activated) "重新激活" else "激活"
    }

    private fun updateServiceSwitchUI() {
        binding.switchService.isChecked = MonitorState.serviceRunning.value
        binding.tvServiceStatus.text = if (MonitorState.serviceRunning.value) "运行中" else "已停止"
    }

    private fun requestEssentialPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        locationPermissionLauncher.launch(perms.toTypedArray())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasLocationPermission() &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            toast("已在电池优化白名单中")
        }
    }

    private fun scheduleBackupWorker() {
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(
            MonitorWorker.BACKUP_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MonitorWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun networkTypeText(type: String): String = when (type) {
        "wifi" -> "Wi-Fi"
        "mobile" -> "移动数据"
        "ethernet" -> "以太网"
        "bluetooth" -> "蓝牙"
        "none" -> "无网络"
        else -> type
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
