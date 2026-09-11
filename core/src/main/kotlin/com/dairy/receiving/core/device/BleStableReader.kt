package com.dairy.receiving.core.device

import com.dairy.receiving.core.model.ProbeReading
import com.dairy.receiving.core.model.WeightReading
import java.time.Clock
import java.time.Instant

/**
 * 滑动窗口稳定判定：连续 window 个采样的极差 <= epsilon 视为稳定。
 */
internal class StableWindow(private val window: Int, private val epsilon: Double) {
    private val buf = ArrayDeque<Double>()
    val size: Int get() = buf.size

    /** 推入一个采样，返回 (当前值, 是否已稳定)。 */
    fun feed(value: Double): Pair<Double, Boolean> {
        buf.addLast(value)
        while (buf.size > window) buf.removeFirst()
        val stable = buf.size >= window && (buf.max() - buf.min()) <= epsilon
        return value to stable
    }
}

/**
 * BLE 温度计稳定值判定。窗口未满不输出；窗口满后输出读数与稳定标志，
 * 未稳定读数仅供展示，不进入判定。
 */
class StableReadingAccumulator(
    private val window: Int = 5,
    epsilon: Double,
    private val deviceId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val win = StableWindow(window, epsilon)

    @Synchronized
    fun accept(value: Double): ProbeReading? {
        val (v, stable) = win.feed(value)
        if (win.size < window) return null
        return ProbeReading(v, stable, Instant.now(clock), deviceId)
    }
}

/**
 * BLE 采样秤稳定值判定。窗口未满/未稳定也输出 [WeightReading]（UI 实时显示重量变化），
 * 但 stable=false 的读数不会被样品链接受；设备 ID 必须是指定采样秤。
 */
class StableScaleAccumulator(
    private val window: Int = 5,
    epsilon: Double,
    private val deviceId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val win = StableWindow(window, epsilon)

    @Synchronized
    fun accept(value: Double): WeightReading {
        val (v, stable) = win.feed(value)
        return WeightReading(v, stable, Instant.now(clock), deviceId)
    }
}
