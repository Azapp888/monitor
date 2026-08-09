package com.venue.monitor.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.venue.monitor.MainActivity
import com.venue.monitor.api.ApiClient
import com.venue.monitor.data.PrefsManager
import com.venue.monitor.data.UpdateCheckResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 应用更新检查器
 *
 * 启动时调用 checkAndPrompt()：
 *  - 调用后端 /api/version/check?current_version_code=xxx
 *  - 若有新版本且 force_update=true：弹出强制更新对话框，用户必须下载安装，不能跳过
 *  - 若有新版本且 force_update=false：弹出普通更新对话框，允许"稍后"
 *  - 无新版本或检查失败：进入主界面（失败不阻塞启动）
 *
 * 下载流程：
 *  - 使用 OkHttp 下载到外部缓存目录
 *  - 通过 FileProvider 触发系统安装器
 *  - Android 8+ 需要用户授予"安装未知来源应用"权限
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /**
     * 检查更新并根据结果弹窗
     *
     * @param activity     当前 Activity（用于显示对话框）
     * @param scope        生命周期作用域
     * @param onNoUpdate   无需更新时回调（进入主界面）
     * @param onError      检查失败时回调（继续启动，不阻塞）
     */
    fun checkAndPrompt(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        onNoUpdate: () -> Unit,
        onError: (String) -> Unit
    ) {
        val currentCode = getCurrentVersionCode(activity)
        val prefs = PrefsManager(activity)

        scope.launch(Dispatchers.IO) {
            val result = try {
                ApiClient.rebuild()
                ApiClient.get().checkUpdate(currentCode)
            } catch (e: Exception) {
                Log.w(TAG, "版本检查失败: ${e.message}")
                return@launch withContext(Dispatchers.Main) {
                    onError("版本检查失败：${e.message ?: e.javaClass.simpleName}")
                }
            }

            // 检查失败（服务器返回 error）
            if (result.error != null) {
                return@launch withContext(Dispatchers.Main) {
                    onError(result.error!!)
                }
            }

            // 无新版本
            if (!result.hasUpdate || result.latest == null) {
                return@launch withContext(Dispatchers.Main) {
                    onNoUpdate()
                }
            }

            // 有新版本 - 主线程弹窗
            val latest = result.latest!!
            withContext(Dispatchers.Main) {
                if (latest.forceUpdate) {
                    showForceUpdateDialog(activity, scope, prefs, latest)
                } else {
                    showOptionalUpdateDialog(activity, scope, prefs, latest, onNoUpdate)
                }
            }
        }
    }

    /** 强制更新对话框 - 不可取消，必须下载安装 */
    private fun showForceUpdateDialog(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        prefs: PrefsManager,
        latest: com.venue.monitor.data.LatestVersion
    ) {
        val msg = buildString {
            append("发现新版本 v${latest.versionName}\n\n")
            if (latest.releaseNotes.isNotBlank()) {
                append("更新内容：\n${latest.releaseNotes}\n\n")
            }
            if (latest.fileSize > 0) {
                append("下载大小：${formatSize(latest.fileSize)}\n\n")
            }
            append("⚠️ 此为强制更新版本，必须更新后才能使用。")
        }
        AlertDialog.Builder(activity)
            .setTitle("应用更新")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("立即更新") { d, _ ->
                startDownload(activity, scope, prefs, latest, isForce = true)
            }
            .show()
    }

    /** 普通更新对话框 - 允许"稍后"，跳过后进入主界面 */
    private fun showOptionalUpdateDialog(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        prefs: PrefsManager,
        latest: com.venue.monitor.data.LatestVersion,
        onNoUpdate: () -> Unit
    ) {
        val msg = buildString {
            append("发现新版本 v${latest.versionName}\n\n")
            if (latest.releaseNotes.isNotBlank()) {
                append("更新内容：\n${latest.releaseNotes}")
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("立即更新") { d, _ ->
                startDownload(activity, scope, prefs, latest, isForce = false, onSkipped = onNoUpdate)
            }
            .setNegativeButton("稍后再说") { d, _ ->
                onNoUpdate()
            }
            .show()
    }

    /**
     * 启动 APK 下载 - 显示进度对话框
     * @param isForce 强制更新：下载失败后允许重试，不能跳过
     * @param onSkipped 非强制更新时，下载失败允许跳过
     */
    private fun startDownload(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        prefs: PrefsManager,
        latest: com.venue.monitor.data.LatestVersion,
        isForce: Boolean,
        onSkipped: (() -> Unit)? = null
    ) {
        // 构造下载进度对话框
        val view = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }
        val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val tvPercent = android.widget.TextView(activity).apply {
            text = "准备下载..."
            textSize = 12f
        }
        view.addView(progressBar)
        view.addView(tvPercent)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 v${latest.versionName}")
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()

        // 拼接完整下载 URL
        val baseUrl = prefs.serverUrl.trimEnd('/')
        val fullUrl = if (latest.downloadUrl.startsWith("http")) {
            latest.downloadUrl
        } else {
            baseUrl + latest.downloadUrl
        }

        scope.launch(Dispatchers.IO) {
            val ok = downloadApk(fullUrl, latest.versionName) { percent ->
                Handler(Looper.getMainLooper()).post {
                    progressBar.progress = percent
                    tvPercent.text = "$percent%  (已下载 ${formatSize(latest.fileSize * percent / 100)}/${formatSize(latest.fileSize)})"
                }
            }

            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (ok != null) {
                    // 下载成功，触发安装
                    installApk(activity, ok)
                    // 安装完成后系统会跳出安装界面，Activity 暂停
                    // 强制更新场景下：用户若取消安装，回到 Activity 时仍会被强制更新对话框拦截
                } else {
                    // 下载失败
                    if (isForce) {
                        AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage("下载失败，请检查网络后重试")
                            .setCancelable(false)
                            .setPositiveButton("重试") { _, _ ->
                                startDownload(activity, scope, prefs, latest, isForce = true)
                            }
                            .show()
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage("下载失败：${if (latest.fileSize > 0) "网络异常" else "未知错误"}")
                            .setPositiveButton("重试") { _, _ ->
                                startDownload(activity, scope, prefs, latest, isForce = false, onSkipped = onSkipped)
                            }
                            .setNegativeButton("跳过") { _, _ -> onSkipped?.invoke() }
                            .show()
                    }
                }
            }
        }
    }

    /**
     * 下载 APK 文件到外部缓存目录
     * @param url 完整下载 URL
     * @param versionName 版本名（用于命名文件）
     * @param onProgress 进度回调（0-100）
     * @return 下载成功返回 File，失败返回 null
     */
    private suspend fun downloadApk(
        url: String,
        versionName: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "下载失败 HTTP ${resp.code}")
                    return@withContext null
                }
                val total = resp.body?.contentLength() ?: -1L
                val file = File(contextCacheDir, "app_update_v${versionName}.apk")
                if (file.exists()) file.delete()

                FileOutputStream(file).use { out ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var downloaded = 0L
                    val ins = resp.body?.byteStream() ?: return@withContext null
                    while (true) {
                        bytesRead = ins.read(buffer)
                        if (bytesRead == -1) break
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            onProgress(percent)
                        }
                    }
                    out.flush()
                }
                onProgress(100)
                file
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载异常: ${e.message}")
            null
        }
    }

    /** 触发系统安装器 */
    private fun installApk(activity: Activity, apkFile: File) {
        try {
            // Android 8+ 需检查"安装未知来源应用"权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    // 引导用户授权安装权限
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    activity.startActivity(intent)
                    // 授权后用户需返回 App 重新点击"安装" - 这里先返回不直接安装
                    // 为简化体验，授权后用户回到 App 仍会被强制更新对话框拦截，再次点击会走到此处
                    return
                }
            }

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动安装器失败: ${e.message}")
            android.widget.Toast.makeText(
                activity,
                "启动安装失败：${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 获取当前应用版本号（versionCode，整数） */
    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: Exception) {
            1L
        }
    }

    /** 缓存目录：优先外部缓存，回退内部缓存 */
    private val contextCacheDir: File
        get() = try {
            val ext = Environment.getExternalStorageDirectory()
            File(ext, "Android/data/com.venue.monitor/cache").apply { mkdirs() }
        } catch (e: Exception) {
            File(System.getProperty("java.io.tmpdir"))
        }

    /** 格式化文件大小 */
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }
}
