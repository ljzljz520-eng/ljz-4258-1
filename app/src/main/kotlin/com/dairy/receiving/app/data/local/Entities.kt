package com.dairy.receiving.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单车离线行；mementoJson 为完整快照，断网可恢复、可继续核对。 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val tripId: String,
    val truckId: String,
    val updatedAt: Long,
    val overall: String,
    val dirty: Boolean,
    val mementoJson: String,
)

/** 离线仓室（按车-仓唯一）。 */
@Entity(tableName = "compartments")
data class CompartmentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tripId: String,
    val code: String,
    val farm: String,
    val farmBatch: String,
    val loadedAt: String,
    val status: String,
    val recommendation: String,
    val unloadGroupId: String?,
)

/** 封签链：装车单期望签、NFC 权威签、手写件、是否不可辨认。 */
@Entity(tableName = "seals")
data class SealEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tripId: String,
    val compartment: String,
    val expectedSeal: String,
    val nfcSeal: String?,
    val nfcTag: String?,
    val writtenSeal: String?,
    val illegible: Boolean,
    val checkedBy: String,
    val checkedAt: String,
)

/** 样品链：个体样/混合样、来源仓、瓶签、深度、重量（含来源设备）与稳定标志、时序。 */
@Entity(tableName = "samples")
data class SampleEntity(
    @PrimaryKey val sampleId: String,
    val tripId: String,
    val bottleTag: String,
    val kind: String,
    val sourceCompartments: String,
    val depthCm: Double?,
    val stirringSeconds: Int?,
    val weightG: Double?,
    val weightStable: Boolean,
    /** 重量来源设备：只有指定 BLE 采样秤的读数才构成合法重量链。 */
    val weightDeviceId: String?,
    val takenAt: String,
    val takenBy: String,
)

/**
 * 卸奶管线连接见证（按车-组检索）：清洁验收、连接时刻、前段冲洗液去向、
 * 共用歧管车辆。首仓/末仓残留归属由 core 规则按组分顺序推导，不落库。
 */
@Entity(tableName = "pipeline_connections")
data class PipelineConnectionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tripId: String,
    val groupId: String,
    val pipeline: String,
    val hose: String,
    /** 计划卸奶顺序（逗号分隔，首仓 = 第一个） */
    val compartments: String,
    val cleaningMethod: String,
    val cleaningAcceptedAt: String,
    val cleaningValidUntil: String,
    val flushDestination: String,
    val flushVolumeLiters: Double?,
    val backflowSuspected: Boolean,
    val sharedWithTruck: String?,
    val connectedBy: String,
    val connectedAt: String,
)
