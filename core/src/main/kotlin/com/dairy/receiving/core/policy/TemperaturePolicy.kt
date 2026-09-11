package com.dairy.receiving.core.policy

import com.dairy.receiving.core.model.*
import java.time.Duration
import java.time.Instant

object TemperaturePolicy {

    fun evaluate(c: Compartment, cfg: ReceivingPolicyConfig, now: Instant): List<Finding> {
        val out = mutableListOf<Finding>()
        fun f(code: FindingCode, detail: String) = Finding(code, c.code, detail, now)

        // 车载记录缺段
        val log = c.truckLog
        if (log != null) {
            val span = Duration.between(log.loadedAt, log.arrivedAt)
            var coveredMs = 0L
            val merged = mergeIntervals(log.intervals)
            for (r in merged) {
                val lo = maxOf(r.start, log.loadedAt)
                val hi = minOf(r.endInclusive, log.arrivedAt)
                if (!hi.isBefore(lo)) coveredMs += Duration.between(lo, hi).toMillis()
            }
            val gap = span.minusMillis(coveredMs)
            if (gap > cfg.maxAllowedLogGap) {
                out += f(FindingCode.TEMP_LOG_GAP,
                    "仓${c.code.value}: 车载温度记录缺段 ${gap.toMinutes()} 分钟 " +
                        "(允许 ${cfg.maxAllowedLogGap.toMinutes()} 分钟)")
            }
            if (log.maxRecordedCelsius > cfg.tempWarnCelsius) {
                out += f(
                    if (log.maxRecordedCelsius > cfg.tempRejectCelsius)
                        FindingCode.TEMP_HIGH_REJECT else FindingCode.TEMP_HIGH_WARN,
                    "仓${c.code.value}: 车载记录最高 ${log.maxRecordedCelsius}℃")
            }
        }

        // BLE 探针
        val p = c.probe
        if (p == null) {
            // 已到封签之后仍无探针值 -> 提示
            if (c.seal != null) {
                out += f(FindingCode.TEMP_MISSING, "仓${c.code.value}: 尚无稳定探针温度")
            }
        } else {
            if (!p.stable) {
                out += f(FindingCode.TEMP_UNSTABLE,
                    "仓${c.code.value}: 探针 ${p.celsius}℃ 未稳定（设备 ${p.deviceId}）")
            } else {
                if (p.celsius > cfg.tempRejectCelsius) {
                    out += f(FindingCode.TEMP_HIGH_REJECT, "仓${c.code.value}: 探针 ${p.celsius}℃")
                } else if (p.celsius > cfg.tempWarnCelsius) {
                    out += f(FindingCode.TEMP_HIGH_WARN, "仓${c.code.value}: 探针 ${p.celsius}℃")
                }
                if (log != null) {
                    val diff = kotlin.math.abs(p.celsius - log.maxRecordedCelsius)
                    if (diff > cfg.probeVsLogToleranceCelsius) {
                        out += f(FindingCode.TEMP_DIVERGENCE,
                            "仓${c.code.value}: 探针 ${p.celsius}℃ 与车载记录峰值 " +
                                "${log.maxRecordedCelsius}℃ 相差 ${"%.1f".format(diff)}℃")
                    }
                }
            }
        }
        return out
    }

    private fun mergeIntervals(list: List<ClosedRange<Instant>>): List<ClosedRange<Instant>> {
        if (list.isEmpty()) return emptyList()
        val sorted = list.sortedBy { it.start }
        val result = mutableListOf(sorted[0])
        for (r in sorted.drop(1)) {
            val last = result.last()
            result += if (!r.start.isAfter(last.endInclusive)) {
                result.removeAt(result.lastIndex)
                last.start..maxOf(last.endInclusive, r.endInclusive)
            } else r
        }
        return result
    }
}
