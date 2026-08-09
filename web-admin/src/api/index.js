import axios from 'axios'
import { ElMessage } from 'element-plus'

const baseURL = import.meta.env.VITE_API_BASE || '/api'

const http = axios.create({
  baseURL,
  timeout: 15000
})

// 请求拦截：自动携带 token
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('vm_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：统一错误处理
http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const msg = error.response?.data?.error || error.message || '请求失败'
    if (error.response?.status === 401) {
      // 未授权，清除登录态并跳转登录页
      localStorage.removeItem('vm_token')
      localStorage.removeItem('vm_user')
      if (location.pathname !== '/login') {
        location.href = '/login'
      }
    } else {
      ElMessage.error(msg)
    }
    // 标记错误已被拦截器处理，调用方 catch 时避免重复提示
    error.__axiosHandled = true
    return Promise.reject(error)
  }
)

// ========== 鉴权 ==========
export const login = (username, password) =>
  http.post('/auth/login', { username, password })

export const changePassword = (oldPassword, newPassword) =>
  http.post('/auth/change-password', { oldPassword, newPassword })

export const getMe = () => http.get('/auth/me')

// ========== 设备管理 ==========
export const getDevices = (params) => http.get('/devices', { params })
export const getDevice = (id) => http.get(`/devices/${id}`)
export const createDevice = (data) => http.post('/devices', data)
export const updateDevice = (id, data) => http.put(`/devices/${id}`, data)
export const regenerateToken = (id) => http.post(`/devices/${id}/regenerate-token`)
export const deleteDevice = (id) => http.delete(`/devices/${id}`)

// ========== 监控数据 ==========
export const getMonitorList = (deviceId, params) => http.get(`/monitor/list/${deviceId}`, { params })
export const getLatest = () => http.get('/monitor/latest')
export const getStats = () => http.get('/monitor/stats/overview')

// ========== 来电记录 ==========
export const getPhoneCalls = (params) => http.get('/monitor/phone-calls', { params })
export const getPhoneCall = (id) => http.get(`/monitor/phone-calls/${id}`)
export const deletePhoneCall = (id) => http.delete(`/monitor/phone-calls/${id}`)

// ========== 短信记录 ==========
export const getSmsList = (params) => http.get('/monitor/sms', { params })
export const getSms = (id) => http.get(`/monitor/sms/${id}`)
export const deleteSms = (id) => http.delete(`/monitor/sms/${id}`)
export const deleteSmsBatch = (params) => http.post('/monitor/sms/batch-delete', params)

// ========== 应用使用 ==========
export const getAppUsageList = (params) => http.get('/monitor/app-usage', { params })
export const getCurrentApps = (deviceId) => http.get(`/monitor/app-usage/${deviceId}/current`)

// ========== 录音文件 ==========
export const getAudioList = (params) => http.get('/monitor/audio', { params })
export const getAudio = (id) => http.get(`/monitor/audio/${id}`)
export const deleteAudio = (id) => http.delete(`/monitor/audio/${id}`)
/**
 * 拉取录音二进制并返回浏览器可播放的 Object URL。
 * 因 <audio> 标签无法携带 Authorization 头，需通过 axios 取 blob 再生成 URL。
 * 调用方负责在不用时 URL.revokeObjectURL()。
 */
export async function fetchAudioBlobUrl(id) {
  const resp = await http.get(`/monitor/audio/${id}/download`, { responseType: 'blob' })
  return URL.createObjectURL(resp)
}

// ========== 加入申请与审批 ==========
export const getJoinRequests = (params) => http.get('/join-requests', { params })
export const getJoinRequest = (id) => http.get(`/join-requests/${id}`)
export const approveJoinRequest = (id, review_comment) =>
  http.post(`/join-requests/${id}/approve`, { review_comment })
export const rejectJoinRequest = (id, review_comment) =>
  http.post(`/join-requests/${id}/reject`, { review_comment })

// ========== 操作日志 ==========
export const getOperationLogs = (params) => http.get('/join-requests/logs/list', { params })

// ========== 应用版本管理 ==========
export const getReleases = () => http.get('/version')
export const uploadRelease = (formData, onUploadProgress) =>
  http.post('/version/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 5 * 60 * 1000, // 5 分钟超时（APK 文件较大）
    onUploadProgress
  })
export const activateRelease = (id) => http.put(`/version/${id}/activate`)
export const setReleaseForce = (id, force_update) =>
  http.put(`/version/${id}/force`, { force_update })
export const deleteRelease = (id) => http.delete(`/version/${id}`)

export default http
