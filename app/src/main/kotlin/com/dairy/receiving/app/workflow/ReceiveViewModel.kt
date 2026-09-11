package com.dairy.receiving.app.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dairy.receiving.app.data.RoomTripStore
import com.dairy.receiving.app.device.ble.ScaleReadingSource
import com.dairy.receiving.applogic.device.NfcScanRouter
import com.dairy.receiving.applogic.device.message
import com.dairy.receiving.applogic.persist.restore
import com.dairy.receiving.core.demo.DemoScenarios
import com.dairy.receiving.core.device.NfcScan
import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.report.TripReportBuilder
import com.dairy.receiving.core.workflow.CommandResult
import com.dairy.receiving.core.workflow.TripWorkflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

data class ScaleUi(
    val deviceId: String,
    val grams: Double?,
    val stable: Boolean,
)

data class CompartmentUi(
    val code: String,
    val farm: String,
    val farmBatch: String,
    val status: CompartmentStatus,
    val recommendation: Recommendation,
    val findings: List<Finding>,
    val sealExpected: String?,
    val sealNfc: String?,
    val sealWritten: String?,
    val sealWrittenIllegible: Boolean,
    val boundaryDeclared: Boolean,
    val stirringConfirmed: Boolean,
    val probeCelsius: Double?,
    val probeStable: Boolean,
    val sampleCount: Int,
    val preUnloadSample: Boolean,
    val group: String?,
)

data class TripUiState(
    val tripId: String = "",
    val truckId: String = "",
    val overall: Recommendation = Recommendation.ACCEPT,
    val compartments: List<CompartmentUi> = emptyList(),
    val selected: String? = null,
    val toast: String? = null,
    val auditTail: List<String> = emptyList(),
    val report: String = "",
    val scale: ScaleUi? = null,
) {
    val selectedCompartment get(): CompartmentUi? = compartments.firstOrNull { it.code == selected }
}

/**
 * 收奶核对编排器。所有判定来自 core 规则引擎；VM 只做状态投影与离线落库。
 * 系统不自动开阀/不自动接收——本类没有任何“自动放行”调用。
 * 样品重量只能来自 [scaleSource]（指定 BLE 采样秤），不存在手工录入克重的入口。
 */
class ReceiveViewModel(
    private val store: RoomTripStore,
    private val clock: Clock = Clock.systemUTC(),
    private val scaleSource: ScaleReadingSource? = null,
) : ViewModel() {

    private val _ui = MutableStateFlow(TripUiState())
    val ui: StateFlow<TripUiState> = _ui.asStateFlow()

    private var wf: TripWorkflow? = null
    private val nfcRouter = NfcScanRouter(clock)
    private var operator = OperatorId("recv-07")
    private var pendingBottleSample: SampleId? = null
    /** 当前秤读数（来自指定 BLE 设备）；取样时只取稳定值。 */
    private var latestWeight: WeightReading? = null

    init {
        // 订阅指定采样秤；未注入（演练/无 BLE 硬件）时重量流缺失，取样会被规则阻断
        scaleSource?.let { src ->
            _ui.update { it.copy(scale = ScaleUi(src.scaleDeviceId, null, false)) }
            viewModelScope.launch {
                src.readings().collect { r ->
                    latestWeight = r
                    _ui.update {
                        it.copy(scale = ScaleUi(src.scaleDeviceId, r.grams, r.stable))
                    }
                }
            }
        }
    }

    fun setOperator(id: String) { if (id.isNotBlank()) operator = OperatorId(id) }

    fun loadDemo(index: Int) {
        val (_, factory) = DemoScenarios.all()[index]
        bind(factory(), persist = false)
        _ui.update { it.copy(toast = "演练场景已载入（异常等待处置）") }
    }

    fun newTruck(truck: String, vararg codes: String) {
        val now = Instant.now(clock)
        val list = codes.map {
            Compartment(CompartmentCode(it), FarmId("牧场A"), FarmBatchId("A-0911-1"),
                now, CompartmentStatus.REGISTERED)
        }
        val w = TripWorkflow(TripWorkflow.newTripId(), TruckId(truck.ifBlank { "未标车" }),
            list, clock = clock)
        // 装车预报绑定离线缓存：仓 -> 期望封签（罐口 NFC 扫签后与之核对并闭合封签核验）
        list.forEach { c ->
            w.registerNfcBinding(ExpectedSealBinding(
                c.code, SealId("S-A-${c.code.value}"), c.farm, c.farmBatch, now, null))
        }
        bind(w, persist = true)
    }

    private fun bind(workflow: TripWorkflow, persist: Boolean) {
        wf = workflow
        if (persist) persist()
        project()
    }

    fun select(code: String) = _ui.update { it.copy(selected = code) }

    fun onNfcScan(scan: NfcScan) {
        val w = wf ?: return
        val code = _ui.value.selected?.let(::CompartmentCode)
        val result = nfcRouter.handle(scan, w, code, pendingBottleSample, operator)
        pendingBottleSample = null
        persist()
        project(result.message())
    }

    /**
     * 手写封签补录（NFC 为权威）。若罐口扫签已带入 NFC 读签，补录只更新手写件，
     * 不得覆盖 NFC 身份；最终核验仍由 SealPolicy 比较 NFC 与装车单期望签。
     */
    fun manualSeal(written: String, illegible: Boolean) {
        val w = wf ?: return; val code = current() ?: return
        val existing = w.compartments[code]?.seal
        w.verifySeal(code, SealCheck(
            expected = existing?.expected ?: SealId("S-A-${code.value}"),
            nfc = existing?.nfc,
            nfcTag = existing?.nfcTag,
            written = written.ifBlank { null }?.let(::SealId),
            writtenIllegible = illegible,
            checkedBy = operator, checkedAt = Instant.now(clock)))
        persist(); project("手写封签已登记（以 NFC 为权威）")
    }

    fun probeReading(c: Double, stable: Boolean) {
        val w = wf ?: return; val code = current() ?: return
        w.recordProbe(code, ProbeReading(c, stable, Instant.now(clock), "probe-handheld"))
        persist(); project(if (stable) "稳定探针温度 $c℃" else "探针未稳定，不参与判定")
    }

    fun attachTruckLog(gapMinutes: Long) {
        val w = wf ?: return; val code = current() ?: return
        val c = w.compartments[code]!!
        val arrived = Instant.now(clock)
        val loaded = c.loadedAt
        val intervals = if (gapMinutes <= 0) listOf(loaded..arrived)
        else listOf(loaded..arrived.minusSeconds(gapMinutes * 60 + 60),
            arrived.minusSeconds(30)..arrived)
        w.attachTruckLog(code, TruckTemperatureLog(loaded, arrived, intervals, 4.5))
        persist(); project("车载温度记录已关联（缺段 $gapMinutes 分钟）")
    }

    fun sensory(normal: Boolean, note: String) {
        val w = wf ?: return; val code = current() ?: return
        w.sensory(code, SensoryCheck(normal, note, operator, Instant.now(clock)))
        persist(); project("感官检查已记录")
    }

    fun stirring(seconds: Int) {
        val w = wf ?: return; val code = current() ?: return
        w.confirmStirring(code, seconds, operator)
        persist(); project("搅拌 $seconds 秒已确认")
    }

    /**
     * 取样：规定深度 + **指定 BLE 采样秤的稳定重量**，瓶签待 NFC 绑定。
     * 没有秤流 / 读数未稳定 / 设备不匹配时不带入重量，由规则判
     * SAMPLE_WEIGHT_MISSING / SAMPLE_WEIGHT_WRONG_DEVICE（阻断），
     * 即“样品重量链不能由指定 BLE 设备之外的任何途径产生”。
     */
    fun takeIndividualSample(depthCm: Double) {
        val w = wf ?: return; val code = current() ?: return
        val r = latestWeight
        val sid = TripWorkflow.newSampleId()
        val tag = BottleTagId("B-${code.value}-${sid.value.take(4)}")
        pendingBottleSample = sid
        w.bindBottle(tag, sid)
        w.takeSample(code, Sample(sid, tag, SampleKind.INDIVIDUAL, listOf(code),
            depthCm, w.compartments[code]?.stirringSeconds,
            r?.takeIf { it.stable && it.deviceId == w.config.designatedScaleDeviceId },
            Instant.now(clock), operator))
        persist()
        val msg = when {
            r == null ->
                "个体样 ${sid.value} 已留存，但未收到指定 BLE 采样秤 ${w.config.designatedScaleDeviceId} 读数：重量链缺失将阻断开卸"
            !r.stable ->
                "个体样 ${sid.value} 已留存，秤读数未稳定：重量链不成立，请稳定后复称"
            r.deviceId != w.config.designatedScaleDeviceId ->
                "个体样 ${sid.value} 重量来自非指定设备 ${r.deviceId}，重量链不予承认"
            else ->
                "个体样 ${sid.value} 已留存（${r.grams}g@${r.deviceId}），请把瓶签靠近 NFC 读头复核"
        }
        project(msg)
    }

    fun reportReload(farm: String, batch: String) {
        val w = wf ?: return; val code = current() ?: return
        w.reportSecondaryLoad(code, SecondaryLoad(FarmId(farm), FarmBatchId(batch),
            Instant.now(clock), "收奶员现场登记"))
        persist(); project("已登记途中补装，仓室挂起")
    }

    fun declareComposite(otherCodes: List<String>, tank: String) {
        val w = wf ?: return; val code = current() ?: return
        val all = (listOf(code.value) + otherCodes).distinct().map(::CompartmentCode)
        val sid = TripWorkflow.newSampleId()
        val tag = BottleTagId("MIX-${sid.value}")
        val cs = Sample(sid, tag, SampleKind.COMPOSITE, all, null, null, null,
            Instant.now(clock), operator)
        w.bindBottle(tag, sid)
        w.registerCompositeSample(cs)
        val r = w.declareUnload(UnloadDeclaration(
            "g-${sid.value.take(6)}", all, UnloadBoundary.COMPOSITE,
            TankId(tank.ifBlank { "T-MIX" }), sid, operator, Instant.now(clock)))
        persist(); project(if (r.accepted) "混合卸载边界已声明：组分仓身份全部留档"
        else "混合声明被拒：${r.findings.filter { it.code.blocking }.joinToString { it.code.name }}")
    }

    fun declareSeparate(tank: String) {
        val w = wf ?: return; val code = current() ?: return
        val r = w.declareUnload(UnloadDeclaration(
            "g-${code.value}", listOf(code), UnloadBoundary.SEPARATE,
            TankId(tank.ifBlank { "T-1" }), null, operator, Instant.now(clock)))
        persist(); project(r.message)
    }

    /** 收奶员人工开阀——系统只记录；搅拌与卸奶边界声明是与封签/样品同级的硬前置。 */
    fun humanOpenValve(): CommandResult? {
        val w = wf ?: return null; val code = current() ?: return null
        val r = w.confirmValveOpened(code, operator)
        persist()
        project(if (r.accepted) "已记录人工开阀（系统未驱动任何执行机构）"
        else "开阀未登记：${r.message}")
        return r
    }

    fun humanCloseValve() {
        val w = wf ?: return; val code = current() ?: return
        w.confirmValveClosed(code, operator)
        persist(); project("已记录关阀")
    }

    fun supervisorOverride(reason: String, codes: Set<FindingCode>) {
        val w = wf ?: return; val code = current() ?: return
        if (reason.isBlank()) { project("覆核必须填写理由"); return }
        w.override(OperatorId("supervisor"), reason, mapOf(code to codes))
        persist(); project("主管双签覆核已留痕，缺陷记录保留")
    }

    fun publishLab(sampleId: String, antibiotic: ScreenAssay, adulteration: ScreenAssay,
                   fat: Double?, protein: Double?, fp: Double?) {
        val w = wf ?: return
        val r = w.publishLab(LabResult(SampleId(sampleId), fat, protein, null, fp, null,
            antibiotic, adulteration, Instant.now(clock)))
        persist(); project(if (r.accepted) "实验室结果已发布并回指仓室" else r.message)
    }

    fun buildReport() {
        val w = wf ?: return
        _ui.update { it.copy(report = TripReportBuilder.build(w).render()) }
    }

    private fun current(): CompartmentCode? =
        _ui.value.selected?.let(::CompartmentCode) ?: wf?.compartments?.keys?.firstOrNull()

    private fun project(toast: String? = null) {
        val w = wf ?: return
        val declared = w.unloadDeclarations.flatMap { it.compartments }.toSet()
        val uiComps = w.compartments.values.sortedBy { it.code.value }.map { c ->
            val fs = w.findingsFor(c.code)
            CompartmentUi(
                code = c.code.value, farm = c.farm.value, farmBatch = c.farmBatch.value,
                status = c.status, recommendation = w.recommendation(c.code),
                findings = fs,
                sealExpected = c.seal?.expected?.value,
                sealNfc = c.seal?.nfc?.value,
                sealWritten = c.seal?.written?.value,
                sealWrittenIllegible = c.seal?.writtenIllegible == true,
                boundaryDeclared = c.code in declared,
                stirringConfirmed = c.stirringConfirmedAt != null,
                probeCelsius = c.probe?.celsius, probeStable = c.probe?.stable == true,
                sampleCount = c.samples.size,
                preUnloadSample = c.hasPreUnloadSample,
                group = c.unloadGroupId,
            )
        }
        _ui.update {
            it.copy(
                tripId = w.tripId.value, truckId = w.truckId.value,
                overall = w.tripRecommendation(), compartments = uiComps,
                selected = it.selected ?: uiComps.firstOrNull()?.code,
                toast = toast,
                auditTail = w.events.takeLast(6).reversed().map { e ->
                    "#${e.seq} ${e.action} ${e.detail}"
                },
            )
        }
    }

    fun consumeToast() = _ui.update { it.copy(toast = null) }

    private fun persist() {
        val w = wf ?: return
        viewModelScope.launch { runCatching { store.save(w.snapshot()) } }
    }

    fun restore(tripId: String) {
        viewModelScope.launch {
            store.restore(tripId, clock)?.let { bind(it, persist = false) }
        }
    }
}
