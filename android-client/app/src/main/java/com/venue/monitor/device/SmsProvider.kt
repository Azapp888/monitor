package com.venue.monitor.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.venue.monitor.data.SmsItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 短信内容 Provider - 从系统短信数据库读取全部短信内容
 *
 * 读取路径：content://sms/ （全部短信）
 * 权限要求：android.permission.READ_SMS
 */
class SmsProvider(private val context: Context) {

    /** 是否拥有读取短信的权限 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 读取全部短信（按时间倒序，先新后旧）
     * @param limit 最大条数，null 表示不限
     * @param sinceSmsId 仅返回大于该 smsId 的条目（用于增量同步），null 表示全量
     */
    fun readAllSms(limit: Int? = 500, sinceSmsId: Long? = null): List<SmsItem> {
        if (!hasPermission()) return emptyList()

        val items = mutableListOf<SmsItem>()
        val uri: Uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,          // 0: sms id
            Telephony.Sms.ADDRESS,      // 1: 对方号码
            Telephony.Sms.BODY,         // 2: 正文
            Telephony.Sms.TYPE,         // 3: 1=收 2=发
            Telephony.Sms.DATE,         // 4: 时间戳 (ms)
            Telephony.Sms.READ,         // 5: 0未读 1已读
            Telephony.Sms.SERVICE_CENTER // 6: 短信中心
        )

        val selection: String?
        val selectionArgs: Array<String>?
        if (sinceSmsId != null) {
            selection = "${Telephony.Sms._ID} > ?"
            selectionArgs = arrayOf(sinceSmsId.toString())
        } else {
            selection = null
            selectionArgs = null
        }

        val sortOrder = if (limit != null) {
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        } else {
            "${Telephony.Sms.DATE} DESC"
        }

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val smsId = cursor.getLong(0)
                    val address = cursor.getString(1)
                    val body = cursor.getString(2)
                    val type = cursor.getInt(3)
                    val dateMs = cursor.getLong(4)
                    val read = cursor.getInt(5)
                    val serviceCenter = cursor.getString(6)

                    val personName = lookupContactName(address)

                    items += SmsItem(
                        smsId = smsId,
                        address = address,
                        body = body,
                        type = type,
                        personName = personName,
                        receivedAt = msToIso(dateMs),
                        read = read,
                        serviceCenter = serviceCenter
                    )
                } while (cursor.moveToNext())
            }
        } catch (e: Throwable) {
            // 读取失败就跳过，不阻塞主流程
        } finally {
            try { cursor?.close() } catch (_: Throwable) {}
        }

        return items
    }

    /** 读取最新一条短信的 ID（用于记录增量同步位置） */
    fun getLatestSmsId(): Long? {
        if (!hasPermission()) return null
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null, null,
                "${Telephony.Sms._ID} DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        } catch (_: Throwable) {} finally {
            try { cursor?.close() } catch (_: Throwable) {}
        }
        return null
    }

    /** 根据号码查联系人姓名（无匹配返回 null） */
    private fun lookupContactName(address: String?): String? {
        if (address.isNullOrBlank()) return null
        var cursor: Cursor? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        } catch (_: Throwable) {} finally {
            try { cursor?.close() } catch (_: Throwable) {}
        }
        return null
    }

    private fun msToIso(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    companion object {
        /** SMS 类型常量，方便调用方解读 */
        const val TYPE_INBOX = 1   // 收到
        const val TYPE_SENT = 2    // 发出
        const val TYPE_DRAFT = 3   // 草稿
    }
}
