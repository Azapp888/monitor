// WebSocket 客户端 - 接收后端实时推送的监控数据，并支持向服务端发送指令
// 支持多订阅者（Layout 全局通知 + DeviceDetail 页面级处理）
import { ElNotification } from 'element-plus'

let ws = null
let reconnectTimer = null
let currentToken = null
// 订阅者列表：每个订阅者接收所有 WS 消息
const subscribers = new Set()

/** 订阅 WS 消息，返回取消订阅函数 */
export function subscribeWsMessages(handler) {
  subscribers.add(handler)
  return () => subscribers.delete(handler)
}

export function connectWebSocket(token, onMessage) {
  if (ws && ws.readyState === WebSocket.OPEN) return
  if (onMessage) subscribers.add(onMessage)
  currentToken = token

  // 优先用环境变量配置的 WS 地址（生产部署跨域时使用），否则走当前 host 的 /ws
  const envWsBase = import.meta.env.VITE_WS_BASE
  let url
  if (envWsBase) {
    // VITE_WS_BASE 形如 wss://monitor.azayu.top/ws
    url = `${envWsBase}?token=${encodeURIComponent(token)}`
  } else {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    url = `${protocol}//${location.host}/ws?token=${encodeURIComponent(token)}`
  }

  try {
    ws = new WebSocket(url)
  } catch (e) {
    scheduleReconnect(token)
    return
  }

  ws.onopen = () => {
    console.log('[WS] 已连接')
  }

  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      // 派发给所有订阅者
      for (const handler of subscribers) {
        try { handler(msg) } catch (e) { /* 单个订阅者异常不影响其他 */ }
      }
    } catch (e) {
      // 忽略非 JSON 消息
    }
  }

  ws.onclose = () => {
    console.log('[WS] 连接关闭，3 秒后重连')
    ws = null
    scheduleReconnect(currentToken)
  }

  ws.onerror = () => {
    // 错误处理由 onclose 接管
  }
}

function scheduleReconnect(token) {
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    if (localStorage.getItem('vm_token')) {
      connectWebSocket(token || localStorage.getItem('vm_token'), null)
    }
  }, 3000)
}

/** 向服务端发送 JSON 消息（如 audio_control 指令） */
export function sendWsMessage(message) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(typeof message === 'string' ? message : JSON.stringify(message))
      return true
    } catch (e) {
      console.warn('[WS] 发送失败', e)
      return false
    }
  }
  return false
}

/** 判断 WS 是否处于连接状态 */
export function isWsOpen() {
  return !!(ws && ws.readyState === WebSocket.OPEN)
}

export function closeWebSocket() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (ws) {
    ws.onclose = null
    ws.close()
    ws = null
  }
  currentToken = null
  subscribers.clear()
}
