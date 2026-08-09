package com.venue.monitor.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnCanceledListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 位置信息提供者
 *
 * 策略（针对真机慢/无 GMS 的问题优化）：
 *  1. 优先用 LocationManager.getLastKnownLocation() 同步拿缓存 → 毫秒级
 *  2. 缓存有值且不超过 30 分钟直接返回
 *  3. 缓存不可用才调 FusedLocation.getCurrentLocation，配合 5 秒超时
 *  4. Fused 超时/失败继续返回 LocationManager 缓存（哪怕旧也比 null 好）
 *  5. 全部失败返回 null，但不阻塞上层上报流程
 */
class LocationProvider(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /** 位置缓存最大"新鲜度"：超过这个时间认为缓存太旧，需要尝试刷新（毫秒） */
    private val cacheMaxAgeMs = 30L * 60 * 1000

    /** 定位总超时：5 秒内拿不到就返回缓存或 null，避免卡住整个上报流程 */
    private val fetchTimeoutMs = 5000L

    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? {
        // 1. 先拿系统缓存（纯同步，毫秒级）
        val cached = getBestCachedLocation()
        val cacheAge = if (cached != null) System.currentTimeMillis() - cached.time else Long.MAX_VALUE
        if (cached != null && cacheAge <= cacheMaxAgeMs) {
            return cached
        }

        // 2. 缓存较旧/为空 → 尝试 Fused 刷新，5 秒超时
        val fresh = try {
            withTimeout(fetchTimeoutMs) {
                fetchFreshLocation()
            }
        } catch (_: Throwable) {
            null
        }

        // 3. 刷新拿到就用，否则退回缓存（哪怕旧），最后 null
        return fresh ?: cached
    }

    /**
     * 主动触发一次定位（不在乎结果），让系统缓存有值，后续采集都走秒回路径。
     * 在服务启动时调用一次即可。
     */
    @SuppressLint("MissingPermission")
    fun prefetchWarmUp() {
        try {
            // 用 PRIORITY_LOW_POWER，不强制 GPS，省点电，我们只是为了填缓存
            val task = fusedClient.getCurrentLocation(Priority.PRIORITY_LOW_POWER, null)
            task.addOnSuccessListener(OnSuccessListener<Location?> { /* 填缓存 */ })
            task.addOnFailureListener(OnFailureListener { /* 失败也无所谓，LocationManager 也可能有缓存 */ })
        } catch (_: Throwable) {
            // GMS 不存在或崩溃，忽略
        }
    }

    // ================= 内部方法 =================

    /** 用 FusedLocation 取一次实时定位，不自带超时，由外层 withTimeout 控制 */
    @SuppressLint("MissingPermission")
    private suspend fun fetchFreshLocation(): Location? =
        try {
            withContext(Dispatchers.Default) {
                suspendCancellableCoroutine { cont ->
                    try {
                        val task = fusedClient.getCurrentLocation(
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            null
                        )
                        task.addOnSuccessListener(OnSuccessListener<Location?> { loc ->
                            if (cont.isActive) cont.resume(loc)
                        })
                        task.addOnFailureListener(OnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        })
                        task.addOnCanceledListener(OnCanceledListener {
                            if (cont.isActive) cont.resume(null)
                        })
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } catch (se: SecurityException) {
            null
        } catch (t: Throwable) {
            null
        }

    /** 从 LocationManager 所有 Provider 里挑一个最准最新的缓存返回（同步） */
    @SuppressLint("MissingPermission")
    private fun getBestCachedLocation(): Location? {
        var best: Location? = null
        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                val loc: Location? = try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (loc == null) continue
                if (best == null || isBetterLocation(loc, best)) {
                    best = loc
                }
            }
        } catch (_: Throwable) { /* ignore */ }
        return best
    }

    /** 标准"更好位置"判断：优先更新时间，其次精度 */
    private fun isBetterLocation(location: Location, currentBest: Location): Boolean {
        val timeDelta = location.time - currentBest.time
        val isSignificantlyNewer = timeDelta > cacheMaxAgeMs
        val isSignificantlyOlder = timeDelta < -cacheMaxAgeMs
        val isNewer = timeDelta > 0

        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        val accuracyDelta = (location.accuracy - currentBest.accuracy).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200

        val isFromSameProvider = (location.provider == currentBest.provider)

        return when {
            isMoreAccurate -> true
            isNewer && !isLessAccurate -> true
            isNewer && !isSignificantlyLessAccurate && isFromSameProvider -> true
            else -> false
        }
    }
}
