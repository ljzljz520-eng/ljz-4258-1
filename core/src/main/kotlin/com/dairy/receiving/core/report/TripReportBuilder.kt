package com.dairy.receiving.core.report

import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.policy.PipelinePolicy
import com.dairy.receiving.core.workflow.TripWorkflow

/**
 * 单车接收报告：按独立仓室列身份、时序、样品链、缺陷与建议，
 * 再列混合卸载边界与组分回溯。混卸不抹除仓身份，报告里每仓一段。
 * 卸奶管线见证单列一段：清洁验收/连接时刻/冲洗液去向与首仓/末仓归属，
 * 残留影响只落在首末仓，不在组分间分摊。
 */
data class CompartmentReport(
    val code: String,
    val farm: String,
    val farmBatch: String,
    val loadedAt: String,
    val status: CompartmentStatus,
    val recommendation: Recommendation,
    val findings: List<String>,
    val samples: List<String>,
    val unloadGroupId: String?,
)

data class TripReport(
    val tripId: String,
    val truckId: String,
    val overall: Recommendation,
    val compartments: List<CompartmentReport>,
    val compositeGroups: List<String>,
    val pipelines: List<String>,
    val audit: List<String>,
) {
    fun render(): String = buildString {
        appendLine("收奶接收报告  车=$truckId 单=$tripId")
        appendLine("总建议：$overall（仅为系统提示，接收/开阀由人工决定）")
        appendLine("=".repeat(60))
        for (r in compartments) {
            appendLine("仓 ${r.code} | ${r.farm}/${r.farmBatch} | 装车 ${r.loadedAt}")
            appendLine("  状态=${r.status} 建议=${r.recommendation} 混卸组=${r.unloadGroupId ?: "-"}")
            if (r.findings.isEmpty()) appendLine("  缺陷：无")
            else r.findings.forEach { appendLine("  - $it") }
            appendLine("  样品链：${r.samples.ifEmpty { listOf("(无)") }.joinToString("; ")}")
        }
        if (compositeGroups.isNotEmpty()) {
            appendLine("-".repeat(60))
            compositeGroups.forEach { appendLine(it) }
        }
        if (pipelines.isNotEmpty()) {
            appendLine("-".repeat(60))
            pipelines.forEach { appendLine(it) }
        }
        appendLine("-".repeat(60))
        audit.forEach { appendLine(it) }
    }
}

object TripReportBuilder {
    fun build(wf: TripWorkflow): TripReport {
        val reports = wf.compartments.values.sortedBy { it.code.value }.map { c ->
            CompartmentReport(
                code = c.code.value,
                farm = c.farm.value,
                farmBatch = c.farmBatch.value,
                loadedAt = c.loadedAt.toString(),
                status = c.status,
                recommendation = wf.recommendation(c.code),
                findings = wf.findingsFor(c.code).map { f ->
                    val tag = if (f.open) "" else " [主管覆核:${f.overriddenBy?.supervisor?.value}]"
                    "[${f.code.severity}] ${f.code.name}: ${f.detail}$tag"
                },
                samples = c.samples.map { s ->
                    val w = s.weight?.let { "${it.grams}g@${it.deviceId}/稳定=${it.stable}" } ?: "(无秤重)"
                    "${s.kind}/瓶${s.bottleTag.value}/${s.id.value}@${s.takenAt}" +
                        " 深度${s.depthCm}cm 量$w"
                },
                unloadGroupId = c.unloadGroupId,
            )
        }
        val groups = wf.unloadDeclarations.filter { it.boundary == UnloadBoundary.COMPOSITE }
            .map { d ->
                "混合组 ${d.groupId} -> 罐 ${d.targetTank.value}；组分仓 " +
                    "${d.compartments.joinToString { it.value }}；混合样 ${d.compositeSample?.value}"
            }
        val pipelines = PipelinePolicy.latestConnections(wf.connections).values.map { conn ->
            val exp = PipelinePolicy.exposure(conn, wf.compartments)
            val flush = conn.flush
            "管线见证 组${conn.groupId} -> 管线 ${conn.pipeline.value} 软管 ${conn.hose.value}" +
                "；清洁 ${conn.cleaning.method} 验收@${conn.cleaning.acceptedAt} " +
                "有效至 ${conn.cleaning.validUntil}；连接@${conn.connectedAt}" +
                "；冲洗液->${flush.destination}" +
                (flush.volumeLiters?.let { " ${it}L" } ?: "") +
                (if (flush.backflowSuspected) "（疑似回流）" else "") +
                (conn.sharedWithTruck?.let { "；与车 ${it.value} 共用歧管" } ?: "") +
                "；首仓 ${exp.firstCompartment.value}" +
                (exp.lastCompartment?.let { " 末仓 ${it.value}" } ?: "（尚未开卸）") +
                "（残留不分摊）"
        }
        return TripReport(
            tripId = wf.tripId.value,
            truckId = wf.truckId.value,
            overall = wf.tripRecommendation(),
            compartments = reports,
            compositeGroups = groups,
            pipelines = pipelines,
            audit = wf.events.map { "#${it.seq} ${it.at} ${it.actor?.value ?: "SYSTEM"} ${it.action} ${it.detail}" },
        )
    }
}
