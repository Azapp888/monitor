package com.venue.monitor.api

import com.venue.monitor.data.ApiResponse
import com.venue.monitor.data.AppUsageReportRequest
import com.venue.monitor.data.JoinRequestSubmit
import com.venue.monitor.data.JoinRequestSubmitResponse
import com.venue.monitor.data.JoinRequestStatusResponse
import com.venue.monitor.data.MonitorReport
import com.venue.monitor.data.PhoneCallReportRequest
import com.venue.monitor.data.RegisterRequest
import com.venue.monitor.data.RegisterResponse
import com.venue.monitor.data.ReportResponse
import com.venue.monitor.data.SmsReportRequest
import com.venue.monitor.data.UpdateCheckResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    /** 设备激活 */
    @POST("api/monitor/report/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    /** 上报数据（设备凭证放在 body 中，便于通用） */
    @POST("api/monitor/report")
    suspend fun report(@Body body: MonitorReportRequest): ReportResponse

    /** 心跳 */
    @POST("api/monitor/report/heartbeat")
    suspend fun heartbeat(@Body body: DeviceCredentialsBody): ApiResponse

    /** 下线 */
    @POST("api/monitor/report/offline")
    suspend fun offline(@Body body: DeviceCredentialsBody): ApiResponse

    /** 上报来电信息（来电号码、状态、时间） */
    @POST("api/monitor/report/phone-call")
    suspend fun reportPhoneCall(@Body body: PhoneCallReportRequest): ApiResponse

    /** 上传录音分片（multipart/form-data）
     *  Parts: device_code, device_token, session_id, chunk_index, duration_seconds, mime_type, file
     */
    @Multipart
    @POST("api/monitor/report/audio")
    suspend fun uploadAudio(
        @Part("device_code") deviceCode: RequestBody,
        @Part("device_token") deviceToken: RequestBody,
        @Part("session_id") sessionId: RequestBody,
        @Part("chunk_index") chunkIndex: RequestBody,
        @Part("duration_seconds") durationSeconds: RequestBody,
        @Part("mime_type") mimeType: RequestBody,
        @Part file: MultipartBody.Part
    ): ApiResponse

    /** 批量上报短信内容 */
    @POST("api/monitor/report/sms")
    suspend fun reportSms(@Body body: SmsReportRequest): ApiResponse

    /** 批量上报应用使用情况（前台+后台应用列表，实时变化时立即上报） */
    @POST("api/monitor/report/app-usage")
    suspend fun reportAppUsage(@Body body: AppUsageReportRequest): ApiResponse

    // ========== 加入申请相关接口（无需鉴权）==========

    /** 提交加入申请 - 设备端未激活时使用 */
    @POST("api/join-requests")
    suspend fun submitJoinRequest(@Body body: JoinRequestSubmit): JoinRequestSubmitResponse

    /** 按申请编号查询状态 - 设备端轮询使用 */
    @GET("api/join-requests/status/{code}")
    suspend fun getJoinRequestStatus(@Path("code") code: String): JoinRequestStatusResponse

    // ========== 版本检查接口（无需鉴权）==========

    /** 检查更新 - 启动时调用，传入当前版本号 */
    @GET("api/version/check")
    suspend fun checkUpdate(@Query("current_version_code") currentVersionCode: Long): UpdateCheckResponse
}

/** 带 device_code/device_token 的上报请求体 */
data class MonitorReportRequest(
    @com.google.gson.annotations.SerializedName("device_code") val deviceCode: String,
    @com.google.gson.annotations.SerializedName("device_token") val deviceToken: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val altitude: Double? = null,
    val speed: Double? = null,
    @com.google.gson.annotations.SerializedName("battery_level") val batteryLevel: Int? = null,
    @com.google.gson.annotations.SerializedName("battery_charging") val batteryCharging: Boolean? = null,
    @com.google.gson.annotations.SerializedName("battery_temperature") val batteryTemperature: Double? = null,
    @com.google.gson.annotations.SerializedName("network_type") val networkType: String? = null,
    @com.google.gson.annotations.SerializedName("network_strength") val networkStrength: Int? = null,
    @com.google.gson.annotations.SerializedName("wifi_ssid") val wifiSsid: String? = null,
    @com.google.gson.annotations.SerializedName("ip_address") val ipAddress: String? = null,
    @com.google.gson.annotations.SerializedName("foreground_app") val foregroundApp: String? = null,
    @com.google.gson.annotations.SerializedName("recent_apps") val recentApps: List<String>? = null,
    val extra: Map<String, Any?>? = null,
    @com.google.gson.annotations.SerializedName("recorded_at") val recordedAt: String
)

data class DeviceCredentialsBody(
    @com.google.gson.annotations.SerializedName("device_code") val deviceCode: String,
    @com.google.gson.annotations.SerializedName("device_token") val deviceToken: String
)
