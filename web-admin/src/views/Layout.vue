<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="logo">
        <span v-if="!collapsed">设备监控</span>
        <span v-else>VM</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <template #title>仪表盘</template>
        </el-menu-item>
        <el-menu-item index="/map">
          <el-icon><Location /></el-icon>
          <template #title>实时地图</template>
        </el-menu-item>
        <el-menu-item index="/devices">
          <el-icon><Cellphone /></el-icon>
          <template #title>设备管理</template>
        </el-menu-item>
        <el-menu-item index="/join-requests">
          <el-icon><Promotion /></el-icon>
          <template #title>加入审批</template>
        </el-menu-item>
        <el-menu-item index="/app-releases">
          <el-icon><Upload /></el-icon>
          <template #title>版本管理</template>
        </el-menu-item>
        <el-menu-item index="/phone-calls">
          <el-icon><Phone /></el-icon>
          <template #title>来电记录</template>
        </el-menu-item>
        <el-menu-item index="/sms">
          <el-icon><ChatDotRound /></el-icon>
          <template #title>短信记录</template>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <template #title>系统设置</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="page-title">{{ route.meta.title || '' }}</span>
        </div>
        <div class="header-right">
          <el-badge :value="onlineCount" :hidden="onlineCount === 0" class="online-badge">
            <el-tag type="success" size="small" effect="plain" class="online-tag">在线 {{ onlineCount }}</el-tag>
          </el-badge>
          <el-dropdown @command="onCommand">
            <span class="user-info">
              <el-icon><User /></el-icon>
              <span class="user-name">{{ auth.user?.username || 'admin' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="settings">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElNotification } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { connectWebSocket, closeWebSocket } from '@/utils/ws'
import { getStats } from '@/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)
const onlineCount = ref(0)
let statsTimer = null

// 手机端（<768px）自动折叠侧边栏
const isMobile = ref(window.innerWidth < 768)
function handleResize() {
  const mobile = window.innerWidth < 768
  isMobile.value = mobile
  if (mobile) collapsed.value = true
}

async function loadStats() {
  try {
    const s = await getStats()
    onlineCount.value = s.onlineDevices
  } catch (e) { /* 忽略 */ }
}

/** 全局 WS 消息处理：来电/录音/设备上下线等实时通知 */
function onWsMessage(msg) {
  if (!msg || !msg.type) return
  switch (msg.type) {
    case 'phone_call': {
      const data = msg.data || {}
      const stateText = {
        ringing: '正在响铃', answered: '已接听',
        ended: '通话结束', missed: '未接来电'
      }[data.call_state] || data.call_state
      const number = data.phone_number || '未知号码'
      ElNotification({
        title: '来电提醒',
        message: `${msg.device_name || msg.device_code || '设备'} · ${number} · ${stateText}`,
        type: data.call_state === 'missed' ? 'warning' : 'info',
        duration: 6000
      })
      break
    }
    case 'audio_error': {
      ElNotification({
        title: '录音异常',
        message: `${msg.device_name || msg.device_code || '设备'}：${msg.error || '未知错误'}`,
        type: 'error',
        duration: 6000
      })
      break
    }
    // audio_chunk / audio_started / audio_stopped / monitor_data / device_online / device_offline
    // 由具体页面（如 DeviceDetail）自行处理，这里不弹通知避免刷屏
  }
}

function onCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
      .then(() => {
        closeWebSocket()
        auth.logout()
        router.replace('/login')
      })
      .catch(() => {})
  } else if (cmd === 'settings') {
    router.push('/settings')
  }
}

onMounted(() => {
  window.addEventListener('resize', handleResize)
  connectWebSocket(auth.token, onWsMessage)
  loadStats()
  statsTimer = setInterval(loadStats, 30000)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  if (statsTimer) clearInterval(statsTimer)
})
</script>

<style scoped>
.layout { height: 100vh; }
.aside {
  background: #304156;
  transition: width 0.28s;
  overflow: hidden;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  background: #2b2f3a;
}
.aside :deep(.el-menu) { border-right: none; }
.header {
  background: #fff;
  border-bottom: 1px solid #e6e6e6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}
.header-left { display: flex; align-items: center; gap: 12px; }
.collapse-btn { font-size: 20px; cursor: pointer; color: #5a5e66; }
.page-title { font-size: 16px; font-weight: 500; }
.header-right { display: flex; align-items: center; gap: 16px; }
.user-info { display: flex; align-items: center; gap: 4px; cursor: pointer; color: #5a5e66; }
.main { background: #f5f7fa; padding: 16px; overflow: auto; }

/* 移动端适配 */
@media (max-width: 768px) {
  .page-title { display: none; }
  .online-tag :deep(.el-tag__content) { display: none; }
  .online-tag { padding: 0 8px !important; }
  .user-name { display: none; }
  .main { padding: 10px; }
  .header { padding: 0 10px; }
}
</style>
