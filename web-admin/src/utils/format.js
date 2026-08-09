// 格式化工具函数

/**
 * 解析服务器时间字符串为 Date。
 * 数据库时间有两种格式：
 *   - recorded_at: ISO 带时区 "2026-07-31T04:18:44Z"
 *   - received_at: UTC 无时区标记 "2026-07-31 04:17:29"（SQLite datetime('now') 生成）
 * 无时区标记的一律按 UTC 解析（补 Z），否则会按本地时区误解 8 小时。
 */
function parseTime(time) {
  if (!time) return null
  let s = String(time).trim()
  // "2026-07-31 04:17:29" -> "2026-07-31T04:17:29Z"
  if (!/Z$|[+-]\d{2}:?\d{2}$/.test(s)) {
    s = s.replace(' ', 'T') + 'Z'
  }
  const d = new Date(s)
  return isNaN(d.getTime()) ? null : d
}

/** 网络类型转中文 */
export function networkTypeText(type) {
  const map = { wifi: 'Wi-Fi', mobile: '移动数据', ethernet: '以太网', bluetooth: '蓝牙', none: '无网络', other: '其他' }
  return map[type] || type || '未知'
}

/** 电池充电状态文本 */
export function batteryChargingText(charging) {
  return charging ? '充电中' : '未充电'
}

/** 电池电量颜色 */
export function batteryColor(level) {
  if (level == null) return ''
  if (level <= 20) return 'danger'
  if (level <= 50) return 'warning'
  return 'success'
}

/** 设备在线状态标签类型 */
export function statusTagType(status) {
  return status === 'online' ? 'success' : 'info'
}

export function statusText(status) {
  return status === 'online' ? '在线' : '离线'
}

/** 格式化时间为本地可读格式 */
export function formatTime(time) {
  const d = parseTime(time)
  if (!d) return time || '-'
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 相对时间（如 "3 分钟前"） */
export function fromNow(time) {
  if (!time) return '从未'
  const d = parseTime(time)
  if (!d) return time
  const diff = Date.now() - d.getTime()
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 7 * day) return `${Math.floor(diff / day)} 天前`
  return formatTime(time)
}
