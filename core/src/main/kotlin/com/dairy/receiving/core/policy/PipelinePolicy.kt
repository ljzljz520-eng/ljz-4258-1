package com.dairy.receiving.core.policy

import com.dairy.receiving.core.model.*
import java.time.Instant

/**
 * 卸奶管线残留见证规则。
 *
 * 核心不变量：**管线残留绝不平均分摊**。
 *  - 首仓（连接组分顺序的第一仓）承担管线前段残留：冲洗液、上一车/上一批
 *    滞留奶只与首仓的奶接触，清洁过期/冲洗液入奶/回流/共用歧管等风险
 *    全部只落在首仓；
 *  - 末仓（已开始卸奶的仓中最后开卸者）的奶卸后滞留管线，身份保留到
 *    下一次连接见证；
 *  - 中间仓不受管线影响，不因同组首仓的管线缺陷被连坐。
 *
 * 见证缺失（已声明卸奶边界但无连接记录）本身即阻断级缺陷；
 * 连接见证也是开阀登记的同级硬前置（见 [UnloadPolicy.openValveBlockers]）。
 */
object PipelinePolicy {

    /** 同一组的重复连接以最新一条为准（历史连接留在审计链）。 */
    fun latestConnections(connections: List<PipelineConnection>): Map<String, PipelineConnection> =
        connections.groupBy { it.groupId }.mapValues { it.value.last() }

    /** 推导首仓/末仓归属。首仓只卸一部分时，首仓即末仓。 */
    fun exposure(
        conn: PipelineConnection,
        compartments: Map<CompartmentCode, Compartment>,
    ): PipelineExposure {
        val first = conn.compartments.first()
        val last = conn.compartments
            .mapNotNull { compartments[it] }
            .filter { it.unloadStartedAt != null }
            .maxByOrNull { it.unloadStartedAt!! }
            ?.code
        return PipelineExposure(conn.groupId, conn.pipeline, first, last)
    }

    /** 软管更换后的暴露仓：更换时刻正在卸的仓（最新开卸且未关阀），否则首仓。 */
    fun hoseSwapExposure(
        conn: PipelineConnection,
        swap: HoseSwap,
        compartments: Map<CompartmentCode, Compartment>,
    ): CompartmentCode =
        conn.compartments.mapNotNull { compartments[it] }
            .filter { it.unloadStartedAt != null && it.unloadFinishedAt == null }
            .filter { !it.unloadStartedAt!!.isAfter(swap.swappedAt) }
            .maxByOrNull { it.unloadStartedAt!! }
            ?.code ?: conn.compartments.first()

    fun evaluate(
        declarations: List<UnloadDeclaration>,
        connections: List<PipelineConnection>,
        hoseSwaps: List<HoseSwap>,
        compartments: Map<CompartmentCode, Compartment>,
        now: Instant,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val latest = latestConnections(connections)

        // 已声明卸奶边界但无管线连接见证：每个组分仓挂阻断（开阀前置同样拦截）
        for (d in declarations) {
            if (d.groupId in latest) continue
            for (code in d.compartments) {
                out += Finding(FindingCode.PIPELINE_WITNESS_MISSING, code,
                    "仓${code.value}: 卸奶组 ${d.groupId} 未见证管线连接" +
                        "（清洁验收/连接时刻/前段冲洗液去向未登记）", now)
            }
        }

        for ((groupId, conn) in latest) {
            val exp = exposure(conn, compartments)
            val first = exp.firstCompartment
            val flushVol = conn.flush.volumeLiters?.let { "${it}L" } ?: "体积未计"

            // —— 首仓/末仓常态留痕（INFO，不影响接收建议）——
            out += Finding(FindingCode.PIPELINE_RESIDUAL_FIRST, first,
                "组$groupId 首仓：管线 ${conn.pipeline.value} 前段残留（冲洗液 $flushVol）" +
                    "只由本仓承担，不在组分仓间分摊", now)
            exp.lastCompartment?.let { last ->
                out += Finding(FindingCode.PIPELINE_RESIDUAL_LAST, last,
                    "组$groupId 末仓：卸后本仓奶滞留管线 ${conn.pipeline.value}，" +
                        "身份保留至下一连接见证", now)
            }

            // —— 清洁状态过期：连接时刻晚于清洁验收有效期 ——
            if (conn.connectedAt.isAfter(conn.cleaning.validUntil)) {
                out += Finding(FindingCode.PIPELINE_CLEANING_EXPIRED, first,
                    "仓${first.value}: 管线 ${conn.pipeline.value} 清洁（${conn.cleaning.method}）" +
                        "有效期至 ${conn.cleaning.validUntil}，连接时刻 ${conn.connectedAt} 已过期；" +
                        "残留风险只落在首仓", now)
            }

            // —— 前段冲洗液去向 ——
            when (conn.flush.destination) {
                FlushDestination.MILK_TANK ->
                    out += Finding(FindingCode.PIPELINE_FLUSH_TO_MILK, first,
                        "仓${first.value}: 前段冲洗液（$flushVol）冲入原奶罐，" +
                            "首仓被冲洗液污染", now)
                FlushDestination.UNKNOWN ->
                    out += Finding(FindingCode.PIPELINE_FLUSH_DESTINATION_UNKNOWN, first,
                        "仓${first.value}: 前段冲洗液（$flushVol）去向不明，" +
                            "无法排除进入原奶", now)
                FlushDestination.RECLAIM, FlushDestination.WASTE -> Unit
            }
            if (conn.flush.backflowSuspected) {
                out += Finding(FindingCode.PIPELINE_FLUSH_BACKFLOW, first,
                    "仓${first.value}: 前段冲洗液疑似回流倒灌入首仓" +
                        "（${conn.pipeline.value}，$flushVol）", now)
            }

            // —— 两车共用歧管：首仓接触前车滞留奶，留痕可追溯 ——
            conn.sharedWithTruck?.let { peer ->
                out += Finding(FindingCode.PIPELINE_SHARED_MANIFOLD, first,
                    "仓${first.value}: 与车 ${peer.value} 共用歧管 ${conn.pipeline.value}，" +
                        "首仓接触前车管线滞留奶；影响不摊入其他组分仓", now)
            }

            // —— 软管临时更换：暴露仓 = 更换时刻正在卸的仓，否则首仓 ——
            for (swap in hoseSwaps.filter { it.groupId == groupId }) {
                val exposed = hoseSwapExposure(conn, swap, compartments)
                if (swap.cleanedVerified) {
                    out += Finding(FindingCode.PIPELINE_HOSE_SWAPPED, exposed,
                        "仓${exposed.value}: 卸奶中途更换软管 " +
                            "${swap.oldHose?.value ?: "-"}->${swap.newHose.value}（清洁已核验），" +
                            "更换后第一股奶接触本仓", now)
                } else {
                    out += Finding(FindingCode.PIPELINE_HOSE_UNVERIFIED, exposed,
                        "仓${exposed.value}: 临时更换软管 ${swap.newHose.value} 清洁未核验" +
                            "（原因：${swap.reason}），本仓接触未核验软管", now)
                }
            }
        }

        // —— 只卸一部分即关阀：余奶身份保留在本仓 ——
        for (c in compartments.values) {
            if (c.partialUnload) {
                out += Finding(FindingCode.PARTIAL_UNLOAD, c.code,
                    "仓${c.code.value} 只卸一部分即关阀：余奶身份保留在本仓，" +
                        "不与后续仓混合；若为本组首仓，管线残留影响仍只归本仓", now)
            }
        }
        return out
    }
}
