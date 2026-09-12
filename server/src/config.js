// 服务器配置
const path = require('path');

module.exports = {
  // HTTP 服务端口
  PORT: process.env.PORT || 12345,

  // JWT 密钥（生产环境请通过环境变量覆盖）
  JWT_SECRET: process.env.JWT_SECRET || 'venue-monitor-secret-change-me-in-production',

  // JWT 有效期
  JWT_EXPIRES_IN: '7d',

  // 设备上报鉴权 token 有效期
  DEVICE_TOKEN_EXPIRES_IN: '365d',

  // 数据库文件路径
  DB_PATH: process.env.DB_PATH || path.join(__dirname, '..', 'data.db'),

  // 默认管理员账号（首次启动自动创建）
  DEFAULT_ADMIN: {
    username: 'admin',
    password: 'admin123'
  },

  // CORS 允许的来源
  CORS_ORIGIN: process.env.CORS_ORIGIN || '*'
};
