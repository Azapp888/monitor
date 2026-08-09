package com.venue.monitor.data

import com.google.gson.annotations.SerializedName

/** 设备激活/注册请求 */
data class RegisterRequest(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("device_token") val deviceToken: String,
    val model: String? = null,
    @SerializedName("os_version") val osVersion: String? = null,
    @SerializedName("app_version") val appVersion: String? = null
)

data class RegisterResponse(
    val ok: Boolean = false,
    @SerializedName("device_id") val deviceId: Long = 0,
    @SerializedName("interval_seconds") val intervalSeconds: Int = 600
)

/** 数据上报请求体 - 包含位置、电量、网络三类信息 */
data class MonitorReport(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val altitude: Double? = null,
    val speed: Double? = null,

    @SerializedName("battery_level") val batteryLevel: Int? = null,
    @SerializedName("battery_charging") val batteryCharging: Boolean? = null,
    @SerializedName("battery_temperature") val batteryTemperature: Double? = null,

    @SerializedName("network_type") val networkType: String? = null,
    @SerializedName("network_strength") val networkStrength: Int? = null,

    val extra: Map<String, Any?>? = null,
    @SerializedName("recorded_at") val recordedAt: String
)

data class ReportResponse(val ok: Boolean = false, @SerializedName("log_id") val logId: Long = 0)

/** 通用响应 */
data class ApiResponse(val ok: Boolean = false, val error: String? = null)

/** 来电上报请求体
 * call_state: ringing(响铃) / answered(接听) / ended(挂断) / missed(未接)
 */
data class PhoneCallReportRequest(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("phone_number") val phoneNumber: String? = null,
    @SerializedName("call_state") val callState: String,
    @SerializedName("ring_started_at") val ringStartedAt: String? = null,
    @SerializedName("answered_at") val answeredAt: String? = null,
    @SerializedName("ended_at") val endedAt: String? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    val extra: Map<String, Any?>? = null
)

/** 本地缓存的最新一次采集数据，用于 UI 展示 */
data class MonitorSnapshot(
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double?,
    val batteryLevel: Int?,
    val batteryCharging: Boolean?,
    val networkType: String?,
    val networkStrength: Int?,
    val recordedAt: String,
    val uploadStatus: String // success / failed / pending
)

/** 单条短信内容 */
data class SmsItem(
    @SerializedName("sms_id") val smsId: Long,
    val address: String?,         // 对方号码
    val body: String?,            // 短信正文
    val type: Int?,               // 1=收到 2=发出
    @SerializedName("person_name") val personName: String?, // 联系人姓名（如有）
    @SerializedName("received_at") val receivedAt: String?, // 收发时间 ISO8601
    val read: Int?,               // 0=未读 1=已读
    @SerializedName("service_center") val serviceCenter: String? // 短信中心号码
)

/** 短信批量上报请求体 */
data class SmsReportRequest(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("device_token") val deviceToken: String,
    val items: List<SmsItem>,
    @SerializedName("sync_type") val syncType: String, // "full"=全量同步 "incremental"=增量(新短信)
    @SerializedName("reported_at") val reportedAt: String
)

// ========== 加入申请相关数据模型 ==========

/** 申请通过后服务器返回的设备凭证 */
data class JoinRequestCredentials(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("device_token") val deviceToken: String
)

// ========== 应用使用上报相关数据模型 ==========

/** 单个应用使用信息 - 与 AppUsageProvider.AppInfo 对应，用于上报 */
data class AppUsageItem(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("app_label") val appLabel: String? = null,
    @SerializedName("version_name") val versionName: String? = null,
    @SerializedName("is_foreground") val isForeground: Boolean = false,
    @SerializedName("last_used_at") val lastUsedAt: Long = 0,
    @SerializedName("total_time_visible") val totalTimeVisible: Long = 0
)

/** 应用使用批量上报请求体 */
data class AppUsageReportRequest(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("event_type") val eventType: String = "snapshot", // snapshot | foreground_change
    @SerializedName("recorded_at") val recordedAt: String,
    val apps: List<AppUsageItem>
)

// ========== 应用版本检查相关数据模型 ==========

/** 版本检查响应 - 服务器返回是否有新版本及最新版本信息 */
data class UpdateCheckResponse(
    @SerializedName("has_update") val hasUpdate: Boolean = false,
    val latest: LatestVersion? = null,
    @SerializedName("current_version_code") val currentVersionCode: Long = 0,
    val message: String? = null,
    val error: String? = null
)

/** 最新版本信息 */
data class LatestVersion(
    @SerializedName("version_code") val versionCode: Long,
    @SerializedName("version_name") val versionName: String,
    @SerializedName("download_url") val downloadUrl: String,  // 相对路径，设备端拼接 serverUrl
    @SerializedName("file_size") val fileSize: Long = 0,
    val md5: String? = null,
    @SerializedName("release_notes") val releaseNotes: String = "",
    @SerializedName("force_update") val forceUpdate: Boolean = false
)

/** 设备端提交加入申请的请求体 - 自动收集设备信息一并提交 */
data class JoinRequestSubmit(
    @SerializedName("device_name") val deviceName: String? = null,
    val model: String? = null,
    @SerializedName("os_version") val osVersion: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("android_id") val androidId: String? = null,
    @SerializedName("phone_number") val phoneNumber: String? = null,
    @SerializedName("requester_name") val requesterName: String? = null,
    val contact: String? = null
)

/** 提交申请的响应 */
data class JoinRequestSubmitResponse(
    val ok: Boolean = false,
    @SerializedName("request_id") val requestId: Long = 0,
    @SerializedName("request_code") val requestCode: String = "",
    val status: String = "pending",
    val message: String? = null,
    val error: String? = null
)

/** 申请状态查询响应 - approved 时 credentials 字段携带设备凭证 */
data class JoinRequestStatusResponse(
    val ok: Boolean = false,
    @SerializedName("request_id") val requestId: Long = 0,
    @SerializedName("request_code") val requestCode: String = "",
    val status: String = "pending",  // pending / approved / rejected
    @SerializedName("review_comment") val reviewComment: String? = null,
    @SerializedName("reviewed_at") val reviewedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    val credentials: JoinRequestCredentials? = null,
    val error: String? = null
)
