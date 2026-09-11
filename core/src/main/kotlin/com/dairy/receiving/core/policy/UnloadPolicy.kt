package com.dairy.receiving.core.policy

import com.dairy.receiving.core.model.*
import java.time.Instant

/**
 * 卸奶边界规则。系统只校验、提示、留痕；不开阀、不决定接收。
 *
 * 多仓混合卸载（COMPOSITE）的身份保持条件：
 *  1) 每个组分仓都已在卸前留存代表性个体样；
 *  2) 每个组分仓都没有未决的阻断级缺陷；
 *  3) 混合样瓶必须登记，且记录全部组分仓身份（混卸记录可回溯到仓）。
 * 任一条不满足 => COMPOSITE_TRACEABILITY_LOST，禁止声明为可卸（人可双签覆核）。
 */
object UnloadPolicy {

    fun evaluateComposite(
        declaration: UnloadDeclaration,
        compartments: Map<CompartmentCode, Compartment>,
        openBlockings: Map<CompartmentCode, List<Finding>>,
        compositeSamples: Map<SampleId, Sample>,
        now: Instant,
    ): List<Finding> {
        if (declaration.boundary != UnloadBoundary.COMPOSITE) return emptyList()
        val out = mutableListOf<Finding>()

        val missing = declaration.compartments.filter { code ->
            val c = compartments[code] ?: return@filter true
            !c.hasPreUnloadSample
        }
        if (missing.isNotEmpty()) {
            out += Finding(
                FindingCode.COMPOSITE_TRACEABILITY_LOST, null,
                "混合组 ${declaration.groupId}: 组分仓 ${missing.joinToString { it.value }} " +
                    "未在卸前留存个体样，混卸后异常仓将失去身份", now)
        }

        val blocked = declaration.compartments.filter { code ->
            openBlockings[code].orEmpty().any { it.code.blocking && it.open }
        }
        if (blocked.isNotEmpty()) {
            out += Finding(
                FindingCode.COMPOSITE_TRACEABILITY_LOST, null,
                "混合组 ${declaration.groupId}: 组分仓 ${blocked.joinToString { it.value }} " +
                    "存在未决阻断缺陷，禁止以混合方式稀释身份", now)
        }

        if (declaration.compositeSample == null) {
            out += Finding(
                FindingCode.COMPOSITE_TRACEABILITY_LOST, null,
                "混合组 ${declaration.groupId}: 未登记混合样瓶，无法回溯组分", now)
        } else {
            val composite = compositeSamples[declaration.compositeSample]
            if (composite == null) {
                out += Finding(
                    FindingCode.COMPOSITE_TRACEABILITY_LOST, null,
                    "混合组 ${declaration.groupId}: 混合样 ${declaration.compositeSample.value} 不存在", now)
            } else if (composite.sourceCompartments.toSet() != declaration.compartments.toSet()) {
                out += Finding(
                    FindingCode.COMPOSITE_TRACEABILITY_LOST, null,
                    "混合组 ${declaration.groupId}: 混合样组分 " +
                        "${composite.sourceCompartments.map { it.value }} 与声明组不一致", now)
            }
        }
        return out
    }

    /** 单仓/组分仓进入 READY 前必须逐项确认的硬条件。 */
    fun compartmentReady(c: Compartment): Boolean {
        if (c.secondaryLoads.isNotEmpty()) return false
        if (c.seal == null) return false
        if (c.sensory == null) return false
        if (!c.hasPreUnloadSample) return false
        return true
    }
}
