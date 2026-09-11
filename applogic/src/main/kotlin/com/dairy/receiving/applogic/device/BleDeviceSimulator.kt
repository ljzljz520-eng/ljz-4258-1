package com.dairy.receiving.applogic.device

import com.dairy.receiving.core.device.StableReadingAccumulator
import com.dairy.receiving.core.device.StableScaleAccumulator
import com.dairy.receiving.core.model.ProbeReading
import com.dairy.receiving.core.model.WeightReading
import kotlin.math.sin

/**
 * 设备模拟器：无 BLE 硬件/离线演练时喂流。生产环境 Android 端直接用
 * BluetoothLeScanner/GATT 回调替换 emitSequence；稳定判定逻辑保持一致（设备侧也判稳）。
 */
object BleDeviceSimulator {

    /** 指定采样秤默认设备 ID，与 ReceivingPolicyConfig.designatedScaleDeviceId 一致。 */
    const val SCALE_DEVICE_ID = "ble-scale-01"

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

    /**
     * 指定 BLE 采样秤流：前几拍上秤晃动（未稳定），后段稳定在 finalG。
     * 默认设备 ID 即收奶区指定秤；演练“非指定设备”时传其他 deviceId。
     */
    fun scaleStream(
        finalG: Double = 200.0,
        deviceId: String = SCALE_DEVICE_ID,
        ticks: Int = 8,
    ): List<WeightReading> {
        val acc = StableScaleAccumulator(window = 5, epsilon = 1.0, deviceId = deviceId)
        return (0 until ticks).map { i ->
            val v = if (i < 3) finalG * (0.6 + i * 0.13) else finalG
            acc.accept(v)
        }
    }
}
