package com.dairy.receiving.core.model

/**
 * 代表性样品链：个体样在卸奶前、搅拌后、规定深度取样，
 * 样瓶 NFC 标签必须与系统绑定的瓶签一致，防止混样贴错。
 */
data class Sample(
    val id: SampleId,
    val bottleTag: BottleTagId,
    val kind: SampleKind,
    val sourceCompartments: List<CompartmentCode>,
    val depthCm: Double?,
    val stirringSeconds: Int?,
    val weightG: Double?,
    val weightStable: Boolean,
    val takenAt: TimePoint,
    val takenBy: OperatorId,
)
