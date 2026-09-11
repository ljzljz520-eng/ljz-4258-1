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

    /**
     * 开阀登记前必须逐项满足的硬前置，任一不满足系统拒绝登记开阀。
     * 各项为**同等级前置**，返回所有未满足项（一次提示完整，不允许逐项跳过）：
     *  1. 无途中补装（身份污染即永久挂起）；
     *  2. 封签已核验；
     *  3. 感官已检查；
     *  4. **搅拌已确认且时长达标**（取样代表性前提）；
     *  5. 卸前个体样已留存（代表性）；
     *  6. **卸奶边界已声明且本仓属于某个已登记声明**（独立/混合边界必须先于开阀闭合）；
     *  7. **卸奶管线连接已见证**（清洁验收/连接时刻/前段冲洗液去向已登记，
     *     首仓/末仓残留归属才可确定）。
     */
    fun openValveBlockers(
        c: Compartment,
        cfg: ReceivingPolicyConfig,
        declaredCompartments: Set<CompartmentCode>,
        witnessedGroups: Set<String> = emptySet(),
    ): List<String> {
        val missing = mutableListOf<String>()
        if (c.secondaryLoads.isNotEmpty()) missing += "途中补装未决"
        if (c.seal == null) missing += "封签未核验"
        if (c.sensory == null) missing += "感官未检查"
        if (c.stirringConfirmedAt == null) {
            missing += "搅拌未确认"
        } else if ((c.stirringSeconds ?: 0) < cfg.minStirringSeconds) {
            missing += "搅拌时长不足（${c.stirringSeconds}s < ${cfg.minStirringSeconds}s）"
        }
        if (!c.hasPreUnloadSample) missing += "卸前个体样缺失"
        if (c.code !in declaredCompartments) missing += "卸奶边界未声明（独立/混合）"
        else if (c.unloadGroupId == null || c.unloadGroupId !in witnessedGroups) {
            missing += "管线连接未见证（清洁验收/冲洗液去向）"
        }
        return missing
    }

    /** 单仓/组分仓进入 READY 前必须逐项确认的硬条件（搅拌与卸奶边界为同等级前置）。 */
    fun compartmentReady(
        c: Compartment,
        cfg: ReceivingPolicyConfig = ReceivingPolicyConfig(),
        declaredCompartments: Set<CompartmentCode> = emptySet(),
        witnessedGroups: Set<String> = emptySet(),
    ): Boolean = openValveBlockers(c, cfg, declaredCompartments, witnessedGroups).isEmpty()
}
