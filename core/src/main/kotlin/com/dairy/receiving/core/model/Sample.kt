package com.dairy.receiving.core.model

/**
 * BLE 采样秤稳定读数。重量链只能由指定的采样秤设备产生：
 *  [deviceId] 必须等于本车 [ReceivingPolicyConfig.designatedScaleDeviceId]，
 *  否则该重量不被样品链承认（规则见 SamplingPolicy）。
 */
data class WeightReading(
    val grams: Double,
    val stable: Boolean,
    val at: TimePoint,
    val deviceId: String,
)

/**
 * 代表性样品链：个体样在卸奶前、搅拌后、规定深度取样，
 * 样瓶 NFC 标签必须与系统绑定的瓶签一致，防止混样贴错；
 * 样重必须来自指定 BLE 采样秤的稳定读数（[weight]），禁止手工录入重量。
 */
data class Sample(
    val id: SampleId,
    val bottleTag: BottleTagId,
    val kind: SampleKind,
    val sourceCompartments: List<CompartmentCode>,
    val depthCm: Double?,
    val stirringSeconds: Int?,
    val weight: WeightReading?,
    val takenAt: TimePoint,
    val takenBy: OperatorId,
) {
    /** 采样秤读数（克）；null 表示该样没有合法秤读数。 */
    val weightG: Double? get() = weight?.grams
    /** 秤是否给出稳定值；无读数视为未稳定。 */
    val weightStable: Boolean get() = weight?.stable == true
    /** 重量来源设备；null 表示重量链缺失。 */
    val weightDeviceId: String? get() = weight?.deviceId
}

