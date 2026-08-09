package com.venue.monitor.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.util.LruCache

/**
 * 应用使用情况提供者 - 读取当前前台应用 + 后台运行应用列表
 *
 * 依赖系统"使用情况访问权限"（PACKAGE_USAGE_STATS），需用户在系统设置中手动授予。
 *
 * 返回数据包含：
 *  - 当前前台应用（含名称/版本/累计可见时长）
 *  - 后台运行应用列表（最近一段时间活跃过的应用，含名称/版本/最后活跃时间）
 *
 * 通过 LruCache 缓存 PackageManager 查询结果（应用名称/版本），避免重复 IPC 调用。
 */
class AppUsageProvider(private val context: Context) {

    /**
     * 单个应用的使用信息
     * @param packageName    包名
     * @param appLabel       应用显示名称（如"微信"），无法获取时为 null
     * @param versionName    版本名，无法获取时为 null
     * @param isForeground   是否为当前前台应用
     * @param isRunning      是否为运行中（前台或后台活跃过）
     * @param lastUsedAt     最近活跃时间（毫秒时间戳）
     * @param totalTimeVisible 累计可见时长（毫秒，由 UsageStatsManager 统计）
     */
    data class AppInfo(
        val packageName: String,
        val appLabel: String?,
        val versionName: String?,
        val isForeground: Boolean,
        val isRunning: Boolean,
        val lastUsedAt: Long,
        val totalTimeVisible: Long
    )

    /** 一次采集的完整结果 */
    data class UsageSnapshot(
        /** 当前前台应用（可能为 null，如刚解锁尚未切换） */
        val foreground: AppInfo?,
        /** 后台运行的应用列表（不含前台，按最近活跃时间倒序） */
        val background: List<AppInfo>,
        /** 采集时间（毫秒） */
        val capturedAt: Long
    ) {
        /** 全部应用（前台在前） */
        val all: List<AppInfo> get() = (foreground?.let { listOf(it) } ?: emptyList()) + background
    }

    /** 兼容旧调用的简化结果 */
    data class UsageInfo(
        val foregroundApp: String?,
        val recentApps: List<String>
    )

    /** PackageManager 信息缓存（包名 -> Pair(label, versionName)），避免重复 IPC */
    private val pkgInfoCache = object : LruCache<String, Pair<String?, String?>>(64) {}

    /** 是否已授予"使用情况访问权限" */
    fun hasUsageAccess(): Boolean {
        return try {
            val am = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = am.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** 兼容旧接口：返回简化的 UsageInfo */
    fun getUsageInfo(): UsageInfo {
        if (!hasUsageAccess()) return UsageInfo(null, emptyList())
        val snapshot = getSnapshot()
        return UsageInfo(
            foregroundApp = snapshot.foreground?.packageName,
            recentApps = snapshot.all.map { it.packageName }
        )
    }

    /**
     * 采集当前应用使用快照 - 包含前台应用 + 后台运行应用
     *
     * 算法：
     *  1. 用 queryEvents 分析最近 N 秒内事件序列，找出当前前台应用
     *  2. 用 queryUsageStats 取最近 24 小时活跃过的应用
     *  3. 仅保留"最近活跃过"的应用（lastTimeUsed 在最近 BACKGROUND_WINDOW_MS 内），
     *     作为"后台运行"应用列表
     *  4. 通过 PackageManager 解析应用显示名与版本号
     */
    fun getSnapshot(): UsageSnapshot {
        if (!hasUsageAccess()) return UsageSnapshot(null, emptyList(), System.currentTimeMillis())
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val pm = context.packageManager

            // 1. 找出当前前台应用：分析最近 60 秒的事件序列
            var foregroundPkg: String? = null
            val fgStack = mutableMapOf<String, Long>() // pkg -> 最近一次到前台时间
            val events = usm.queryEvents(now - 60_000L, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == context.packageName) continue // 忽略自身
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> fgStack[event.packageName] = event.timeStamp
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> fgStack.remove(event.packageName)
                }
            }
            foregroundPkg = fgStack.entries.maxByOrNull { it.value }?.key

            // 2. 取最近 24 小时活跃过的应用，按 lastTimeUsed 过滤为"仍在后台运行"的应用
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60 * 1000L, now)
            val backgroundThreshold = now - BACKGROUND_WINDOW_MS
            val allActive = stats
                .filter { it.packageName != context.packageName }
                .filter { it.lastTimeUsed >= backgroundThreshold }
                .sortedByDescending { it.lastTimeUsed }
                .take(MAX_APPS)

            // 3. 拆分前台/后台，解析应用名/版本
            var foreground: AppInfo? = null
            val backgroundList = mutableListOf<AppInfo>()

            for (stat in allActive) {
                val isFg = (stat.packageName == foregroundPkg)
                val (label, version) = resolvePkgInfo(stat.packageName, pm)
                val info = AppInfo(
                    packageName = stat.packageName,
                    appLabel = label,
                    versionName = version,
                    isForeground = isFg,
                    isRunning = true,
                    lastUsedAt = stat.lastTimeUsed,
                    totalTimeVisible = stat.totalTimeInForeground
                )
                if (isFg) {
                    foreground = info
                } else {
                    backgroundList.add(info)
                }
            }

            // 前台应用可能在最近 24h stats 中没有记录（刚刚切换且 stats 未刷新），
            // 但 events 中已识别为前台，则手动构造一条
            if (foreground == null && foregroundPkg != null) {
                val (label, version) = resolvePkgInfo(foregroundPkg, pm)
                foreground = AppInfo(
                    packageName = foregroundPkg,
                    appLabel = label,
                    versionName = version,
                    isForeground = true,
                    isRunning = true,
                    lastUsedAt = fgStack[foregroundPkg] ?: now,
                    totalTimeVisible = 0L
                )
            }

            UsageSnapshot(foreground, backgroundList, now)
        } catch (e: Exception) {
            UsageSnapshot(null, emptyList(), System.currentTimeMillis())
        }
    }

    /**
     * 解析应用的显示名与版本名 - 带 LruCache 缓存
     * 系统应用或已卸载的应用返回 (null, null)
     */
    private fun resolvePkgInfo(pkg: String, pm: PackageManager): Pair<String?, String?> {
        synchronized(pkgInfoCache) {
            pkgInfoCache.get(pkg)?.let { return it }
        }
        var label: String? = null
        var version: String? = null
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            label = pm.getApplicationLabel(ai).toString()
            // 过滤掉系统核心服务（无图标的）避免噪音
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                && ai.uid < Process.FIRST_APPLICATION_UID
            ) {
                label = null
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // 已卸载
        } catch (_: Throwable) { /* ignore */ }

        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, 0)
            version = info.versionName
        } catch (_: Throwable) { /* ignore */ }

        val pair = Pair(label, version)
        synchronized(pkgInfoCache) {
            pkgInfoCache.put(pkg, pair)
        }
        return pair
    }

    companion object {
        /** 视为"仍在后台运行"的时间窗口：最近 5 分钟活跃过的应用 */
        private const val BACKGROUND_WINDOW_MS = 5L * 60 * 1000
        /** 单次最多返回的应用数（含前台） */
        private const val MAX_APPS = 30
    }
}
