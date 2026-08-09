# Azapp 内存管理 · 设备监控系统

> 面向 Android 设备（手机/平板）的远程监控管理平台，包含 Android 客户端、Web 管理后台与 Node.js 后端服务三大组成部分。

![Android](https://img.shields.io/badge/Platform-Android-green) ![Web](https://img.shields.io/badge/Platform-Web-blue) ![Server](https://img.shields.io/badge/Backend-Node.js-43853D) ![License](https://img.shields.io/badge/License-MIT-orange)

---

## 目录

- [一、项目介绍](#一项目介绍)
- [二、三大模块简介](#二三大模块简介)
- [三、本地部署教程](#三本地部署教程)
- [四、项目目录结构](#四项目目录结构)
- [五、分支规划说明](#五分支规划说明)
- [六、版本更新说明](#六版本更新说明)
- [七、开源协议](#七开源协议)
- [八、使用注意事项](#八使用注意事项)

---

## 一、项目介绍

### 1.1 开发用途

本项目是一套 **Android 设备远程监控管理系统**，用于对部署在指定场所（如展馆、门店、机房等）的 Android 设备进行统一纳管：

- 设备侧安装 **Android 客户端**（Azapp内存管理），开机自启、常驻后台，自动采集设备状态并上报；
- 管理侧使用 **Web 管理后台**，实时查看设备在线状态、位置、通话、短信、录音、应用使用情况等；
- 服务端采用 **Node.js + SQLite** 轻量架构，单机即可部署，支持设备激活、绑定、Token 鉴权与 WebSocket 实时推送。

### 1.2 核心功能

| 模块 | 功能点 |
| ---- | ------ |
| 设备管理 | 设备注册/激活、加入申请审批、Token 重发、在线状态监控、设备删除 |
| 实时监控 | 位置（GPS/网络）、电量、内存、CPU、当前应用使用情况，WebSocket 实时推送 |
| 通话监控 | 来电/去电记录上报，管理端可查看通话详情 |
| 短信监控 | 读取全部短信 + 新短信实时接收，支持查看与批量删除 |
| 录音管理 | 管理端远程开启录音，音频文件上传、试听、下载、删除 |
| 系统防护 | 设备管理员激活（防卸载）、开机自启、前台服务常驻、应用隐藏入口 |
| 应用更新 | 客户端内置更新检查，支持 APK 版本发布与静默升级引导 |
| 管理后台 | 管理员登录、密码修改、仪表盘统计、设备地图分布、设备详情、版本发布管理 |

---

## 二、三大模块简介

> 说明：当前版本包含 **Android 客户端** 与 **Web 网页端（含后端服务）** 两大业务端；Windows 桌面客户端暂未开发，仓库分支亦未创建 windows 分支，如需新增可随时补充。

### 2.1 安卓 Android 客户端（`android-client/`）

- **应用名称**：Azapp内存管理
- **包名**：`com.venue.monitor`　**版本**：`1.0.0`　**minSdk** 26 / **targetSdk** 34
- **技术栈**：Kotlin + Android Gradle Plugin + ViewBinding + Retrofit/OkHttp + WorkManager + Google Location + WebSocket

**核心能力**

1. **常驻监控**：前台服务（location|dataSync|microphone 类型）+ WorkManager 定时任务兜底，保证进程存活；
2. **数据采集**：位置、电量、内存、CPU、当前前台应用、通话记录、短信内容，支持离线暂存（PendingUploadStore）后补传；
3. **实时通道**：WebSocket 长连接，实时接收管理端指令（如远程开启录音、更新检查）；
4. **系统级防护**：设备管理员（防卸载）、开机自启（BOOT_COMPLETED + 应用替换）、隐藏桌面图标（activity-alias）、隐藏配置入口（标题连点 7 次进入）；
5. **应用更新**：内置 UpdateChecker，支持版本比对与 APK 安装（FileProvider + REQUEST_INSTALL_PACKAGES）。

**文件结构**

```
android-client/
├── app/
│   ├── src/main/
│   │   ├── java/com/venue/monitor/
│   │   │   ├── MainActivity.kt / SplashActivity.kt / ConfigActivity.kt
│   │   │   ├── MonitorApplication.kt / MonitorState.kt
│   │   │   ├── api/            # Retrofit API 定义与客户端
│   │   │   ├── data/           # 数据模型、偏好存储、离线暂存
│   │   │   ├── device/         # 设备状态采集（电量/内存/应用/短信等）
│   │   │   ├── location/       # 位置服务
│   │   │   ├── net/            # WebSocket 客户端
│   │   │   ├── receiver/       # 开机/短信/来电广播接收器
│   │   │   ├── service/        # 前台监控服务、录音服务
│   │   │   ├── update/         # 版本更新检查
│   │   │   └── work/           # WorkManager 定时任务
│   │   └── res/                # 布局、图标、资源
│   ├── build.gradle.kts / proguard-rules.pro
│   └── ...
├── build.gradle.kts / settings.gradle.kts
├── gradle/  gradlew  gradlew.bat  gradle.properties
└── local.properties            # 本地 SDK 路径（不入库）
```

**运行环境**

| 项目 | 要求 |
| ---- | ---- |
| 构建工具 | Android Studio（Koala 及以上），JDK 17 |
| Gradle | 8.x（仓库自带 wrapper） |
| 设备系统 | Android 8.0（API 26）及以上 |
| 依赖仓库 | 已配置阿里云镜像 + google() + mavenCentral() |

### 2.2 Web 网页端（`web-admin/`）

- **名称**：venue-monitor-web　**版本**：`1.0.0`
- **技术栈**：Vue 3 + Vite + Vue Router + Pinia + Element Plus + ECharts + 高德地图 JS API

**核心功能**

1. **登录与权限**：管理员账号登录、JWT 鉴权、修改密码；
2. **仪表盘（Dashboard）**：设备总数、在线/离线统计、报警概览，ECharts 图表展示；
3. **设备管理（Devices）**：设备列表、详情、Token 管理、设备删除；
4. **地图视图（MapView）**：基于高德地图展示设备实时位置分布；
5. **加入申请（JoinRequests）**：设备激活申请审批流（同意/拒绝 + 操作日志）；
6. **通话记录（PhoneCalls）** / **短信记录（SmsMessages）**：浏览、详情、删除；
7. **录音管理（Audio）**：录音列表、在线试听、下载、删除；
8. **版本发布（AppReleases）**：管理 APK 版本、上传安装包、发布更新；
9. **设置（Settings）**：系统参数配置。

**文件结构**

```
web-admin/
├── index.html
├── vite.config.js              # dev 端口 3010，/api 与 /ws 代理到 12345
├── package.json
├── .env                        # 高德地图 Key（不入库，按 .env.example 创建）
├── .env.example                # 环境变量模板
├── .env.production             # 生产环境 API 地址
├── dist/                       # 构建产物（npm run build 生成）
└── src/
    ├── main.js / App.vue / style.css
    ├── router/                 # 路由
    ├── stores/                 # Pinia 状态（auth 等）
    ├── api/                    # axios 接口封装
    ├── utils/                  # amap / ws / format 工具
    ├── components/             # 通用组件（AmapView）
    └── views/                  # 页面（Dashboard/Devices/MapView/…）
```

**运行环境**

| 项目 | 要求 |
| ---- | ---- |
| 运行时 | Node.js 18+、npm |
| 开发启动 | `npm install && npm run dev`（端口 3010） |
| 生产构建 | `npm run build`（产物在 dist/） |
| 地图依赖 | 高德地图 Web 端 JS Key + 安全密钥（申请地址：https://lbs.amap.com/dev/key/app） |

### 2.3 后端服务（`server/`）

- **名称**：venue-monitor-server　**版本**：`1.0.0`
- **技术栈**：Node.js + Express + better-sqlite3 + JSON Web Token + ws

**核心功能**

1. 管理员认证（登录、改密、JWT 7 天有效期）；
2. 设备管理接口（CRUD、Token 生成/重发、删除）；
3. 监控数据上报接口（激活注册、数据上报、心跳），设备 Token 鉴权（有效期 365 天）；
4. 加入申请审批流程 + 操作日志；
5. 短信/通话/录音/应用使用数据查询与删除，音频文件上传下载；
6. 版本发布管理（APK 上传与元信息）；
7. WebSocket 实时推送（`/ws`，设备在线状态、指令下发）；
8. SQLite 持久化（better-sqlite3，首次启动自动建表并创建默认管理员）。

**文件结构**

```
server/
├── package.json
├── src/
│   ├── index.js                # 服务入口（HTTP + WS）
│   ├── config.js               # 端口/JWT/数据库/默认账号配置
│   ├── db.js                   # SQLite 初始化与建表
│   ├── websocket.js            # WebSocket 实时通道
│   ├── middleware/             # auth / deviceAuth 鉴权
│   └── routes/                 # auth / device / monitor / join / version
├── uploads/audio/              # 录音文件存储（运行时生成）
└── data.db                     # SQLite 数据库（运行时生成，不入库）
```

**运行环境**

| 项目 | 要求 |
| ---- | ---- |
| 运行时 | Node.js 18+ |
| 启动 | `npm install && npm start`（默认端口 12345） |
| 数据库 | SQLite（better-sqlite3，需本机可编译/安装） |
| 默认账号 | `admin` / `admin123`（首次启动自动创建，请及时修改） |

---

## 三、本地部署教程

### 3.1 Windows 部署（后端 + 网页端）

> 服务端与网页端均可在 Windows 上运行（需先安装 Node.js 18+）。

**第一步：安装 Node.js**

前往 https://nodejs.org 下载并安装 LTS 版本，安装完成后打开 CMD/PowerShell 验证：

```bash
node -v
npm -v
```

**第二步：启动后端服务**

```bash
# 进入 server 目录
cd server
npm install
npm start
```

看到如下输出即启动成功（默认端口 12345）：

```
手机服务器监控系统 - 后端服务已启动
  HTTP:  http://localhost:12345
  WS:    ws://localhost:12345/ws
  默认账号: admin / admin123
```

**第三步：启动 Web 管理后台（开发模式）**

```bash
cd web-admin
npm install
npm run dev
```

浏览器访问 `http://localhost:3010`，使用 `admin / admin123` 登录。

**第四步（可选）：构建网页端生产包**

```bash
npm run build
```

构建产物位于 `web-admin/dist/`，可用任意静态服务器托管，并将 `dist` 目录通过 Nginx 等反向代理到后端 `12345` 端口。

### 3.2 安卓端安装 / 构建

**方式一：直接安装 APK（推荐）**

1. 从 GitHub Releases 下载最新 APK：`Azapp内存管理 v1.0.0`；
2. 将 APK 拷贝至目标安卓设备，点击安装（需允许"安装未知来源应用"）；
3. 首次打开 App，在**主界面标题连续点击 7 次**进入隐藏的"系统服务配置"页；
4. 填写服务器地址（如 `http://192.168.101.69:12345`）并获取/输入激活码完成激活；
5. 按引导授予权限：位置（含后台）、电话、短信、录音、使用情况访问、设备管理员（防卸载）、开机自启、忽略电池优化。

**方式二：Android Studio 源码构建**

1. 使用 Android Studio 打开 `android-client/` 目录；
2. 等待 Gradle 同步完成（JDK 17，仓库已配置国内镜像）；
3. 配置 `android-client/local.properties` 中的 `sdk.dir`（指向本机 SDK 路径）；
4. 点击 Run ▶ 安装到模拟器或真机，或 Build → Build APK(s) 生成安装包。

> 说明：`web-admin/.env` 中 `VITE_API_BASE` 需指向可访问的后端地址；安卓客户端在配置页填写的地址即后端 `12345` 端口地址。

---

## 四、项目目录结构

```
monitor/                          # 仓库根目录（main 分支仅含文档与配置）
├── README.md                     # 本项目说明文档（本文件）
├── .gitignore                    # 全局忽略规则（缓存/日志/临时文件）
│
├── android-client/               # 【安卓端】Android Studio 工程源码
│   ├── app/                      #   应用模块（源码 + 资源 + 构建配置）
│   ├── gradle/  gradlew*         #   Gradle wrapper
│   ├── build.gradle.kts          #   工程级构建脚本
│   ├── settings.gradle.kts       #   工程设置（rootProject: VenueMonitor）
│   └── local.properties          #   本地 SDK 路径（不入库）
│
├── web-admin/                    # 【网页端】Vue3 管理后台源码
│   ├── src/                      #   前端源码（页面/路由/组件/状态）
│   ├── vite.config.js            #   Vite 构建与代理配置
│   ├── .env / .env.example       #   环境变量（Key 不入库）
│   └── dist/                     #   构建产物（npm run build 生成）
│
└── server/                       # 【后端服务】Node.js API + WebSocket
    ├── src/                      #   入口/路由/中间件/数据库/WS
    ├── uploads/                  #   录音等上传文件（运行时生成）
    └── package.json
```

**分支与目录对应关系**

| 分支 | 对应目录 | 内容 |
| ---- | -------- | ---- |
| `main` | 根目录 | 项目说明文档、公共配置（README.md、.gitignore） |
| `android` | `android-client/` | 安卓端完整源码 + APK 安装包 |
| `web` | `web-admin/` + `server/` | 网页端管理后台源码 + 后端服务源码 |

---

## 五、分支规划说明

仓库采用"文档与代码隔离"的多分支管理策略：

- **main 分支**：仅存放统一项目说明、公共配置与 README 文档，不含任何业务源码；
- **android 分支**：安卓客户端完整代码与 APK 安装包；
- **web 分支**：网页端管理后台与后端服务完整代码；
- 各业务分支相互独立、互不混杂；新增模块（如 Windows 客户端）时，从 main 派生对应分支即可。

---

## 六、版本更新说明

### v1.0.0（2026-08-09）

**新增**

- 安卓客户端：设备激活/加入申请、前台常驻服务、开机自启、设备管理员防卸载、GPS/电量/内存/CPU 采集、通话与短信监控、远程录音、应用使用统计、内置版本更新；
- Web 管理后台：管理员登录、仪表盘统计、设备管理、高德地图实时位置、加入申请审批、通话/短信/录音查看、APK 版本发布管理；
- 后端服务：RESTful API + WebSocket 实时推送、SQLite 持久化、JWT/设备 Token 双鉴权。

**适配系统**

| 端 | 适配系统 |
| -- | -------- |
| 安卓客户端 | Android 8.0（API 26）及以上（含 Android 14/15） |
| 网页端 | Chrome / Edge / Firefox / Safari 等现代浏览器 |
| 后端 | Windows 10/11、Linux、macOS（Node.js 18+） |

---

## 七、开源协议

本项目采用 **MIT License** 开源协议，详情见仓库 LICENSE 文件（如有）。你可以自由使用、修改、分发本项目，但需保留原版权声明。

---

## 八、使用注意事项

1. **合规使用**：本系统具备通话、短信、录音、位置等敏感信息采集能力，请仅在**合法授权的设备与场所**使用，并提前告知相关人员；禁止用于非法监控、侵犯隐私等行为，由此产生的法律责任由使用者自行承担；
2. **默认账号安全**：后端默认管理员 `admin / admin123`，上线前务必通过"修改密码"接口更换，并设置强密码；
3. **密钥安全**：`web-admin/.env` 中的高德地图 Key、后端 `JWT_SECRET` 均为示例/本地值，生产环境请通过环境变量覆盖（`JWT_SECRET`、`PORT`、`DB_PATH`、`CORS_ORIGIN` 等）；
4. **录音权限合规**：Android 14+ 对麦克风前台服务有严格限制，远程录音功能请确保业务场景合规，且客户端已实现录音前台服务声明；
5. **设备解锁风险**：激活"设备管理员"后，卸载 App 前必须先取消设备管理员激活，请妥善保管激活方式；
6. **数据备份**：数据库为本地 SQLite 文件（`server/data.db`），建议定期备份；录音文件位于 `server/uploads/audio/`，请一并纳入备份策略；
7. **网络环境**：设备上报与管理后台需能访问后端服务（含 WebSocket 端口），请正确配置防火墙与反向代理。
