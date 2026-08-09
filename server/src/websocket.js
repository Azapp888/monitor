// WebSocket 服务 - 支持管理端 + 设备端双向通信
//   - 管理端（admin）：JWT 鉴权，订阅实时推送（监控数据/来电/录音分片）
//   - 设备端（device）：device_code + device_token 鉴权，接收 audio_control 等指令
//   - 管理端断开时自动向其正在监听的设备发送 stop 指令
const { WebSocketServer } = require('ws');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');
const config = require('./config');
const db = require('./db');

let wss = null;

// 已认证的连接集合（admin + device 混合）
const clients = new Set();

/**
 * 客户端元数据：
 *   { ws, role: 'admin'|'device', deviceId?, userId?, listening?: Set<deviceId> }
 * 管理端的 listening 记录其当前正在监听（实时录音）的设备 id 集合
 */
function getMeta(ws) { return ws.__meta; }

function initWebSocket(server) {
  wss = new WebSocketServer({ server, path: '/ws' });

  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://localhost');
    const role = url.searchParams.get('type') || 'admin';

    if (role === 'device') {
      // 设备端鉴权
      const deviceCode = url.searchParams.get('device_code');
      const deviceToken = url.searchParams.get('device_token');
      if (!deviceCode || !deviceToken) {
        ws.close(1008, '缺少设备凭证');
        return;
      }
      const device = db.prepare('SELECT * FROM devices WHERE device_code = ? AND device_token = ?')
        .get(deviceCode, deviceToken);
      if (!device) {
        ws.close(1008, '设备凭证无效');
        return;
      }
      ws.__meta = { role: 'device', ws, deviceId: device.id, deviceCode: device.device_code };
      clients.add(ws);
      // 更新设备在线状态
      db.prepare("UPDATE devices SET status = 'online', last_seen_at = datetime('now') WHERE id = ?")
        .run(device.id);
      broadcastToAdmins({ type: 'device_online', device_id: device.id });
      ws.send(JSON.stringify({ type: 'connected', role: 'device', device_id: device.id }));
    } else {
      // 管理端鉴权
      const token = url.searchParams.get('token');
      if (!token) { ws.close(1008, '未提供 token'); return; }
      try {
        const payload = jwt.verify(token, config.JWT_SECRET);
        ws.__meta = { role: 'admin', ws, userId: payload.sub || payload.id, listening: new Set() };
        clients.add(ws);
        ws.send(JSON.stringify({ type: 'connected', role: 'admin', time: new Date().toISOString() }));
      } catch (e) {
        ws.close(1008, 'token 无效');
        return;
      }
    }

    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch (e) { return; }
      handleMessage(ws, msg);
    });

    ws.on('close', () => onClosed(ws));
    ws.on('error', () => onClosed(ws));
  });

  console.log(`[WS] WebSocket 服务已启动于 /ws（admin + device 双通道）`);
}

function onClosed(ws) {
  if (!ws.__meta) return;
  const meta = ws.__meta;
  clients.delete(ws);

  if (meta.role === 'device') {
    // 设备端断开：更新离线，并通知所有正在监听该设备的管理端
    db.prepare("UPDATE devices SET status = 'offline', last_seen_at = datetime('now') WHERE id = ?")
      .run(meta.deviceId);
    broadcastToAdmins({ type: 'device_offline', device_id: meta.deviceId });
  } else if (meta.role === 'admin') {
    // 管理端断开：向其正在监听的所有设备发送 stop 指令（关键需求：管理端退出自动停止录音）
    if (meta.listening && meta.listening.size > 0) {
      for (const deviceId of meta.listening) {
        sendToDevice(deviceId, { type: 'audio_control', action: 'stop', reason: 'admin_disconnected' });
      }
    }
  }
}

function handleMessage(ws, msg) {
  const meta = getMeta(ws);
  if (!meta) return;

  if (meta.role === 'admin') {
    // 管理端下行指令：audio_control { action, device_id }
    if (msg.type === 'audio_control' && msg.device_id != null) {
      const deviceId = Number(msg.device_id);
      if (msg.action === 'start') {
        // 记录监听关系，生成 session_id
        const sessionId = 'aud-' + crypto.randomBytes(8).toString('hex');
        meta.listening.add(deviceId);
        const ok = sendToDevice(deviceId, {
          type: 'audio_control',
          action: 'start',
          session_id: sessionId,
          started_by: 'admin'
        });
        // 设备若不在线，立即清理监听关系并告知管理端
        if (!ok) {
          meta.listening.delete(deviceId);
          ws.send(JSON.stringify({ type: 'audio_error', device_id: deviceId, error: '设备未连接' }));
        } else {
          ws.send(JSON.stringify({ type: 'audio_started', device_id: deviceId, session_id: sessionId }));
        }
      } else if (msg.action === 'stop') {
        meta.listening.delete(deviceId);
        sendToDevice(deviceId, { type: 'audio_control', action: 'stop', reason: 'admin_stopped' });
        ws.send(JSON.stringify({ type: 'audio_stopped', device_id: deviceId }));
      }
    }
  } else if (meta.role === 'device') {
    // 设备端上行：录音分片通知、状态反馈等（实际录音文件通过 HTTP 上传）
    if (msg.type === 'audio_chunk') {
      // 转发给所有正在监听该设备的管理端
      sendToListeningAdmins(meta.deviceId, {
        type: 'audio_chunk',
        device_id: meta.deviceId,
        session_id: msg.session_id,
        audio_id: msg.audio_id,
        chunk_index: msg.chunk_index,
        duration_seconds: msg.duration_seconds
      });
    } else if (msg.type === 'audio_stopped') {
      sendToListeningAdmins(meta.deviceId, {
        type: 'audio_stopped',
        device_id: meta.deviceId,
        session_id: msg.session_id
      });
    } else if (msg.type === 'audio_error') {
      sendToListeningAdmins(meta.deviceId, {
        type: 'audio_error',
        device_id: meta.deviceId,
        error: msg.error
      });
    }
    // 监控数据 / 来电等仍然走 HTTP 上报后由 routes 层广播，避免重复
  }
}

// 向指定设备发送消息，返回是否发送成功
function sendToDevice(deviceId, message) {
  if (!wss) return false;
  const payload = JSON.stringify(message);
  for (const ws of clients) {
    const meta = getMeta(ws);
    if (meta && meta.role === 'device' && meta.deviceId === Number(deviceId) && ws.readyState === 1) {
      try { ws.send(payload); return true; } catch (e) { /* ignore */ }
    }
  }
  return false;
}

// 向所有正在监听 deviceId 的管理端发送
function sendToListeningAdmins(deviceId, message) {
  if (!wss) return;
  const payload = JSON.stringify(message);
  for (const ws of clients) {
    const meta = getMeta(ws);
    if (meta && meta.role === 'admin' && meta.listening && meta.listening.has(Number(deviceId)) && ws.readyState === 1) {
      try { ws.send(payload); } catch (e) { /* ignore */ }
    }
  }
}

// 向所有管理端广播
function broadcastToAdmins(message) {
  if (!wss) return;
  const payload = typeof message === 'string' ? message : JSON.stringify(message);
  for (const ws of clients) {
    const meta = getMeta(ws);
    if (meta && meta.role === 'admin' && ws.readyState === 1) {
      try { ws.send(payload); } catch (e) { /* ignore */ }
    }
  }
}

// 兼容旧调用：broadcast = broadcastToAdmins
function broadcast(message) { broadcastToAdmins(message); }

module.exports = { initWebSocket, broadcast, broadcastToAdmins, sendToDevice };
