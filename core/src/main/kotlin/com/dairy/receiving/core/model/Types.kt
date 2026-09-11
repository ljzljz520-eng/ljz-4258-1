package com.dairy.receiving.core.model

import java.time.Instant

/** 强标识类型：保证跨仓室、样品、封签链路不被字符串混用。 */
@JvmInline value class TripId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class TruckId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class FarmId(val value: String) { init { require(value.isNotBlank()) } }
/** 牧场批（一次挤奶/一批次），身份核对的主键之一 */
@JvmInline value class FarmBatchId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class CompartmentCode(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class SealId(val value: String)
@JvmInline value class NfcTagId(val value: String)
@JvmInline value class SampleId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class BottleTagId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class TankId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class OperatorId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class Pin(val value: String)
/** 卸奶管线/歧管编号（收奶区固定设备台账） */
@JvmInline value class PipelineId(val value: String) { init { require(value.isNotBlank()) } }
/** 卸奶软管编号（临时更换须留痕） */
@JvmInline value class HoseId(val value: String) { init { require(value.isNotBlank()) } }

typealias TimePoint = Instant
