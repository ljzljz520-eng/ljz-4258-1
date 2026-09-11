package com.dairy.receiving.core.workflow

import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.policy.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 命令结果：系统给判定与建议，但是否开阀/接收由人决定。 */
data class CommandResult(
    val accepted: Boolean,
    val findings: List<Finding>,
    val message: String,
)

data class AuditEvent(
    val seq: Long,
    val at: TimePoint,
    val actor: OperatorId?,
    val action: String,
    val detail: String,
)

/**
 * 单车收奶工作流。纯 Kotlin、离线可跑；Room 负责持久化本对象快照。
 * 不变量：
 *  - 每个仓室独立评估，混合卸载不合并身份；
 *  - 任何未决阻断项 => 不允许进入 READY_TO_UNLOAD；
 *  - 系统绝不驱动阀门，只记录收奶员的人工开阀动作。
 */
class TripWorkflow(
    val tripId: TripId,
    val truckId: TruckId,
    compartments: List<Compartment>,
    val config: ReceivingPolicyConfig = ReceivingPolicyConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val now: Instant get() = Instant.now(clock)

    private val _compartments = compartments.associateBy { it.code }.toMutableMap()
    val compartments: Map<CompartmentCode, Compartment> get() = _compartments

    private val sealBindings = mutableMapOf<CompartmentCode, ExpectedSealBinding>()
    private val bottleBindings = mutableMapOf<BottleTagId, SampleId>()
    private val declarations = mutableListOf<UnloadDeclaration>()
    private val compositeSamples = mutableMapOf<SampleId, Sample>()
    private val overrides = mutableListOf<SupervisorOverride>()
    private val audit = mutableListOf<AuditEvent>()

    /** 样品 ID -> 来源仓，用于实验室结果回指 */
    private val sampleSources = mutableMapOf<SampleId, List<CompartmentCode>>()

    constructor(memento: TripMemento, clock: Clock = Clock.systemUTC()) : this(
        tripId = memento.tripId,
        truckId = memento.truckId,
        compartments = memento.compartments,
        config = memento.config,
        clock = clock,
    ) {
        memento.sealBindings.forEach { sealBindings[it.compartment] = it }
        bottleBindings.putAll(memento.bottleBindings)
        compositeSamples.putAll(memento.compositeSamples)
        declarations.addAll(memento.declarations)
        overrides.addAll(memento.overrides)
        audit.addAll(memento.audit)
        sampleSources.putAll(memento.sampleSources)
    }

    val events: List<AuditEvent> get() = audit.toList()
    val supervisorOverrides: List<SupervisorOverride> get() = overrides.toList()
    val unloadDeclarations: List<UnloadDeclaration> get() = declarations.toList()

    /** 装车预报中为该仓登记的期望封签绑定（罐口扫签/封签核验时的比对基准）。 */
    fun expectedSeal(code: CompartmentCode): ExpectedSealBinding? = sealBindings[code]

    /** 已声明卸奶边界中包含该仓的最新声明（开阀前置之一）。 */
    fun declarationOf(code: CompartmentCode): UnloadDeclaration? =
        declarations.lastOrNull { code in it.compartments }

    private fun log(actor: OperatorId?, action: String, detail: String) {
        audit += AuditEvent(audit.size + 1L, now, actor, action, detail)
    }

    private fun update(c: Compartment) {
        _compartments[c.code] = deriveStatus(c)
    }

    // ---------------- 到厂登记 ----------------

    /**
     * 登记装车预报中的“仓-封签-牧场批-NFC 标签”绑定（离线缓存/预报数据）。
     * 罐口 NFC 扫签后与该绑定核对：NFC 签是封签核验的权威输入。
     */
    fun registerNfcBinding(binding: ExpectedSealBinding) {
        sealBindings[binding.compartment] = binding
        log(null, "NFC_BINDING_CACHED",
            "仓${binding.compartment.value} -> 签${binding.seal.value} " +
                "${binding.farm.value}/${binding.farmBatch.value}")
    }

    /**
     * 罐口 NFC 扫签：一步闭合到封签核验。
     *  - 身份与预报不符：登记途中补装线索并挂起（不产生封签核验，签不可信）；
     *  - 身份相符：NFC 读签直接形成 [SealCheck]（权威输入），expected 取装车预报绑定，
     *    无预报时以标签自报签号为准；手写件缺失不阻断 NFC 核验。
     */
    fun scanHatchTag(code: CompartmentCode, tagFarm: FarmId, tagBatch: FarmBatchId,
                     tagSeal: SealId, tag: NfcTagId, by: OperatorId) {
        val c = must(code)
        if (tagFarm != c.farm || tagBatch != c.farmBatch) {
            val sl = SecondaryLoad(tagFarm, tagBatch, now,
                "罐口签 $tag 携带身份 ${tagFarm.value}/${tagBatch.value}，与预报不符")
            update(c.copy(secondaryLoads = c.secondaryLoads + sl))
            log(by, "RELOAD_SUSPECTED_NFC", "仓${code.value}")
            return
        }
        val expected = sealBindings[code]?.seal ?: tagSeal
        update(c.copy(seal = SealCheck(
            expected = expected,
            nfc = tagSeal,
            nfcTag = tag,
            written = null,
            writtenIllegible = false,
            checkedBy = by,
            checkedAt = now,
        )))
        log(by, "HATCH_TAG_SCANNED",
            "仓${code.value} 签${tagSeal.value}（NFC 读签已闭合封签核验）")
    }

    // ---------------- 逐仓确认 ----------------

    fun verifySeal(code: CompartmentCode, seal: SealCheck): CommandResult {
        val c = must(code)
        update(c.copy(seal = seal))
        log(seal.checkedBy, "SEAL_CHECKED",
            "仓${code.value} nfc=${seal.nfc?.value} written=${seal.written?.value}")
        return snapshot("封签核对完成", code)
    }

    fun attachTruckLog(code: CompartmentCode, logData: TruckTemperatureLog): CommandResult {
        update(must(code).copy(truckLog = logData))
        log(null, "TRUCK_TEMP_LOG_ATTACHED", "仓${code.value}")
        return snapshot("车载温度记录已关联", code)
    }

    /** BLE 温度计稳定值（设备侧已判稳，stable 标志仍参与规则）。 */
    fun recordProbe(code: CompartmentCode, reading: ProbeReading): CommandResult {
        update(must(code).copy(probe = reading))
        log(null, "PROBE_READING", "仓${code.value} ${reading.celsius}℃ stable=${reading.stable}")
        return snapshot("探针温度已记录", code)
    }

    fun confirmStirring(code: CompartmentCode, seconds: Int, by: OperatorId): CommandResult {
        update(must(code).copy(stirringConfirmedAt = now, stirringSeconds = seconds))
        log(by, "STIRRING_CONFIRMED", "仓${code.value} ${seconds}s")
        return snapshot("搅拌已确认", code)
    }

    /**
     * 取样。系统先登记瓶签绑定（扫码/扫瓶 NFC），再落样品；
     * 若在开卸后取样，样品照样留存但产生“先卸后取”阻断缺陷。
     */
    fun bindBottle(tag: BottleTagId, sample: SampleId) {
        bottleBindings[tag] = sample
        log(null, "BOTTLE_BOUND", "${tag.value} -> ${sample.value}")
    }

    /** 登记混合样（独立于个体样），记录全部组分仓身份以便回溯。 */
    fun registerCompositeSample(sample: Sample): CommandResult {
        require(sample.kind == SampleKind.COMPOSITE) { "必须是混合样" }
        val sourceSet = sample.sourceCompartments.toSet()
        require(sourceSet.all { it in _compartments }) { "混合样含未知仓室" }
        require(sourceSet.size >= 2) { "混合样至少两个组分仓" }
        sampleSources[sample.id] = sample.sourceCompartments
        compositeSamples[sample.id] = sample
        log(sample.takenBy, "COMPOSITE_SAMPLE_REGISTERED",
            "${sample.id.value} 瓶${sample.bottleTag.value} 组分=${sample.sourceCompartments.map { it.value }}")
        return CommandResult(true, emptyList(), "混合样已登记")
    }

    fun takeSample(code: CompartmentCode, sample: Sample): CommandResult {
        val c = must(code)
        sampleSources[sample.id] = sample.sourceCompartments
        update(c.copy(samples = c.samples + sample))
        log(sample.takenBy, "SAMPLE_TAKEN",
            "仓${code.value} ${sample.id.value} 瓶${sample.bottleTag.value} @${sample.takenAt}")
        return snapshot("样品已留存", code)
    }

    fun sensory(code: CompartmentCode, check: SensoryCheck): CommandResult {
        update(must(code).copy(sensory = check))
        log(check.checkedBy, "SENSORY_CHECKED",
            "仓${code.value} normal=${check.normal} ${check.note}")
        return snapshot("感官检查已记录", code)
    }

    /** 收奶员登记途中补装（司机自报/证据），立即挂起该仓。 */
    fun reportSecondaryLoad(code: CompartmentCode, sl: SecondaryLoad): CommandResult {
        update(must(code).copy(secondaryLoads = must(code).secondaryLoads + sl))
        log(null, "RELOAD_REPORTED",
            "仓${code.value} += ${sl.farm.value}/${sl.farmBatch.value}")
        return snapshot("已登记途中补装，仓室挂起", code)
    }

    // ---------------- 卸奶边界 ----------------

    /**
     * 声明卸奶边界。混合卸载必须带全部组分仓身份与混合样；
     * 不满足身份保持条件时拒绝该声明（不改变任何仓状态）。
     */
    fun declareUnload(declaration: UnloadDeclaration): CommandResult {
        val findings = UnloadPolicy.evaluateComposite(
            declaration, _compartments, blockingByCompartment(), compositeSamples, now)
        val openBlock = findings.filter { it.code.blocking && it.open }
        if (declaration.boundary == UnloadBoundary.COMPOSITE && openBlock.isNotEmpty()) {
            log(declaration.declaredBy, "COMPOSITE_DECLINED",
                "组${declaration.groupId}: ${openBlock.joinToString { it.code.name }}")
            return CommandResult(false, findings,
                "混合卸载声明被拒：异常仓身份无法保持")
        }
        declarations += declaration
        for (code in declaration.compartments) {
            update(must(code).copy(unloadGroupId = declaration.groupId))
        }
        log(declaration.declaredBy, "UNLOAD_DECLARED",
            "组${declaration.groupId} 边界=${declaration.boundary} " +
                "罐=${declaration.targetTank.value} 组分=${declaration.compartments.map { it.value }}")
        return CommandResult(true, findingsFor(declaration.compartments), "卸奶边界已声明")
    }

    /**
     * 收奶员人工开阀确认。系统仅记录、不驱动执行机构；
     * 硬前置（封签/感官/搅拌/卸前样/卸奶边界声明，同等级逐项）不满足
     * 或存在未决阻断项时拒绝登记（人可先请主管双签覆核）。
     */
    fun confirmValveOpened(code: CompartmentCode, by: OperatorId): CommandResult {
        val c = must(code)
        val blockers = UnloadPolicy.openValveBlockers(
            c, config, declarations.flatMap { it.compartments }.toSet())
        if (blockers.isNotEmpty()) {
            return CommandResult(false, findingsFor(code),
                "仓${code.value} 不具备开卸条件：${blockers.joinToString("、")}")
        }
        val openBlock = openBlockings(code)
        if (openBlock.isNotEmpty()) {
            return CommandResult(false, findingsFor(code),
                "仓${code.value} 存在未决阻断：${openBlock.joinToString { it.code.name }}")
        }
        val start = now
        update(c.copy(status = CompartmentStatus.UNLOADING, unloadStartedAt = start))
        log(by, "HUMAN_OPENED_VALVE",
            "仓${code.value}（人工操作，系统未驱动阀门）@$start")
        return snapshot("已记录人工开阀", code)
    }

    /**
     * 登记“现场绕过系统物理开阀”的既成事实（不补开任何许可，仅留痕并暴露时序缺陷）。
     * 用于收奶员发现阀门已被物理打开后的事后补录。
     */
    fun recordPhysicalBypass(code: CompartmentCode, at: TimePoint, by: OperatorId?): CommandResult {
        val c = must(code)
        update(c.copy(status = CompartmentStatus.UNLOADING, unloadStartedAt = at))
        log(by, "PHYSICAL_VALVE_BYPASS_RECORDED",
            "仓${code.value} 在系统许可之外被物理开阀 @$at")
        return snapshot("已记录物理开阀（绕过），时序缺陷将被标记", code)
    }

    fun confirmValveClosed(code: CompartmentCode, by: OperatorId): CommandResult {
        val c = must(code)
        update(c.copy(status = CompartmentStatus.UNLOADED, unloadFinishedAt = now))
        log(by, "HUMAN_CLOSED_VALVE", "仓${code.value}")
        return snapshot("已记录关阀，卸奶完成", code)
    }

    // ---------------- 主管双签覆核 ----------------

    /** 主管覆核：带工号+理由对具体缺陷码放行；全程留痕，不删除原始缺陷。 */
    fun override(
        supervisor: OperatorId, reason: String,
        scope: Map<CompartmentCode?, Set<FindingCode>>,
    ): CommandResult {
        require(reason.isNotBlank()) { "覆核必须填写理由" }
        val o = SupervisorOverride(supervisor, reason, now, scope)
        overrides += o
        // 覆核只影响建议引擎对“未决”的判定：保留记录、标记覆核人
        log(supervisor, "SUPERVISOR_OVERRIDE",
            "理由=\"$reason\" 范围=$scope")
        return CommandResult(true, allFindings().map { f ->
            val codes = scope[f.compartment] ?: scope[null]
            if (codes != null && f.code in codes && f.open) f.copy(overriddenBy = o) else f
        }, "主管覆核已记录")
    }

    // ---------------- 实验室结果 ----------------

    fun publishLab(result: LabResult): CommandResult {
        val sources = sampleSources[result.sampleId]
            ?: return CommandResult(false, emptyList(), "样品 ${result.sampleId.value} 不属于本车")
        for (code in sources) {
            val c = must(code)
            update(c.copy(labResults = c.labResults + (result.sampleId to result)))
        }
        log(null, "LAB_RESULT_PUBLISHED",
            "样${result.sampleId.value} -> 仓${sources.map { it.value }} " +
                "抗生素=${result.antibiotic} 掺假=${result.adulteration}")
        return snapshot("实验室结果已发布并回指仓室", sources)
    }

    // ---------------- 评估 / 建议 ----------------

    fun allFindings(): List<Finding> {
        val out = mutableListOf<Finding>()
        for (c in _compartments.values) {
            out += SealPolicy.evaluate(c, sealBindings[c.code], now)
            out += TemperaturePolicy.evaluate(c, config, now)
            out += SamplingPolicy.evaluate(c, bottleBindings, config, now)
            out += SamplingPolicy.preUnloadCheck(c, now)
            out += ReloadPolicy.evaluate(c, now)
            out += LabPolicy.evaluate(c, now)
        }
        for (d in declarations) {
            out += UnloadPolicy.evaluateComposite(
                d, _compartments, blockingByCompartment(out), compositeSamples, now)
        }
        return applyOverrideMarks(out)
    }

    private fun applyOverrideMarks(findings: List<Finding>): List<Finding> {
        if (overrides.isEmpty()) return findings
        // 简化语义：任一最新覆核覆盖到的 (compartment, code) 标记
        return findings.map { f ->
            val o = overrides.lastOrNull { it.covers(f.compartment, f.code) }
            o?.let { f.copy(overriddenBy = it) } ?: f
        }
    }

    fun findingsFor(code: CompartmentCode): List<Finding> =
        allFindings().filter { it.compartment == code }

    fun findingsFor(codes: List<CompartmentCode>): List<Finding> =
        allFindings().filter { it.compartment in codes }

    fun openBlockings(code: CompartmentCode): List<Finding> =
        findingsFor(code).filter { it.code.blocking && it.open }

    private fun blockingByCompartment(
        base: List<Finding> = allFindings(),
    ): Map<CompartmentCode, List<Finding>> =
        base.filter { it.compartment != null }
            .groupBy { it.compartment!! }

    /**
     * 接收建议（人决定，系统不自动接收）。
     * 抗生素/掺假 => REJECT；其余未决阻断 => HOLD；警告 => ACCEPT_WITH_NOTE；否则 ACCEPT。
     */
    fun recommendation(code: CompartmentCode): Recommendation {
        val fs = findingsFor(code)
        val rejectCodes = setOf(FindingCode.ANTIBIOTIC_POSITIVE, FindingCode.SUSPECT_ADULTERATION)
        return when {
            fs.any { it.code in rejectCodes && it.open } -> Recommendation.REJECT
            fs.any { it.code.blocking && it.open } -> Recommendation.HOLD
            fs.any { it.code.severity == FindingSeverity.WARNING && it.open } ->
                Recommendation.ACCEPT_WITH_NOTE
            else -> Recommendation.ACCEPT
        }
    }

    fun tripRecommendation(): Recommendation {
        val recs = _compartments.keys.map { recommendation(it) }
        return when {
            recs.any { it == Recommendation.REJECT } -> Recommendation.REJECT
            recs.any { it == Recommendation.HOLD } -> Recommendation.HOLD
            recs.any { it == Recommendation.ACCEPT_WITH_NOTE } -> Recommendation.ACCEPT_WITH_NOTE
            else -> Recommendation.ACCEPT
        }
    }

    // ---------------- 状态推导 ----------------

    private fun deriveStatus(c: Compartment): Compartment {
        if (c.status == CompartmentStatus.UNLOADED ||
            c.status == CompartmentStatus.UNLOADING) return c
        val hasBlocking = openBlockingsForState(c).any { it.open }
        val progressed = when {
            c.hasPreUnloadSample -> CompartmentStatus.SAMPLED
            c.stirringConfirmedAt != null -> CompartmentStatus.STIRRED
            c.seal != null -> CompartmentStatus.SEAL_VERIFIED
            else -> CompartmentStatus.REGISTERED
        }
        return if (hasBlocking || c.secondaryLoads.isNotEmpty())
            c.copy(status = CompartmentStatus.HELD)
        else c.copy(status = progressed)
    }

    private fun openBlockingsForState(c: Compartment): List<Finding> = listOf(
        SealPolicy.evaluate(c, sealBindings[c.code], now),
        TemperaturePolicy.evaluate(c, config, now),
        SamplingPolicy.evaluate(c, bottleBindings, config, now),
        ReloadPolicy.evaluate(c, now),
        LabPolicy.evaluate(c, now),
    ).flatten().filter { it.code.blocking }

    private fun must(code: CompartmentCode): Compartment =
        _compartments[code] ?: throw IllegalArgumentException("未知仓室 ${code.value}")

    private fun snapshot(msg: String, code: CompartmentCode) =
        CommandResult(true, findingsFor(code), msg)

    private fun snapshot(msg: String, codes: List<CompartmentCode>) =
        CommandResult(true, findingsFor(codes), msg)

    fun snapshot(): TripMemento = TripMemento(
        tripId = tripId,
        truckId = truckId,
        compartments = _compartments.values.sortedBy { it.code.value },
        config = config,
        sealBindings = sealBindings.values.toList(),
        bottleBindings = bottleBindings.toMap(),
        compositeSamples = compositeSamples.toMap(),
        declarations = declarations.toList(),
        overrides = overrides.toList(),
        audit = audit.toList(),
        sampleSources = sampleSources.toMap(),
    )

    companion object {
        fun newTripId() = TripId(UUID.randomUUID().toString())
        fun newSampleId() = SampleId(UUID.randomUUID().toString().take(8))
    }
}
