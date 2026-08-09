<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>短信记录</span>
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
            <el-select
              v-model="filters.type"
              placeholder="短信类型"
              clearable
              size="small"
              style="width: 140px; margin-left: 12px"
              @change="loadList(1)"
            >
              <el-option label="收到的短信" :value="1" />
              <el-option label="发出的短信" :value="2" />
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
            <el-input
              v-model="filters.keyword"
              placeholder="号码/内容搜索"
              clearable
              size="small"
              style="width: 200px; margin-left: 12px"
              @clear="loadList(1)"
              @keyup.enter="loadList(1)"
            />
            <el-button size="small" style="margin-left: 12px" @click="loadList(1)">查询</el-button>
            <el-button size="small" @click="resetFilters">重置</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" v-loading="loading" stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column label="收发时间" prop="received_at" width="180" sortable>
          <template #default="{ row }">{{ formatTime(row.received_at) }}</template>
        </el-table-column>
        <el-table-column label="设备" min-width="160">
          <template #default="{ row }">
            <span>{{ row.device_name || row.device_code || row.device_id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对方号码" min-width="140">
          <template #default="{ row }">
            <div>
              <span v-if="row.address" style="font-family: monospace; font-weight: 500">
                {{ row.address }}
              </span>
              <span v-else class="text-info">未知号码</span>
              <div v-if="row.person_name" class="person-name">{{ row.person_name }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
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
        <el-table-column label="短信内容" min-width="320" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="sms-body">{{ row.body || '(空内容)' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="短信中心" width="160">
          <template #default="{ row }">
            <span v-if="row.service_center" style="font-family: monospace; font-size: 12px">
              {{ row.service_center }}
            </span>
            <span v-else class="text-info">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="onView(row)">查看</el-button>
            <el-button text type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <div v-if="selected.length > 0" class="bulk-actions">
          <span>已选 {{ selected.length }} 条</span>
          <el-button size="small" type="danger" @click="onDeleteBatch">批量删除</el-button>
        </div>
        <el-pagination
          v-model:current-page="page.page"
          v-model:page-size="page.pageSize"
          :total="page.total"
          :page-sizes="[20, 50, 100, 200]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadList(1)"
          @current-change="loadList()"
        />
      </div>
    </el-card>

    <!-- 查看短信详情抽屉 -->
    <el-drawer
      v-model="detailVisible"
      title="短信详情"
      direction="rtl"
      size="520px"
    >
      <div v-if="current" class="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="收发时间">{{ formatTime(current.received_at) }}</el-descriptions-item>
          <el-descriptions-item label="设备">
            {{ current.device_name || current.device_code || current.device_id }}
          </el-descriptions-item>
          <el-descriptions-item label="对方号码">
            <span style="font-family: monospace">{{ current.address || '-' }}</span>
            <span v-if="current.person_name" style="margin-left: 8px; color: #606266">
              ({{ current.person_name }})
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            <el-tag :type="current.type === 2 ? 'primary' : 'success'" size="small">
              {{ current.type === 2 ? '发出' : '收到' }}
            </el-tag>
            <el-tag
              v-if="current.read === 0"
              type="danger"
              size="small"
              effect="plain"
              style="margin-left: 4px"
            >未读</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="短信中心">
            <span style="font-family: monospace">{{ current.service_center || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="接收时间（服务端）">
            {{ formatTime(current.created_at || current.received_at) }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="detail-body-title">短信内容</div>
        <div class="detail-body">{{ current.body || '(空内容)' }}</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSmsList, deleteSms, deleteSmsBatch, getDevices } from '@/api'
import { formatTime } from '@/utils/format'

const list = ref([])
const devices = ref([])
const loading = ref(false)
const selected = ref([])
const filters = reactive({
  deviceId: '',
  type: '',
  range: null,
  keyword: ''
})
const page = reactive({ page: 1, pageSize: 20, total: 0 })

const detailVisible = ref(false)
const current = ref(null)

async function loadList(p) {
  if (p) page.page = p
  loading.value = true
  try {
    const params = { page: page.page, pageSize: page.pageSize }
    if (filters.deviceId) params.deviceId = filters.deviceId
    if (filters.type !== '' && filters.type != null) params.type = filters.type
    if (filters.range && filters.range.length === 2) {
      params.start = filters.range[0]
      params.end = filters.range[1]
    }
    if (filters.keyword) params.keyword = filters.keyword
    const res = await getSmsList(params)
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
  filters.type = ''
  filters.range = null
  filters.keyword = ''
  loadList(1)
}

function onSelectionChange(rows) {
  selected.value = rows
}

function onView(row) {
  current.value = row
  detailVisible.value = true
}

function onDelete(row) {
  ElMessageBox.confirm(`确定删除该短信记录？`, '提示', { type: 'warning' })
    .then(async () => {
      await deleteSms(row.id)
      ElMessage.success('已删除')
      loadList()
    })
    .catch(() => {})
}

function onDeleteBatch() {
  if (selected.value.length === 0) return
  ElMessageBox.confirm(
    `确定删除选中的 ${selected.value.length} 条短信记录？`,
    '提示',
    { type: 'warning' }
  ).then(async () => {
    await deleteSmsBatch({ ids: selected.value.map((r) => r.id) })
    ElMessage.success('已删除')
    loadList()
  }).catch(() => {})
}

onMounted(() => {
  loadDevices()
  loadList(1)
})
</script>

<style scoped>
.card-header { display: flex; align-items: center; justify-content: space-between; }
.filters { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.text-info { color: #909399; }
.person-name { font-size: 12px; color: #909399; margin-top: 2px; }
.sms-body {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
  color: #303133;
  word-break: break-all;
}
.table-footer {
  margin-top: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.bulk-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #606266;
  font-size: 13px;
}

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
</style>
