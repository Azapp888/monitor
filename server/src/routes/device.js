// 设备管理路由 - 管理端使用
const express = require('express');
const crypto = require('crypto');
const db = require('../db');
const { authRequired } = require('../middleware/auth');

const router = express.Router();

// 所有设备管理接口均需管理员鉴权
router.use(authRequired);

// 设备列表（支持分页、关键字搜索、状态过滤）
router.get('/', (req, res) => {
  const { keyword, status, page = 1, pageSize = 20 } = req.query;
  const limit = Math.min(parseInt(pageSize, 10) || 20, 100);
  const offset = (Math.max(parseInt(page, 10) || 1, 1) - 1) * limit;

  const conditions = [];
  const params = [];
  if (keyword) {
    conditions.push('(device_code LIKE ? OR device_name LIKE ? OR model LIKE ?)');
    params.push(`%${keyword}%`, `%${keyword}%`, `%${keyword}%`);
  }
  if (status) {
    conditions.push('status = ?');
    params.push(status);
  }
  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';

  const total = db.prepare(`SELECT COUNT(*) as count FROM devices ${where}`).get(...params).count;
  const rows = db.prepare(`
    SELECT * FROM devices ${where}
    ORDER BY last_seen_at DESC NULLS LAST, id DESC
    LIMIT ? OFFSET ?
  `).all(...params, limit, offset);

  res.json({ total, page: Math.floor(offset / limit) + 1, pageSize: limit, list: rows });
});

// 设备详情
router.get('/:id', (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(req.params.id);
  if (!device) return res.status(404).json({ error: '设备不存在' });
  res.json({ device });
});

// 新建设备并生成 device_code / device_token
router.post('/', (req, res) => {
  const { device_name, platform = 'android', model, os_version, app_version } = req.body || {};
  if (!device_name) return res.status(400).json({ error: '设备名称不能为空' });

  const device_code = 'DEV-' + crypto.randomBytes(6).toString('hex').toUpperCase();
  const device_token = crypto.randomBytes(24).toString('hex');

  const info = db.prepare(`
    INSERT INTO devices (device_code, device_name, device_token, platform, model, os_version, app_version)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).run(device_code, device_name, device_token, platform, model || null, os_version || null, app_version || null);

  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(info.lastInsertRowid);
  res.status(201).json({ device });
});

// 更新设备信息
router.put('/:id', (req, res) => {
  const { device_name, platform, model, os_version, app_version, status } = req.body || {};
  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(req.params.id);
  if (!device) return res.status(404).json({ error: '设备不存在' });

  db.prepare(`
    UPDATE devices SET
      device_name = COALESCE(?, device_name),
      platform = COALESCE(?, platform),
      model = COALESCE(?, model),
      os_version = COALESCE(?, os_version),
      app_version = COALESCE(?, app_version),
      status = COALESCE(?, status)
    WHERE id = ?
  `).run(
    device_name || null, platform || null, model || null,
    os_version || null, app_version || null, status || null, device.id
  );

  res.json({ device: db.prepare('SELECT * FROM devices WHERE id = ?').get(device.id) });
});

// 重新生成设备 token
router.post('/:id/regenerate-token', (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(req.params.id);
  if (!device) return res.status(404).json({ error: '设备不存在' });
  const device_token = crypto.randomBytes(24).toString('hex');
  db.prepare('UPDATE devices SET device_token = ? WHERE id = ?').run(device_token, device.id);
  res.json({ device_token });
});

// 删除设备（级联删除其监控数据）
router.delete('/:id', (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(req.params.id);
  if (!device) return res.status(404).json({ error: '设备不存在' });
  db.prepare('DELETE FROM devices WHERE id = ?').run(device.id);
  res.json({ message: '设备已删除' });
});

module.exports = router;
