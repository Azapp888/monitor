package com.venue.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局可观察状态 - 供 UI 层（MainActivity）订阅系统服务实时状态
 */
object MonitorState {

    /** 服务是否正在运行 */
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    /** 最后一次采集结果描述 */
    private val _lastReport = MutableStateFlow<String?>(null)
    val lastReport: StateFlow<String?> = _lastReport.asStateFlow()

    /** 当前电量百分比 */
    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    /** 是否在充电 */
    private val _batteryCharging = MutableStateFlow(false)
    val batteryCharging: StateFlow<Boolean> = _batteryCharging.asStateFlow()

    /** 当前网络类型 */
    private val _networkType = MutableStateFlow("unknown")
    val networkType: StateFlow<String> = _networkType.asStateFlow()

    /** 最后一次上报时间（毫秒） */
    private val _lastUploadAt = MutableStateFlow<Long?>(null)
    val lastUploadAt: StateFlow<Long?> = _lastUploadAt.asStateFlow()

    /** 上次上报是否成功 */
    private val _lastUploadOk = MutableStateFlow<Boolean?>(null)
    val lastUploadOk: StateFlow<Boolean?> = _lastUploadOk.asStateFlow()

    /** 设备端 WS 是否已连接（用于 UI 显示与录音指令通道状态） */
    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected.asStateFlow()

    fun setServiceRunning(running: Boolean) { _serviceRunning.value = running }
    fun setLastReport(text: String?) { _lastReport.value = text }
    fun setBattery(level: Int?, charging: Boolean) {
        _batteryLevel.value = level
        _batteryCharging.value = charging
    }
    fun setNetworkType(type: String) { _networkType.value = type }
    fun setUploadResult(success: Boolean, time: Long) {
        _lastUploadOk.value = success
        _lastUploadAt.value = time
    }
    fun setWsConnected(connected: Boolean) { _wsConnected.value = connected }
}
