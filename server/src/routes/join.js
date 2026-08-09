// 设备加入申请路由
//
//   公开接口（设备端使用，无需鉴权）：
//     POST /api/join-requests              提交申请
//     GET  /api/join-requests/status/:code  按申请编号查询状态
//
//   管理端接口（需 JWT 鉴权）：
//     GET  /api/join-requests              申请列表（支持状态过滤、分页）
//     GET  /api/join-requests/:id          申请详情
//     POST /api/join-requests/:id/approve  一键同意 -> 自动创建设备 + 生成 device_code/token
//     POST /api/join-requests/:id/reject   拒绝申请
//     GET  /api/join-requests/logs/list    操作日志列表（按时间倒序）
const express = require('express');
const crypto = require('crypto');
const db = require('../db');
const { authRequired } = require('../middleware/auth');
const { broadcastToAdmins } = require('../websocket');

const router = express.Router();

// 写入操作日志辅助函数（仅管理端接口使用）
function logOperation(user, action, targetType, targetId, detail) {
  try {
    db.prepare(`
      INSERT INTO operation_logs (user_id, username, action, target_type, target_id, detail)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(user.id, user.username, action, targetType, targetId, detail ? JSON.stringify(detail) : null);
  } catch (e) {
    console.error('[op_log] 写入失败:', e.message);
  }
}

// ========== 公开接口（设备端，无需鉴权）==========

// 提交加入申请 - 设备端未激活时调用
router.post('/', (req, res) => {
  const {
    device_name, model, os_version, app_version,
    android_id, phone_number, requester_name, contact
  } = req.body || {};

  // 至少要求 android_id 或 model 之一，便于后续识别
  if (!android_id && !model) {
    return res.status(400).json({ error: 'android_id 或 model 至少提供一项' });
  }

  // 去重：若该 android_id 已存在 pending 申请，复用同一条记录（避免同一设备重复提交）
  if (android_id) {
    const existing = db.prepare(
      `SELECT * FROM join_requests WHERE android_id = ? AND status = 'pending' ORDER BY id DESC LIMIT 1`
    ).get(android_id);
    if (existing) {
      return res.json({
        ok: true,
        request_id: existing.id,
        request_code: existing.request_code,
        status: existing.status,
        message: '已有待审核申请'
      });
    }
  }

  // 生成唯一申请编号：JR- 时间戳 - 随机
  const requestCode = 'JR-' + Date.now().toString(36).toUpperCase()
    + '-' + crypto.randomBytes(3).toString('hex').toUpperCase();

  const info = db.prepare(`
    INSERT INTO join_requests
      (request_code, device_name, model, os_version, app_version,
       android_id, phone_number, requester_name, contact)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    requestCode,
    device_name || null,
    model || null,
    os_version || null,
    app_version || null,
    android_id || null,
    phone_number || null,
    requester_name || null,
    contact || null
  );

  // 实时通知管理端：有新申请
  broadcastToAdmins({
    type: 'join_request_new',
    request_id: info.lastInsertRowid,
    request_code: requestCode
  });

  res.status(201).json({
    ok: true,
    request_id: info.lastInsertRowid,
    request_code: requestCode,
    status: 'pending',
    message: '申请已提交，请等待管理员审批'
  });
});

// 按申请编号查询状态 - 设备端轮询使用，返回最简信息
// 注意路径用 /status/:code 避免与其他子路径冲突
router.get('/status/:code', (req, res) => {
  const row = db.prepare(`
    SELECT id, request_code, status, device_id,
           review_comment, reviewed_at, created_at
    FROM join_requests WHERE request_code = ?
  `).get(req.params.code);

  if (!row) return res.status(404).json({ error: '申请不存在' });

  // 若已审批通过，附带设备凭证（设备端凭此激活）
  let credentials = null;
  if (row.status === 'approved' && row.device_id) {
    const device = db.prepare('SELECT device_code, device_token FROM devices WHERE id = ?')
      .get(row.device_id);
    if (device) {
      credentials = {
        device_code: device.device_code,
        device_token: device.device_token
      };
    }
  }

  res.json({
    ok: true,
    request_id: row.id,
    request_code: row.request_code,
    status: row.status,                  // pending / approved / rejected
    review_comment: row.review_comment,
    reviewed_at: row.reviewed_at,
    created_at: row.created_at,
    credentials                          // approved 时返回 device_code/token
  });
});

// ========== 以下接口需要管理员鉴权 ==========
router.use(authRequired);

// 申请列表（支持状态过滤、分页、关键字搜索）
router.get('/', (req, res) => {
  const { status, keyword, page = 1, pageSize = 20 } = req.query;
  const limit = Math.min(parseInt(pageSize, 10) || 20, 100);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = [];
  const params = [];
  if (status) { conditions.push('status = ?'); params.push(status); }
  if (keyword) {
    conditions.push('(request_code LIKE ? OR device_name LIKE ? OR model LIKE ? OR requester_name LIKE ? OR android_id LIKE ?)');
    params.push(`%${keyword}%`, `%${keyword}%`, `%${keyword}%`, `%${keyword}%`, `%${keyword}%`);
  }
  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';

  const total = db.prepare(`SELECT COUNT(*) as count FROM join_requests ${where}`).get(...params).count;
  const list = db.prepare(`
    SELECT * FROM join_requests ${where}
    ORDER BY CASE status WHEN 'pending' THEN 0 ELSE 1 END, created_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset);

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list });
});

// 操作日志列表（按时间倒序）- 放在 /:id 之前避免冲突
router.get('/logs/list', (req, res) => {
  const { action, target_type, page = 1, pageSize = 50 } = req.query;
  const limit = Math.min(parseInt(pageSize, 10) || 50, 200);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = [];
  const params = [];
  if (action) { conditions.push('action = ?'); params.push(action); }
  if (target_type) { conditions.push('target_type = ?'); params.push(target_type); }
  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';

  const total = db.prepare(`SELECT COUNT(*) as count FROM operation_logs ${where}`).get(...params).count;
  const list = db.prepare(`
    SELECT * FROM operation_logs ${where}
    ORDER BY created_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset).map(row => {
    if (typeof row.detail === 'string') {
      try { row.detail = JSON.parse(row.detail); } catch (e) { /* keep as string */ }
    }
    return row;
  });

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list });
});

// 申请详情
router.get('/:id', (req, res) => {
  const row = db.prepare('SELECT * FROM join_requests WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '申请不存在' });
  res.json({ request: row });
});

// 一键同意 - 自动创建设备并生成 device_code/token
router.post('/:id/approve', (req, res) => {
  const { review_comment } = req.body || {};
  const row = db.prepare('SELECT * FROM join_requests WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '申请不存在' });
  if (row.status === 'approved') return res.status(400).json({ error: '该申请已审批通过' });

  // 生成设备凭证
  const deviceCode = 'DEV-' + crypto.randomBytes(6).toString('hex').toUpperCase();
  const deviceToken = crypto.randomBytes(24).toString('hex');
  const deviceName = row.device_name
    || (row.model ? `${row.model}` : null)
    || `设备-${row.request_code.slice(-6)}`;

  const tx = db.transaction(() => {
    // 创建设备
    const info = db.prepare(`
      INSERT INTO devices (device_code, device_name, device_token, platform, model, os_version, app_version)
      VALUES (?, ?, ?, 'android', ?, ?, ?)
    `).run(deviceCode, deviceName, deviceToken, row.model, row.os_version, row.app_version);

    const deviceId = info.lastInsertRowid;

    // 更新申请状态
    db.prepare(`
      UPDATE join_requests SET
        status = 'approved', device_id = ?,
        review_comment = ?, reviewed_by = ?, reviewed_at = datetime('now')
      WHERE id = ?
    `).run(deviceId, review_comment || null, req.user.username, row.id);

    // 写入操作日志
    logOperation(req.user, 'approve_join', 'join_request', row.id, {
      request_code: row.request_code,
      device_id: deviceId,
      device_code: deviceCode,
      comment: review_comment || null
    });
    logOperation(req.user, 'create_device', 'device', deviceId, {
      source: 'join_request',
      request_code: row.request_code
    });

    return deviceId;
  });

  const deviceId = tx();

  // 实时通知管理端：申请状态变更
  broadcastToAdmins({
    type: 'join_request_updated',
    request_id: row.id,
    status: 'approved',
    device_id: deviceId
  });

  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(deviceId);
  res.json({
    ok: true,
    message: '审批通过，设备凭证已生成',
    device,
    request_code: row.request_code
  });
});

// 拒绝申请
router.post('/:id/reject', (req, res) => {
  const { review_comment } = req.body || {};
  const row = db.prepare('SELECT * FROM join_requests WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '申请不存在' });
  if (row.status !== 'pending') return res.status(400).json({ error: '该申请已处理' });

  db.prepare(`
    UPDATE join_requests SET
      status = 'rejected',
      review_comment = ?, reviewed_by = ?, reviewed_at = datetime('now')
    WHERE id = ?
  `).run(review_comment || null, req.user.username, row.id);

  logOperation(req.user, 'reject_join', 'join_request', row.id, {
    request_code: row.request_code,
    comment: review_comment || null
  });

  broadcastToAdmins({
    type: 'join_request_updated',
    request_id: row.id,
    status: 'rejected'
  });

  res.json({ ok: true, message: '已拒绝' });
});

module.exports = router;
