package com.venue.monitor

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.venue.monitor.data.PrefsManager
import com.venue.monitor.databinding.ActivitySplashBinding
import com.venue.monitor.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 启动加载界面 - App 启动时执行三项核心检查，全部通过后才进入主界面：
 *   1. 网络连接 - 设备是否已连接到互联网
 *   2. 服务器响应 - 配置的服务器地址是否可达且 /health 返回正常
 *   3. 设备功能完整性 - 关键系统服务（位置/电量等）能否读取
 *
 * 任一关键检查失败时显示错误并允许重试；非致命问题（如位置未开启）允许用户"仍然继续"。
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRetry.setOnClickListener {
            startChecks()
        }
        binding.btnContinue.setOnClickListener {
            proceedToMain()
        }

        // 短暂延迟后开始检测，避免界面闪现
        handler.postDelayed({ startChecks() }, 300)
    }

    private fun startChecks() {
        // 重置 UI
        binding.tvSplashError.visibility = View.GONE
        binding.tvSplashError.text = ""
        binding.btnRetry.visibility = View.GONE
        binding.btnContinue.visibility = View.GONE
        setStepStatus(0, "检测中...", COLOR_GRAY)
        setStepStatus(1, "待检测", COLOR_GRAY)
        setStepStatus(2, "待检测", COLOR_GRAY)
        binding.progressSplash.progress = 0
        binding.tvSplashStep.text = "正在检测网络连接..."

        scope.launch {
            // ===== 1. 网络检查 =====
            val netOk = checkNetwork()
            if (netOk) {
                setStepStatus(0, "已连接", COLOR_GREEN)
            } else {
                setStepStatus(0, "无网络", COLOR_RED)
                fail("未检测到可用的网络连接，请检查 Wi-Fi 或移动数据后重试。")
                return@launch
            }
            binding.progressSplash.progress = 33
            binding.tvSplashStep.text = "正在连接服务器..."

            // ===== 2. 服务器响应检查 =====
            val serverResult = checkServer()
            if (serverResult.first) {
                setStepStatus(1, "响应正常", COLOR_GREEN)
            } else {
                setStepStatus(1, "无法访问", COLOR_RED)
                fail("服务器无响应：${serverResult.second}\n\n请确认服务器地址是否正确：\n${PrefsManager(this@SplashActivity).serverUrl}")
                return@launch
            }
            binding.progressSplash.progress = 66
            binding.tvSplashStep.text = "正在检查设备功能..."

            // ===== 3. 设备功能完整性检查 =====
            val deviceResult = checkDeviceFunctions()
            val warnings = deviceResult.first
            val fatal = deviceResult.second
            if (fatal.isEmpty()) {
                setStepStatus(2, "正常", COLOR_GREEN)
                binding.progressSplash.progress = 100
                binding.tvSplashStep.text = "检测完成"
                // 轻震动反馈
                vibrate(50)
                handler.postDelayed({ proceedToMain() }, 300)
            } else {
                setStepStatus(2, "存在警告", COLOR_ORANGE)
                binding.progressSplash.progress = 100
                binding.tvSplashStep.text = "检测完成（有警告）"
                // 显示警告 + 允许继续
                warn(fatal + warnings)
            }
        }
    }

    /** 检查网络连接 */
    private fun checkNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return info != null && info.isConnected
        }
    }

    /** 检查服务器响应 - 调用 /health 接口，5 秒超时 */
    private suspend fun checkServer(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val serverUrl = PrefsManager(this@SplashActivity).serverUrl
        if (serverUrl.isBlank()) return@withContext Pair(false, "服务器地址未配置")

        val healthUrl = (if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/") + "health"
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(healthUrl).get().build()

        try {
            val result = withTimeoutOrNull(6000L) {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withTimeoutOrNull null
                    val body = resp.body?.string() ?: return@withTimeoutOrNull null
                    JSONObject(body).optBoolean("ok", false)
                }
            } ?: return@withContext Pair(false, "请求超时")

            if (result) Pair(true, "")
            else Pair(false, "服务器返回异常")
        } catch (e: Exception) {
            Pair(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 检查设备功能完整性 - 检测关键系统服务是否可用
     * 返回 (warnings, fatal) - warnings 是非致命提示，fatal 表示致命缺失
     */
    private fun checkDeviceFunctions(): Pair<List<String>, List<String>> {
        val warnings = mutableListOf<String>()
        val fatal = mutableListOf<String>()

        // 1. 位置服务是否开启（warning - 不开启也能上报，只是位置为空）
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (lm != null) {
            val gps = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            val network = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            if (!gps && !network) {
                warnings.add("位置服务未开启，将无法上报位置信息。请到系统设置中开启位置。")
            }
        }

        // 2. 电量读取（一般总是可用）
        // 无需特别检查，BroadcastReceiver.ACTION_BATTERY_CHANGED 总能收到

        // 3. 应用版本与设备型号基本信息可读取（致命缺失 - 不应失败）
        try {
            packageManager.getPackageInfo(packageName, 0)
            // 设备型号
            val model = android.os.Build.MODEL
            if (model.isNullOrBlank()) {
                fatal.add("无法读取设备型号")
            }
        } catch (e: Exception) {
            fatal.add("应用信息读取失败：${e.message}")
        }

        return Pair(warnings, fatal)
    }

    private fun fail(message: String) {
        binding.tvSplashError.text = message
        binding.tvSplashError.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.VISIBLE
        binding.btnContinue.visibility = View.GONE
        binding.tvSplashStep.text = "检测失败"
        vibrate(200)
    }

    private fun warn(messages: List<String>) {
        binding.tvSplashError.text = messages.joinToString("\n")
        binding.tvSplashError.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.VISIBLE
        binding.btnContinue.visibility = View.VISIBLE
        binding.tvSplashStep.text = "检测完成（存在警告）"
    }

    private fun proceedToMain() {
        // 启动前检查更新：强制更新时不进入主界面，弹强制更新对话框
        UpdateChecker.checkAndPrompt(
            activity = this,
            scope = lifecycleScope,
            onNoUpdate = {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            },
            onError = { msg ->
                // 版本检查失败不阻塞，提示后进入主界面
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        )
    }

    private fun setStepStatus(index: Int, text: String, color: Int) {
        val tv = when (index) {
            0 -> binding.tvCheckNetworkStatus
            1 -> binding.tvCheckServerStatus
            else -> binding.tvCheckDeviceStatus
        }
        tv.text = text
        tv.setTextColor(color)
    }

    private fun vibrate(ms: Long) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.takeIf { it.hasVibrator() }?.vibrate(ms)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val COLOR_GREEN = 0xFF4CAF50.toInt()
        private const val COLOR_RED = 0xFFE74C3C.toInt()
        private const val COLOR_ORANGE = 0xFFFF9800.toInt()
        private const val COLOR_GRAY = 0xFF999999.toInt()
    }
}
