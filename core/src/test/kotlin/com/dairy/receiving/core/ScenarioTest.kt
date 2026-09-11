package com.dairy.receiving.core

import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.policy.SamplingPolicy
import com.dairy.receiving.core.report.TripReportBuilder
import com.dairy.receiving.core.workflow.TripWorkflow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var instant: Instant = Instant.parse("2026-09-11T06:00:00Z")) : Clock() {
    fun advance(d: Duration) { instant = instant.plus(d) }
    override fun instant(): Instant = instant
    override fun withZone(zone: ZoneId?): Clock = this
    override fun getZone(): ZoneId = ZoneOffset.UTC
}

/** 用户给出的五类异常场景端到端验证。 */
class ScenarioTest {

    private val t0 = Instant.parse("2026-09-11T03:20:00Z")
    private val arrival = Instant.parse("2026-09-11T06:00:00Z")
    private val op = OperatorId("recv-07")
    private val sup = OperatorId("supervisor-02")

    private fun comp(
        code: String, farm: String = "牧场A", batch: String = "A-0911-1",
    ) = Compartment(
        code = CompartmentCode(code),
        farm = FarmId(farm),
        farmBatch = FarmBatchId(batch),
        loadedAt = t0,
        status = CompartmentStatus.REGISTERED,
    )

    private fun sealOk(c: String, nfc: String = "S-A-$c", expectedSeal: String? = null) =
        SealCheck(SealId(expectedSeal ?: nfc), SealId(nfc), NfcTagId("tag-$c"),
            SealId(nfc), false, op, arrival)

    private fun probeGood(c: String, at: Instant = arrival) =
        ProbeReading(4.0, true, at, "probe-1")

    private fun stir(c: Compartment, wf: TripWorkflow, clock: MutableClock, sec: Int = 180) {
        clock.advance(Duration.ofSeconds(30))
        wf.confirmStirring(c.code, sec, op)
    }

    private fun fullPrep(
        c: Compartment, wf: TripWorkflow, clock: MutableClock,
        bottle: String = "B-${c.code.value}", sampleId: String = "SMP-${c.code.value}",
        depth: Double = 50.0, weight: Double = 200.0, weightStable: Boolean = true,
        weightDevice: String = DEFAULT_SCALE_DEVICE_ID,
        labelBound: Boolean = true,
    ): SampleId {
        val sid = SampleId(sampleId)
        if (labelBound) wf.bindBottle(BottleTagId(bottle), sid)
        clock.advance(Duration.ofMinutes(1))
        wf.attachTruckLog(c.code, TruckTemperatureLog(t0, arrival,
            listOf(t0..arrival), 4.5))
        wf.recordProbe(c.code, probeGood(c.code.value))
        wf.verifySeal(c.code, sealOk(c.code.value))
        wf.sensory(c.code, SensoryCheck(true, "色泽气味正常", op, clock.instant))
        stir(c, wf, clock)
        clock.advance(Duration.ofMinutes(1))
        val s = Sample(sid, BottleTagId(bottle), SampleKind.INDIVIDUAL,
            listOf(c.code), depth, 180,
            WeightReading(weight, weightStable, clock.instant, weightDevice),
            clock.instant, op)
        wf.takeSample(c.code, s)
        return sid
    }

    private fun declareSeparate(wf: TripWorkflow, c: Compartment, clock: MutableClock) {
        clock.advance(Duration.ofSeconds(10))
        wf.declareUnload(UnloadDeclaration(
            "g-${c.code.value}", listOf(c.code), UnloadBoundary.SEPARATE,
            TankId("T-1"), null, op, clock.instant))
    }

    /** 见证一次合规管线连接（清洁有效/冲洗液回收/无回流/独占管线）。 */
    private fun connectClean(wf: TripWorkflow, groupId: String, clock: MutableClock) {
        clock.advance(Duration.ofSeconds(5))
        val r = wf.recordPipelineConnection(
            groupId = groupId,
            pipeline = PipelineId("P-1"),
            hose = HoseId("H-1"),
            cleaning = CleaningAcceptance(PipelineId("P-1"), "CIP", op,
                clock.instant.minus(Duration.ofHours(1)),
                clock.instant.plus(Duration.ofHours(2))),
            flush = FlushRecord(FlushDestination.RECLAIM, 20.0, false, op, clock.instant),
            by = op)
        assertTrue(r.accepted, "合规管线连接见证必须被接受")
    }

    // ---- 场景 1：封签号手写不清 -------------------------------------------------

    @Test
    fun `1a 手写封签不清且无NFC - 无法核验阻断开卸`() {
        val clock = MutableClock(arrival)
        val c = comp("1")
        val wf = TripWorkflow(TripId("t1"), TruckId("豫M-001"), listOf(c), clock = clock)
        wf.verifySeal(c.code, SealCheck(
            SealId("S-A-1"), nfc = null, nfcTag = null,
            written = SealId("S-A-?"), writtenIllegible = true,
            checkedBy = op, checkedAt = arrival))
        wf.sensory(c.code, SensoryCheck(true, "正常", op, arrival))

        assertTrue(wf.openBlockings(c.code).any { it.code == FindingCode.SEAL_UNVERIFIED })
        assertEquals(Recommendation.HOLD, wf.recommendation(c.code))
        clock.advance(Duration.ofMinutes(5))
        val r = wf.confirmValveOpened(c.code, op)
        assertFalse(r.accepted, "无有效封签核验时系统必须拒绝登记开阀")
        assertNotEquals(CompartmentStatus.UNLOADING, wf.compartments[c.code]!!.status)
    }

    @Test
    fun `1b 手写不清但NFC一致 - 仅警告留痕仍可按NFC核验放行`() {
        val clock = MutableClock(arrival)
        val c = comp("1")
        val wf = TripWorkflow(TripId("t1"), TruckId("豫M-001"), listOf(c), clock = clock)
        fullPrep(c, wf, clock)
        // 重放一次“手填不清”的封签核对（NFC 权威）
        wf.verifySeal(c.code, SealCheck(
            SealId("S-A-1"), SealId("S-A-1"), NfcTagId("tag-1"),
            written = SealId("S-A-l"), writtenIllegible = true,
            checkedBy = op, checkedAt = clock.instant))
        declareSeparate(wf, c, clock)
        connectClean(wf, "g-1", clock)

        assertTrue(wf.findingsFor(c.code).any { it.code == FindingCode.SEAL_ILLEGIBLE })
        assertFalse(wf.openBlockings(c.code).any { it.code == FindingCode.SEAL_ILLEGIBLE })
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, wf.recommendation(c.code))
        assertTrue(wf.confirmValveOpened(c.code, op).accepted)
    }

    @Test
    fun `1c NFC与装车单不符 - 判换签嫌疑`() {
        val clock = MutableClock(arrival)
        val c = comp("1")
        val wf = TripWorkflow(TripId("t1"), TruckId("豫M-001"), listOf(c), clock = clock)
        wf.verifySeal(c.code, SealCheck(
            SealId("S-A-1"), SealId("S-FORGED"), NfcTagId("tag-1"),
            SealId("S-A-1"), false, op, arrival))
        assertTrue(wf.openBlockings(c.code).any { it.code == FindingCode.SEAL_MISMATCH })
        assertEquals(Recommendation.HOLD, wf.recommendation(c.code))
    }

    // ---- 场景 2：车载温度记录缺段 ----------------------------------------------

    @Test
    fun `2 车载温度缺段 - 警告且探针偏差可叠加`() {
        val clock = MutableClock(arrival)
        val c = comp("2")
        val wf = TripWorkflow(TripId("t2"), TruckId("豫M-002"), listOf(c), clock = clock)
        // 装车到到厂 160 分钟，只覆盖 100 分钟 => 缺段 60 分钟 > 允许 20
        val log = TruckTemperatureLog(t0, arrival, listOf(
            t0..t0.plus(Duration.ofMinutes(60)),
            t0.plus(Duration.ofMinutes(120))..arrival,
        ), maxRecordedCelsius = 4.5)
        fullPrep(c, wf, clock)
        wf.attachTruckLog(c.code, log)

        val gap = wf.findingsFor(c.code).single { it.code == FindingCode.TEMP_LOG_GAP }
        assertTrue(gap.detail.contains("60"))
        assertFalse(gap.code.blocking, "缺段本身为警告，不自动拒收")
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, wf.recommendation(c.code))
    }

    @Test
    fun `2b 探针超拒收线 - 阻断拒收`() {
        val clock = MutableClock(arrival)
        val c = comp("2")
        val wf = TripWorkflow(TripId("t2"), TruckId("豫M-002"), listOf(c), clock = clock)
        fullPrep(c, wf, clock)
        clock.advance(Duration.ofMinutes(2))
        wf.recordProbe(c.code, ProbeReading(9.2, true, clock.instant, "probe-1"))
        assertTrue(wf.openBlockings(c.code).any { it.code == FindingCode.TEMP_HIGH_REJECT })
        assertEquals(Recommendation.HOLD, wf.recommendation(c.code)) // 温度未发布拒收令，建议挂起核查
    }

    // ---- 场景 3：一仓先卸后取样 -----------------------------------------------

    @Test
    fun `3a 缺少卸前个体样 - 系统拒绝登记开阀`() {
        val clock = MutableClock(arrival)
        val c = comp("3")
        val wf = TripWorkflow(TripId("t3"), TruckId("豫M-003"), listOf(c), clock = clock)
        // 只做封签、温度、感官、搅拌，不留样
        wf.verifySeal(c.code, sealOk("3"))
        wf.recordProbe(c.code, probeGood("3"))
        wf.sensory(c.code, SensoryCheck(true, "正常", op, arrival))
        stir(c, wf, clock)
        declareSeparate(wf, c, clock)

        assertTrue(wf.findingsFor(c.code).any { it.code == FindingCode.SAMPLE_MISSING })
        val r = wf.confirmValveOpened(c.code, op)
        assertFalse(r.accepted)
        assertTrue(r.message.contains("开卸条件"))
    }

    @Test
    fun `3b 既成事实先卸后取 - 样品代表性阻断`() {
        // 现场绕过系统物理开阀后补样：策略层必须识别 SAMPLE_AFTER_UNLOAD
        val start = arrival.plus(Duration.ofMinutes(10))
        val c = comp("3").copy(unloadStartedAt = start)
        val late = Sample(SampleId("SMP-3L"), BottleTagId("B-3"), SampleKind.INDIVIDUAL,
            listOf(c.code), 50.0, 180,
            WeightReading(200.0, true, start.plus(Duration.ofMinutes(5)),
                DEFAULT_SCALE_DEVICE_ID),
            start.plus(Duration.ofMinutes(5)), op)
        val findings = SamplingPolicy.evaluate(
            c.copy(samples = listOf(late)),
            mapOf(BottleTagId("B-3") to SampleId("SMP-3L")),
            ReceivingPolicyConfig(), start.plus(Duration.ofMinutes(6)))
        assertTrue(findings.any { it.code == FindingCode.SAMPLE_AFTER_UNLOAD && it.code.blocking })
    }

    // ---- 场景 4：混合样瓶贴错 ---------------------------------------------------

    @Test
    fun `4 混卸组中一样瓶贴错 - 阻断且异常仓身份保持`() {
        val clock = MutableClock(arrival)
        val a = comp("4A")
        val b = comp("4B")
        val wf = TripWorkflow(TripId("t4"), TruckId("豫M-004"), listOf(a, b), clock = clock)

        fullPrep(a, wf, clock, bottle = "B-4A", sampleId = "SMP-4A")
        // B 仓：物理瓶签 B-4A（贴成了 A 的瓶），系统绑定表指向 SMP-4A
        fullPrep(b, wf, clock, bottle = "B-4A", sampleId = "SMP-4B", labelBound = false)
        wf.bindBottle(BottleTagId("B-4A"), SampleId("SMP-4A")) // 瓶签实际属于 A

        val mis = wf.findingsFor(b.code).filter { it.code == FindingCode.SAMPLE_LABEL_MISMATCH }
        assertEquals(1, mis.size)
        assertTrue(mis.single().detail.contains("混合样瓶贴错"))
        assertEquals(Recommendation.HOLD, wf.recommendation(b.code))
        assertEquals(Recommendation.ACCEPT, wf.recommendation(a.code),
            "A 仓完全合规，不被 B 仓贴错连坐")

        // 尝试混合卸载 -> 身份保持条件不满足，声明被拒
        clock.advance(Duration.ofMinutes(2))
        val cs = Sample(SampleId("SMP-MIX"), BottleTagId("B-MIX"), SampleKind.COMPOSITE,
            listOf(a.code, b.code), null, null, null, clock.instant, op)
        wf.bindBottle(BottleTagId("B-MIX"), cs.id)
        wf.registerCompositeSample(cs)
        val decl = UnloadDeclaration("g1", listOf(a.code, b.code),
            UnloadBoundary.COMPOSITE, TankId("T-9"), cs.id, op, clock.instant)
        val r = wf.declareUnload(decl)
        assertFalse(r.accepted)
        assertTrue(r.findings.any { it.code == FindingCode.COMPOSITE_TRACEABILITY_LOST })

        // 关键不变量：报告中 B 仓独立成段，异常没有被“混合”吞掉
        val report = TripReportBuilder.build(wf)
        val segB = report.compartments.single { it.code == "4B" }
        assertTrue(segB.findings.any { it.contains("SAMPLE_LABEL_MISMATCH") })
    }

    // ---- 场景 5：车辆中途补装另一牧场原奶 --------------------------------------

    @Test
    fun `5 罐口标签携带另一牧场身份 - 判途中补装并挂起`() {
        val clock = MutableClock(arrival)
        val a = comp("5A", farm = "牧场A", batch = "A-0911-1")
        val b = comp("5B")
        val wf = TripWorkflow(TripId("t5"), TruckId("豫M-005"), listOf(a, b), clock = clock)

        // A 仓罐口扫签：标签却写着牧场C 的批
        wf.scanHatchTag(a.code, FarmId("牧场C"), FarmBatchId("C-0911-9"),
            SealId("S-C-9"), NfcTagId("tag-5A"), op)

        assertTrue(wf.openBlockings(a.code).any { it.code == FindingCode.RELOAD_DETECTED })
        assertEquals(CompartmentStatus.HELD, wf.compartments[a.code]!!.status)
        assertEquals(Recommendation.HOLD, wf.recommendation(a.code))
        // 即便补全一切手续，A 仓也不能开卸
        wf.verifySeal(a.code, sealOk("5A"))
        wf.sensory(a.code, SensoryCheck(true, "正常", op, clock.instant))
        assertFalse(wf.confirmValveOpened(a.code, op).accepted)

        // 司机自报补装同样触发
        clock.advance(Duration.ofMinutes(3))
        wf.reportSecondaryLoad(b.code, SecondaryLoad(
            FarmId("牧场D"), FarmBatchId("D-X"),
            arrival.minus(Duration.ofMinutes(40)), "司机自报+GPS停靠点"))
        assertTrue(wf.openBlockings(b.code).any { it.code == FindingCode.RELOAD_DETECTED })
    }

    // ---- 混合卸载身份保持（正向）+ 实验室事后追溯 ------------------------------

    @Test
    fun `6 合规混卸后某仓抗生素阳性 - 能回溯到具体仓并拒收该仓`() {
        val clock = MutableClock(arrival)
        val a = comp("6A")
        val b = comp("6B")
        val wf = TripWorkflow(TripId("t6"), TruckId("豫M-006"), listOf(a, b), clock = clock)
        val sidA = fullPrep(a, wf, clock, "B-6A", "SMP-6A")
        val sidB = fullPrep(b, wf, clock, "B-6B", "SMP-6B")

        clock.advance(Duration.ofMinutes(3))
        val cs = Sample(SampleId("SMP-MIX6"), BottleTagId("B-MIX6"), SampleKind.COMPOSITE,
            listOf(a.code, b.code), null, null, null, clock.instant, op)
        wf.bindBottle(BottleTagId("B-MIX6"), cs.id)
        wf.registerCompositeSample(cs)
        val decl = UnloadDeclaration("g6", listOf(a.code, b.code),
            UnloadBoundary.COMPOSITE, TankId("T-9"), cs.id, op, clock.instant)
        assertTrue(wf.declareUnload(decl).accepted)
        connectClean(wf, "g6", clock)
        assertTrue(wf.confirmValveOpened(a.code, op).accepted)
        assertTrue(wf.confirmValveOpened(b.code, op).accepted)
        clock.advance(Duration.ofMinutes(20))
        wf.confirmValveClosed(a.code, op)
        wf.confirmValveClosed(b.code, op)

        // 卸后实验室发布：B 仓个体样抗生素阳性
        clock.advance(Duration.ofHours(2))
        val r = wf.publishLab(LabResult(sidB, 3.6, 3.1, 1030.0, -0.530, 15.0,
            ScreenAssay.POSITIVE, ScreenAssay.NEGATIVE, clock.instant))
        assertTrue(r.accepted)
        assertEquals(Recommendation.REJECT, wf.recommendation(b.code))
        assertEquals(Recommendation.ACCEPT, wf.recommendation(a.code),
            "A 仓身份独立，不被 B 仓阳性连坐")
        assertEquals(Recommendation.REJECT, wf.tripRecommendation())

        val report = TripReportBuilder.build(wf).render()
        assertTrue(report.contains("ANTIBIOTIC_POSITIVE"))
        assertTrue(report.contains("仓 6B"))
        assertTrue(report.contains("混合组 g6"))
        assertTrue(report.contains("组分仓 6A, 6B"))
        // 审计链显示阀门是人开的，系统从未自动开阀
        assertTrue(wf.events.any { it.action == "HUMAN_OPENED_VALVE" })
        assertFalse(wf.events.any { it.action.startsWith("AUTO_") })
    }

    // ---- 系统只提示、不自动开阀/不自动接收 -------------------------------------

    @Test
    fun `7 全程就绪系统也不自动开阀 - 状态停在SAMPLED等待人工`() {
        val clock = MutableClock(arrival)
        val c = comp("7")
        val wf = TripWorkflow(TripId("t7"), TruckId("豫M-007"), listOf(c), clock = clock)
        fullPrep(c, wf, clock)
        declareSeparate(wf, c, clock)
        connectClean(wf, "g-7", clock)
        assertEquals(CompartmentStatus.SAMPLED, wf.compartments[c.code]!!.status)
        assertEquals(Recommendation.ACCEPT, wf.recommendation(c.code))
    }

    @Test
    fun `8 主管双签覆核留痕 - 缺陷不删除`() {
        val clock = MutableClock(arrival)
        val c = comp("8")
        val wf = TripWorkflow(TripId("t8"), TruckId("豫M-008"), listOf(c), clock = clock)
        wf.verifySeal(c.code, SealCheck(SealId("S-A-8"), null, null,
            SealId("S-A-?"), true, op, arrival))
        val before = wf.allFindings().single { it.code == FindingCode.SEAL_UNVERIFIED }
        assertTrue(before.open)
        wf.override(sup, "现场已与牧场视频核对封签编号，特批",
            mapOf(c.code to setOf(FindingCode.SEAL_UNVERIFIED)))
        val after = wf.allFindings().single { it.code == FindingCode.SEAL_UNVERIFIED }
        assertFalse(after.open)
        assertEquals(sup, after.overriddenBy?.supervisor)
        assertTrue(wf.events.any { it.action == "SUPERVISOR_OVERRIDE" })
    }

    // ---- 修复 1：样品重量链只能由指定 BLE 采样秤产生 ---------------------------

    private fun readyNoSample(
        code: String, clock: MutableClock,
        expected: String = "S-A-$code",
    ): Pair<Compartment, TripWorkflow> {
        val c = comp(code)
        val wf = TripWorkflow(TripId("t-$code"), TruckId("豫M-$code"), listOf(c),
            clock = clock)
        // 装车预报绑定（期望封签）
        wf.registerNfcBinding(ExpectedSealBinding(c.code, SealId(expected),
            c.farm, c.farmBatch, t0, NfcTagId("tag-$code")))
        // 罐口 NFC 扫签直接闭合封签核验（见修复 2 的用例）
        wf.scanHatchTag(c.code, c.farm, c.farmBatch, SealId(expected),
            NfcTagId("tag-$code"), op)
        wf.recordProbe(c.code, probeGood(code))
        wf.sensory(c.code, SensoryCheck(true, "正常", op, clock.instant))
        wf.confirmStirring(c.code, 180, op)
        return c to wf
    }

    private fun individualSample(
        c: Compartment, at: Instant, grams: Double?, stable: Boolean = true,
        device: String = DEFAULT_SCALE_DEVICE_ID,
    ) = Sample(SampleId("SMP-${c.code.value}-x"), BottleTagId("B-${c.code.value}"),
        SampleKind.INDIVIDUAL, listOf(c.code), 50.0, 180,
        grams?.let { WeightReading(it, stable, at, device) }, at, op)

    @Test
    fun `9a 重量来自非指定BLE设备 - 重量链阻断且拒绝登记开阀`() {
        val clock = MutableClock(arrival)
        val (c, wf) = readyNoSample("9A", clock)
        val s = individualSample(c, clock.instant, grams = 200.0,
            device = "ble-scale-UNKNOWN")
        wf.bindBottle(s.bottleTag, s.id); wf.takeSample(c.code, s)
        wf.declareUnload(UnloadDeclaration("g-9A", listOf(c.code),
            UnloadBoundary.SEPARATE, TankId("T-1"), null, op, clock.instant))
        connectClean(wf, "g-9A", clock)

        assertTrue(wf.openBlockings(c.code).any {
            it.code == FindingCode.SAMPLE_WEIGHT_WRONG_DEVICE
        }, "非指定采样秤的重量必须阻断")
        val r = wf.confirmValveOpened(c.code, op)
        assertFalse(r.accepted)
    }

    @Test
    fun `9b 没有采样秤读数 - 判重量链缺失阻断（手工克重无入口）`() {
        val clock = MutableClock(arrival)
        val (c, wf) = readyNoSample("9B", clock)
        val s = individualSample(c, clock.instant, grams = null) // 无秤读数
        wf.bindBottle(s.bottleTag, s.id); wf.takeSample(c.code, s)

        assertTrue(wf.findingsFor(c.code).any { it.code == FindingCode.SAMPLE_WEIGHT_MISSING })
        assertTrue(wf.openBlockings(c.code).any { it.code == FindingCode.SAMPLE_WEIGHT_MISSING })
    }

    @Test
    fun `9c 指定秤稳定读数 - 重量链成立可开阀；未稳定读数仅警告且不得开阀`() {
        val clock = MutableClock(arrival)
        val (c, wf) = readyNoSample("9C", clock)
        val good = individualSample(c, clock.instant, grams = 200.0,
            device = DEFAULT_SCALE_DEVICE_ID)
        wf.bindBottle(good.bottleTag, good.id); wf.takeSample(c.code, good)
        wf.declareUnload(UnloadDeclaration("g-9C", listOf(c.code),
            UnloadBoundary.SEPARATE, TankId("T-1"), null, op, clock.instant))
        connectClean(wf, "g-9C", clock)
        assertFalse(wf.findingsFor(c.code).any {
            it.code in setOf(FindingCode.SAMPLE_WEIGHT_MISSING,
                FindingCode.SAMPLE_WEIGHT_WRONG_DEVICE)
        })
        assertTrue(wf.confirmValveOpened(c.code, op).accepted)

        // 同一读数若未稳定：只是警告级缺陷（不自动拒收），但重量链判定仍以 stable 为准
        val wobbly = WeightReading(200.0, false, clock.instant, DEFAULT_SCALE_DEVICE_ID)
        assertTrue(
            SamplingPolicy.evaluate(
                c.copy(samples = listOf(good.copy(weight = wobbly))),
                mapOf(good.bottleTag to good.id),
                ReceivingPolicyConfig(), clock.instant,
            ).any { it.code == FindingCode.SAMPLE_WEIGHT_UNSTABLE && !it.code.blocking })
    }

    // ---- 修复 2：罐口 NFC 扫签闭合到封签核验 ------------------------------------

    @Test
    fun `10a 罐口NFC身份与签号相符 - 扫签即完成封签核验`() {
        val clock = MutableClock(arrival)
        val c = comp("10")
        val wf = TripWorkflow(TripId("t10"), TruckId("豫M-010"), listOf(c), clock = clock)
        wf.registerNfcBinding(ExpectedSealBinding(c.code, SealId("S-A-10"),
            c.farm, c.farmBatch, t0, NfcTagId("tag-10")))

        wf.scanHatchTag(c.code, c.farm, c.farmBatch, SealId("S-A-10"),
            NfcTagId("tag-10"), op)

        val seal = wf.compartments[c.code]!!.seal
        assertNotNull(seal, "罐口扫签必须直接形成 SealCheck，闭合封签核验")
        assertEquals(SealId("S-A-10"), seal!!.nfc)
        assertFalse(wf.findingsFor(c.code).any {
            it.code in setOf(FindingCode.SEAL_MISMATCH, FindingCode.SEAL_UNVERIFIED,
                FindingCode.SEAL_TEXT_MISMATCH, FindingCode.FARM_MISMATCH)
        }, "身份/签号相符时不得产生封签类阻断")
        assertTrue(wf.events.any {
            it.action == "HATCH_TAG_SCANNED" && it.detail.contains("封签核验")
        })
    }

    @Test
    fun `10b 罐口NFC签号与装车预报不符 - 扫签即判换签阻断`() {
        val clock = MutableClock(arrival)
        val c = comp("10")
        val wf = TripWorkflow(TripId("t10"), TruckId("豫M-010"), listOf(c), clock = clock)
        // 预报期望 S-A-10，标签却携带 S-FORGED
        wf.registerNfcBinding(ExpectedSealBinding(c.code, SealId("S-A-10"),
            c.farm, c.farmBatch, t0, NfcTagId("tag-10")))
        wf.scanHatchTag(c.code, c.farm, c.farmBatch, SealId("S-FORGED"),
            NfcTagId("tag-10"), op)
        assertTrue(wf.openBlockings(c.code).any { it.code == FindingCode.SEAL_MISMATCH })
    }

    @Test
    fun `10c 身份不符的罐口签 - 不产生封签核验仅挂起补装`() {
        val clock = MutableClock(arrival)
        val c = comp("10")
        val wf = TripWorkflow(TripId("t10"), TruckId("豫M-010"), listOf(c), clock = clock)
        wf.registerNfcBinding(ExpectedSealBinding(c.code, SealId("S-A-10"),
            c.farm, c.farmBatch, t0, NfcTagId("tag-10")))
        wf.scanHatchTag(c.code, FarmId("牧场C"), FarmBatchId("C-X"),
            SealId("S-C-1"), NfcTagId("tag-x"), op)
        assertNull(wf.compartments[c.code]!!.seal, "身份污染的签不得形成封签核验")
        assertTrue(wf.openBlockings(c.code).any { it.code == FindingCode.RELOAD_DETECTED })
    }

    // ---- 修复 3：搅拌与卸奶边界是开阀同级硬前置 ---------------------------------

    @Test
    fun `11a 未确认搅拌 - 拒绝登记开阀`() {
        val clock = MutableClock(arrival)
        val c = comp("11")
        val wf = TripWorkflow(TripId("t11"), TruckId("豫M-011"), listOf(c), clock = clock)
        wf.verifySeal(c.code, sealOk("11"))
        wf.recordProbe(c.code, probeGood("11"))
        wf.sensory(c.code, SensoryCheck(true, "正常", op, arrival))
        // 故意不搅拌
        val sid = SampleId("SMP-11")
        wf.bindBottle(BottleTagId("B-11"), sid)
        wf.takeSample(c.code, Sample(sid, BottleTagId("B-11"), SampleKind.INDIVIDUAL,
            listOf(c.code), 50.0, null,
            WeightReading(200.0, true, clock.instant, DEFAULT_SCALE_DEVICE_ID),
            clock.instant, op))
        wf.declareUnload(UnloadDeclaration("g-11", listOf(c.code),
            UnloadBoundary.SEPARATE, TankId("T-1"), null, op, clock.instant))
        connectClean(wf, "g-11", clock)

        val r = wf.confirmValveOpened(c.code, op)
        assertFalse(r.accepted)
        assertTrue(r.message.contains("搅拌"))
    }

    @Test
    fun `11b 未声明卸奶边界 - 拒绝登记开阀，边界与其他前置同级`() {
        val clock = MutableClock(arrival)
        val c = comp("11")
        val wf = TripWorkflow(TripId("t11"), TruckId("豫M-011"), listOf(c), clock = clock)
        // 封签/探针/感官/搅拌/卸前样齐备，唯独不声明卸奶边界
        fullPrep(c, wf, clock, bottle = "B-11", sampleId = "SMP-11")
        val r = wf.confirmValveOpened(c.code, op)
        assertFalse(r.accepted)
        assertTrue(r.message.contains("卸奶边界"))
    }

    @Test
    fun `11c 搅拌与边界等前置全部闭合 - 允许登记人工开阀`() {
        val clock = MutableClock(arrival)
        val c = comp("11")
        val wf = TripWorkflow(TripId("t11"), TruckId("豫M-011"), listOf(c), clock = clock)
        fullPrep(c, wf, clock, bottle = "B-11", sampleId = "SMP-11")
        declareSeparate(wf, c, clock)
        connectClean(wf, "g-11", clock)
        assertTrue(wf.confirmValveOpened(c.code, op).accepted)
    }
}
