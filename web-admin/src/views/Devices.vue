<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>设备管理</span>
          <el-button type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>新建设备
          </el-button>
        </div>
      </template>

      <!-- 搜索栏 -->
      <el-form :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="设备名称/编码/型号" clearable @keyup.enter="onSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="在线" value="online" />
            <el-option label="离线" value="offline" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 设备表格 -->
      <el-table :data="list" v-loading="loading" border stripe>
        <el-table-column label="设备名称" min-width="120" prop="device_name" />
        <el-table-column label="设备编码" min-width="140" prop="device_code">
          <template #default="{ row }">
            <span style="font-family: monospace; font-size: 12px">{{ row.device_code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="型号" width="120" prop="model" show-overflow-tooltip>
          <template #default="{ row }">{{ row.model || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后上报" width="160">
          <template #default="{ row }">{{ fromNow(row.last_seen_at) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="$router.push(`/devices/${row.id}`)">详情</el-button>
            <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" @click="onRemove(row)">删除</el-button>
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

    <!-- 新建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing.id ? '编辑设备' : '新建设备'" width="520px" class="resp-dialog">
      <el-form :model="editing" label-width="100px">
        <el-form-item label="设备名称" required>
          <el-input v-model="editing.device_name" placeholder="例如：张三的手机" />
        </el-form-item>
        <el-form-item label="型号">
          <el-input v-model="editing.model" placeholder="可选，如 Pixel 7" />
        </el-form-item>
        <el-form-item label="系统版本">
          <el-input v-model="editing.os_version" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 设备凭证对话框（新建后展示） -->
    <el-dialog v-model="credentialVisible" title="设备凭证" width="520px" class="resp-dialog">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        请将以下凭证填入手机端 App 的"服务器配置"中，激活设备后方可上报数据。
      </el-alert>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="设备编码">
          <span style="font-family: monospace">{{ credential.device_code }}</span>
          <el-button text size="small" @click="copy(credential.device_code)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="设备令牌">
          <span style="font-family: monospace; word-break: break-all">{{ credential.device_token }}</span>
          <el-button text size="small" @click="copy(credential.device_token)">复制</el-button>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="credentialVisible = false">我已记录</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDevices, createDevice, updateDevice, deleteDevice } from '@/api'
import { statusTagType, statusText, formatTime, fromNow } from '@/utils/format'

const list = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', status: '', page: 1, pageSize: 20 })

const dialogVisible = ref(false)
const saving = ref(false)
const editing = reactive({ id: null, device_name: '', model: '', os_version: '' })

const credentialVisible = ref(false)
const credential = reactive({ device_code: '', device_token: '' })

async function loadList() {
  loading.value = true
  try {
    const res = await getDevices(query)
    list.value = res.list
    total.value = res.total
  } catch (e) { /* 忽略 */ } finally {
    loading.value = false
  }
}

function onSearch() { query.page = 1; loadList() }
function onReset() { query.keyword = ''; query.status = ''; query.page = 1; loadList() }

function openCreate() {
  Object.assign(editing, { id: null, device_name: '', model: '', os_version: '' })
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(editing, { id: row.id, device_name: row.device_name, model: row.model || '', os_version: row.os_version || '' })
  dialogVisible.value = true
}

async function onSave() {
  if (!editing.device_name) {
    ElMessage.warning('请填写设备名称')
    return
  }
  saving.value = true
  try {
    if (editing.id) {
      await updateDevice(editing.id, { device_name: editing.device_name, model: editing.model, os_version: editing.os_version })
      ElMessage.success('修改成功')
    } else {
      const res = await createDevice({ device_name: editing.device_name, platform: 'android', model: editing.model, os_version: editing.os_version })
      ElMessage.success('创建成功')
      Object.assign(credential, { device_code: res.device.device_code, device_token: res.device.device_token })
      credentialVisible.value = true
    }
    dialogVisible.value = false
    loadList()
  } catch (e) { /* 忽略 */ } finally {
    saving.value = false
  }
}

async function onRemove(row) {
  try {
    await ElMessageBox.confirm(`确定删除设备"${row.device_name || row.device_code}"？该设备的所有监控数据将被清除。`, '删除确认', { type: 'warning' })
    await deleteDevice(row.id)
    ElMessage.success('已删除')
    loadList()
  } catch (e) { /* 取消 */ }
}

function copy(text) {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}

onMounted(loadList)
</script>
