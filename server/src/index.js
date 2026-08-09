// 手机服务器监控系统 - 后端入口
const http = require('http');
const path = require('path');
const fs = require('fs');
const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const config = require('./config');
require('./db'); // 初始化数据库

const authRoutes = require('./routes/auth');
const deviceRoutes = require('./routes/device');
const monitorRoutes = require('./routes/monitor');
const joinRoutes = require('./routes/join');
const versionRoutes = require('./routes/version');
const { initWebSocket } = require('./websocket');

const app = express();

app.use(cors({ origin: config.CORS_ORIGIN }));
app.use(express.json({ limit: '1mb' }));
app.use(morgan('dev'));

// 确保上传目录存在
const UPLOAD_ROOT = path.join(__dirname, '..', 'uploads');
fs.mkdirSync(path.join(UPLOAD_ROOT, 'audio'), { recursive: true });

// 健康检查
app.get('/health', (req, res) => res.json({ ok: true, time: new Date().toISOString() }));

// 路由挂载
app.use('/api/auth', authRoutes);
app.use('/api/devices', deviceRoutes);
app.use('/api/monitor', monitorRoutes);
app.use('/api/join-requests', joinRoutes);
app.use('/api/version', versionRoutes);

// 全局错误处理
app.use((err, req, res, next) => {
  console.error('[ERR]', err);
  res.status(500).json({ error: '服务器内部错误' });
});

const server = http.createServer(app);
initWebSocket(server);

server.listen(config.PORT, () => {
  console.log('========================================');
  console.log('  手机服务器监控系统 - 后端服务已启动');
  console.log('========================================');
  console.log(`  HTTP:  http://localhost:${config.PORT}`);
  console.log(`  WS:    ws://localhost:${config.PORT}/ws`);
  console.log(`  数据库: ${config.DB_PATH}`);
  console.log(`  上传目录: ${UPLOAD_ROOT}`);
  console.log(`  默认账号: ${config.DEFAULT_ADMIN.username} / ${config.DEFAULT_ADMIN.password}`);
  console.log('----------------------------------------');
  console.log('  设备上报接口:');
  console.log(`    POST /api/monitor/report/register   设备激活`);
  console.log(`    POST /api/monitor/report            上报监控数据`);
  console.log(`    POST /api/monitor/report/heartbeat  心跳`);
  console.log(`    POST /api/monitor/report/offline    下线`);
  console.log(`    POST /api/monitor/report/phone-call 上报来电`);
  console.log(`    POST /api/monitor/report/audio      上传录音分片(multipart)`);
  console.log('----------------------------------------');
  console.log('  管理端接口:');
  console.log(`    GET  /api/monitor/phone-calls       来电记录列表`);
  console.log(`    GET  /api/monitor/audio             录音列表`);
  console.log(`    GET  /api/monitor/audio/:id/download 下载/播放录音`);
  console.log('========================================');
});
