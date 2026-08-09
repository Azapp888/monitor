<template>
  <div class="page-container">
    <!-- 顶部：基本信息 + 最新状态 -->
    <el-row :gutter="16">
      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>设备信息</span>
              <el-button text @click="$router.back()">
                <el-icon><Back /></el-icon>返回
              </el-button>
            </div>
          </template>
          <el-descriptions :column="1" border v-if="device">
            <el-descriptions-item label="设备名称">{{ device.device_name }}</el-descriptions-item>
            <el-descriptions-item label="设备编码">
              <span style="font-family: monospace">{{ device.device_code }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="型号">{{ device.model || '-' }}</el-descriptions-item>
            <el-descriptions-item label="系统版本">{{ device.os_version || '-' }}</el-descriptions-item>
            <el-descriptions-item label="App 版本">{{ device.app_version || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusTagType(device.status)" size="small">{{ statusText(device.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="最后上报">{{ formatTime(device.last_seen_at) }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatTime(device.created_at) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="14">
        <el-card shadow="never">
          <template #header><span>最新监控状态</span></template>
          <el-row :gutter="16" v-if="latest">
            <el-col :xs="24" :sm="8">
              <div class="metric">
                <div class="metric-label">电量</div>
                <div class="metric-value">
                  <el-progress type="dashboard" :percentage="latest.battery_level ?? 0" :color="batteryProgressColor" :width="100" />
                </div>
                <div class="metric-sub">
                  <el-tag :type="latest.battery_charging ? 'success' : 'info'" size="small" effect="light" round>
                    <el-icon v-if="latest.battery_charging" style="margin-right: 2px"><Lightning /></el-icon>
                    {{ batteryChargingText(latest.battery_charging) }}
                  </el-tag>
                </div>
              </div>
            </el-col>
            <el-col :xs="24" :sm="16">
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="位置">
                  <span v-if="latest.latitude != null">
                    {{ latest.latitude.toFixed(5) }}, {{ latest.longitude.toFixed(5) }}
                    <span v-if="latest.accuracy" class="text-info">（精度 ±{{ latest.accuracy.toFixed(0) }}m）</span>
                  </span>
                  <span v-else class="text-info">无位置数据</span>
                </el-descriptions-item>
                <el-descriptions-item label="网络">
                  <el-tag size="small" :type="networkTagType(latest.network_type)">{{ networkTypeText(latest.network_type) }}</el-tag>
                  <span v-if="latest.network_strength != null" class="metric-sub">强度 {{ latest.network_strength }}/4</span>
                </el-descriptions-item>
                <el-descriptions-item label="WiFi 名称">
                  <span v-if="latest.extra?.ssid">{{ latest.extra.ssid }}</span>
                  <span v-else class="text-info">-</span>
                </el-descriptions-item>
                <el-descriptions-item label="设备 IP">
                  <span v-if="latest.extra?.ip_address" style="font-family: monospace">{{ latest.extra.ip_address }}</span>
                  <span v-else class="text-info">-</span>
                </el-descriptions-item>
                <el-descriptions-item label="当前使用软件">
                  <template v-if="latest.extra?.foreground_app">
                    <el-tag size="small" type="warning" effect="light">
                      {{ appName(latest.extra.foreground_app) }}
                    </el-tag>
                    <div v-if="latest.extra?.recent_apps?.length" class="recent-apps">
                      <span v-for="pkg in latest.extra.recent_apps.slice(0, 5)" :key="pkg" class="app-chip">{{ appName(pkg) }}</span>
                    </div>
                  </template>
                  <span v-else class="text-info">-</span>
                </el-descriptions-item>
                <el-descriptions-item label="电池温度">
                  {{ latest.battery_temperature != null ? latest.battery_temperature.toFixed(1) + ' °C' : '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="上报时间">{{ formatTime(latest.recorded_at) }}</el-descriptions-item>
                <el-descriptions-item label="接收时间">{{ formatTime(latest.received_at) }}</el-descriptions-item>
              </el-descriptions>
            </el-col>
          </el-row>
          <el-empty v-else description="暂无监控数据" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 历史数据 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>历史轨迹与趋势</span>
          <div class="history-controls">
            <el-radio-group v-model="range" size="small" @change="onRangeChange">
              <el-radio-button value="today">今天</el-radio-button>
              <el-radio-button value="7d">最近7天</el-radio-button>
              <el-radio-button value="30d">最近30天</el-radio-button>
            </el-radio-group>
            <el-date-picker
              v-model="customRange"
              type="datetimerange"
              size="small"
              class="range-picker"
              range-separator="-"
              start-placeholder="开始"
              end-placeholder="结束"
              value-format="YYYY-MM-DD HH:mm:ss"
              @change="onCustomRange"
            />
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="历史轨迹" name="track">
          <div class="track-map-wrapper">
            <AmapView :track="trackPoints" :markers="latestMarker" :auto-fit="true" />
          </div>
          <div class="track-info">共 {{ trackPoints.length }} 个轨迹点</div>
        </el-tab-pane>
        <el-tab-pane label="电量趋势" name="battery">
          <div ref="batteryChartEl" class="chart-wrapper"></div>
        </el-tab-pane>
        <el-tab-pane label="网络分布" name="network">
          <div ref="networkChartEl" class="chart-wrapper"></div>
        </el-tab-pane>
        <el-tab-pane label="应用使用" name="apps">
          <div class="apps-tab">
            <!-- 当前状态卡片 -->
            <el-card shadow="never" class="apps-current-card">
              <template #header>
                <div class="apps-card-header">
                  <span>当前应用状态</span>
                  <el-button text size="small" @click="loadCurrentApps">刷新</el-button>
                </div>
              </template>
              <div v-if="currentAppsLoading" v-loading="true" style="height: 60px"></div>
              <div v-else-if="currentApps.apps && currentApps.apps.length">
                <div v-if="currentApps.foreground" class="fg-app">
                  <el-tag type="success" size="small" effect="dark">前台</el-tag>
                  <span class="app-name">{{ currentApps.foreground.app_label || currentApps.foreground.package_name }}</span>
                  <span class="app-pkg">{{ currentApps.foreground.package_name }}</span>
                  <span class="app-ver" v-if="currentApps.foreground.version_name">v{{ currentApps.foreground.version_name }}</span>
                </div>
                <div v-else class="no-fg">无前台应用</div>
                <el-divider content-position="left">
                  后台运行 ({{ currentApps.apps.filter(a => !a.is_foreground).length }})
                </el-divider>
                <div class="bg-apps-list">
                  <el-tag
                    v-for="app in currentApps.apps.filter(a => !a.is_foreground)"
                    :key="app.package_name"
                    class="bg-app-tag"
                    :title="`${app.app_label || app.package_name}\n${app.package_name}\n最近活跃: ${formatLastUsed(app.last_used_at)}`"
                  >
                    {{ app.app_label || app.package_name }}
                  </el-tag>
                  <span v-if="currentApps.apps.filter(a => !a.is_foreground).length === 0" class="no-fg">无后台应用</span>
                </div>
                <div class="snapshot-time" v-if="currentApps.recorded_at">
                  最近快照：{{ formatTime(currentApps.recorded_at) }}
                </div>
              </div>
              <el-empty v-else description="暂无应用使用数据" :image-size="60" />
            </el-card>

            <!-- 历史时间线 -->
            <el-card shadow="never" style="margin-top: 12px">
              <template #header>
                <div class="apps-card-header">
                  <span>应用使用历史</span>
                  <div class="apps-filters">
                    <el-input
                      v-model="appFilters.package"
                      placeholder="包名过滤"
                      clearable
                      size="small"
                      style="width: 180px"
                      @keyup.enter="loadAppUsage(1)"
                      @clear="loadAppUsage(1)"
                    />
                    <el-checkbox
                      v-model="appFilters.foreground_only"
                      size="small"
                      style="margin-left: 12px"
                      @change="loadAppUsage(1)"
                    >仅前台</el-checkbox>
                    <el-button size="small" style="margin-left: 12px" @click="loadAppUsage(1)">查询</el-button>
                  </div>
                </div>
              </template>
              <el-table :data="appUsageList" v-loading="appUsageLoading" stripe size="small">
                <el-table-column label="时间" width="170">
                  <template #default="{ row }">{{ formatTime(row.recorded_at) }}</template>
                </el-table-column>
                <el-table-column label="应用" min-width="160">
                  <template #default="{ row }">
                    <span>{{ row.app_label || row.package_name }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="包名" min-width="200" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span style="font-family: monospace; font-size: 11px">{{ row.package_name }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="版本" width="100" prop="version_name">
                  <template #default="{ row }">{{ row.version_name || '-' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.is_foreground ? 'success' : 'info'" size="small">
                      {{ row.is_foreground ? '前台' : '后台' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="事件" width="110" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.event_type === 'foreground_change' ? 'warning' : ''" size="small" effect="plain">
                      {{ row.event_type === 'foreground_change' ? '切换' : '快照' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="累计可见" width="100">
                  <template #default="{ row }">{{ formatDuration(row.total_time_visible) }}</template>
                </el-table-column>
                <el-table-column label="最近活跃" width="140">
                  <template #default="{ row }">{{ formatLastUsed(row.last_used_at) }}</template>
                </el-table-column>
              </el-table>
              <el-pagination
                v-model:current-page="appPage.page"
                v-model:page-size="appPage.pageSize"
                :total="appPage.total"
                :page-sizes="[20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                style="margin-top: 12px"
                @current-change="loadAppUsage()"
                @size-change="loadAppUsage(1)"
              />
            </el-card>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 实时录音监听 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>实时录音监听</span>
          <div class="audio-controls">
            <el-tag v-if="audioStatus === 'recording'" type="danger" size="small" effect="dark">
              <span class="rec-dot"></span> 录音中
            </el-tag>
            <el-tag v-else-if="audioStatus === 'error'" type="warning" size="small">录音异常</el-tag>
            <el-tag v-else-if="audioStatus === 'starting'" type="info" size="small">开启中…</el-tag>
            <el-tag v-else type="info" size="small">未开启</el-tag>
            <el-button
              :type="audioStatus === 'recording' ? 'danger' : 'primary'"
              size="small"
              :loading="audioStatus === 'starting'"
              :disabled="audioStatus === 'starting'"
              @click="toggleRecording"
            >
              <el-icon v-if="audioStatus === 'recording'"><VideoPause /></el-icon>
              <el-icon v-else><Microphone /></el-icon>
              {{ audioStatus === 'recording' ? '停止录音' : '开始录音' }}
            </el-button>
            <el-button size="small" @click="loadAudioList">刷新</el-button>
          </div>
        </div>
      </template>

      <div v-if="currentSessionId" class="session-info">
        会话 ID：<span style="font-family: monospace">{{ currentSessionId }}</span>
      </div>

      <el-table :data="audioList" v-loading="audioLoading" stripe size="small" empty-text="暂无录音分片">
        <el-table-column label="#" prop="chunk_index" width="60" />
        <el-table-column label="接收时间" width="180">
          <template #default="{ row }">{{ formatTime(row.received_at) }}</template>
        </el-table-column>
        <el-table-column label="时长" width="100">
          <template #default="{ row }">
            {{ row.duration_seconds != null ? row.duration_seconds.toFixed(1) + ' s' : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatBytes(row.file_size) }}</template>
        </el-table-column>
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button
              :type="playingId === row.id ? 'warning' : 'primary'"
              link
              size="small"
              @click="togglePlay(row)"
            >
              <el-icon><VideoPlay v-if="playingId !== row.id" /><VideoPause v-else /></el-icon>
              {{ playingId === row.id ? '暂停' : '播放' }}
            </el-button>
            <el-button link type="danger" size="small" @click="onDeleteAudio(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 隐藏的音频播放器，由按钮控制 -->
      <audio
        ref="audioEl"
        @ended="onPlayEnded"
        @error="onPlayError"
        style="display:none"
      />
    </el-card>

    <!-- 短信记录 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>短信记录</span>
          <div class="sms-controls">
            <el-select
              v-model="smsFilters.type"
              placeholder="类型"
              clearable
              size="small"
              style="width: 120px"
              @change="loadSmsList(1)"
            >
              <el-option label="收到的" :value="1" />
              <el-option label="发出的" :value="2" />
            </el-select>
            <el-date-picker
              v-model="smsFilters.range"
              type="datetimerange"
              size="small"
              range-separator="-"
              start-placeholder="开始"
              end-placeholder="结束"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 340px; margin-left: 12px"
              @change="loadSmsList(1)"
            />
            <el-button size="small" style="margin-left: 12px" @click="loadSmsList(1)">查询</el-button>
            <el-button size="small" @click="loadSmsList()">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="smsList" v-loading="smsLoading" stripe size="small">
        <el-table-column label="收发时间" prop="received_at" width="170">
          <template #default="{ row }">{{ formatTime(row.received_at) }}</template>
        </el-table-column>
        <el-table-column label="对方号码" width="140">
          <template #default="{ row }">
            <div>
              <span v-if="row.address" style="font-family: monospace; font-weight: 500">
                {{ row.address }}
              </span>
              <span v-else class="text-info">未知</span>
              <div v-if="row.person_name" class="person-name">{{ row.person_name }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.type === 2 ? 'primary' : 'success'" size="small">
              {{ row.type === 2 ? '发出' : '收到' }}
            </el-tag>
            <el-tag
              v-if="row.read === 0"
              type="danger"
              size="small"
              effect="plain"
              style="margin-left: 4px"
            >未读</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="短信内容" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="sms-body-inline">{{ row.body || '(空内容)' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="onViewSms(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="smsPage.page"
          v-model:page-size="smsPage.pageSize"
          :total="smsPage.total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadSmsList(1)"
          @current-change="loadSmsList()"
        />
      </div>
    </el-card>

    <!-- 查看短信详情抽屉 -->
    <el-drawer
      v-model="smsDetailVisible"
      title="短信详情"
      direction="rtl"
      size="520px"
    >
      <div v-if="currentSms" class="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="收发时间">{{ formatTime(currentSms.received_at) }}</el-descriptions-item>
          <el-descriptions-item label="对方号码">
            <span style="font-family: monospace">{{ currentSms.address || '-' }}</span>
            <span v-if="currentSms.person_name" style="margin-left: 8px; color: #606266">
              ({{ currentSms.person_name }})
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            <el-tag :type="currentSms.type === 2 ? 'primary' : 'success'" size="small">
              {{ currentSms.type === 2 ? '发出' : '收到' }}
            </el-tag>
            <el-tag
              v-if="currentSms.read === 0"
              type="danger"
              size="small"
              effect="plain"
              style="margin-left: 4px"
            >未读</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div class="detail-body-title">短信内容</div>
        <div class="detail-body">{{ currentSms.body || '(空内容)' }}</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDevice, getMonitorList, getAudioList, deleteAudio, fetchAudioBlobUrl, getSmsList, getAppUsageList, getCurrentApps } from '@/api'
import { sendWsMessage, subscribeWsMessages, isWsOpen } from '@/utils/ws'
import { statusTagType, statusText, formatTime, networkTypeText, batteryChargingText } from '@/utils/format'
import AmapView from '@/components/AmapView.vue'

const route = useRoute()
const deviceId = route.params.id

const device = ref(null)
const latest = ref(null)
const history = ref([])
const range = ref('7d')
const customRange = ref(null)
const activeTab = ref('track')

const batteryChartEl = ref(null)
const networkChartEl = ref(null)
let batteryChart = null
let networkChart = null

// ===== 录音监听状态 =====
// audioStatus: 'idle' | 'starting' | 'recording' | 'error'
const audioStatus = ref('idle')
const currentSessionId = ref('')
const audioList = ref([])
const audioLoading = ref(false)
const audioEl = ref(null)
const playingId = ref(null)
let currentBlobUrl = null
let unsubscribeWs = null

// ===== 短信记录状态 =====
const smsList = ref([])
const smsLoading = ref(false)
const smsFilters = reactive({ type: '', range: null })
const smsPage = reactive({ page: 1, pageSize: 20, total: 0 })
const smsDetailVisible = ref(false)
const currentSms = ref(null)

// ===== 应用使用 =====
const appUsageList = ref([])
const appUsageLoading = ref(false)
const appFilters = reactive({ package: '', foreground_only: false })
const appPage = reactive({ page: 1, pageSize: 20, total: 0 })
const currentApps = ref({ recorded_at: null, foreground: null, apps: [] })
const currentAppsLoading = ref(false)
// 监听 WS app_usage 消息时刷新当前状态
let appUsageWsUnsub = null

const trackPoints = computed(() =>
  history.value
    .filter((h) => h.latitude != null && h.longitude != null)
    .map((h) => ({ latitude: h.latitude, longitude: h.longitude, recorded_at: h.recorded_at }))
    .reverse() // 按时间正序绘制轨迹
)

const latestMarker = computed(() => {
  if (!latest.value || latest.value.latitude == null) return []
  return [{
    id: device.value?.id,
    name: device.value?.device_name,
    status: device.value?.status,
    latitude: latest.value.latitude,
    longitude: latest.value.longitude,
    latest: latest.value
  }]
})

// 常见应用包名 → 中文名映射（未知包名回退为包名最后一段）
const KNOWN_APPS = {
  'com.tencent.mm': '微信',
  'com.tencent.mobileqq': 'QQ',
  'com.tencent.tim': 'TIM',
  'com.taobao.taobao': '淘宝',
  'com.taobao.idlefish': '闲鱼',
  'com.eg.android.AlipayGphone': '支付宝',
  'com.sina.weibo': '微博',
  'com.ss.android.ugc.aweme': '抖音',
  'com.ss.android.article.news': '今日头条',
  'com.smile.gifmaker': '快手',
  'com.xingin.xhs': '小红书',
  'com.netease.cloudmusic': '网易云音乐',
  'com.tencent.qqmusic': 'QQ音乐',
  'com.kugou.android': '酷狗音乐',
  'com.qiyi.video': '爱奇艺',
  'com.youku.phone': '优酷',
  'com.tencent.qqlive': '腾讯视频',
  'com.bilibili.app.blue': '哔哩哔哩',
  'tv.danmaku.bili': '哔哩哔哩',
  'com.tencent.wework': '企业微信',
  'com.alibaba.android.rimet': '钉钉',
  'com.ss.android.lark': '飞书',
  'com.UCMobile': 'UC浏览器',
  'com.tencent.mtt': 'QQ浏览器',
  'com.android.chrome': 'Chrome浏览器',
  'com.huawei.browser': '华为浏览器',
  'com.miui.home': 'MIUI桌面',
  'com.android.settings': '系统设置',
  'com.android.camera': '相机',
  'com.android.gallery3d': '相册',
  'com.android.contacts': '联系人',
  'com.android.dialer': '拨号',
  'com.android.mms': '短信',
  'com.android.launcher': '桌面',
  'com.tencent.android.qqdownloader': '应用宝',
  'com.xiaomi.market': '小米应用商店',
  'com.huawei.appmarket': '华为应用市场',
  'com.tencent.news': '腾讯新闻',
  'com.tencent.karaoke': '全民K歌',
  'com.tencent.music': 'QQ音乐',
  'com.kingsoft.moffice_pro': 'WPS Office',
  'com.wps.pro': 'WPS',
  'com.baidu.searchbox': '百度',
  'com.jingdong.app.mall': '京东',
  'com.sankuai.meituan': '美团',
  'com.dianping.v1': '大众点评',
  'com.ss.android.ugc.live': '抖音火山版',
  'com.hihonor.health': '荣耀健康',
  'com.huawei.health': '华为运动健康',
  'com.xiaomi.health': '小米运动健康'
}

/** 包名转可读应用名 */
function appName(pkg) {
  if (!pkg) return '-'
  if (KNOWN_APPS[pkg]) return KNOWN_APPS[pkg]
  // 未知包名：取最后一段（如 com.example.app -> app）
  const parts = String(pkg).split('.')
  return parts[parts.length - 1]
}

const batteryProgressColor = computed(() => {
  const level = latest.value?.battery_level
  if (level == null) return '#909399'
  if (level <= 20) return '#f56c6c'
  if (level <= 50) return '#e6a23c'
  return '#67c23a'
})

function networkTagType(type) {
  if (type === 'wifi') return 'success'
  if (type === 'mobile') return 'warning'
  if (type === 'none') return 'danger'
  return 'info'
}

function getRangeParams() {
  // 数据库 recorded_at 存的是 ISO 格式（如 2026-07-31T04:18:44Z），
  // 前端参数必须用同样格式，否则 SQLite 字符串比较会出错
  if (customRange.value && customRange.value.length === 2) {
    // el-date-picker 返回 "YYYY-MM-DD HH:mm:ss"，转为 ISO 格式
    return {
      start: customRange.value[0].replace(' ', 'T') + 'Z',
      end: customRange.value[1].replace(' ', 'T') + 'Z'
    }
  }
  const now = new Date()
  const end = now.toISOString()
  let start
  if (range.value === 'today') {
    const d = new Date(now)
    d.setHours(0, 0, 0, 0)
    start = d.toISOString()
  } else if (range.value === '7d') {
    start = new Date(now.getTime() - 7 * 24 * 3600 * 1000).toISOString()
  } else {
    start = new Date(now.getTime() - 30 * 24 * 3600 * 1000).toISOString()
  }
  return { start, end }
}

async function loadDevice() {
  const res = await getDevice(deviceId)
  device.value = res.device
}

// ===== 应用使用加载 =====
async function loadCurrentApps() {
  currentAppsLoading.value = true
  try {
    const res = await getCurrentApps(deviceId)
    currentApps.value = res
  } catch (e) { /* 拦截器处理 */ } finally {
    currentAppsLoading.value = false
  }
}

async function loadAppUsage(page) {
  if (page) appPage.page = page
  appUsageLoading.value = true
  try {
    const params = {
      device_id: deviceId,
      page: appPage.page,
      pageSize: appPage.pageSize
    }
    if (appFilters.package) params.package = appFilters.package
    if (appFilters.foreground_only) params.foreground_only = '1'
    const res = await getAppUsageList(params)
    appUsageList.value = res.list
    appPage.total = res.total
  } catch (e) { /* 拦截器处理 */ } finally {
    appUsageLoading.value = false
  }
}

// 格式化"最近活跃"（毫秒时间戳 -> 相对时间）
function formatLastUsed(ms) {
  if (!ms || ms <= 0) return '-'
  const diff = Date.now() - ms
  if (diff < 0) return '刚刚'
  if (diff < 60_000) return `${Math.floor(diff / 1000)} 秒前`
  if (diff < 3600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86400_000) return `${Math.floor(diff / 3600_000)} 小时前`
  return formatTime(new Date(ms).toISOString())
}

// 格式化时长（毫秒 -> 可读）
function formatDuration(ms) {
  if (!ms || ms <= 0) return '-'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

async function loadHistory() {
  try {
    const params = { ...getRangeParams(), page: 1, pageSize: 500 }
    const res = await getMonitorList(deviceId, params)
    history.value = res.list
    latest.value = res.list[0] || null
    await nextTick()
    renderCharts()
  } catch (e) {
    console.error('loadHistory 失败:', e)
  }
}

function onRangeChange() {
  customRange.value = null
  loadHistory()
}

function onCustomRange() {
  range.value = ''
  loadHistory()
}

function renderCharts() {
  renderBatteryChart()
  renderNetworkChart()
}

function renderBatteryChart() {
  if (!batteryChartEl.value) return
  if (!batteryChart) batteryChart = echarts.init(batteryChartEl.value)
  const data = history.value.filter((h) => h.battery_level != null).reverse()
  if (data.length === 0) {
    batteryChart.clear()
    batteryChart.setOption({
      title: { text: '暂无电量数据', left: 'center', top: 'center', textStyle: { fontSize: 14, color: '#909399' } }
    })
    return
  }
  batteryChart.setOption({
    tooltip: { trigger: 'axis', formatter: (p) => `${p[0].axisValue}<br/>电量：${p[0].data}%` },
    grid: { left: 50, right: 30, top: 30, bottom: 60 },
    xAxis: {
      type: 'category',
      data: data.map((d) => formatTime(d.recorded_at).slice(5)),
      axisLabel: { rotate: 30 }
    },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series: [{
      name: '电量',
      type: 'line',
      smooth: true,
      data: data.map((d) => d.battery_level),
      areaStyle: { color: 'rgba(103,194,58,0.2)' },
      lineStyle: { color: '#67C23A' },
      itemStyle: { color: '#67C23A' }
    }]
  })
  batteryChart.resize()
}

function renderNetworkChart() {
  if (!networkChartEl.value) return
  if (!networkChart) networkChart = echarts.init(networkChartEl.value)
  const counts = {}
  history.value.forEach((h) => {
    const t = h.network_type || 'unknown'
    counts[t] = (counts[t] || 0) + 1
  })
  const data = Object.entries(counts).map(([name, value]) => ({
    name: networkTypeText(name),
    value
  }))
  if (data.length === 0) {
    networkChart.clear()
    networkChart.setOption({
      title: { text: '暂无网络数据', left: 'center', top: 'center', textStyle: { fontSize: 14, color: '#909399' } }
    })
    return
  }
  networkChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      avoidLabelOverlap: false,
      label: { show: true, formatter: '{b}\n{d}%' },
      data
    }]
  })
  networkChart.resize()
}

watch(activeTab, (val) => {
  setTimeout(() => renderCharts(), 100)
  // 切换到应用使用 Tab 时首次加载
  if (val === 'apps' && appUsageList.value.length === 0) {
    loadCurrentApps()
    loadAppUsage(1)
  }
})

function onResize() {
  batteryChart?.resize()
  networkChart?.resize()
}

// ===== 录音控制 =====

function toggleRecording() {
  if (audioStatus.value === 'recording') {
    stopRecording()
  } else {
    startRecording()
  }
}

function startRecording() {
  if (!isWsOpen()) {
    ElMessage.warning('WebSocket 未连接，无法下发录音指令')
    return
  }
  if (device.value?.status !== 'online') {
    ElMessage.warning('设备当前离线，无法开启录音')
    return
  }
  audioStatus.value = 'starting'
  const ok = sendWsMessage({
    type: 'audio_control',
    action: 'start',
    device_id: Number(deviceId)
  })
  if (!ok) {
    audioStatus.value = 'error'
    ElMessage.error('指令发送失败')
  }
}

function stopRecording() {
  sendWsMessage({
    type: 'audio_control',
    action: 'stop',
    device_id: Number(deviceId)
  })
  audioStatus.value = 'idle'
  currentSessionId.value = ''
  // 停止后刷新一次列表以获取最后一段
  setTimeout(() => loadAudioList(), 1500)
}

async function loadAudioList() {
  audioLoading.value = true
  try {
    const res = await getAudioList({ deviceId, pageSize: 200 })
    audioList.value = res.list
  } finally {
    audioLoading.value = false
  }
}

async function togglePlay(row) {
  // 切换播放/暂停
  if (playingId.value === row.id) {
    audioEl.value?.pause()
    playingId.value = null
    return
  }
  // 释放上一次的 blob URL
  if (currentBlobUrl) {
    URL.revokeObjectURL(currentBlobUrl)
    currentBlobUrl = null
  }
  try {
    const url = await fetchAudioBlobUrl(row.id)
    currentBlobUrl = url
    if (audioEl.value) {
      audioEl.value.src = url
      await audioEl.value.play()
      playingId.value = row.id
    }
  } catch (e) {
    ElMessage.error('录音加载失败')
  }
}

function onPlayEnded() {
  playingId.value = null
  if (currentBlobUrl) {
    URL.revokeObjectURL(currentBlobUrl)
    currentBlobUrl = null
  }
}

function onPlayError() {
  playingId.value = null
  ElMessage.error('录音播放失败')
}

function onDeleteAudio(row) {
  ElMessageBox.confirm('确定删除该录音分片？', '提示', { type: 'warning' })
    .then(async () => {
      await deleteAudio(row.id)
      ElMessage.success('已删除')
      if (playingId.value === row.id) {
        audioEl.value?.pause()
        playingId.value = null
      }
      loadAudioList()
    })
    .catch(() => {})
}

function formatBytes(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(2) + ' MB'
}

// ===== 短信记录 =====
async function loadSmsList(p) {
  if (p) smsPage.page = p
  smsLoading.value = true
  try {
    const params = { deviceId, page: smsPage.page, pageSize: smsPage.pageSize }
    if (smsFilters.type !== '' && smsFilters.type != null) params.type = smsFilters.type
    if (smsFilters.range && smsFilters.range.length === 2) {
      params.start = smsFilters.range[0]
      params.end = smsFilters.range[1]
    }
    const res = await getSmsList(params)
    smsList.value = res.list
    smsPage.total = res.total
  } finally {
    smsLoading.value = false
  }
}

function onViewSms(row) {
  currentSms.value = row
  smsDetailVisible.value = true
}

/** WS 消息处理 */
function onWsMessage(msg) {
  if (!msg) return
  // 仅处理与本设备相关的消息
  if (msg.device_id !== Number(deviceId)) return
  switch (msg.type) {
    case 'monitor_data':
      // 收到新监控数据（位置/电量/网络等），刷新历史数据和图表
      loadHistory()
      break
    case 'device_online':
    case 'device_offline':
      // 设备状态变化，刷新设备和历史数据
      loadDevice()
      loadHistory()
      break
    case 'audio_started':
      audioStatus.value = 'recording'
      currentSessionId.value = msg.session_id || ''
      ElMessage.success('录音已开启')
      loadAudioList()
      break
    case 'audio_stopped':
      audioStatus.value = 'idle'
      currentSessionId.value = ''
      ElMessage.info('录音已停止')
      loadAudioList()
      break
    case 'audio_chunk':
      // 收到新分片：追加到列表（避免全量刷新）
      audioList.value.unshift({
        id: msg.audio_id,
        chunk_index: msg.chunk_index,
        received_at: msg.received_at,
        duration_seconds: msg.duration_seconds,
        file_size: null
      })
      break
    case 'audio_error':
      audioStatus.value = 'error'
      ElMessage.error('设备录音异常：' + (msg.error || '未知错误'))
      break
    case 'sms':
      // 收到新短信通知：刷新短信列表
      loadSmsList()
      break
    case 'app_usage':
      // 收到应用使用上报：若当前在应用使用 Tab，刷新当前状态
      if (String(msg.device_id) === String(deviceId) && activeTab.value === 'apps') {
        loadCurrentApps()
      }
      break
  }
}

onMounted(async () => {
  await loadDevice()
  await loadHistory()
  await loadAudioList()
  await loadSmsList()
  // 预加载当前应用状态（不阻塞首屏）
  loadCurrentApps()
  window.addEventListener('resize', onResize)
  // 订阅 WS 消息
  unsubscribeWs = subscribeWsMessages(onWsMessage)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  batteryChart?.dispose()
  networkChart?.dispose()
  // 退出页面时若仍在录音，自动停止（兜底；正常情况 Layout 退出会触发服务端 stop）
  if (audioStatus.value === 'recording') {
    sendWsMessage({ type: 'audio_control', action: 'stop', device_id: Number(deviceId) })
  }
  // 取消订阅
  if (unsubscribeWs) unsubscribeWs()
  // 释放音频资源
  if (audioEl.value) {
    audioEl.value.pause()
  }
  if (currentBlobUrl) {
    URL.revokeObjectURL(currentBlobUrl)
    currentBlobUrl = null
  }
})
</script>

<style scoped>
.metric { text-align: center; }
.metric-label { color: #909399; font-size: 13px; margin-bottom: 8px; }
.metric-value { display: flex; justify-content: center; }
.metric-sub { color: #909399; font-size: 12px; margin-top: 8px; }
.history-controls { display: flex; align-items: center; }
.range-picker { width: 360px; margin-left: 12px; }
.track-map-wrapper { height: 460px; }
.track-info { text-align: center; color: #909399; margin-top: 8px; font-size: 13px; }
.chart-wrapper { height: 460px; }
.apps-tab { padding: 4px 0; }
.apps-card-header { display: flex; align-items: center; justify-content: space-between; }
.apps-filters { display: flex; align-items: center; }
.fg-app {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  flex-wrap: wrap;
}
.fg-app .app-name { font-size: 16px; font-weight: 600; color: #303133; }
.fg-app .app-pkg { font-family: monospace; font-size: 11px; color: #909399; }
.fg-app .app-ver { font-size: 11px; color: #67C23A; }
.no-fg { color: #909399; font-size: 13px; padding: 8px 0; }
.bg-apps-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 0;
}
.bg-app-tag { cursor: default; }
.snapshot-time { color: #909399; font-size: 12px; margin-top: 8px; }
.apps-current-card { margin-bottom: 0; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.audio-controls { display: flex; align-items: center; gap: 8px; }
.recent-apps { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 4px; }
.app-chip {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  background: #f0f2f5;
  color: #606266;
  font-size: 12px;
  line-height: 18px;
}
.session-info { margin-bottom: 12px; color: #606266; font-size: 13px; }
.rec-dot {
  display: inline-block; width: 6px; height: 6px;
  background: #fff; border-radius: 50%; margin-right: 4px;
  animation: blink 1s infinite;
}
@keyframes blink { 50% { opacity: 0.3; } }

/* 短信记录卡片相关样式 */
.sms-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.text-info { color: #909399; }
.person-name { font-size: 12px; color: #909399; margin-top: 2px; }
.sms-body-inline {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
  color: #303133;
  word-break: break-all;
}
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.detail-body-title {
  margin: 20px 0 8px;
  font-size: 13px;
  color: #909399;
}
.detail-body {
  background: #f5f7fa;
  padding: 14px 16px;
  border-radius: 6px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  color: #303133;
}

/* 移动端适配 */
@media (max-width: 768px) {
  .history-controls {
    flex-wrap: wrap;
    gap: 8px;
  }
  .range-picker {
    width: 100%;
    margin-left: 0;
  }
  .history-controls .el-radio-group {
    width: 100%;
  }
  .chart-wrapper,
  .track-map-wrapper {
    height: 320px;
  }
  .sms-controls {
    gap: 8px;
  }
  .sms-controls > * {
    width: 100%;
    margin-left: 0 !important;
  }
}
</style>
