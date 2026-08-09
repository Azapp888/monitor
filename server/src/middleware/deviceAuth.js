// 设备端鉴权中间件 - 基于 device_code + device_token
const db = require('../db');

function deviceAuthRequired(req, res, next) {
  const header = req.headers.authorization || '';
  // 支持两种方式：Header: "Device <code>:<token>" 或 body 中携带
  let deviceCode = null;
  let deviceToken = null;

  if (header.startsWith('Device ')) {
    const raw = header.slice(7);
    const sep = raw.indexOf(':');
    if (sep > 0) {
      deviceCode = raw.slice(0, sep);
      deviceToken = raw.slice(sep + 1);
    }
  }

  if (!deviceCode || !deviceToken) {
    deviceCode = req.body && req.body.device_code;
    deviceToken = req.body && req.body.device_token;
  }

  if (!deviceCode || !deviceToken) {
    return res.status(401).json({ error: '缺少设备凭证' });
  }

  const device = db.prepare('SELECT * FROM devices WHERE device_code = ? AND device_token = ?')
    .get(deviceCode, deviceToken);
  if (!device) {
    return res.status(403).json({ error: '设备凭证无效' });
  }
  req.device = device;
  next();
}

module.exports = { deviceAuthRequired };
