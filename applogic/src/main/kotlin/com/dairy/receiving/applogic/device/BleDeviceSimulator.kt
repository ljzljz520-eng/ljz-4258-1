package com.dairy.receiving.applogic.device

import com.dairy.receiving.core.device.StableReadingAccumulator
import com.dairy.receiving.core.model.ProbeReading
import kotlin.math.sin

/**
 * 设备模拟器：无 BLE 硬件/离线演练时喂流。生产环境 Android 端直接用
 * BluetoothGatt 回调替换 emitSequence；稳定判定逻辑保持一致（设备侧也判稳）。
 */
object BleDeviceSimulator {

    fun probeStream(
        base: Double = 4.1,
        noise: Double = 0.15,
        settleAfter: Int = 3,
        deviceId: String = "probe-sim",
        ticks: Int = 8,
    ): List<ProbeReading> {
        val acc = StableReadingAccumulator(window = 5, epsilon = 0.4, deviceId = deviceId)
        return (0 until ticks).mapNotNull { i ->
            val v = if (i < settleAfter) base + sin(i * 1.7) * 1.4
                    else base + (if (i % 2 == 0) noise else -noise)
            acc.accept(v)
        }
    }

    fun scaleStream(finalG: Double = 200.0, deviceId: String = "scale-sim", ticks: Int = 8)
        : List<ProbeReading> {
        val acc = StableReadingAccumulator(window = 5, epsilon = 1.0, deviceId = deviceId)
        return (0 until ticks).mapNotNull { i ->
            val v = if (i < 3) finalG * (0.6 + i * 0.13) else finalG
            // ProbeReading 复用作“带稳定标志的数值”；秤场景用 weightStable 解释
            acc.accept(v)
        }
    }
}
