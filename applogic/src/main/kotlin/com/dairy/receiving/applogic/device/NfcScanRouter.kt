package com.dairy.receiving.applogic.device

import com.dairy.receiving.core.device.NfcScan
import com.dairy.receiving.core.device.ParsedNfc
import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.workflow.TripWorkflow
import java.time.Clock

/**
 * NFC 扫签路由：
 *  - 罐口签 -> 身份核对（含途中补装识别），身份相符时**直接闭合到封签核验**
 *    （NFC 读签为权威，形成 SealCheck，无需再手工登记封签）；
 *  - 瓶签 -> 样品绑定。
 * UI 每次检测到 TAG intent 调用 handle；当前步骤上下文由 Screen 给出。
 */
sealed class NfcRouteResult {
    data class Hatch(
        val binding: ExpectedSealBinding,
        val reloadSuspected: Boolean,
        /** 身份相符且已用 NFC 读签完成封签核验；false 表示仅登记了身份异常。 */
        val sealVerified: Boolean = false,
    ) : NfcRouteResult()
    data class BottleBound(val tag: BottleTagId, val sample: SampleId) : NfcRouteResult()
    data class UnknownPayload(val raw: String) : NfcRouteResult()
    data class Ignored(val reason: String) : NfcRouteResult()
}

class NfcScanRouter(
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * @param currentCompartment 当前逐仓核对页面对应的仓
     * @param pendingSample 取样流程中等待绑定瓶签的样品
     */
    fun handle(
        scan: NfcScan,
        wf: TripWorkflow,
        currentCompartment: CompartmentCode?,
        pendingSample: SampleId?,
        by: OperatorId,
    ): NfcRouteResult {
        return when (val p = scan.parse()) {
            is ParsedNfc.Hatch -> {
                val code = currentCompartment
                    ?: return NfcRouteResult.Ignored("未选择仓室，罐口签不处理")
                val c = wf.compartments[code] ?: return NfcRouteResult.Ignored("未知仓室")
                val farm = FarmId(p.farm); val batch = FarmBatchId(p.batch)
                val observed = ExpectedSealBinding(code, SealId(p.seal), farm, batch,
                    c.loadedAt, p.tag)
                if (farm == c.farm && batch == c.farmBatch) {
                    // 身份相符：扫签即封签核验（expected 取装车预报绑定，无预报取标签自报）
                    wf.scanHatchTag(code, farm, batch, SealId(p.seal), p.tag, by)
                    val expected = wf.expectedSeal(code) ?: observed
                    NfcRouteResult.Hatch(expected, reloadSuspected = false, sealVerified = true)
                } else {
                    wf.scanHatchTag(code, farm, batch, SealId(p.seal), p.tag, by)
                    NfcRouteResult.Hatch(observed, reloadSuspected = true, sealVerified = false)
                }
            }
            is ParsedNfc.Bottle -> {
                val sid = pendingSample
                    ?: return NfcRouteResult.Ignored("当前无待绑定样品，瓶签忽略")
                wf.bindBottle(p.bottle, sid)
                NfcRouteResult.BottleBound(p.bottle, sid)
            }
            null -> NfcRouteResult.UnknownPayload(scan.payload)
        }
    }
}

/** 把路由结果转成给收奶员的中文提示。 */
fun NfcRouteResult.message(): String = when (this) {
    is NfcRouteResult.Hatch -> when {
        reloadSuspected ->
            "⚠ 罐口签身份 ${binding.farm.value}/${binding.farmBatch.value} 与预报不符，" +
                "已挂起（疑似途中补装），封签不予核验"
        sealVerified ->
            "罐口签 OK 并已完成封签核验：${binding.seal.value} " +
                "${binding.farm.value}/${binding.farmBatch.value}（NFC 为权威）"
        else ->
            "罐口签 OK：${binding.seal.value} ${binding.farm.value}/${binding.farmBatch.value}"
    }
    is NfcRouteResult.BottleBound -> "样瓶 ${tag.value} 已绑定样品 ${sample.value}"
    is NfcRouteResult.UnknownPayload -> "无法识别的标签内容：$raw"
    is NfcRouteResult.Ignored -> "忽略：$reason"
}
