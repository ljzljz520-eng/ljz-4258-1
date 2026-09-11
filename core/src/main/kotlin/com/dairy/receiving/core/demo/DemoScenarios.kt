package com.dairy.receiving.core.demo

import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.workflow.TripWorkflow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 五个现场异常演练场景的构造器（离线可复现），同时作为业务规则的可执行说明。
 * 每个构造器返回“异常已发生、等待处置”的 TripWorkflow。
 */
object DemoScenarios {

    private val t0 = Instant.parse("2026-09-11T03:20:00Z")
    private val arrival = Instant.parse("2026-09-11T06:00:00Z")
    private val op = OperatorId("recv-07")

    private fun clock() = Clock.fixed(arrival, java.time.ZoneOffset.UTC)

    private fun wf(vararg codes: String, truck: String) =
        TripWorkflow(TripId("demo-${codes.joinToString("")}"), TruckId(truck),
            codes.map {
                Compartment(CompartmentCode(it), FarmId("牧场A"),
                    FarmBatchId("A-0911-1"), t0, CompartmentStatus.REGISTERED)
            }, clock = clock())

    private fun prepare(wf: TripWorkflow, code: String, bottle: String = "B-$code") {
        val c = wf.compartments[CompartmentCode(code)]!!
        wf.attachTruckLog(c.code, TruckTemperatureLog(t0, arrival, listOf(t0..arrival), 4.5))
        wf.recordProbe(c.code, ProbeReading(4.0, true, arrival, "probe-1"))
        wf.verifySeal(c.code, SealCheck(SealId("S-A-$code"), SealId("S-A-$code"),
            NfcTagId("tag-$code"), SealId("S-A-$code"), false, op, arrival))
        wf.sensory(c.code, SensoryCheck(true, "色泽气味正常", op, arrival))
        wf.confirmStirring(c.code, 180, op)
        val sid = SampleId("SMP-$code")
        wf.bindBottle(BottleTagId(bottle), sid)
        wf.takeSample(c.code, Sample(sid, BottleTagId(bottle), SampleKind.INDIVIDUAL,
            listOf(c.code), 50.0, 180,
            WeightReading(200.0, true, arrival, DEFAULT_SCALE_DEVICE_ID),
            arrival, op))
    }

    /** 1 封签号手写不清：NFC 缺失，手填无法辨认 => 阻断挂起。 */
    fun illegibleSeal(): TripWorkflow {
        val wf = wf("1", truck = "豫M-001")
        val c = CompartmentCode("1")
        wf.verifySeal(c, SealCheck(SealId("S-A-1"), null, null,
            SealId("S-A-?"), true, op, arrival))
        wf.sensory(c, SensoryCheck(true, "正常", op, arrival))
        return wf
    }

    /** 2 车载温度记录缺段：60 分钟空白，探针正常 => 警告+记录。 */
    fun truckLogGap(): TripWorkflow {
        val wf = wf("2", truck = "豫M-002")
        prepare(wf, "2")
        wf.attachTruckLog(CompartmentCode("2"), TruckTemperatureLog(
            t0, arrival,
            listOf(t0..t0.plus(Duration.ofMinutes(60)),
                t0.plus(Duration.ofMinutes(120))..arrival),
            4.5))
        return wf
    }

    /** 3 一仓先卸后取样：物理开阀早于个体样 => 代表性阻断。 */
    fun unloadBeforeSample(): TripWorkflow {
        val wf = wf("3", truck = "豫M-003")
        val c = CompartmentCode("3")
        wf.verifySeal(c, SealCheck(SealId("S-A-3"), SealId("S-A-3"),
            NfcTagId("tag-3"), SealId("S-A-3"), false, op, arrival))
        wf.recordProbe(c, ProbeReading(4.0, true, arrival, "probe-1"))
        wf.sensory(c, SensoryCheck(true, "正常", op, arrival))
        wf.confirmStirring(c, 180, op)
        // 现场绕过系统物理开卸，事后补录
        val start = arrival.plus(Duration.ofMinutes(10))
        wf.recordPhysicalBypass(c, start, op)
        // 5 分钟后才补取个体样 -> SAMPLE_AFTER_UNLOAD 阻断
        val late = Sample(SampleId("SMP-3L"), BottleTagId("B-3"), SampleKind.INDIVIDUAL,
            listOf(c), 50.0, 180,
            WeightReading(200.0, true, start.plus(Duration.ofMinutes(5)),
                DEFAULT_SCALE_DEVICE_ID),
            start.plus(Duration.ofMinutes(5)), op)
        wf.bindBottle(BottleTagId("B-3"), late.id)
        wf.takeSample(c, late)
        return wf
    }

    /** 4 混合样瓶贴错：B 仓样瓶贴成 A 仓，混卸被拒，B 身份独立挂起。 */
    fun mislabeledBottle(): TripWorkflow {
        val wf = wf("4A", "4B", truck = "豫M-004")
        prepare(wf, "4A", bottle = "B-4A")
        prepare(wf, "4B", bottle = "B-4B")
        // 收奶员又扫到一枚贴在 B 样品上的 A 瓶签（混合样瓶贴错）
        val wrong = Sample(SampleId("SMP-4B-2"), BottleTagId("B-4A"),
            SampleKind.INDIVIDUAL, listOf(CompartmentCode("4B")),
            50.0, 180,
            WeightReading(200.0, true, arrival, DEFAULT_SCALE_DEVICE_ID),
            arrival, op)
        wf.takeSample(CompartmentCode("4B"), wrong)
        return wf
    }

    /** 5 中途补装另一牧场：罐口 NFC 身份与预报不符 => 阻断挂起。 */
    fun midTripReload(): TripWorkflow {
        val wf = wf("5A", "5B", truck = "豫M-005")
        wf.scanHatchTag(CompartmentCode("5A"), FarmId("牧场C"),
            FarmBatchId("C-0911-9"), SealId("S-C-9"), NfcTagId("tag-5A"), op)
        prepare(wf, "5B")
        return wf
    }

    // ---------------- 卸奶管线残留见证演练 ----------------

    private fun cleaning(pipeline: String, validUntil: java.time.Instant) =
        CleaningAcceptance(PipelineId(pipeline), "CIP", op,
            validUntil.minus(Duration.ofHours(2)), validUntil)

    private fun flush(dest: FlushDestination, backflow: Boolean = false) =
        FlushRecord(dest, 20.0, backflow, op, arrival)

    private fun declareAndConnect(
        wf: TripWorkflow, code: String,
        cleaningValidUntil: java.time.Instant = arrival.plus(Duration.ofHours(2)),
        dest: FlushDestination = FlushDestination.RECLAIM,
        backflow: Boolean = false,
        sharedWith: TruckId? = null,
    ) {
        val c = CompartmentCode(code)
        wf.declareUnload(UnloadDeclaration("g-$code", listOf(c), UnloadBoundary.SEPARATE,
            TankId("T-1"), null, op, arrival))
        wf.recordPipelineConnection("g-$code", PipelineId("P-1"), HoseId("H-1"),
            cleaning("P-1", cleaningValidUntil), flush(dest, backflow),
            sharedWithTruck = sharedWith, by = op)
    }

    /** 6 管线清洁状态过期：连接时刻晚于清洁有效期 => 首仓阻断。 */
    fun pipelineCleaningExpired(): TripWorkflow {
        val wf = wf("6", truck = "豫M-006")
        prepare(wf, "6")
        declareAndConnect(wf, "6",
            cleaningValidUntil = arrival.minus(Duration.ofMinutes(30)))
        return wf
    }

    /** 7 前段冲洗液回流：冲洗液倒灌回槽车首仓 => 首仓阻断。 */
    fun flushBackflow(): TripWorkflow {
        val wf = wf("7", truck = "豫M-007")
        prepare(wf, "7")
        declareAndConnect(wf, "7", backflow = true)
        return wf
    }

    /** 8 软管临时更换未核验：卸奶中途换管，暴露仓阻断。 */
    fun hoseSwapUnverified(): TripWorkflow {
        val wf = wf("8", truck = "豫M-008")
        prepare(wf, "8")
        declareAndConnect(wf, "8")
        wf.confirmValveOpened(CompartmentCode("8"), op)
        wf.recordHoseSwap("g-8", HoseId("H-9"), cleanedVerified = false,
            reason = "原软管鼓包临时更换", by = op)
        return wf
    }

    /** 9 两车共用歧管：首仓接触前车滞留奶 => 警告留痕，不分摊。 */
    fun sharedManifold(): TripWorkflow {
        val wf = wf("9A", "9B", truck = "豫M-009")
        prepare(wf, "9A")
        prepare(wf, "9B")
        declareAndConnect(wf, "9A", sharedWith = TruckId("豫M-888"))
        declareAndConnect(wf, "9B")
        return wf
    }

    /** 10 首仓只卸一部分：首仓即末仓，残留影响同仓保留，余奶不混。 */
    fun firstCompartmentPartial(): TripWorkflow {
        val wf = wf("10A", "10B", truck = "豫M-010")
        prepare(wf, "10A", bottle = "B-10A")
        prepare(wf, "10B", bottle = "B-10B")
        // 混合边界 + 混合样，组分身份齐全
        val cs = Sample(SampleId("SMP-MIX10"), BottleTagId("B-MIX10"), SampleKind.COMPOSITE,
            listOf(CompartmentCode("10A"), CompartmentCode("10B")), null, null, null,
            arrival, op)
        wf.bindBottle(BottleTagId("B-MIX10"), cs.id)
        wf.registerCompositeSample(cs)
        wf.declareUnload(UnloadDeclaration("g-10",
            listOf(CompartmentCode("10A"), CompartmentCode("10B")),
            UnloadBoundary.COMPOSITE, TankId("T-9"), cs.id, op, arrival))
        wf.recordPipelineConnection("g-10", PipelineId("P-2"), HoseId("H-2"),
            cleaning("P-2", arrival.plus(Duration.ofHours(2))),
            flush(FlushDestination.RECLAIM), by = op)
        // 首仓 10A 开阀后只卸一部分即关阀
        wf.confirmValveOpened(CompartmentCode("10A"), op)
        wf.confirmValveClosed(CompartmentCode("10A"), op, complete = false)
        return wf
    }

    fun all(): List<Pair<String, () -> TripWorkflow>> = listOf(
        "封签手写不清" to ::illegibleSeal,
        "车载温度缺段" to ::truckLogGap,
        "先卸后取样" to ::unloadBeforeSample,
        "混样瓶贴错" to ::mislabeledBottle,
        "中途补装另一牧场" to ::midTripReload,
        "管线清洁状态过期" to ::pipelineCleaningExpired,
        "前段冲洗液回流" to ::flushBackflow,
        "软管临时更换未核验" to ::hoseSwapUnverified,
        "两车共用歧管" to ::sharedManifold,
        "首仓只卸一部分" to ::firstCompartmentPartial,
    )
}
