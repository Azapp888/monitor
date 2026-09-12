// 监控数据路由 - 包括设备端上报与管理端查询
const express = require('express');
const path = require('path');
const fs = require('fs');
const multer = require('multer');
const db = require('../db');
const { authRequired } = require('../middleware/auth');
const { deviceAuthRequired } = require('../middleware/deviceAuth');
const { broadcast, broadcastToAdmins } = require('../websocket');

const router = express.Router();

// 上传根目录
const UPLOAD_ROOT = path.join(__dirname, '..', 'uploads');
const AUDIO_DIR = path.join(UPLOAD_ROOT, 'audio');

// 配置 multer：录音分片上传到 audio 目录，按设备/会话分目录
const audioStorage = multer.diskStorage({
  destination: (req, file, cb) => {
    const deviceId = req.device && req.device.id ? String(req.device.id) : 'unknown';
    const sessionId = (req.body && req.body.session_id) || 'no-session';
    const dir = path.join(AUDIO_DIR, deviceId, sessionId);
    fs.mkdirSync(dir, { recursive: true });
    cb(null, dir);
  },
  filename: (req, file, cb) => {
    const idx = (req.body && req.body.chunk_index) || '0';
    const ts = Date.now();
    // 使用 m4a 扩展名（AAC 编码）；设备端实际编码可不同，但 m4a 兼容性最好
    cb(null, `chunk_${ts}_${idx}.m4a`);
  }
});
const audioUpload = multer({
  storage: audioStorage,
  limits: { fileSize: 20 * 1024 * 1024 } // 单段最多 20MB
});

// ========== 管理端鉴权（list/stats/phone-calls/audio/sms/app-usage）==========
router.use('/list', authRequired);
router.use('/stats', authRequired);
router.use('/phone-calls', authRequired);
router.use('/audio', authRequired);
router.use('/sms', authRequired);
router.use('/app-usage', authRequired);

// 将数据库行中的 extra JSON 字符串解析为对象（供前端直接使用）
function parseExtra(row) {
  if (row && typeof row.extra === 'string') {
    try {
      row.extra = JSON.parse(row.extra);
    } catch (e) {
      row.extra = null;
    }
  }
  return row;
}

// 获取设备监控数据列表（支持时间范围、分页）
router.get('/list/:deviceId', (req, res) => {
  const { deviceId } = req.params;
  const { start, end, page = 1, pageSize = 50 } = req.query;
  const device = db.prepare('SELECT id FROM devices WHERE id = ?').get(deviceId);
  if (!device) return res.status(404).json({ error: '设备不存在' });

  const limit = Math.min(parseInt(pageSize, 10) || 50, 500);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = ['device_id = ?'];
  const params = [deviceId];
  if (start) {
    conditions.push('recorded_at >= ?');
    params.push(start);
  }
  if (end) {
    conditions.push('recorded_at <= ?');
    params.push(end);
  }
  const where = `WHERE ${conditions.join(' AND ')}`;

  const total = db.prepare(`SELECT COUNT(*) as count FROM monitor_logs ${where}`).get(...params).count;
  const list = db.prepare(`
    SELECT * FROM monitor_logs ${where}
    ORDER BY recorded_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset).map(parseExtra);

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list });
});

// 设备最新状态汇总（用于首页地图与列表）
router.get('/latest', authRequired, (req, res) => {
  const devices = db.prepare('SELECT * FROM devices ORDER BY last_seen_at DESC NULLS LAST').all();
  const latest = devices.map(device => {
    const row = db.prepare(`
      SELECT * FROM monitor_logs WHERE device_id = ? ORDER BY recorded_at DESC LIMIT 1
    `).get(device.id);
    return { ...device, latest: parseExtra(row) };
  });
  res.json({ list: latest });
});

// 全局统计
router.get('/stats/overview', (req, res) => {
  const totalDevices = db.prepare('SELECT COUNT(*) as c FROM devices').get().c;
  const onlineDevices = db.prepare("SELECT COUNT(*) as c FROM devices WHERE status = 'online'").get().c;
  const todayLogs = db.prepare("SELECT COUNT(*) as c FROM monitor_logs WHERE date(received_at) = date('now')").get().c;
  const totalLogs = db.prepare('SELECT COUNT(*) as c FROM monitor_logs').get().c;
  const todayCalls = db.prepare("SELECT COUNT(*) as c FROM phone_calls WHERE date(received_at) = date('now')").get().c;
  const totalAudios = db.prepare('SELECT COUNT(*) as c FROM audio_files').get().c;
  res.json({ totalDevices, onlineDevices, todayLogs, totalLogs, todayCalls, totalAudios });
});

// ========== 来电记录接口 ==========

// 来电列表（支持按设备过滤、时间范围、分页）
router.get('/phone-calls', (req, res) => {
  const { deviceId, start, end, page = 1, pageSize = 50 } = req.query;
  const limit = Math.min(parseInt(pageSize, 10) || 50, 500);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = [];
  const params = [];
  if (deviceId) { conditions.push('pc.device_id = ?'); params.push(deviceId); }
  if (start) { conditions.push('pc.received_at >= ?'); params.push(start); }
  if (end) { conditions.push('pc.received_at <= ?'); params.push(end); }
  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';

  const total = db.prepare(`SELECT COUNT(*) as count FROM phone_calls pc ${where}`).get(...params).count;
  const list = db.prepare(`
    SELECT pc.*, d.device_name, d.device_code
    FROM phone_calls pc
    LEFT JOIN devices d ON d.id = pc.device_id
    ${where}
    ORDER BY pc.received_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset);

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list });
});

// 单条来电详情
router.get('/phone-calls/:id', (req, res) => {
  const row = db.prepare(`
    SELECT pc.*, d.device_name, d.device_code
    FROM phone_calls pc
    LEFT JOIN devices d ON d.id = pc.device_id
    WHERE pc.id = ?
  `).get(req.params.id);
  if (!row) return res.status(404).json({ error: '来电记录不存在' });
  res.json({ call: row });
});

// 删除来电记录
router.delete('/phone-calls/:id', (req, res) => {
  const row = db.prepare('SELECT id FROM phone_calls WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '来电记录不存在' });
  db.prepare('DELETE FROM phone_calls WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

// ========== 短信记录接口 ==========

// 短信列表（支持按设备过滤、类型、时间范围、关键词搜索、分页）
router.get('/sms', (req, res) => {
  const { deviceId, type, start, end, keyword, page = 1, pageSize = 50 } = req.query;
  const limit = Math.min(parseInt(pageSize, 10) || 50, 500);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = [];
  const params = [];
  if (deviceId) { conditions.push('s.device_id = ?'); params.push(deviceId); }
  if (type !== undefined && type !== '') { conditions.push('s.type = ?'); params.push(parseInt(type, 10)); }
  if (start) { conditions.push('s.received_at >= ?'); params.push(start); }
  if (end) { conditions.push('s.received_at <= ?'); params.push(end); }
  if (keyword) {
    conditions.push('(s.address LIKE ? OR s.body LIKE ? OR s.person_name LIKE ?)');
    const kw = `%${keyword}%`;
    params.push(kw, kw, kw);
  }
  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';

  const total = db.prepare(`SELECT COUNT(*) as count FROM sms_messages s ${where}`).get(...params).count;
  const list = db.prepare(`
    SELECT s.*, d.device_name, d.device_code
    FROM sms_messages s
    LEFT JOIN devices d ON d.id = s.device_id
    ${where}
    ORDER BY s.received_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset);

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list });
});

// 单条短信详情
router.get('/sms/:id', (req, res) => {
  const row = db.prepare(`
    SELECT s.*, d.device_name, d.device_code
    FROM sms_messages s
    LEFT JOIN devices d ON d.id = s.device_id
    WHERE s.id = ?
  `).get(req.params.id);
  if (!row) return res.status(404).json({ error: '短信记录不存在' });
  res.json({ sms: row });
});

// 批量删除短信（ids 数组）
router.post('/sms/batch-delete', (req, res) => {
  const ids = (req.body && Array.isArray(req.body.ids)) ? req.body.ids : [];
  if (ids.length === 0) return res.status(400).json({ error: 'ids 必填' });
  const placeholders = ids.map(() => '?').join(',');
  const info = db.prepare(`DELETE FROM sms_messages WHERE id IN (${placeholders})`).run(...ids);
  res.json({ ok: true, deleted: info.changes });
});

// 删除单条短信
router.delete('/sms/:id', (req, res) => {
  const row = db.prepare('SELECT id FROM sms_messages WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '短信记录不存在' });
  db.prepare('DELETE FROM sms_messages WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

// ========== 录音文件接口 ==========

// 录音列表（支持按设备/会话过滤、时间范围、分页）
router.get('/audio', (req, res) => {
  const { deviceId, sessionId, start, end, page = 1, pageSize = 100 } = req.query;
  const limit = Math.min(parseInt(pageSize, 10) || 100, 500);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = [];
  const params = [];
  if (deviceId) { conditions.push('a.device_id = ?'); params.push(deviceId); }
  if (sessionId) { conditions.push('a.session_id = ?'); params.push(sessionId); }
  if (start) { conditions.push('a.received_at >= ?'); params.push(start); }
  if (end) { conditions.push('a.received_at <= ?'); params.push(end); }
  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';

  const total = db.prepare(`SELECT COUNT(*) as count FROM audio_files a ${where}`).get(...params).count;
  const list = db.prepare(`
    SELECT a.*, d.device_name, d.device_code
    FROM audio_files a
    LEFT JOIN devices d ON d.id = a.device_id
    ${where}
    ORDER BY a.received_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset);

  // 为每条记录补充可访问的下载/播放 URL
  const host = `${req.protocol}://${req.get('host')}`;
  const items = list.map(a => ({
    ...a,
    url: `${host}/api/monitor/audio/${a.id}/download`,
    play_url: `${host}/api/monitor/audio/${a.id}/download`
  }));

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list: items });
});

// 单条录音详情
router.get('/audio/:id', (req, res) => {
  const row = db.prepare(`
    SELECT a.*, d.device_name, d.device_code
    FROM audio_files a
    LEFT JOIN devices d ON d.id = a.device_id
    WHERE a.id = ?
  `).get(req.params.id);
  if (!row) return res.status(404).json({ error: '录音文件不存在' });
  const host = `${req.protocol}://${req.get('host')}`;
  res.json({
    audio: {
      ...row,
      url: `${host}/api/monitor/audio/${row.id}/download`,
      play_url: `${host}/api/monitor/audio/${row.id}/download`
    }
  });
});

// 下载/播放录音文件（使用 sendFile 自动支持 Range 请求，便于 HTML5 audio 流式播放）
router.get('/audio/:id/download', (req, res) => {
  const row = db.prepare('SELECT * FROM audio_files WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '录音文件不存在' });
  const abs = path.join(UPLOAD_ROOT, row.file_path);
  if (!fs.existsSync(abs)) return res.status(410).json({ error: '文件已丢失' });
  // 已通过 router.use('/audio', authRequired) 完成管理员鉴权
  res.sendFile(abs);
});

// 删除录音文件（同时删除磁盘文件）
router.delete('/audio/:id', (req, res) => {
  const row = db.prepare('SELECT * FROM audio_files WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '录音文件不存在' });
  const abs = path.join(UPLOAD_ROOT, row.file_path);
  try { if (fs.existsSync(abs)) fs.unlinkSync(abs); } catch (e) { /* ignore */ }
  db.prepare('DELETE FROM audio_files WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

// ========== 设备端上报接口（需设备 token 鉴权）==========

// 设备注册/激活：首次启动时根据 device_code + device_token 完成"激活"并换取上报 token
router.post('/report/register', (req, res) => {
  const { device_code, device_token, model, os_version, app_version } = req.body || {};
  if (!device_code || !device_token) {
    return res.status(400).json({ error: 'device_code 与 device_token 必填' });
  }
  const device = db.prepare('SELECT * FROM devices WHERE device_code = ? AND device_token = ?')
    .get(device_code, device_token);
  if (!device) return res.status(403).json({ error: '设备凭证无效' });

  db.prepare(`
    UPDATE devices SET
      model = COALESCE(?, model),
      os_version = COALESCE(?, os_version),
      app_version = COALESCE(?, app_version),
      status = 'online',
      last_seen_at = datetime('now')
    WHERE id = ?
  `).run(model || null, os_version || null, app_version || null, device.id);

  broadcast({ type: 'device_online', device_id: device.id });
  res.json({ ok: true, device_id: device.id, interval_seconds: 600 });
});

// 设备上报监控数据（位置 + 电量 + 网络等）
router.post('/report', deviceAuthRequired, (req, res) => {
  const device = req.device;
  const {
    latitude, longitude, accuracy, altitude, speed,
    battery_level, battery_charging, battery_temperature,
    network_type, network_strength, extra,
    recorded_at
  } = req.body || {};

  if (recorded_at == null) {
    return res.status(400).json({ error: 'recorded_at 必填' });
  }

  const lat = (latitude != null && !isNaN(latitude)) ? Number(latitude) : null;
  const lng = (longitude != null && !isNaN(longitude)) ? Number(longitude) : null;

  // 记录设备上报 IP（兼容 Cloudflare 代理的 x-forwarded-for）
  const forwarded = req.headers['x-forwarded-for'];
  const deviceIp = forwarded
    ? String(forwarded).split(',')[0].trim()
    : (req.ip || req.socket?.remoteAddress || null);

  // 将设备 IP 合并进 extra（保留原有 ssid / foreground_app / recent_apps 等）
  const extraMerged = (extra && typeof extra === 'object')
    ? { ...extra, ip_address: deviceIp }
    : { ip_address: deviceIp };

  const info = db.prepare(`
    INSERT INTO monitor_logs
      (device_id, latitude, longitude, accuracy, altitude, speed,
       battery_level, battery_charging, battery_temperature,
       network_type, network_strength, extra, recorded_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    device.id, lat, lng,
    accuracy != null ? Number(accuracy) : null,
    altitude != null ? Number(altitude) : null,
    speed != null ? Number(speed) : null,
    battery_level != null ? Math.max(0, Math.min(100, parseInt(battery_level, 10))) : null,
    battery_charging ? 1 : 0,
    battery_temperature != null ? Number(battery_temperature) : null,
    network_type || null,
    network_strength != null ? parseInt(network_strength, 10) : null,
    JSON.stringify(extraMerged),
    recorded_at
  );

  db.prepare("UPDATE devices SET status = 'online', last_seen_at = datetime('now') WHERE id = ?")
    .run(device.id);

  const row = db.prepare('SELECT * FROM monitor_logs WHERE id = ?').get(info.lastInsertRowid);
  broadcast({
    type: 'monitor_data',
    device_id: device.id,
    device_code: device.device_code,
    device_name: device.device_name,
    data: parseExtra(row)
  });

  res.json({ ok: true, log_id: info.lastInsertRowid });
});

// 设备心跳
router.post('/report/heartbeat', deviceAuthRequired, (req, res) => {
  db.prepare("UPDATE devices SET status = 'online', last_seen_at = datetime('now') WHERE id = ?")
    .run(req.device.id);
  res.json({ ok: true });
});

// 设备下线
router.post('/report/offline', deviceAuthRequired, (req, res) => {
  db.prepare("UPDATE devices SET status = 'offline', last_seen_at = datetime('now') WHERE id = ?")
    .run(req.device.id);
  broadcast({ type: 'device_offline', device_id: req.device.id });
  res.json({ ok: true });
});

// 设备上报来电信息（来电号码、状态、时间）
router.post('/report/phone-call', deviceAuthRequired, (req, res) => {
  const device = req.device;
  const {
    phone_number, call_state,
    ring_started_at, answered_at, ended_at,
    duration_seconds, extra
  } = req.body || {};

  if (call_state == null) {
    return res.status(400).json({ error: 'call_state 必填' });
  }

  const info = db.prepare(`
    INSERT INTO phone_calls
      (device_id, phone_number, call_state, ring_started_at, answered_at, ended_at, duration_seconds, extra)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    device.id,
    phone_number || null,
    call_state,
    ring_started_at || null,
    answered_at || null,
    ended_at || null,
    duration_seconds != null ? parseInt(duration_seconds, 10) : null,
    extra ? JSON.stringify(extra) : null
  );

  const row = db.prepare('SELECT * FROM phone_calls WHERE id = ?').get(info.lastInsertRowid);

  // 通过 WebSocket 实时推送给管理端
  broadcastToAdmins({
    type: 'phone_call',
    device_id: device.id,
    device_code: device.device_code,
    device_name: device.device_name,
    data: row
  });

  res.json({ ok: true, call_id: info.lastInsertRowid });
});

// 设备批量上报短信（items: [{sms_id, address, body, type, person_name, received_at, read, service_center}]）
// sync_type: "full"=全量同步（存在则更新） "incremental"=增量（跳过已存在）
router.post('/report/sms', deviceAuthRequired, (req, res) => {
  const device = req.device;
  const { items = [], sync_type = 'incremental' } = req.body || {};

  if (!Array.isArray(items) || items.length === 0) {
    return res.status(400).json({ error: 'items 必填' });
  }

  const selectStmt = db.prepare('SELECT id FROM sms_messages WHERE device_id = ? AND sms_id = ?');
  const insertStmt = db.prepare(`
    INSERT INTO sms_messages
      (device_id, sms_id, address, body, type, person_name, read, service_center, received_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  const updateStmt = db.prepare(`
    UPDATE sms_messages SET
      address = ?, body = ?, type = ?, person_name = ?, read = ?, service_center = ?, received_at = ?
    WHERE device_id = ? AND sms_id = ?
  `);

  let inserted = 0;
  let updated = 0;
  let skipped = 0;

  const tx = db.transaction(() => {
    for (const item of items) {
      const smsId = item.sms_id;
      const address = item.address || null;
      const body = item.body || null;
      const type = (item.type === 2) ? 2 : 1;
      const personName = item.person_name || null;
      const read = (item.read === 0) ? 0 : 1;
      const serviceCenter = item.service_center || null;
      const receivedAt = item.received_at || new Date().toISOString();

      const existing = selectStmt.get(device.id, smsId);
      if (existing) {
        if (sync_type === 'full') {
          updateStmt.run(address, body, type, personName, read, serviceCenter, receivedAt, device.id, smsId);
          updated++;
        } else {
          skipped++;
        }
      } else {
        insertStmt.run(device.id, smsId, address, body, type, personName, read, serviceCenter, receivedAt);
        inserted++;
      }
    }
  });
  tx();

  broadcastToAdmins({
    type: 'sms_sync',
    device_id: device.id,
    device_code: device.device_code,
    device_name: device.device_name,
    count: items.length,
    inserted,
    received_at: new Date().toISOString()
  });

  res.json({ ok: true, inserted, updated, skipped, total: items.length });
});

// 设备上传录音分片（multipart/form-data）
// 表单字段：session_id, chunk_index, duration_seconds, mime_type, file（二进制）
router.post('/report/audio', deviceAuthRequired, audioUpload.single('file'), (req, res) => {
  const device = req.device;
  if (!req.file) {
    return res.status(400).json({ error: '缺少录音文件' });
  }
  const sessionId = req.body.session_id;
  const chunkIndex = parseInt(req.body.chunk_index || '0', 10);
  const durationSeconds = req.body.duration_seconds != null
    ? Number(req.body.duration_seconds) : null;
  const mimeType = req.body.mime_type || req.file.mimetype || 'audio/mp4';

  // 计算相对路径（相对 uploads 目录）
  const absPath = req.file.path;
  const relPath = path.relative(UPLOAD_ROOT, absPath).replace(/\\/g, '/');

  const info = db.prepare(`
    INSERT INTO audio_files
      (device_id, session_id, chunk_index, file_path, file_url, file_size, duration_seconds, mime_type)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    device.id,
    sessionId || 'no-session',
    chunkIndex,
    relPath,
    null, // file_url 在读取时动态生成（含 host）
    req.file.size,
    durationSeconds,
    mimeType
  );

  const audioId = info.lastInsertRowid;
  const host = `${req.protocol}://${req.get('host')}`;
  const url = `${host}/api/monitor/audio/${audioId}/download`;

  // 通过 WS 通知正在监听该设备的管理端：有新分片
  broadcastToAdmins({
    type: 'audio_chunk',
    device_id: device.id,
    device_code: device.device_code,
    device_name: device.device_name,
    session_id: sessionId,
    audio_id: audioId,
    chunk_index: chunkIndex,
    duration_seconds: durationSeconds,
    url,
    received_at: new Date().toISOString()
  });

  res.json({ ok: true, audio_id: audioId, url });
});

// ========== 应用使用情况 ==========
// 设备端实时上报当前前台/后台应用列表
// Body: { device_code, device_token, event_type, recorded_at, apps: [{package_name, app_label, version_name, is_foreground, last_used_at, total_time_visible}] }
router.post('/report/app-usage', deviceAuthRequired, (req, res) => {
  const device = req.device;
  const { event_type, recorded_at, apps } = req.body || {};
  if (!Array.isArray(apps) || apps.length === 0) {
    return res.json({ ok: true, inserted: 0 });
  }
  if (!recorded_at) {
    return res.status(400).json({ error: 'recorded_at 必填' });
  }
  const safeEvent = (event_type === 'foreground_change') ? 'foreground_change' : 'snapshot';

  const stmt = db.prepare(`
    INSERT INTO app_usage_logs
      (device_id, package_name, app_label, version_name, is_foreground,
       event_type, last_used_at, total_time_visible, recorded_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  const tx = db.transaction((rows) => {
    for (const a of rows) {
      stmt.run(
        device.id,
        a.package_name || 'unknown',
        a.app_label || null,
        a.version_name || null,
        a.is_foreground ? 1 : 0,
        safeEvent,
        a.last_used_at != null ? Number(a.last_used_at) : null,
        a.total_time_visible != null ? Number(a.total_time_visible) : null,
        recorded_at
      );
    }
  });
  tx(apps);

  // 实时通知管理端
  broadcast({
    type: 'app_usage',
    device_id: device.id,
    event_type: safeEvent,
    count: apps.length,
    foreground: apps.find(a => a.is_foreground)?.package_name || null
  });

  res.json({ ok: true, inserted: apps.length });
});

// 应用使用历史列表（管理端查看）
// Query: device_id(必填), start, end, package, foreground_only, page, pageSize
router.get('/app-usage', authRequired, (req, res) => {
  const { device_id, start, end, package, foreground_only, page = 1, pageSize = 50 } = req.query;
  if (!device_id) return res.status(400).json({ error: 'device_id 必填' });

  const limit = Math.min(parseInt(pageSize, 10) || 50, 200);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = ['device_id = ?'];
  const params = [device_id];
  if (start) { conditions.push('recorded_at >= ?'); params.push(start); }
  if (end) { conditions.push('recorded_at <= ?'); params.push(end); }
  if (package) { conditions.push('package_name LIKE ?'); params.push(`%${package}%`); }
  if (foreground_only === '1' || foreground_only === 'true') {
    conditions.push('is_foreground = 1');
  }
  const where = `WHERE ${conditions.join(' AND ')}`;

  const total = db.prepare(`SELECT COUNT(*) as count FROM app_usage_logs ${where}`).get(...params).count;
  const list = db.prepare(`
    SELECT * FROM app_usage_logs ${where}
    ORDER BY recorded_at DESC, id DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset);

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list });
});

// 设备当前应用使用状态（最近一次快照）
router.get('/app-usage/:deviceId/current', authRequired, (req, res) => {
  const deviceId = req.params.deviceId;
  // 取该设备最近一批的快照（同 recorded_at 的所有应用）
  const latest = db.prepare(`
    SELECT recorded_at FROM app_usage_logs
    WHERE device_id = ?
    ORDER BY id DESC LIMIT 1
  `).get(deviceId);
  if (!latest) return res.json({ recorded_at: null, foreground: null, apps: [] });

  const apps = db.prepare(`
    SELECT package_name, app_label, version_name, is_foreground,
           last_used_at, total_time_visible, recorded_at
    FROM app_usage_logs
    WHERE device_id = ? AND recorded_at = ?
    ORDER BY is_foreground DESC, total_time_visible DESC
  `).all(deviceId, latest.recorded_at);

  const foreground = apps.find(a => a.is_foreground) || null;
  res.json({
    recorded_at: latest.recorded_at,
    foreground,
    apps
  });
});

module.exports = router;
