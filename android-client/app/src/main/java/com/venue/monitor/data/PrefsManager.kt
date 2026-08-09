package com.venue.monitor.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储 - 管理服务器地址、设备凭证等
 */
class PrefsManager(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 服务器基础地址，例如 http://192.168.1.100:3000 */
    var serverUrl: String
        get() = sp.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = sp.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    /** 设备编码（由管理端创建设备后生成） */
    var deviceCode: String
        get() = sp.getString(KEY_DEVICE_CODE, "") ?: ""
        set(value) = sp.edit().putString(KEY_DEVICE_CODE, value).apply()

    /** 设备 token（与 deviceCode 配套使用） */
    var deviceToken: String
        get() = sp.getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    /** 设备是否已激活 */
    val isActivated: Boolean
        get() = deviceCode.isNotEmpty() && deviceToken.isNotEmpty()

    /** 上报间隔（毫秒），默认 10 分钟 */
    var intervalMillis: Long
        get() = sp.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MS)
        set(value) = sp.edit().putLong(KEY_INTERVAL, value).apply()

    /** 系统服务是否已启动 */
    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 最后一次采集快照（JSON 字符串） */
    var lastSnapshot: String
        get() = sp.getString(KEY_LAST_SNAPSHOT, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_SNAPSHOT, value).apply()

    /** 最后一次上报成功时间 */
    var lastUploadTime: Long
        get() = sp.getLong(KEY_LAST_UPLOAD, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPLOAD, value).apply()

    /** 最后一次全量同步短信的 sms_id（用于增量同步，避免重复上传历史短信） */
    var lastSmsSyncId: Long
        get() = sp.getLong(KEY_LAST_SMS_SYNC_ID, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SMS_SYNC_ID, value).apply()

    /** 是否已执行过首次短信全量同步 */
    var smsFullSynced: Boolean
        get() = sp.getBoolean(KEY_SMS_FULL_SYNCED, false)
        set(value) = sp.edit().putBoolean(KEY_SMS_FULL_SYNCED, value).apply()

    /** 待审批的加入申请编号（提交后保存，App 重启后仍可继续轮询状态） */
    var pendingJoinRequestCode: String
        get() = sp.getString(KEY_PENDING_JOIN_CODE, "") ?: ""
        set(value) = sp.edit().putString(KEY_PENDING_JOIN_CODE, value).apply()

    companion object {
        private const val PREF_NAME = "venue_monitor_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_CODE = "device_code"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_INTERVAL = "interval_millis"
        private const val KEY_ENABLED = "service_enabled"
        private const val KEY_LAST_SNAPSHOT = "last_snapshot"
        private const val KEY_LAST_UPLOAD = "last_upload_time"
        private const val KEY_LAST_SMS_SYNC_ID = "last_sms_sync_id"
        private const val KEY_SMS_FULL_SYNCED = "sms_full_synced"
        private const val KEY_PENDING_JOIN_CODE = "pending_join_code"

        const val DEFAULT_SERVER_URL = "https://monitorserve.azayu.top"
        const val DEFAULT_INTERVAL_MS = 10L * 60 * 1000 // 10 分钟
    }
}
