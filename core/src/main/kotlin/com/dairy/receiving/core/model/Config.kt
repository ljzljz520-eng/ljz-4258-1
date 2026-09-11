package com.dairy.receiving.core.model

import java.time.Duration

data class ReceivingPolicyConfig(
    val tempWarnCelsius: Double = 6.0,
    val tempRejectCelsius: Double = 8.0,
    val probeVsLogToleranceCelsius: Double = 1.5,
    val maxAllowedLogGap: Duration = Duration.ofMinutes(20),
    val minStirringSeconds: Int = 120,
    val sampleDepthMinCm: Double = 30.0,
    val sampleDepthMaxCm: Double = 80.0,
    val minSampleWeightG: Double = 50.0,
    /** 收奶厂接收的牧场白名单；null 表示不做白名单校验 */
    val allowedFarms: Set<FarmId>? = null,
)
