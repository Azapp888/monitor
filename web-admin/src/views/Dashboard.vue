<template>
  <div class="page-container">
    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="label">设备总数</div>
          <div class="value">{{ stats.totalDevices }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="label">在线设备</div>
          <div class="value text-success">{{ stats.onlineDevices }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="label">今日上报</div>
          <div class="value">{{ stats.todayLogs }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat-card">
          <div class="label">历史上报总量</div>
          <div class="value">{{ stats.totalLogs }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 地图 + 最近上报 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :xs="24" :lg="16">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>设备位置地图</span>
              <el-button text @click="loadLatest">
                <el-icon><Refresh /></el-icon>刷新
              </el-button>
            </div>
          </template>
          <div class="map-wrapper">
            <AmapView :markers="mapMarkers" />
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="8">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>最近上报</span>
              <el-tag v-if="wsConnected" type="success" size="small" effect="plain">实时</el-tag>
            </div>
          </template>
          <el-table :data="recentReports" size="small" :max-height="420" empty-text="暂无数据">
            <el-table-column label="设备" min-width="100">
              <template #default="{ row }">
                <span>{{ row.device_name || row.device_code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="电量" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.data?.battery_level != null" :type="batteryColor(row.data.battery_level)" size="small">
                  {{ row.data.battery_level }}%
                </el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="网络" width="90">
              <template #default="{ row }">{{ networkTypeText(row.data?.network_type) }}</template>
            </el-table-column>
            <el-table-column label="时间" width="80">
              <template #default="{ row }">{{ fromNow(row.data?.received_at || row.data?.recorded_at) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { getStats, getLatest } from '@/api'
import { connectWebSocket, closeWebSocket } from '@/utils/ws'
import { useAuthStore } from '@/stores/auth'
import { networkTypeText, batteryColor, fromNow } from '@/utils/format'
import AmapView from '@/components/AmapView.vue'

const auth = useAuthStore()
const stats = reactive({ totalDevices: 0, onlineDevices: 0, todayLogs: 0, totalLogs: 0 })
const latestList = ref([])
const recentReports = ref([])
const wsConnected = ref(false)
let pollTimer = null

const mapMarkers = computed(() =>
  latestList.value.map((d) => ({
    id: d.id,
    name: d.device_name || d.device_code,
    status: d.status,
    latitude: d.latest?.latitude,
    longitude: d.latest?.longitude,
    latest: d.latest
  }))
)

async function loadStats() {
  try {
    Object.assign(stats, await getStats())
  } catch (e) { /* 忽略 */ }
}

async function loadLatest() {
  try {
    const res = await getLatest()
    latestList.value = res.list
    // 用各设备的最新一条记录初始化"最近上报"列表（按时间倒序）
    const reports = res.list
      .filter((d) => d.latest)
      .map((d) => ({
        device_name: d.device_name,
        device_code: d.device_code,
        data: d.latest
      }))
      .sort((a, b) => (b.data.received_at || b.data.recorded_at || '').localeCompare(a.data.received_at || a.data.recorded_at || ''))
    if (reports.length > 0) recentReports.value = reports
  } catch (e) { /* 忽略 */ }
}

function onWsMessage(msg) {
  if (msg.type === 'connected') {
    wsConnected.value = true
  } else if (msg.type === 'monitor_data') {
    recentReports.value.unshift({
      device_name: msg.device_name,
      device_code: msg.device_code,
      data: msg.data
    })
    if (recentReports.value.length > 30) recentReports.value.pop()
    loadLatest()
    loadStats()
  } else if (msg.type === 'device_online' || msg.type === 'device_offline') {
    loadLatest()
    loadStats()
  }
}

onMounted(() => {
  loadStats()
  loadLatest()
  connectWebSocket(auth.token, onWsMessage)
  pollTimer = setInterval(() => { loadStats(); loadLatest() }, 60000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.stat-row .stat-card { text-align: center; }
.map-wrapper { height: 480px; }
</style>
