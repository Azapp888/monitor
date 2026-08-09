<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>加入申请审批</span>
          <div class="filters">
            <el-input
              v-model="query.keyword"
              placeholder="申请编号/设备名称/型号/Android ID"
              clearable
              size="small"
              style="width: 280px"
              @keyup.enter="onSearch"
              @clear="onSearch"
            />
            <el-select
              v-model="query.status"
              placeholder="全部状态"
              clearable
              size="small"
              style="width: 140px; margin-left: 12px"
              @change="onSearch"
            >
              <el-option label="待审批" value="pending" />
              <el-option label="已通过" value="approved" />
              <el-option label="已拒绝" value="rejected" />
            </el-select>
            <el-button size="small" style="margin-left: 12px" @click="onSearch">查询</el-button>
            <el-button size="small" @click="onReset">重置</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="pendingCount > 0" type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        当前有 <strong>{{ pendingCount }}</strong> 条待审批申请，请及时处理。
      </el-alert>

      <!-- 申请列表 -->
      <el-table :data="list" v-loading="loading" border stripe>
        <el-table-column label="申请编号" min-width="180" prop="request_code">
          <template #default="{ row }">
            <span style="font-family: monospace; font-size: 12px">{{ row.request_code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="设备名称" min-width="120" prop="device_name">
          <template #default="{ row }">{{ row.device_name || '-' }}</template>
        </el-table-column>
        <el-table-column label="型号" min-width="140" prop="model">
          <template #default="{ row }">
            <span style="font-family: monospace; font-size: 12px">{{ row.model || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="系统版本" min-width="120" prop="os_version">
          <template #default="{ row }">{{ row.os_version || '-' }}</template>
        </el-table-column>
        <el-table-column label="Android ID" min-width="140" prop="android_id">
          <template #default="{ row }">
            <span v-if="row.android_id" style="font-family: monospace; font-size: 11px">{{ row.android_id }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="手机号" width="120" prop="phone_number">
          <template #default="{ row }">{{ row.phone_number || '-' }}</template>
        </el-table-column>
        <el-table-column label="申请时间" width="160" prop="created_at">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审批人" width="100" prop="reviewed_by">
          <template #default="{ row }">{{ row.reviewed_by || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'pending'">
              <el-button type="success" size="small" @click="onApprove(row)">一键同意</el-button>
              <el-button type="danger" size="small" @click="onReject(row)">拒绝</el-button>
            </template>
            <template v-else-if="row.status === 'approved'">
              <el-button type="primary" size="small" @click="onViewCredential(row)">查看凭证</el-button>
            </template>
            <template v-else>
              <span style="color: #999; font-size: 12px">已处理</span>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 16px"
        @current-change="loadList"
        @size-change="loadList"
      />
    </el-card>

    <!-- 操作日志卡片 -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>最近操作日志</span>
          <el-button text size="small" @click="loadLogs">刷新</el-button>
        </div>
      </template>
      <el-table :data="logs" v-loading="logsLoading" border size="small">
        <el-table-column label="时间" width="160" prop="created_at">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="100" prop="username" />
        <el-table-column label="动作" width="140">
          <template #default="{ row }">
            <el-tag :type="actionTagType(row.action)" size="small">{{ actionText(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象类型" width="120" prop="target_type" />
        <el-table-column label="对象 ID" width="80" prop="target_id" />
        <el-table-column label="详情" min-width="280">
          <template #default="{ row }">
            <pre v-if="row.detail" style="margin: 0; font-size: 11px; white-space: pre-wrap; word-break: break-all">{{ formatDetail(row.detail) }}</pre>
            <span v-else style="color: #999">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 拒绝对话框 -->
    <el-dialog v-model="rejectVisible" title="拒绝申请" width="480px" class="resp-dialog">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        拒绝后申请人可在 App 端修改信息重新提交。
      </el-alert>
      <el-form>
        <el-form-item label="申请编号">
          <span style="font-family: monospace">{{ currentRow?.request_code }}</span>
        </el-form-item>
        <el-form-item label="拒绝理由">
          <el-input
            v-model="reviewComment"
            type="textarea"
            :rows="3"
            placeholder="可选，将反馈给申请人"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="doReject">确认拒绝</el-button>
      </template>
    </el-dialog>

    <!-- 凭证展示对话框（一键同意后展示） -->
    <el-dialog v-model="credentialVisible" title="审批通过 - 设备凭证" width="560px" class="resp-dialog">
      <el-alert type="success" :closable="false" show-icon style="margin-bottom: 12px">
        已自动生成设备凭证，请将以下信息告知申请人，由其在 App 端激活。
      </el-alert>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="申请编号">
          <span style="font-family: monospace">{{ approvedRow?.request_code }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="设备名称">
          {{ approvedRow?.device_name || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="设备编码">
          <span style="font-family: monospace">{{ approvedCredential.device_code }}</span>
          <el-button text size="small" @click="copy(approvedCredential.device_code)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="设备令牌">
          <span style="font-family: monospace; word-break: break-all">{{ approvedCredential.device_token }}</span>
          <el-button text size="small" @click="copy(approvedCredential.device_token)">复制</el-button>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="credentialVisible = false">已记录</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getJoinRequests,
  approveJoinRequest,
  rejectJoinRequest,
  getOperationLogs
} from '@/api'
import { useAuthStore } from '@/stores/auth'
import { connectWebSocket, closeWebSocket } from '@/utils/ws'
import { formatTime } from '@/utils/format'

const list = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', status: '', page: 1, pageSize: 20 })

const logs = ref([])
const logsLoading = ref(false)

const rejectVisible = ref(false)
const submitting = ref(false)
const currentRow = ref(null)
const reviewComment = ref('')

const credentialVisible = ref(false)
const approvedRow = ref(null)
const approvedCredential = reactive({ device_code: '', device_token: '' })

const pendingCount = computed(() => list.value.filter(r => r.status === 'pending').length)

let wsHandler = null

async function loadList() {
  loading.value = true
  try {
    const res = await getJoinRequests(query)
    list.value = res.list
    total.value = res.total
  } catch (e) { /* 拦截器已处理 */ } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await getOperationLogs({ pageSize: 30 })
    logs.value = res.list
  } catch (e) { /* 忽略 */ } finally {
    logsLoading.value = false
  }
}

function onSearch() { query.page = 1; loadList() }
function onReset() { query.keyword = ''; query.status = ''; query.page = 1; loadList() }

function statusTagType(s) {
  return { pending: 'warning', approved: 'success', rejected: 'danger' }[s] || 'info'
}
function statusText(s) {
  return { pending: '待审批', approved: '已通过', rejected: '已拒绝' }[s] || s
}
function actionText(a) {
  return {
    approve_join: '同意申请',
    reject_join: '拒绝申请',
    create_device: '创建设备',
    delete_device: '删除设备',
    update_device: '更新设备'
  }[a] || a
}
function actionTagType(a) {
  return {
    approve_join: 'success',
    reject_join: 'danger',
    create_device: 'primary',
    delete_device: 'danger'
  }[a] || 'info'
}
function formatDetail(d) {
  if (!d) return ''
  if (typeof d === 'string') return d
  return JSON.stringify(d, null, 2)
}

async function onApprove(row) {
  try {
    await ElMessageBox.confirm(
      `确认通过申请"${row.device_name || row.request_code}"？\n系统将自动创建设备并生成 DeviceID 与 Token。`,
      '一键同意',
      { type: 'success', confirmButtonText: '确认同意' }
    )
  } catch (e) {
    return // 用户取消
  }
  submitting.value = true
  try {
    const res = await approveJoinRequest(row.id, '')
    ElMessage.success('审批通过，设备凭证已生成')
    approvedRow.value = row
    Object.assign(approvedCredential, {
      device_code: res.device.device_code,
      device_token: res.device.device_token
    })
    credentialVisible.value = true
    loadList()
    loadLogs()
  } catch (e) { /* 拦截器已处理 */ } finally {
    submitting.value = false
  }
}

function onReject(row) {
  currentRow.value = row
  reviewComment.value = ''
  rejectVisible.value = true
}

async function doReject() {
  submitting.value = true
  try {
    await rejectJoinRequest(currentRow.value.id, reviewComment.value)
    ElMessage.success('已拒绝该申请')
    rejectVisible.value = false
    loadList()
    loadLogs()
  } catch (e) { /* 拦截器已处理 */ } finally {
    submitting.value = false
  }
}

async function onViewCredential(row) {
  // 已通过的申请：通过 join request 详情拿 device_id 再查设备
  try {
    const res = await getJoinRequests({ keyword: row.request_code, pageSize: 1 })
    const item = res.list[0]
    if (item && item.device_id) {
      // 通过 device 接口获取凭证（需 import getDevice）
      const { getDevice } = await import('@/api')
      const devRes = await getDevice(item.device_id)
      approvedRow.value = item
      Object.assign(approvedCredential, {
        device_code: devRes.device.device_code,
        device_token: devRes.device.device_token
      })
      credentialVisible.value = true
    }
  } catch (e) { /* 拦截器已处理 */ }
}

function copy(text) {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}

// 全局 WS 消息处理：收到 join_request_new 立即刷新
function onWsMessage(msg) {
  if (!msg || !msg.type) return
  if (msg.type === 'join_request_new') {
    ElMessage.info('收到新的加入申请')
    loadList()
  } else if (msg.type === 'join_request_updated') {
    // 其他管理端处理后通知刷新
    loadList()
    loadLogs()
  }
}

onMounted(() => {
  const auth = useAuthStore()
  connectWebSocket(auth.token, onWsMessage)
  loadList()
  loadLogs()
})

onUnmounted(() => {
  // 不关闭全局 WS（Layout 也会用），仅清理本地引用
  wsHandler = null
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}
@media (max-width: 768px) {
  .filters { width: 100%; }
}
</style>
