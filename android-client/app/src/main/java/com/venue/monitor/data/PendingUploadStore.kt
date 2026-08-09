package com.venue.monitor.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 持久化待上传队列 - 实现断点续传与失败重试
 *
 * 工作原理：
 *  - 每次 collectAndUpload 采集完数据后，先把数据 enqueue 到本地文件
 *  - 然后调用 drain 逐条上传，上传成功的从队列移除
 *  - 若 App 在上传途中被系统杀死，下次启动时队列里仍有未上传的数据，继续上传
 *
 * 队列结构：
 *  - 单个 JSON 文件 pending_uploads.json，包含一个 entries 数组
 *  - 每条 entry 含：唯一 ID、类型（monitor/sms）、payload（JSON 字符串）、尝试次数、入队时间
 *
 * 容量保护：最多保留 MAX_ENTRIES 条，超过则丢弃最早的（避免无限堆积）。
 */
class PendingUploadStore(context: Context) {

    private val file: File = File(context.applicationContext.filesDir, "pending_uploads.json")
    private val gson = Gson()
    private val lock = ReentrantLock()

    data class Entry(
        @SerializedName("id") val id: String,
        @SerializedName("type") val type: String, // "monitor" | "sms"
        @SerializedName("payload") val payload: String, // 序列化后的请求体 JSON
        @SerializedName("created_at") val createdAt: Long,
        @SerializedName("attempts") var attempts: Int = 0
    )

    private data class StoreFile(
        @SerializedName("entries") val entries: MutableList<Entry> = mutableListOf()
    )

    /** 加入一条待上传记录 */
    fun enqueue(type: String, payloadJson: String) = lock.withLock {
        val store = readStore()
        store.entries.add(Entry(
            id = UUID.randomUUID().toString(),
            type = type,
            payload = payloadJson,
            createdAt = System.currentTimeMillis()
        ))
        // 容量保护：丢弃最早的
        while (store.entries.size > MAX_ENTRIES) {
            store.entries.removeAt(0)
        }
        writeStore(store)
    }

    /** 获取所有待上传条目（不消费） */
    fun peekAll(): List<Entry> = lock.withLock {
        readStore().entries.toList()
    }

    /** 标记某条目上传成功，从队列移除 */
    fun remove(id: String) = lock.withLock {
        val store = readStore()
        store.entries.removeAll { it.id == id }
        writeStore(store)
    }

    /** 标记某条目本次尝试失败（增加 attempts 计数） */
    fun markFailed(id: String) = lock.withLock {
        val store = readStore()
        val entry = store.entries.find { it.id == id }
        if (entry != null) {
            entry.attempts++
            // 超过最大尝试次数则丢弃（避免毒丸消息无限重试）
            if (entry.attempts > MAX_ATTEMPTS) {
                store.entries.removeAll { it.id == id }
            }
        }
        writeStore(store)
    }

    /** 队列大小 */
    fun size(): Int = lock.withLock {
        readStore().entries.size
    }

    private fun readStore(): StoreFile {
        if (!file.exists()) return StoreFile(mutableListOf())
        return try {
            val text = file.readText()
            if (text.isBlank()) StoreFile(mutableListOf())
            else gson.fromJson(text, StoreFile::class.java) ?: StoreFile(mutableListOf())
        } catch (e: JsonSyntaxException) {
            // 文件损坏则丢弃
            StoreFile(mutableListOf())
        } catch (e: Exception) {
            StoreFile(mutableListOf())
        }
    }

    private fun writeStore(store: StoreFile) {
        try {
            val tmp = File(file.parentFile, "pending_uploads.json.tmp")
            tmp.writeText(gson.toJson(store))
            // 原子替换，避免写入中途崩溃损坏文件
            tmp.renameTo(file)
        } catch (_: Throwable) { /* 忽略写入失败 */ }
    }

    companion object {
        /** 最大保留条数（每条目含一次完整采集，包含最多 500 条短信也算一条） */
        private const val MAX_ENTRIES = 100
        /** 单条最大重试次数，超过则丢弃避免毒丸 */
        private const val MAX_ATTEMPTS = 8
    }
}
