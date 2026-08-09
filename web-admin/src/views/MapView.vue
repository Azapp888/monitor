<template>
  <div class="page-container map-page">
    <el-card shadow="never" class="map-card">
      <template #header>
        <div class="card-header">
          <span>实时设备地图</span>
          <div>
            <el-tag v-if="wsConnected" type="success" size="small" effect="plain">实时连接中</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">未连接</el-tag>
            <el-button text style="margin-left: 8px" @click="loadLatest">
              <el-icon><Refresh /></el-icon>刷新
            </el-button>
          </div>
        </div>
      </template>
      <div class="map-wrapper">
        <AmapView :markers="mapMarkers" :auto-fit="true" />
      </div>
    </el-card>

    <!-- 设备列表侧栏 -->
    <el-card shadow="never" class="device-list-card">
      <template #header><span>设备列表（{{ list.length }}）</span></template>
      <el-input v-model="keyword" placeholder="搜索设备" clearable size="small" style="margin-bottom: 8px" />
      <div class="device-list">
        <div
          v-for="d in filteredList"
          :key="d.id"
          class="device-item"
          :class="{ offline: d.status !== 'online' }"
          @click="$router.push(`/devices/${d.id}`)"
        >
          <div class="device-name">
            <span class="dot" :class="d.status === 'online' ? 'online' : 'offline'"></span>
            {{ d.device_name || d.device_code }}
          </div>
          <div class="device-meta">
            <span v-if="d.latest?.battery_level != null">电量 {{ d.latest.battery_level }}%</span>
            <span v-if="d.latest?.network_type">{{ networkTypeText(d.latest.network_type) }}</span>
          </div>
          <div class="device-time">{{ fromNow(d.last_seen_at) }}</div>
        </div>
        <el-empty v-if="filteredList.length === 0" description="暂无设备" :image-size="60" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getLatest } from '@/api'
import { connectWebSocket, closeWebSocket } from '@/utils/ws'
import { useAuthStore } from '@/stores/auth'
import { networkTypeText, fromNow } from '@/utils/format'
import AmapView from '@/components/AmapView.vue'

const auth = useAuthStore()
const list = ref([])
const keyword = ref('')
const wsConnected = ref(false)
let pollTimer = null

const filteredList = computed(() => {
  if (!keyword.value) return list.value
  const k = keyword.value.toLowerCase()
  return list.value.filter((d) =>
    (d.device_name || '').toLowerCase().includes(k) ||
    (d.device_code || '').toLowerCase().includes(k)
  )
})

const mapMarkers = computed(() =>
  list.value
    .filter((d) => d.latest?.latitude != null && d.latest?.longitude != null)
    .map((d) => ({
      id: d.id,
      name: d.device_name || d.device_code,
      status: d.status,
      latitude: d.latest.latitude,
      longitude: d.latest.longitude,
      latest: d.latest
    }))
)

async function loadLatest() {
  try {
    const res = await getLatest()
    list.value = res.list
  } catch (e) { /* 忽略 */ }
}

function onWsMessage(msg) {
  if (msg.type === 'connected') {
    wsConnected.value = true
  } else if (msg.type === 'monitor_data' || msg.type === 'device_online' || msg.type === 'device_offline') {
    loadLatest()
  }
}

onMounted(() => {
  loadLatest()
  connectWebSocket(auth.token, onWsMessage)
  pollTimer = setInterval(loadLatest, 60000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.map-page { display: flex; flex-direction: column; height: 100%; }
.map-card { flex: 1; display: flex; flex-direction: column; }
.map-card :deep(.el-card__body) { flex: 1; padding: 0; }
.map-wrapper { height: 100%; min-height: 500px; }
.device-list-card { margin-top: 16px; }
.device-list { max-height: 280px; overflow-y: auto; }
.device-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}
.device-item:hover { background: #f5f7fa; }
.device-item.offline { opacity: 0.6; }
.device-name { font-weight: 500; display: flex; align-items: center; gap: 6px; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot.online { background: #67c23a; }
.dot.offline { background: #909399; }
.device-meta { font-size: 12px; color: #909399; margin-top: 4px; display: flex; gap: 12px; }
.device-time { font-size: 11px; color: #c0c4cc; margin-top: 2px; }
</style>
