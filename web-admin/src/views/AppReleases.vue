<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>应用版本管理</span>
          <el-button type="primary" size="small" @click="showUpload">上传新版本</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        上传 APK 后默认激活为最新版本。勾选"强制更新"后，设备端启动时必须更新才能使用。
      </el-alert>

      <el-table :data="list" v-loading="loading" border stripe>
        <el-table-column label="版本号" width="100" prop="version_code" />
        <el-table-column label="版本名" width="120" prop="version_name">
          <template #default="{ row }">v{{ row.version_name }}</template>
        </el-table-column>
        <el-table-column label="文件大小" width="120">
          <template #default="{ row }">{{ formatSize(row.file_size) }}</template>
        </el-table-column>
        <el-table-column label="更新说明" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.release_notes" style="white-space: pre-wrap">{{ row.release_notes }}</span>
            <span v-else style="color: #999">-</span>
          </template>
        </el-table-column>
        <el-table-column label="强制更新" width="110" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.force_update"
              @change="(val) => onToggleForce(row, val)"
              :disabled="!row.is_active"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.is_active" type="success" size="small">当前生效</el-tag>
            <el-tag v-else type="info" size="small">历史版本</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传人" width="100" prop="uploaded_by">
          <template #default="{ row }">{{ row.uploaded_by || '-' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="160">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.is_active"
              type="primary"
              size="small"
              @click="onActivate(row)"
            >激活</el-button>
            <a :href="downloadUrl(row)" target="_blank" style="margin-left: 8px">
              <el-button size="small">下载</el-button>
            </a>
            <el-button
              v-if="!row.is_active"
              type="danger"
              size="small"
              style="margin-left: 8px"
              @click="onDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 上传对话框 -->
    <el-dialog v-model="uploadVisible" title="上传新版本 APK" width="560px" class="resp-dialog">
      <el-form :model="form" label-width="100px">
        <el-form-item label="APK 文件" required>
          <input
            ref="fileInput"
            type="file"
            accept=".apk"
            style="display: none"
            @change="onFileChange"
          />
          <el-button @click="$refs.fileInput.click()">选择 APK 文件</el-button>
          <span v-if="form.file" style="margin-left: 12px; font-size: 13px">{{ form.file.name }}</span>
          <span v-else style="margin-left: 12px; color: #999; font-size: 13px">未选择</span>
        </el-form-item>
        <el-form-item label="版本号" required>
          <el-input-number v-model="form.version_code" :min="1" :step="1" controls-position="right" style="width: 200px" />
          <span style="margin-left: 8px; color: #999; font-size: 12px">整数，越大越新</span>
        </el-form-item>
        <el-form-item label="版本名" required>
          <el-input v-model="form.version_name" placeholder="如 1.2.0" style="width: 200px" />
        </el-form-item>
        <el-form-item label="更新说明">
          <el-input
            v-model="form.release_notes"
            type="textarea"
            :rows="4"
            placeholder="本次更新内容，将展示给设备端用户"
          />
        </el-form-item>
        <el-form-item label="强制更新">
          <el-switch v-model="form.force_update" />
          <span style="margin-left: 8px; color: #999; font-size: 12px">开启后设备端必须更新才能使用</span>
        </el-form-item>
        <el-form-item v-if="uploadProgress > 0" label="上传进度">
          <el-progress :percentage="uploadProgress" :status="uploadProgress === 100 ? 'success' : ''" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="doUpload">开始上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getReleases,
  uploadRelease,
  activateRelease,
  setReleaseForce,
  deleteRelease
} from '@/api'
import { formatTime } from '@/utils/format'

const list = ref([])
const loading = ref(false)
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const fileInput = ref(null)
const form = reactive({
  file: null,
  version_code: 1,
  version_name: '',
  release_notes: '',
  force_update: false
})

async function loadList() {
  loading.value = true
  try {
    const res = await getReleases()
    list.value = res.list
  } catch (e) { /* 拦截器处理 */ } finally {
    loading.value = false
  }
}

function showUpload() {
  form.file = null
  form.version_code = (list.value[0]?.version_code || 0) + 1
  form.version_name = ''
  form.release_notes = ''
  form.force_update = false
  uploadProgress.value = 0
  if (fileInput.value) fileInput.value.value = ''
  uploadVisible.value = true
}

function onFileChange(e) {
  const f = e.target.files[0]
  if (!f) return
  if (!f.name.toLowerCase().endsWith('.apk')) {
    ElMessage.warning('请选择 .apk 文件')
    return
  }
  form.file = f
}

async function doUpload() {
  if (!form.file) { ElMessage.warning('请选择 APK 文件'); return }
  if (!form.version_name) { ElMessage.warning('请填写版本名'); return }
  uploading.value = true
  uploadProgress.value = 0
  try {
    const fd = new FormData()
    fd.append('file', form.file)
    fd.append('version_code', String(form.version_code))
    fd.append('version_name', form.version_name)
    fd.append('release_notes', form.release_notes)
    fd.append('force_update', form.force_update ? '1' : '0')
    fd.append('is_active', '1')
    await uploadRelease(fd, (e) => {
      if (e.total) uploadProgress.value = Math.round(e.loaded * 100 / e.total)
    })
    ElMessage.success('上传成功，已设为当前生效版本')
    uploadVisible.value = false
    loadList()
  } catch (e) { /* 拦截器处理 */ } finally {
    uploading.value = false
  }
}

async function onActivate(row) {
  try {
    await ElMessageBox.confirm(`确认激活版本 v${row.version_name}？激活后此版本将成为设备端检查更新时的最新版本。`, '激活版本', { type: 'warning' })
    await activateRelease(row.id)
    ElMessage.success('已激活')
    loadList()
  } catch (e) { /* 取消或拦截器处理 */ }
}

async function onToggleForce(row, val) {
  try {
    await setReleaseForce(row.id, val)
    ElMessage.success(val ? '已开启强制更新' : '已关闭强制更新')
    loadList()
  } catch (e) { /* 拦截器处理 */ }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除版本 v${row.version_name}？APK 文件将一并删除。`, '删除版本', { type: 'warning' })
    await deleteRelease(row.id)
    ElMessage.success('已删除')
    loadList()
  } catch (e) { /* 取消或拦截器处理 */ }
}

function downloadUrl(row) {
  return row.download_url
}

function formatSize(bytes) {
  if (!bytes || bytes <= 0) return '-'
  const kb = bytes / 1024
  if (kb < 1024) return kb.toFixed(1) + ' KB'
  return (kb / 1024).toFixed(1) + ' MB'
}

onMounted(() => loadList())
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
