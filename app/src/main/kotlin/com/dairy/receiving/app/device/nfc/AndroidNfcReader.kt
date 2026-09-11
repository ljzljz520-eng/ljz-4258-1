package com.dairy.receiving.app.device.nfc

import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import com.dairy.receiving.core.device.NfcScan
import com.dairy.receiving.core.device.NfcReader
import com.dairy.receiving.core.model.NfcTagId
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 从 TAG intent/discovered tag 读取 NDEF 文本。
 * 约定载荷：HATCH:<封签号>|<牧场>|<批次> 或 BOTTLE:<瓶签号>。
 * 读不到 NDEF 时回退 UID，绝不猜测内容（身份核对不允许“看着像”）。
 */
class AndroidNfcReader : NfcReader {

    @Volatile private var pendingTag: Tag? = null

    fun onTagDiscovered(tag: Tag) { pendingTag = tag }

    override suspend fun readTag(): NfcScan {
        val tag = suspendCancellableCoroutine { cont ->
            // Activity 在 onNewIntent 中调用 onTagDiscovered；此处简化为自旋等待
            val t = pendingTag
            if (t != null) { pendingTag = null; cont.resume(t) }
        }
        return read(tag)
    }

    companion object {
        fun read(tag: Tag): NfcScan {
            val tagId = NfcTagId(tag.id.joinToString("") { "%02X".format(it) })
            val payload = runCatching {
                Ndef.get(tag)?.use { ndef ->
                    ndef.connect()
                    val msg: NdefMessage = ndef.ndefMessage ?: return@use null
                    msg.records.firstOrNull()?.payload?.let { bytes ->
                        // NDEF Text 记录前若干字节为语言头；直接提取可打印 ASCII/UTF 段
                        val text = String(bytes, Charsets.UTF_8)
                        text.dropWhile { it.code > 0x7E || it.code < 0x20 }
                    }
                }
            }.getOrNull()
            return NfcScan(tagId, payload ?: "")
        }

        fun isEnabled(adapter: NfcAdapter?): Boolean =
            adapter != null && adapter.isEnabled
    }
}
