import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as api from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('vm_token') || '')
  let savedUser = null
  try {
    savedUser = JSON.parse(localStorage.getItem('vm_user') || 'null')
  } catch { /* 非法数据，忽略 */ }
  const user = ref(savedUser)

  const isLogin = computed(() => !!token.value)

  async function login(username, password) {
    const res = await api.login(username, password)
    token.value = res.token
    user.value = res.user
    localStorage.setItem('vm_token', res.token)
    localStorage.setItem('vm_user', JSON.stringify(res.user))
    return res
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('vm_token')
    localStorage.removeItem('vm_user')
  }

  return { token, user, isLogin, login, logout }
})
