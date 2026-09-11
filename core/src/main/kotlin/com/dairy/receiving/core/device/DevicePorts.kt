package com.dairy.receiving.core.device

import com.dairy.receiving.core.model.BottleTagId
import com.dairy.receiving.core.model.NfcTagId

/** 罐口/样瓶 NFC 读头端口（Android 侧用 NfcAdapter 实现）。 */
interface NfcReader {
    /** 阻塞等待一次标签；离线环境可用缓存的装车绑定模拟。 */
    suspend fun readTag(): NfcScan
}

data class NfcScan(
    val tagId: NfcTagId,
    val payload: String,
) {
    /** 约定 payload：HATCH:<seal>|<farm>|<batch> 或 BOTTLE:<sampleId> */
    fun parse(): ParsedNfc? {
        val parts = payload.split("|", ":")
        return when {
            payload.startsWith("HATCH:") -> {
                val p = payload.removePrefix("HATCH:").split("|")
                if (p.size >= 3) ParsedNfc.Hatch(tagId, p[0], p[1], p[2]) else null
            }
            payload.startsWith("BOTTLE:") ->
                ParsedNfc.Bottle(tagId, BottleTagId(payload.removePrefix("BOTTLE:")))
            else -> null
        }
    }
}

sealed class ParsedNfc {
    data class Hatch(
        val tag: NfcTagId, val seal: String, val farm: String, val batch: String,
    ) : ParsedNfc()
    data class Bottle(val tag: NfcTagId, val bottle: BottleTagId) : ParsedNfc()
}
