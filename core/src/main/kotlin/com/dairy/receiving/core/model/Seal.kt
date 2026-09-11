package com.dairy.receiving.core.model

/**
 * 封签核对。NFC 读签为权威；手写件只作辅助，字迹不清/不一致只能产生缺陷、
 * 不能“修正”NFC。expected = 牧场装车单封签号。
 */
data class SealCheck(
    val expected: SealId,
    val nfc: SealId?,
    val nfcTag: NfcTagId?,
    val written: SealId?,
    val writtenIllegible: Boolean,
    val checkedBy: OperatorId,
    val checkedAt: TimePoint,
)

/** 预报/装车单中的封签-仓室绑定，从牧场数据离线缓存。 */
data class ExpectedSealBinding(
    val compartment: CompartmentCode,
    val seal: SealId,
    val farm: FarmId,
    val farmBatch: FarmBatchId,
    val loadedAt: TimePoint,
    val nfcTag: NfcTagId?,
)
