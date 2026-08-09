// App 版本管理路由
//
//   公开接口（设备端，无需鉴权）：
//     GET  /api/version/check?current_version_code=123  检查更新
//     GET  /api/version/download/:id                    下载 APK
//
//   管理端接口（需 JWT 鉴权）：
//     GET  /api/version                  版本列表
//     POST /api/version/upload           上传新版本 APK（multipart: file, version_code, version_name, release_notes, force_update）
//     PUT  /api/version/:id/activate    激活指定版本（同时停用其他）
//     DELETE /api/version/:id            删除版本（同时删除文件）
const express = require('express');
const path = require('path');
const fs = require('fs');
const multer = require('multer');
const db = require('../db');
const { authRequired } = require('../middleware/auth');

const router = express.Router();

// APK 上传目录
const APK_DIR = path.join(__dirname, '..', '..', 'uploads', 'apk');
fs.mkdirSync(APK_DIR, { recursive: true });

// multer 配置：APK 文件，单文件最大 100MB
const apkStorage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, APK_DIR),
  filename: (req, file, cb) => {
    // 文件名格式：app_v<versionCode>_<timestamp>.apk
    const vc = req.body?.version_code || 'unknown';
    const ts = Date.now();
    cb(null, `app_v${vc}_${ts}.apk`);
  }
});
const apkUpload = multer({
  storage: apkStorage,
  limits: { fileSize: 100 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    // 仅允许 .apk 文件
    if (!file.originalname.toLowerCase().endsWith('.apk')) {
      return cb(new Error('仅支持 .apk 文件'));
    }
    cb(null, true);
  }
});

/**
 * 构造 APK 下载 URL（相对路径，设备端用配置的 serverUrl 拼接）
 * 格式：/api/version/download/<id>
 */
function buildDownloadUrl(id) {
  return `/api/version/download/${id}`;
}

// ========== 公开接口（设备端，无需鉴权）==========

// 检查更新 - 设备端启动时调用
// Query: current_version_code (必填)
// 返回 { has_update, latest: { version_code, version_name, download_url, file_size, release_notes, force_update } }
router.get('/check', (req, res) => {
  const currentCode = parseInt(req.query.current_version_code, 10);
  if (isNaN(currentCode)) {
    return res.status(400).json({ error: 'current_version_code 必填且必须为整数' });
  }

  // 取当前激活的最新版本
  const latest = db.prepare(`
    SELECT * FROM app_releases WHERE is_active = 1
    ORDER BY version_code DESC LIMIT 1
  `).get();

  if (!latest) {
    return res.json({
      has_update: false,
      message: '暂无可用版本'
    });
  }

  const hasUpdate = latest.version_code > currentCode;
  res.json({
    has_update: hasUpdate,
    latest: {
      version_code: latest.version_code,
      version_name: latest.version_name,
      download_url: buildDownloadUrl(latest.id),
      file_size: latest.file_size,
      md5: latest.md5,
      release_notes: latest.release_notes || '',
      force_update: !!latest.force_update
    },
    current_version_code: currentCode
  });
});

// 下载 APK - 设备端通过此 URL 下载安装包
router.get('/download/:id', (req, res) => {
  const row = db.prepare('SELECT * FROM app_releases WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '版本不存在' });

  const absPath = path.isAbsolute(row.file_path)
    ? row.file_path
    : path.join(__dirname, '..', '..', row.file_path);
  if (!fs.existsSync(absPath)) {
    return res.status(404).json({ error: 'APK 文件不存在' });
  }

  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', `attachment; filename="app_v${row.version_name}.apk"`);
  res.setHeader('Content-Length', row.file_size || fs.statSync(absPath).size);
  fs.createReadStream(absPath).pipe(res);
});

// ========== 管理端接口（鉴权）==========
router.use(authRequired);

// 版本列表
router.get('/', (req, res) => {
  const list = db.prepare(`
    SELECT * FROM app_releases ORDER BY created_at DESC
  `).all().map(r => ({
    ...r,
    force_update: !!r.force_update,
    is_active: !!r.is_active,
    download_url: buildDownloadUrl(r.id)
  }));
  res.json({ list });
});

// 上传新版本 APK
// multipart/form-data:
//   file: APK 文件
//   version_code: 版本号（整数）
//   version_name: 版本名（如 "1.2.0"）
//   release_notes: 更新说明（可选）
//   force_update: 是否强制更新（"1" 或 "0"）
//   is_active: 是否立即激活（"1" 或 "0"，默认 1）
router.post('/upload', (req, res) => {
  apkUpload.single('file')(req, res, (err) => {
    if (err) {
      return res.status(400).json({ error: err.message || '上传失败' });
    }
    if (!req.file) {
      return res.status(400).json({ error: '未上传 APK 文件' });
    }
    const { version_code, version_name, release_notes, force_update, is_active } = req.body || {};
    const vc = parseInt(version_code, 10);
    if (isNaN(vc)) {
      // 清理已上传的文件
      try { fs.unlinkSync(req.file.path); } catch (_) {}
      return res.status(400).json({ error: 'version_code 必填且为整数' });
    }
    if (!version_name) {
      try { fs.unlinkSync(req.file.path); } catch (_) {}
      return res.status(400).json({ error: 'version_name 必填' });
    }

    // 检查版本号是否已存在
    const existing = db.prepare('SELECT id FROM app_releases WHERE version_code = ?').get(vc);
    if (existing) {
      try { fs.unlinkSync(req.file.path); } catch (_) {}
      return res.status(409).json({ error: `版本号 ${vc} 已存在` });
    }

    const fileSize = req.file.size;
    const relativePath = path.relative(path.join(__dirname, '..', '..'), req.file.path);

    const tx = db.transaction(() => {
      // 若 is_active=1，先把其他版本全部停用
      const activate = (is_active !== '0');
      if (activate) {
        db.prepare('UPDATE app_releases SET is_active = 0').run();
      }
      const info = db.prepare(`
        INSERT INTO app_releases
          (version_code, version_name, file_path, file_size, release_notes,
           force_update, is_active, uploaded_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        vc, version_name, relativePath, fileSize,
        release_notes || null,
        force_update === '1' ? 1 : 0,
        activate ? 1 : 0,
        req.user.username
      );
      return info.lastInsertRowid;
    });

    const id = tx();
    const row = db.prepare('SELECT * FROM app_releases WHERE id = ?').get(id);
    res.status(201).json({
      ok: true,
      release: {
        ...row,
        force_update: !!row.force_update,
        is_active: !!row.is_active,
        download_url: buildDownloadUrl(row.id)
      }
    });
  });
});

// 激活指定版本（同时停用其他）
router.put('/:id/activate', (req, res) => {
  const row = db.prepare('SELECT * FROM app_releases WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '版本不存在' });
  db.prepare('UPDATE app_releases SET is_active = 0').run();
  db.prepare('UPDATE app_releases SET is_active = 1 WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

// 设置强制更新标志
router.put('/:id/force', (req, res) => {
  const { force_update } = req.body || {};
  const row = db.prepare('SELECT * FROM app_releases WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '版本不存在' });
  db.prepare('UPDATE app_releases SET force_update = ? WHERE id = ?')
    .run(force_update ? 1 : 0, req.params.id);
  res.json({ ok: true });
});

// 删除版本（同时删除 APK 文件）
router.delete('/:id', (req, res) => {
  const row = db.prepare('SELECT * FROM app_releases WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: '版本不存在' });
  if (row.is_active) return res.status(400).json({ error: '不能删除当前激活版本，请先激活其他版本' });

  const absPath = path.isAbsolute(row.file_path)
    ? row.file_path
    : path.join(__dirname, '..', '..', row.file_path);
  try { fs.unlinkSync(absPath); } catch (_) { /* 文件可能已不存在 */ }

  db.prepare('DELETE FROM app_releases WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

module.exports = router;
