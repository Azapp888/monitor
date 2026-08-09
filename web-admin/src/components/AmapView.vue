<template>
  <div class="amap-container">
    <div ref="mapEl" class="amap-el"></div>
    <div v-if="error" class="amap-error">
      <el-empty :description="error">
        <template #image><el-icon :size="48" color="#909399"><Location /></el-icon></template>
        <div style="font-size: 12px; color: #909399; margin-top: 8px">
          请在 web-admin/.env 中配置 VITE_AMAP_KEY
        </div>
      </el-empty>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { loadAMap } from '@/utils/amap'
import { networkTypeText } from '@/utils/format'

const props = defineProps({
  // 标记点数组：[{ id, name, latitude, longitude, status, latest }]
  markers: { type: Array, default: () => [] },
  // 轨迹点数组：[{ latitude, longitude, recorded_at }]
  track: { type: Array, default: null },
  // 是否自动调整视野到所有标记
  autoFit: { type: Boolean, default: true },
  // 地图中心点 [lng, lat]
  center: { type: Array, default: () => [116.397428, 39.90923] },
  zoom: { type: Number, default: 5 }
})

const mapEl = ref(null)
const error = ref('')
let map = null
let markerList = []
let trackPolyline = null
let trackMarkers = []
let AMapRef = null

onMounted(async () => {
  try {
    AMapRef = await loadAMap()
    map = new AMapRef.Map(mapEl.value, {
      zoom: props.zoom,
      center: props.center,
      mapStyle: 'amap://styles/whitesmoke'
    })
    map.addControl(new AMapRef.Scale())
    map.addControl(new AMapRef.ToolBar({ position: 'RB' }))
    renderMarkers()
    renderTrack()
  } catch (e) {
    error.value = e.message || '地图加载失败'
  }
})

onUnmounted(() => {
  if (map) {
    map.destroy()
    map = null
  }
})

watch(() => props.markers, () => renderMarkers(), { deep: true })
watch(() => props.track, () => renderTrack(), { deep: true })

function renderMarkers() {
  if (!map || !AMapRef) return
  // 清除旧标记
  markerList.forEach((m) => map.remove(m))
  markerList = []

  const validMarkers = props.markers.filter(
    (m) => m.latitude != null && m.longitude != null
  )
  if (validMarkers.length === 0) return

  validMarkers.forEach((item) => {
    const isOnline = item.status === 'online'
    const color = isOnline ? '#67C23A' : '#909399'
    const marker = new AMapRef.Marker({
      position: [item.longitude, item.latitude],
      title: item.name || item.device_code,
      icon: new AMapRef.Icon({
        size: new AMapRef.Size(25, 34),
        image: 'https://webapi.amap.com/theme/v1.3/markers/n/mark_' + (isOnline ? 'r' : 'b') + '.png',
        imageSize: new AMapRef.Size(25, 34)
      }),
      anchor: 'bottom-center',
      label: {
        content: `<div style="padding:2px 6px;background:${color};color:#fff;border-radius:3px;font-size:12px;white-space:nowrap;">${escapeHtml(item.name || item.device_code || '')}</div>`,
        direction: 'top'
      }
    })

    const info = buildInfoWindow(item)
    marker.on('click', () => {
      info.open(map, marker.getPosition())
    })
    markerList.push(marker)
    map.add(marker)
  })

  if (props.autoFit && markerList.length > 0) {
    map.setFitView(markerList, false, [50, 50, 50, 50])
  }
}

function renderTrack() {
  if (!map || !AMapRef) return
  // 清除旧轨迹
  if (trackPolyline) { map.remove(trackPolyline); trackPolyline = null }
  trackMarkers.forEach((m) => map.remove(m))
  trackMarkers = []

  if (!props.track || props.track.length === 0) return

  const points = props.track
    .filter((p) => p.latitude != null && p.longitude != null)
    .map((p) => [p.longitude, p.latitude])
  if (points.length === 0) return

  trackPolyline = new AMapRef.Polyline({
    path: points,
    isOutline: true,
    outlineColor: '#ffeeff',
    borderWeight: 1,
    strokeColor: '#409EFF',
    strokeOpacity: 0.9,
    strokeWeight: 4,
    strokeStyle: 'solid',
    lineJoin: 'round'
  })
  map.add(trackPolyline)

  // 起点（绿）和终点（红）
  if (points.length >= 2) {
    const start = new AMapRef.Marker({ position: points[0], icon: new AMapRef.Icon({ image: 'https://webapi.amap.com/theme/v1.3/markers/n/start.png', imageSize: new AMapRef.Size(25, 34) }) })
    const end = new AMapRef.Marker({ position: points[points.length - 1], icon: new AMapRef.Icon({ image: 'https://webapi.amap.com/theme/v1.3/markers/n/end.png', imageSize: new AMapRef.Size(25, 34) }) })
    map.add([start, end])
    trackMarkers.push(start, end)
  }

  if (props.autoFit) {
    map.setFitView([trackPolyline, ...trackMarkers], false, [60, 60, 60, 60])
  }
}

function buildInfoWindow(item) {
  const latest = item.latest || {}
  const html = `
    <div style="padding:8px;min-width:220px;font-size:13px;line-height:1.8">
      <div style="font-weight:600;font-size:14px;margin-bottom:4px">${escapeHtml(item.device_name || item.device_code)}</div>
      <div>状态：<span style="color:${item.status === 'online' ? '#67C23A' : '#909399'}">${item.status === 'online' ? '在线' : '离线'}</span></div>
      ${latest.latitude ? `<div>位置：${latest.latitude.toFixed(5)}, ${latest.longitude.toFixed(5)}</div>` : '<div>位置：无</div>'}
      ${latest.battery_level != null ? `<div>电量：${latest.battery_level}% ${latest.battery_charging ? '充电中' : ''}</div>` : ''}
      ${latest.network_type ? `<div>网络：${networkTypeText(latest.network_type)}</div>` : ''}
      ${latest.recorded_at ? `<div>更新：${latest.recorded_at}</div>` : ''}
      <div style="margin-top:6px"><a href="#/devices/${item.id}" style="color:#409eff">查看详情</a></div>
    </div>`
  return new AMapRef.InfoWindow({ content: html, offset: new AMapRef.Pixel(0, -32) })
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

defineExpose({
  fitView: () => {
    const all = [...markerList, trackPolyline].filter(Boolean)
    if (map && all.length > 0) map.setFitView(all, false, [50, 50, 50, 50])
  }
})
</script>

<style scoped>
.amap-container { position: relative; width: 100%; height: 100%; }
.amap-el { width: 100%; height: 100%; }
.amap-error {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: #f5f7fa;
}
</style>
