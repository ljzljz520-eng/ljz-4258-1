package com.dairy.receiving.core

import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.report.TripReportBuilder
import com.dairy.receiving.core.workflow.TripWorkflow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * 卸奶管线残留见证的五类现场场景：
 * 软管临时更换、清洁状态过期、冲洗液回流、两车共用歧管、首仓只卸一部分；
 * 以及核心不变量——首仓/末仓分别保留管线影响，残留绝不平均分摊。
 */
class PipelineScenarioTest {

    private val t0 = Instant.parse("2026-09-11T03:20:00Z")
    private val arrival = Instant.parse("2026-09-11T06:00:00Z")
    private val op = OperatorId("recv-07")

    private fun comp(code: String) = Compartment(
        code = CompartmentCode(code), farm = FarmId("牧场A"),
        farmBatch = FarmBatchId("A-0911-1"), loadedAt = t0,
        status = CompartmentStatus.REGISTERED)

    /** 封签/温度/感官/搅拌/卸前个体样全部合规。 */
    private fun fullPrep(c: Compartment, wf: TripWorkflow, clock: MutableClock) {
        wf.attachTruckLog(c.code, TruckTemperatureLog(t0, arrival, listOf(t0..arrival), 4.5))
        wf.recordProbe(c.code, ProbeReading(4.0, true, arrival, "probe-1"))
        wf.verifySeal(c.code, SealCheck(SealId("S-A-${c.code.value}"),
            SealId("S-A-${c.code.value}"), NfcTagId("tag-${c.code.value}"),
            SealId("S-A-${c.code.value}"), false, op, arrival))
        wf.sensory(c.code, SensoryCheck(true, "色泽气味正常", op, clock.instant))
        wf.confirmStirring(c.code, 180, op)
        val sid = SampleId("SMP-${c.code.value}")
        wf.bindBottle(BottleTagId("B-${c.code.value}"), sid)
        clock.advance(Duration.ofMinutes(1))
        wf.takeSample(c.code, Sample(sid, BottleTagId("B-${c.code.value}"),
            SampleKind.INDIVIDUAL, listOf(c.code), 50.0, 180,
            WeightReading(200.0, true, clock.instant, DEFAULT_SCALE_DEVICE_ID),
            clock.instant, op))
    }

    private fun declareComposite(wf: TripWorkflow, group: String,
                                 comps: List<Compartment>, clock: MutableClock) {
        val codes = comps.map { it.code }
        val cs = Sample(SampleId("SMP-MIX-$group"), BottleTagId("B-MIX-$group"),
            SampleKind.COMPOSITE, codes, null, null, null, clock.instant, op)
        wf.bindBottle(cs.bottleTag, cs.id)
        wf.registerCompositeSample(cs)
        clock.advance(Duration.ofSeconds(30))
        assertTrue(wf.declareUnload(UnloadDeclaration(group, codes,
            UnloadBoundary.COMPOSITE, TankId("T-9"), cs.id, op, clock.instant)).accepted)
    }

    /** 见证一次管线连接；默认清洁有效、冲洗液回收、无回流、独占管线。 */
    private fun connect(
        wf: TripWorkflow, group: String, clock: MutableClock,
        pipeline: String = "P-1", hose: String = "H-1",
        cleaningValidUntil: Instant = clock.instant.plus(Duration.ofHours(2)),
        dest: FlushDestination = FlushDestination.RECLAIM,
        backflow: Boolean = false,
        sharedWith: TruckId? = null,
    ) = wf.recordPipelineConnection(
        groupId = group,
        pipeline = PipelineId(pipeline),
        hose = HoseId(hose),
        cleaning = CleaningAcceptance(PipelineId(pipeline), "CIP", op,
            clock.instant.minus(Duration.ofHours(1)), cleaningValidUntil),
        flush = FlushRecord(dest, 20.0, backflow, op, clock.instant),
        sharedWithTruck = sharedWith,
        by = op)

    private fun pipelineFindings(wf: TripWorkflow, code: CompartmentCode) =
        wf.findingsFor(code).filter { it.code.category == FindingCategory.PIPELINE }

    // ---- 前置：连接见证是开阀同级硬前置 ------------------------------------------

    @Test
    fun `12a 已声明边界但未见证管线连接 - 拒绝登记开阀`() {
        val clock = MutableClock(arrival)
        val c = comp("12")
        val wf = TripWorkflow(TripId("t12"), TruckId("豫M-012"), listOf(c), clock = clock)
        fullPrep(c, wf, clock)
        // 边界未声明时不能见证连接
        val early = connect(wf, "g-12", clock)
        assertFalse(early.accepted)
        wf.declareUnload(UnloadDeclaration("g-12", listOf(c.code),
            UnloadBoundary.SEPARATE, TankId("T-1"), null, op, clock.instant))

        assertTrue(wf.openBlockings(c.code).any { it.code == FindingCode.PIPELINE_WITNESS_MISSING })
        val r = wf.confirmValveOpened(c.code, op)
        assertFalse(r.accepted, "无管线连接见证时系统必须拒绝登记开阀")
        assertTrue(r.message.contains("管线连接未见证"))

        // 见证后即可登记人工开阀；首仓留痕为 INFO，不影响建议
        assertTrue(connect(wf, "g-12", clock).accepted)
        assertTrue(wf.confirmValveOpened(c.code, op).accepted)
        val first = pipelineFindings(wf, c.code).single {
            it.code == FindingCode.PIPELINE_RESIDUAL_FIRST }
        assertEquals(FindingSeverity.INFO, first.code.severity)
    }

    // ---- 场景：清洁状态过期 -------------------------------------------------------

    @Test
    fun `12b 清洁状态过期 - 首仓阻断且残留只归首仓不摊入次仓`() {
        val clock = MutableClock(arrival)
        val a = comp("12B-A"); val b = comp("12B-B")
        val wf = TripWorkflow(TripId("t12b"), TruckId("豫M-112"), listOf(a, b), clock = clock)
        fullPrep(a, wf, clock); fullPrep(b, wf, clock)
        declareComposite(wf, "g-12b", listOf(a, b), clock)
        // 清洁有效期在连接时刻之前 30 分钟已过期
        connect(wf, "g-12b", clock,
            cleaningValidUntil = clock.instant.minus(Duration.ofMinutes(30)))

        val expired = wf.openBlockings(a.code).filter {
            it.code == FindingCode.PIPELINE_CLEANING_EXPIRED }
        assertEquals(1, expired.size, "清洁过期必须阻断首仓")
        assertTrue(expired.single().detail.contains("首仓"))
        // 残留不平均分摊：次仓没有任何管线阻断，建议不受首仓连坐
        assertFalse(pipelineFindings(wf, b.code).any { it.code.blocking },
            "次仓不得分摊首仓的管线阻断")
        assertFalse(wf.confirmValveOpened(a.code, op).accepted)
        assertEquals(Recommendation.HOLD, wf.recommendation(a.code))
        assertEquals(Recommendation.ACCEPT, wf.recommendation(b.code))
    }

    // ---- 场景：冲洗液回流 / 去向 --------------------------------------------------

    @Test
    fun `12c 冲洗液回流与冲入奶罐 - 首仓阻断；去向不明仅警告`() {
        val clock = MutableClock(arrival)
        val a = comp("12C-A"); val b = comp("12C-B"); val c3 = comp("12C-C")
        val wf = TripWorkflow(TripId("t12c"), TruckId("豫M-113"),
            listOf(a, b, c3), clock = clock)
        listOf(a, b, c3).forEach { fullPrep(it, wf, clock) }
        // 三仓各自独立卸入罐，分别见证：回流 / 冲入奶罐 / 去向不明
        listOf(a, b, c3).forEach {
            wf.declareUnload(UnloadDeclaration("g-${it.code.value}", listOf(it.code),
                UnloadBoundary.SEPARATE, TankId("T-1"), null, op, clock.instant))
        }
        connect(wf, "g-12C-A", clock, backflow = true)
        connect(wf, "g-12C-B", clock, dest = FlushDestination.MILK_TANK)
        connect(wf, "g-12C-C", clock, dest = FlushDestination.UNKNOWN)

        assertTrue(wf.openBlockings(a.code).any {
            it.code == FindingCode.PIPELINE_FLUSH_BACKFLOW }, "回流必须阻断首仓")
        assertTrue(wf.openBlockings(b.code).any {
            it.code == FindingCode.PIPELINE_FLUSH_TO_MILK }, "冲洗液入奶必须阻断首仓")
        val unknown = wf.findingsFor(c3.code).single {
            it.code == FindingCode.PIPELINE_FLUSH_DESTINATION_UNKNOWN }
        assertFalse(unknown.code.blocking, "去向不明为警告留痕，不自动阻断")
        assertEquals(Recommendation.HOLD, wf.recommendation(a.code))
        assertEquals(Recommendation.HOLD, wf.recommendation(b.code))
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, wf.recommendation(c3.code))
    }

    // ---- 场景：两辆车共用歧管 ------------------------------------------------------

    @Test
    fun `12d 两车共用歧管 - 首仓警告留痕，他仓不受影响`() {
        val clock = MutableClock(arrival)
        val a = comp("12D-A"); val b = comp("12D-B")
        val wf = TripWorkflow(TripId("t12d"), TruckId("豫M-114"), listOf(a, b), clock = clock)
        fullPrep(a, wf, clock); fullPrep(b, wf, clock)
        declareComposite(wf, "g-12d", listOf(a, b), clock)
        connect(wf, "g-12d", clock, sharedWith = TruckId("豫M-888"))

        val shared = wf.findingsFor(a.code).single {
            it.code == FindingCode.PIPELINE_SHARED_MANIFOLD }
        assertFalse(shared.code.blocking)
        assertTrue(shared.detail.contains("豫M-888"), "留痕必须携带共用车号")
        // 共用影响只归首仓：次仓无共用缺陷，可正常登记开阀
        assertFalse(pipelineFindings(wf, b.code).any {
            it.code == FindingCode.PIPELINE_SHARED_MANIFOLD })
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, wf.recommendation(a.code))
        assertTrue(wf.confirmValveOpened(a.code, op).accepted)
        assertTrue(wf.confirmValveOpened(b.code, op).accepted)
    }

    // ---- 场景：软管临时更换 --------------------------------------------------------

    @Test
    fun `12e 软管临时更换 - 未核验阻断暴露仓，已核验仅留痕`() {
        val clock = MutableClock(arrival)
        val a = comp("12E-A"); val b = comp("12E-B")
        val wf = TripWorkflow(TripId("t12e"), TruckId("豫M-115"), listOf(a, b), clock = clock)
        fullPrep(a, wf, clock); fullPrep(b, wf, clock)
        declareComposite(wf, "g-12e", listOf(a, b), clock)
        connect(wf, "g-12e", clock)
        // A 仓开卸中软管破损临时更换：暴露仓 = 正在卸的 A
        assertTrue(wf.confirmValveOpened(a.code, op).accepted)
        clock.advance(Duration.ofMinutes(3))
        val r = wf.recordHoseSwap("g-12e", HoseId("H-9"),
            cleanedVerified = false, reason = "软管鼓包", by = op)
        assertTrue(r.accepted)

        val blocked = wf.openBlockings(a.code).filter {
            it.code == FindingCode.PIPELINE_HOSE_UNVERIFIED }
        assertEquals(1, blocked.size, "未核验软管必须阻断暴露仓 A")
        assertTrue(blocked.single().detail.contains("H-9"))
        assertFalse(pipelineFindings(wf, b.code).any { it.code.blocking },
            "未开卸的 B 仓不分摊软管风险")
        assertEquals(Recommendation.HOLD, wf.recommendation(a.code))

        // 另一组：更换且清洁已核验 => 仅 INFO 留痕
        val clock2 = MutableClock(arrival)
        val c = comp("12E-C")
        val wf2 = TripWorkflow(TripId("t12e2"), TruckId("豫M-116"), listOf(c), clock = clock2)
        fullPrep(c, wf2, clock2)
        wf2.declareUnload(UnloadDeclaration("g-c", listOf(c.code),
            UnloadBoundary.SEPARATE, TankId("T-1"), null, op, clock2.instant))
        connect(wf2, "g-c", clock2)
        assertTrue(wf2.recordHoseSwap("g-c", HoseId("H-7"),
            cleanedVerified = true, reason = "计划性更换", by = op).accepted)
        val note = pipelineFindings(wf2, c.code).single {
            it.code == FindingCode.PIPELINE_HOSE_SWAPPED }
        assertEquals(FindingSeverity.INFO, note.code.severity)
        assertTrue(wf2.confirmValveOpened(c.code, op).accepted, "已核验换管不妨碍开卸")
    }

    // ---- 场景：首仓只卸一部分 ------------------------------------------------------

    @Test
    fun `12f 首仓只卸一部分 - 首仓即末仓，残留影响同仓保留`() {
        val clock = MutableClock(arrival)
        val a = comp("12F-A"); val b = comp("12F-B")
        val wf = TripWorkflow(TripId("t12f"), TruckId("豫M-117"), listOf(a, b), clock = clock)
        fullPrep(a, wf, clock); fullPrep(b, wf, clock)
        declareComposite(wf, "g-12f", listOf(a, b), clock)
        connect(wf, "g-12f", clock)

        assertTrue(wf.confirmValveOpened(a.code, op).accepted)
        clock.advance(Duration.ofMinutes(8))
        // 首仓 A 只卸一部分即关阀
        wf.confirmValveClosed(a.code, op, complete = false)

        val fa = pipelineFindings(wf, a.code)
        assertTrue(fa.any { it.code == FindingCode.PARTIAL_UNLOAD },
            "部分卸载必须留痕")
        assertTrue(fa.any { it.code == FindingCode.PIPELINE_RESIDUAL_FIRST },
            "A 是首仓，承担前段残留")
        assertTrue(fa.any { it.code == FindingCode.PIPELINE_RESIDUAL_LAST },
            "A 同时是末仓（唯一开卸过的仓），卸后滞留也归 A")
        assertTrue(wf.compartments[a.code]!!.partialUnload)
        // 次仓 B 未开卸：无任何管线残留标记，余奶身份不混
        assertTrue(pipelineFindings(wf, b.code).isEmpty(),
            "B 未接触管线，不得分摊任何管线影响")
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, wf.recommendation(a.code))
        assertEquals(Recommendation.ACCEPT, wf.recommendation(b.code))
    }

    // ---- 核心不变量：残留绝不平均分摊 ----------------------------------------------

    @Test
    fun `12g 三仓混卸 - 仅首末仓带管线影响，中间仓完全干净`() {
        val clock = MutableClock(arrival)
        val comps = listOf(comp("12G-A"), comp("12G-B"), comp("12G-C"))
        val wf = TripWorkflow(TripId("t12g"), TruckId("豫M-118"), comps, clock = clock)
        comps.forEach { fullPrep(it, wf, clock) }
        declareComposite(wf, "g-12g", comps, clock)
        connect(wf, "g-12g", clock)
        // 依次开卸 A -> B -> C，末仓 = 最后开卸的 C
        comps.forEach {
            clock.advance(Duration.ofMinutes(2))
            assertTrue(wf.confirmValveOpened(it.code, op).accepted)
        }
        comps.forEach { wf.confirmValveClosed(it.code, op) }

        val codesA = pipelineFindings(wf, comps[0].code).map { it.code }
        val codesB = pipelineFindings(wf, comps[1].code).map { it.code }
        val codesC = pipelineFindings(wf, comps[2].code).map { it.code }
        assertTrue(FindingCode.PIPELINE_RESIDUAL_FIRST in codesA, "首仓保留前段残留")
        assertTrue(FindingCode.PIPELINE_RESIDUAL_LAST !in codesA)
        assertTrue(codesB.isEmpty(), "中间仓不得带任何管线影响（残留未平均分摊）")
        assertTrue(FindingCode.PIPELINE_RESIDUAL_LAST in codesC, "末仓保留管线滞留")
        assertTrue(FindingCode.PIPELINE_RESIDUAL_FIRST !in codesC)
        // 报告必须渲染管线见证段，首末仓归属可见
        val report = TripReportBuilder.build(wf).render()
        assertTrue(report.contains("管线见证 组g-12g"))
        assertTrue(report.contains("首仓 12G-A 末仓 12G-C"))
        assertTrue(report.contains("残留不分摊"))
    }
}
