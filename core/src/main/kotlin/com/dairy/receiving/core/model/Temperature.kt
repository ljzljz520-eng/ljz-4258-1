package com.dairy.receiving.core.model

/** BLE 探针稳定读数（设备端稳定性判定结果一并带来）。 */
data class ProbeReading(
    val celsius: Double,
    val stable: Boolean,
    val at: TimePoint,
    val deviceId: String,
)

/**
 * 车载温度记录。intervals 为有数据的连续段；
 * [loadedAt, arrivedAt] 之间总跨度减去覆盖跨度即缺段。
 */
data class TruckTemperatureLog(
    val loadedAt: TimePoint,
    val arrivedAt: TimePoint,
    val intervals: List<ClosedRange<TimePoint>>,
    val maxRecordedCelsius: Double,
) {
    init { require(arrivedAt >= loadedAt) }
}
