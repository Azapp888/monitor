# Monitor · 手机服务器监控系统

一套自用的服务器与设备监控系统：后端采集 / 接收设备上报数据，管理端以 Web 界面展示设备状态、位置与通话记录等信息。

## ✨ 功能特性

- **账号与鉴权** — 管理员登录（JWT）、bcrypt 密码哈希、设备专用上报 Token
- **设备管理** — 设备列表、详情、在线状态
- **实时监控** — WebSocket 推送最新指标，前端无需轮询
- **加入申请** — 新设备申请接入 → 管理端审批
- **地图定位** — 集成高德地图展示设备位置
- **通话记录** — 语音文件上传与记录查看（multer）
- **版本发布** — App 版本管理与发布记录
- **SQLite 持久化** — 免运维单文件数据库

## 🧱 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Node.js + Express |
| 数据库 | better-sqlite3 |
| 实时通信 | ws（WebSocket） |
| 认证 | jsonwebtoken + bcryptjs |
| 上传 | multer |
| 管理端 | React 单页应用（构建产物位于 `web-admin/dist`） |
| 地图 | 高德地图 JS API |

## 📁 目录结构

```
monitor/
├── server/
│   ├── src/
│   │   ├── index.js         # 入口，挂载路由与 WebSocket
│   │   ├── config.js        # 端口、JWT、默认管理员、数据库路径
│   │   ├── db.js            # SQLite 初始化
│   │   ├── monitor.js       # 指标采集
│   │   ├── websocket.js     # 实时推送
│   │   ├── middleware/      # 鉴权等中间件
│   │   └── routes/          # auth / device / monitor / join / version
│   ├── uploads/             # 上传文件（不纳入版本控制）
│   └── package.json
└── web-admin/
    └── dist/                # 管理端构建产物（Dashboard、Devices、MapView…）
```

## 🚀 快速开始

```bash
cd server
npm install
npm start            # 默认端口 12345
```

管理端 `web-admin/dist` 用任意静态服务器托管即可（也可由后端统一托管）。

## ⚙️ 配置

`server/src/config.js` 支持环境变量覆盖：

| 变量 | 说明 |
|---|---|
| `PORT` | 服务端口（默认 12345） |
| `JWT_SECRET` | JWT 密钥，**生产环境必须设置** |
| `DB_PATH` | SQLite 文件路径（默认 `server/data.db`） |

首次启动会自动创建默认管理员账号（`admin`）。**上线前请立刻修改默认密码，并设置强随机 `JWT_SECRET`。**

## 📌 说明

仓库已移除 SQLite 数据库文件（`data.db*`）、上传目录、构建备份与日志。管理端 `dist/` 为已构建产物（其源码不在本目录内），如需二次开发请自行初始化前端工程。

## 📄 License

MIT
