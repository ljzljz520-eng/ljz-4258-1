package com.dairy.receiving.core.demo

import com.dairy.receiving.core.report.TripReportBuilder

/** 命令行演示：逐个打印五类异常场景的判定、建议与审计尾链。 */
fun main() {
    println("原奶槽车收奶核对 —— 五类现场异常离线演练\n")
    DemoScenarios.all().forEachIndexed { i, (name, factory) ->
        val wf = factory()
        val report = TripReportBuilder.build(wf)
        println("【场景 ${i + 1}】$name  车=${wf.truckId.value}  总建议=${report.overall}")
        for (c in report.compartments) {
            println("  仓 ${c.code} [${c.status}] 建议=${c.recommendation}")
            c.findings.forEach { println("    $it") }
            println("    样品链: ${c.samples.joinToString("; ").ifBlank { "(无)" }}")
        }
        report.compositeGroups.forEach { println("  $it") }
        wf.events.takeLast(3).forEach { println("  审计 #${it.seq} ${it.action} ${it.detail}") }
        println()
    }
}
