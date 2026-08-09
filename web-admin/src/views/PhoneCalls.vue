<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>来电记录</span>
          <div class="filters">
            <el-select
              v-model="filters.deviceId"
              placeholder="选择设备"
              clearable
              filterable
              size="small"
              style="width: 220px"
              @change="loadList(1)"
            >
              <el-option
                v-for="d in devices"
                :key="d.id"
                :label="d.device_name || d.device_code"
                :value="d.id"
              />
            </el-select>
            <el-date-picker
              v-model="filters.range"
              type="datetimerange"
              size="small"
              range-separator="-"
              start-placeholder="开始"
              end-placeholder="结束"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 360px; margin-left: 12px"
              @change="loadList(1)"
            />
            <el-button size="small" style="margin-left: 12px" @click="loadList(1)">查询</el-button>
            <el-button size="small" @click="resetFilters">重置</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column label="时间" prop="received_at" width="180">
          <template #default="{ row }">{{ formatTime(row.received_at) }}</template>
        </el-table-column>
        <el-table-column label="设备" min-width="160">
          <template #default="{ row }">
            <span>{{ row.device_name || row.device_code || row.device_id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来电号码" min-width="140">
          <template #default="{ row }">
            <span v-if="row.phone_number" style="font-family: monospace">{{ row.phone_number }}</span>
            <span v-else class="text-info">未知号码</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="callStateTagType(row.call_state)" size="small">
              {{ callStateText(row.call_state) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="通话时长" width="120">
          <template #default="{ row }">
            <span v-if="row.duration_seconds != null">{{ row.duration_seconds }} 秒</span>
            <span v-else class="text-info">-</span>
          </template>
        </el-table-column>
        <el-table-column label="响铃时间" width="180">
          <template #default="{ row }">{{ formatTime(row.ring_started_at) || '-' }}</template>
        </el-table-column>
        <el-table-column label="接听时间" width="180">
          <template #default="{ row }">{{ formatTime(row.answered_at) || '-' }}</template>
        </el-table-column>
        <el-table-column label="挂断时间" width="180">
          <template #default="{ row }">{{ formatTime(row.ended_at) || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button text type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page.page"
          v-model:page-size="page.pageSize"
          :total="page.total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadList(1)"
          @current-change="loadList()"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPhoneCalls, deletePhoneCall, getDevices } from '@/api'
import { formatTime } from '@/utils/format'

const list = ref([])
const devices = ref([])
const loading = ref(false)
const filters = reactive({ deviceId: '', range: null })
const page = reactive({ page: 1, pageSize: 20, total: 0 })

function callStateText(state) {
  return { ringing: '响铃中', answered: '已接听', ended: '通话结束', missed: '未接来电' }[state] || state
}
function callStateTagType(state) {
  return { ringing: 'warning', answered: 'success', ended: 'info', missed: 'danger' }[state] || 'info'
}

async function loadList(p) {
  if (p) page.page = p
  loading.value = true
  try {
    const params = { page: page.page, pageSize: page.pageSize }
    if (filters.deviceId) params.deviceId = filters.deviceId
    if (filters.range && filters.range.length === 2) {
      params.start = filters.range[0]
      params.end = filters.range[1]
    }
    const res = await getPhoneCalls(params)
    list.value = res.list
    page.total = res.total
  } finally {
    loading.value = false
  }
}

async function loadDevices() {
  try {
    const res = await getDevices({ pageSize: 200 })
    devices.value = res.list
  } catch (e) { /* 忽略 */ }
}

function resetFilters() {
  filters.deviceId = ''
  filters.range = null
  loadList(1)
}

function onDelete(row) {
  ElMessageBox.confirm(`确定删除该来电记录？`, '提示', { type: 'warning' })
    .then(async () => {
      await deletePhoneCall(row.id)
      ElMessage.success('已删除')
      loadList()
    })
    .catch(() => {})
}

onMounted(() => {
  loadDevices()
  loadList(1)
})
</script>

<style scoped>
.card-header { display: flex; align-items: center; justify-content: space-between; }
.filters { display: flex; align-items: center; }
.text-info { color: #909399; }
.pager { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
