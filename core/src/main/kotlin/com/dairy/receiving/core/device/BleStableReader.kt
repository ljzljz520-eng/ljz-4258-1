package com.dairy.receiving.core.device

import com.dairy.receiving.core.model.ProbeReading
import java.time.Clock
import java.time.Instant

/**
 * BLE 温度计/采样秤“稳定值”判定：连续 window 个采样波动 <= epsilon 即稳定。
 * 设备（Android BLE）只在稳定时输出 ProbeReading(stable=true)；
 * 未稳定值可展示，但不得作为判定依据。
 */
class StableReadingAccumulator(
    private val window: Int = 5,
    private val epsilon: Double,
    private val deviceId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val buf = ArrayDeque<Double>()

    @Synchronized
    fun accept(value: Double): ProbeReading? {
        buf.addLast(value)
        while (buf.size > window) buf.removeFirst()
        if (buf.size < window) return null
        val stable = (buf.max() - buf.min()) <= epsilon
        return ProbeReading(
            celsius = value,
            stable = stable,
            at = Instant.now(clock),
            deviceId = deviceId,
        )
    }
}
