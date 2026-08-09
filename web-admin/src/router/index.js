import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '仪表盘' } },
      { path: 'devices', name: 'devices', component: () => import('@/views/Devices.vue'), meta: { title: '设备管理' } },
      { path: 'devices/:id', name: 'device-detail', component: () => import('@/views/DeviceDetail.vue'), meta: { title: '设备详情' } },
      { path: 'join-requests', name: 'join-requests', component: () => import('@/views/JoinRequests.vue'), meta: { title: '加入审批' } },
      { path: 'app-releases', name: 'app-releases', component: () => import('@/views/AppReleases.vue'), meta: { title: '版本管理' } },
      { path: 'phone-calls', name: 'phone-calls', component: () => import('@/views/PhoneCalls.vue'), meta: { title: '来电记录' } },
      { path: 'sms', name: 'sms', component: () => import('@/views/SmsMessages.vue'), meta: { title: '短信记录' } },
      { path: 'map', name: 'map', component: () => import('@/views/MapView.vue'), meta: { title: '实时地图' } },
      { path: 'settings', name: 'settings', component: () => import('@/views/Settings.vue'), meta: { title: '系统设置' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.token) {
    return { name: 'dashboard' }
  }
})

export default router
