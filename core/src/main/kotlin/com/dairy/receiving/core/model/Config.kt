package com.dairy.receiving.core.model

import java.time.Duration

/** 收奶区指定 BLE 采样秤设备 ID（模拟器与 Android GATT 接入共用同一身份）。 */
const val DEFAULT_SCALE_DEVICE_ID = "ble-scale-01"

data class ReceivingPolicyConfig(
    val tempWarnCelsius: Double = 6.0,
    val tempRejectCelsius: Double = 8.0,
    val probeVsLogToleranceCelsius: Double = 1.5,
    val maxAllowedLogGap: Duration = Duration.ofMinutes(20),
    val minStirringSeconds: Int = 120,
    val sampleDepthMinCm: Double = 30.0,
    val sampleDepthMaxCm: Double = 80.0,
    val minSampleWeightG: Double = 50.0,
    /**
     * 指定 BLE 采样秤设备 ID（与设备 MAC 配对绑定）。
     * 样品重量链只接受来自该设备的稳定读数；其他设备/手工录入的重量一律阻断。
     */
    val designatedScaleDeviceId: String = DEFAULT_SCALE_DEVICE_ID,
    /** 收奶厂接收的牧场白名单；null 表示不做白名单校验 */
    val allowedFarms: Set<FarmId>? = null,
)
