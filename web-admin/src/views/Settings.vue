<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>修改密码</span>
        </div>
      </template>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width: 480px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="onSubmit">确认修改</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header><span>系统信息</span></template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="当前用户">{{ auth.user?.username }}</el-descriptions-item>
        <el-descriptions-item label="后端服务">通过 /api 代理访问</el-descriptions-item>
        <el-descriptions-item label="WebSocket">通过 /ws 连接实时推送</el-descriptions-item>
        <el-descriptions-item label="地图服务">
          <el-tag :type="amapReady ? 'success' : 'warning'" size="small">
            {{ amapReady ? '高德地图已配置' : '未配置高德地图 Key' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { hasAmapKey } from '@/utils/amap'

const auth = useAuthStore()
const amapReady = hasAmapKey()

const formRef = ref(null)
const loading = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, cb) => {
        if (value !== form.newPassword) cb(new Error('两次输入的密码不一致'))
        else cb()
      },
      trigger: 'blur'
    }
  ]
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await changePassword(form.oldPassword, form.newPassword)
      ElMessage.success('密码修改成功，请重新登录')
      auth.logout()
      location.href = '/login'
    } catch (e) { /* 忽略 */ } finally {
      loading.value = false
    }
  })
}
</script>
