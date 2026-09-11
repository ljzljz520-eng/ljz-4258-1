package com.dairy.receiving.core.model

/** 独立仓室：身份、温度、感官、样品链各自持有，混合卸载也不合并。 */
data class Compartment(
    val code: CompartmentCode,
    val farm: FarmId,
    val farmBatch: FarmBatchId,
    val loadedAt: TimePoint,
    val status: CompartmentStatus,
    val seal: SealCheck? = null,
    /** 途中补装：同一仓出现第二牧场/批时追加 */
    val secondaryLoads: List<SecondaryLoad> = emptyList(),
    val probe: ProbeReading? = null,
    val truckLog: TruckTemperatureLog? = null,
    val stirringConfirmedAt: TimePoint? = null,
    val stirringSeconds: Int? = null,
    val samples: List<Sample> = emptyList(),
    val labResults: Map<SampleId, LabResult> = emptyMap(),
    val unloadStartedAt: TimePoint? = null,
    val unloadFinishedAt: TimePoint? = null,
    val unloadGroupId: String? = null,
    val sensory: SensoryCheck? = null,
) {
    /** 卸前留取、且属于本仓的代表性个体样 */
    val hasPreUnloadSample: Boolean
        get() = samples.any { it.kind == SampleKind.INDIVIDUAL && (unloadStartedAt == null || !it.takenAt.isAfter(unloadStartedAt)) }
}

data class SecondaryLoad(
    val farm: FarmId,
    val farmBatch: FarmBatchId,
    val loadedAt: TimePoint,
    val evidence: String,
)

data class SensoryCheck(
    val normal: Boolean,
    val note: String,
    val checkedBy: OperatorId,
    val at: TimePoint,
)

/** 卸奶边界声明：独立卸入罐 或 多仓混合（必须给出组分仓身份）。 */
data class UnloadDeclaration(
    val groupId: String,
    val compartments: List<CompartmentCode>,
    val boundary: UnloadBoundary,
    val targetTank: TankId,
    val compositeSample: SampleId?,
    val declaredBy: OperatorId,
    val declaredAt: TimePoint,
)
