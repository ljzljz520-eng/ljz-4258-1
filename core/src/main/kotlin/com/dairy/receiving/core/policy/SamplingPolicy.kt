package com.dairy.receiving.core.policy

import com.dairy.receiving.core.model.*
import java.time.Instant

/**
 * 取样代表性规则：
 *  - 搅拌时长不足 -> 警告
 *  - 深度越界 / 秤未稳定 -> 警告
 *  - 样瓶标签与所绑仓不符（混样贴错）-> 阻断
 *  - 卸奶开始后才取个体样（先卸后取）-> 阻断
 */
object SamplingPolicy {

    fun evaluate(
        c: Compartment,
        /** 本车样瓶 NFC 读到标签 -> 系统期望绑定仓/样 ID 的解析结果 */
        bottleBinding: Map<BottleTagId, SampleId>,
        cfg: ReceivingPolicyConfig,
        now: Instant,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        fun f(code: FindingCode, detail: String) = Finding(code, c.code, detail, now)

        if ((c.stirringSeconds ?: 0) < cfg.minStirringSeconds && c.stirringConfirmedAt != null) {
            out += f(FindingCode.STIRRING_INSUFFICIENT,
                "仓${c.code.value}: 搅拌 ${c.stirringSeconds}s < ${cfg.minStirringSeconds}s")
        }

        for (s in c.samples) {
            val isIndividualOfThis = s.kind == SampleKind.INDIVIDUAL && s.sourceCompartments == listOf(c.code)

            // 瓶签解析：NFC 读到的瓶签应绑定本样品
            val bound = bottleBinding[s.bottleTag]
            if (bound != null && bound != s.id) {
                out += f(FindingCode.SAMPLE_LABEL_MISMATCH,
                    "仓${c.code.value}: 瓶签 ${s.bottleTag.value} 实际绑定样品 ${bound.value}，" +
                        "与 ${s.id.value} 不符（混合样瓶贴错）")
            } else if (bound == null) {
                out += f(FindingCode.SAMPLE_LABEL_MISMATCH,
                    "仓${c.code.value}: 瓶签 ${s.bottleTag.value} 在本车样品绑定表中不存在")
            }

            if (isIndividualOfThis) {
                if (c.unloadStartedAt != null && s.takenAt.isAfter(c.unloadStartedAt)) {
                    out += f(FindingCode.SAMPLE_AFTER_UNLOAD,
                        "仓${c.code.value}: 个体样 ${s.id.value} 取样 ${s.takenAt} 晚于开卸 " +
                            "${c.unloadStartedAt}，样品不代表原仓")
                }
                s.depthCm?.let { d ->
                    if (d !in cfg.sampleDepthMinCm..cfg.sampleDepthMaxCm) {
                        out += f(FindingCode.SAMPLE_DEPTH_INVALID,
                            "仓${c.code.value}: 取样深度 ${d}cm 不在 " +
                                "${cfg.sampleDepthMinCm}-${cfg.sampleDepthMaxCm}cm")
                    }
                }
                s.weightG?.let { w ->
                    if (!s.weightStable) {
                        out += f(FindingCode.SAMPLE_WEIGHT_UNSTABLE,
                            "仓${c.code.value}: 采样秤读数未稳定（${w}g）")
                    } else if (w < cfg.minSampleWeightG) {
                        out += f(FindingCode.SAMPLE_WEIGHT_UNSTABLE,
                            "仓${c.code.value}: 样量 ${w}g < ${cfg.minSampleWeightG}g")
                    }
                }
            }
        }
        return out
    }

    /** 到“可卸”检查点时：每个组分仓必须已有卸前个体样。 */
    fun preUnloadCheck(c: Compartment, now: Instant): List<Finding> {
        val has = c.samples.any {
            it.kind == SampleKind.INDIVIDUAL &&
                it.sourceCompartments == listOf(c.code) &&
                (c.unloadStartedAt == null || !it.takenAt.isAfter(c.unloadStartedAt))
        }
        return if (has) emptyList()
        else listOf(Finding(FindingCode.SAMPLE_MISSING, c.code,
            "仓${c.code.value}: 卸奶前缺少本仓代表性个体样", now))
    }
}
