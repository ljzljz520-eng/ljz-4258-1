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

/** 样品链：个体样/混合样、来源仓、瓶签、深度、重量与稳定标志、时序。 */
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
    val takenAt: String,
    val takenBy: String,
)
