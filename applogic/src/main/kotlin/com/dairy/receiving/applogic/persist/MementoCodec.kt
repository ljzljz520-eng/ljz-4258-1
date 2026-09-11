package com.dairy.receiving.applogic.persist

import com.dairy.receiving.core.model.*
import com.dairy.receiving.core.workflow.AuditEvent
import com.dairy.receiving.core.workflow.TripMemento
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * 快照 <-> JSON：Room TEXT 列 / 同步报文共用。
 * 手写编解码，避免给离线模块引入反射型序列化框架。
 */
object MementoCodec {

    /** 索引访问，保留嵌套 JSONObject/JSONArray 原生类型（toList() 会转 HashMap）。 */
    private fun JSONArray.items(): List<Any?> =
        (0 until length()).map { get(it) }

    fun encode(m: TripMemento): String = JSONObject().apply {
        put("tripId", m.tripId.value)
        put("truckId", m.truckId.value)
        put("config", jConfig(m.config))
        put("compartments", JSONArray(m.compartments.map { jCompartment(it) }))
        put("sealBindings", JSONArray(m.sealBindings.map { jSealBinding(it) }))
        put("bottleBindings", jMap(m.bottleBindings.map { it.key.value to it.value.value }))
        put("compositeSamples", JSONArray(m.compositeSamples.values.map { jSample(it) }))
        put("declarations", JSONArray(m.declarations.map { jDeclaration(it) }))
        put("overrides", JSONArray(m.overrides.map { jOverride(it) }))
        put("audit", JSONArray(m.audit.map { jAudit(it) }))
        put("sampleSources", jMap(m.sampleSources.map { (k, v) ->
            k.value to v.joinToString(",") { it.value } }))
    }.toString(2)

    fun decode(text: String): TripMemento {
        val o = JSONObject(text)
        val compartments = o.getJSONArray("compartments").items().map { dCompartment(it as JSONObject) }
        val config = if (o.has("config")) dConfig(o.getJSONObject("config"))
            else ReceivingPolicyConfig()
        val sealBindings = o.getJSONArray("sealBindings").items()
            .map { dSealBinding(it as JSONObject) }
        val bottleBindings = o.getJSONObject("bottleBindings").toMap()
            .mapKeys { BottleTagId(it.key) }.mapValues { SampleId(it.value as String) }
        val compositeSamples = o.optJSONArray("compositeSamples")?.items()
            ?.associate { val s = dSample(it as JSONObject); s.id to s } ?: emptyMap()
        val declarations = o.getJSONArray("declarations").items()
            .map { dDeclaration(it as JSONObject) }
        val overrides = o.optJSONArray("overrides")?.items()
            ?.map { dOverride(it as JSONObject) } ?: emptyList()
        val audit = o.getJSONArray("audit").items().mapIndexed { i, v ->
            dAudit(v as JSONObject, (i + 1).toLong())
        }
        val sampleSources = o.getJSONObject("sampleSources").toMap()
            .mapValues { (it.value as String).split(",").filter(String::isNotBlank)
                .map(::CompartmentCode) }
            .mapKeys { SampleId(it.key) }
        return TripMemento(
            TripId(o.getString("tripId")), TruckId(o.getString("truckId")),
            compartments, config, sealBindings, bottleBindings, compositeSamples,
            declarations, overrides, audit, sampleSources,
        )
    }

    // ---- encode helpers ----
    private fun jConfig(c: ReceivingPolicyConfig) = JSONObject().apply {
        put("tempWarn", c.tempWarnCelsius); put("tempReject", c.tempRejectCelsius)
        put("tol", c.probeVsLogToleranceCelsius); put("gapSec", c.maxAllowedLogGap.seconds)
        put("stirSec", c.minStirringSeconds)
        put("depthMin", c.sampleDepthMinCm); put("depthMax", c.sampleDepthMaxCm)
        put("weightMin", c.minSampleWeightG)
        put("scaleDevice", c.designatedScaleDeviceId)
        put("farms", JSONArray(c.allowedFarms?.map { it.value } ?: emptyList<String>()))
    }

    private fun jCompartment(c: Compartment) = JSONObject().apply {
        put("code", c.code.value); put("farm", c.farm.value)
        put("batch", c.farmBatch.value); put("loadedAt", c.loadedAt.toString())
        put("status", c.status.name)
        c.seal?.let { put("seal", jSeal(it)) }
        put("secondary", JSONArray(c.secondaryLoads.map { sl -> JSONObject().apply {
            put("farm", sl.farm.value); put("batch", sl.farmBatch.value)
            put("at", sl.loadedAt.toString()); put("evidence", sl.evidence)
        } }))
        c.probe?.let { put("probe", JSONObject().apply {
            put("c", it.celsius); put("stable", it.stable)
            put("at", it.at.toString()); put("dev", it.deviceId)
        }) }
        c.truckLog?.let { put("log", jTruckLog(it)) }
        c.stirringConfirmedAt?.let { put("stirAt", it.toString()) }
        c.stirringSeconds?.let { put("stirSec", it) }
        put("samples", JSONArray(c.samples.map { jSample(it) }))
        put("lab", JSONArray(c.labResults.map { (sid, r) -> jLab(sid, r) }))
        c.unloadStartedAt?.let { put("unloadStart", it.toString()) }
        c.unloadFinishedAt?.let { put("unloadEnd", it.toString()) }
        c.unloadGroupId?.let { put("group", it) }
        c.sensory?.let { put("sensory", JSONObject().apply {
            put("normal", it.normal); put("note", it.note)
            put("by", it.checkedBy.value); put("at", it.at.toString())
        }) }
    }

    private fun jSeal(s: SealCheck) = JSONObject().apply {
        put("expected", s.expected.value)
        s.nfc?.let { put("nfc", it.value) }
        s.nfcTag?.let { put("nfcTag", it.value) }
        s.written?.let { put("written", it.value) }
        put("illegible", s.writtenIllegible)
        put("by", s.checkedBy.value); put("at", s.checkedAt.toString())
    }

    private fun jTruckLog(l: TruckTemperatureLog) = JSONObject().apply {
        put("loadedAt", l.loadedAt.toString()); put("arrivedAt", l.arrivedAt.toString())
        put("intervals", JSONArray(l.intervals.map {
            JSONArray(listOf(it.start.toString(), it.endInclusive.toString()))
        }))
        put("max", l.maxRecordedCelsius)
    }

    private fun jSample(s: Sample) = JSONObject().apply {
        put("id", s.id.value); put("bottle", s.bottleTag.value); put("kind", s.kind.name)
        put("sources", JSONArray(s.sourceCompartments.map { it.value }))
        s.depthCm?.let { put("depth", it) }
        s.stirringSeconds?.let { put("stirSec", it) }
        s.weight?.let { w -> put("weight", JSONObject().apply {
            put("g", w.grams); put("stable", w.stable)
            put("dev", w.deviceId); put("at", w.at.toString())
        }) }
        put("at", s.takenAt.toString()); put("by", s.takenBy.value)
    }

    private fun jLab(sid: SampleId, r: LabResult) = JSONObject().apply {
        put("sample", sid.value)
        r.fatPct?.let { put("fat", it) }; r.proteinPct?.let { put("protein", it) }
        r.densityKgM3?.let { put("density", it) }
        r.freezingPointC?.let { put("fp", it) }; r.acidityT?.let { put("acidity", it) }
        put("abx", r.antibiotic.name); put("adult", r.adulteration.name)
        put("at", r.publishedAt.toString())
    }

    private fun jSealBinding(b: ExpectedSealBinding) = JSONObject().apply {
        put("comp", b.compartment.value); put("seal", b.seal.value)
        put("farm", b.farm.value); put("batch", b.farmBatch.value)
        put("loadedAt", b.loadedAt.toString())
        b.nfcTag?.let { put("tag", it.value) }
    }

    private fun jDeclaration(d: UnloadDeclaration) = JSONObject().apply {
        put("group", d.groupId)
        put("comps", JSONArray(d.compartments.map { it.value }))
        put("boundary", d.boundary.name); put("tank", d.targetTank.value)
        d.compositeSample?.let { put("composite", it.value) }
        put("by", d.declaredBy.value); put("at", d.declaredAt.toString())
    }

    private fun jOverride(o: SupervisorOverride) = JSONObject().apply {
        put("sup", o.supervisor.value); put("reason", o.reason)
        put("at", o.at.toString())
        put("scope", JSONObject().apply {
            o.scope.forEach { (comp, codes) ->
                put(comp?.value ?: "*", JSONArray(codes.map { it.name }))
            }
        })
    }

    private fun jAudit(a: AuditEvent) = JSONObject().apply {
        put("seq", a.seq); put("at", a.at.toString())
        a.actor?.let { put("actor", it.value) }
        put("action", a.action); put("detail", a.detail)
    }

    private fun jMap(pairs: List<Pair<String, String>>) = JSONObject().apply {
        pairs.forEach { put(it.first, it.second) }
    }

    // ---- decode helpers ----
    private fun dConfig(o: JSONObject) = ReceivingPolicyConfig(
        tempWarnCelsius = o.getDouble("tempWarn"),
        tempRejectCelsius = o.getDouble("tempReject"),
        probeVsLogToleranceCelsius = o.getDouble("tol"),
        maxAllowedLogGap = Duration.ofSeconds(o.getLong("gapSec")),
        minStirringSeconds = o.getInt("stirSec"),
        sampleDepthMinCm = o.getDouble("depthMin"),
        sampleDepthMaxCm = o.getDouble("depthMax"),
        minSampleWeightG = o.getDouble("weightMin"),
        designatedScaleDeviceId = o.optString("scaleDevice")
            .ifBlank { DEFAULT_SCALE_DEVICE_ID },
        allowedFarms = o.getJSONArray("farms").items()
            .map { FarmId(it as String) }.toSet().ifEmpty { null },
    )

    private fun dSeal(o: JSONObject) = SealCheck(
        expected = SealId(o.getString("expected")),
        nfc = o.optString("nfc").ifBlank { null }?.let(::SealId),
        nfcTag = o.optString("nfcTag").ifBlank { null }?.let(::NfcTagId),
        written = o.optString("written").ifBlank { null }?.let(::SealId),
        writtenIllegible = o.getBoolean("illegible"),
        checkedBy = OperatorId(o.getString("by")),
        checkedAt = Instant.parse(o.getString("at")),
    )

    private fun dTruckLog(o: JSONObject) = TruckTemperatureLog(
        loadedAt = Instant.parse(o.getString("loadedAt")),
        arrivedAt = Instant.parse(o.getString("arrivedAt")),
        intervals = o.getJSONArray("intervals").items().map {
            val a = it as JSONArray
            Instant.parse(a.getString(0))..Instant.parse(a.getString(1))
        },
        maxRecordedCelsius = o.getDouble("max"),
    )

    private fun dSample(o: JSONObject) = Sample(
        id = SampleId(o.getString("id")),
        bottleTag = BottleTagId(o.getString("bottle")),
        kind = SampleKind.valueOf(o.getString("kind")),
        sourceCompartments = o.getJSONArray("sources").items()
            .map { CompartmentCode(it as String) },
        depthCm = if (o.has("depth")) o.getDouble("depth") else null,
        stirringSeconds = if (o.has("stirSec")) o.getInt("stirSec") else null,
        weight = when {
            // 新格式：重量链对象（克重/稳定/设备/时刻）
            o.optJSONObject("weight") != null -> o.getJSONObject("weight").let { w ->
                WeightReading(
                    grams = w.getDouble("g"),
                    stable = w.getBoolean("stable"),
                    at = if (w.has("at")) Instant.parse(w.getString("at"))
                         else Instant.parse(o.getString("at")),
                    deviceId = if (w.has("dev")) w.getString("dev") else "unknown-device",
                )
            }
            // 兼容旧快照：手工克重数字 + 布尔稳定标志。旧数据无法证明设备身份，
            // 设备标为 "legacy-manual"，规则会判 SAMPLE_WEIGHT_WRONG_DEVICE（留痕阻断）。
            o.has("weight") && !o.isNull("weight") -> WeightReading(
                grams = o.getDouble("weight"),
                stable = o.optBoolean("weightStable", false),
                at = Instant.parse(o.getString("at")),
                deviceId = "legacy-manual",
            )
            else -> null
        },
        takenAt = Instant.parse(o.getString("at")),
        takenBy = OperatorId(o.getString("by")),
    )

    private fun dLab(o: JSONObject) =
        SampleId(o.getString("sample")) to LabResult(
            sampleId = SampleId(o.getString("sample")),
            fatPct = if (o.has("fat")) o.getDouble("fat") else null,
            proteinPct = if (o.has("protein")) o.getDouble("protein") else null,
            densityKgM3 = if (o.has("density")) o.getDouble("density") else null,
            freezingPointC = if (o.has("fp")) o.getDouble("fp") else null,
            acidityT = if (o.has("acidity")) o.getDouble("acidity") else null,
            antibiotic = ScreenAssay.valueOf(o.getString("abx")),
            adulteration = ScreenAssay.valueOf(o.getString("adult")),
            publishedAt = Instant.parse(o.getString("at")),
        )

    private fun dCompartment(o: JSONObject): Compartment = Compartment(
        code = CompartmentCode(o.getString("code")),
        farm = FarmId(o.getString("farm")),
        farmBatch = FarmBatchId(o.getString("batch")),
        loadedAt = Instant.parse(o.getString("loadedAt")),
        status = CompartmentStatus.valueOf(o.getString("status")),
        seal = if (o.has("seal")) dSeal(o.getJSONObject("seal")) else null,
        secondaryLoads = o.getJSONArray("secondary").items().map {
            val x = it as JSONObject
            SecondaryLoad(FarmId(x.getString("farm")), FarmBatchId(x.getString("batch")),
                Instant.parse(x.getString("at")), x.getString("evidence"))
        },
        probe = o.optJSONObject("probe")?.let { p ->
            ProbeReading(p.getDouble("c"), p.getBoolean("stable"),
                Instant.parse(p.getString("at")), p.getString("dev"))
        },
        truckLog = if (o.has("log")) dTruckLog(o.getJSONObject("log")) else null,
        stirringConfirmedAt = if (o.has("stirAt")) Instant.parse(o.getString("stirAt")) else null,
        stirringSeconds = if (o.has("stirSec")) o.getInt("stirSec") else null,
        samples = o.getJSONArray("samples").items().map { dSample(it as JSONObject) },
        labResults = o.getJSONArray("lab").items().associate { dLab(it as JSONObject) },
        unloadStartedAt = if (o.has("unloadStart")) Instant.parse(o.getString("unloadStart")) else null,
        unloadFinishedAt = if (o.has("unloadEnd")) Instant.parse(o.getString("unloadEnd")) else null,
        unloadGroupId = o.optString("group").ifBlank { null },
        sensory = o.optJSONObject("sensory")?.let { s ->
            SensoryCheck(s.getBoolean("normal"), s.getString("note"),
                OperatorId(s.getString("by")), Instant.parse(s.getString("at")))
        },
    )

    private fun dSealBinding(o: JSONObject) = ExpectedSealBinding(
        compartment = CompartmentCode(o.getString("comp")),
        seal = SealId(o.getString("seal")),
        farm = FarmId(o.getString("farm")),
        farmBatch = FarmBatchId(o.getString("batch")),
        loadedAt = Instant.parse(o.getString("loadedAt")),
        nfcTag = o.optString("tag").ifBlank { null }?.let(::NfcTagId),
    )

    private fun dDeclaration(o: JSONObject) = UnloadDeclaration(
        groupId = o.getString("group"),
        compartments = o.getJSONArray("comps").items().map { CompartmentCode(it as String) },
        boundary = UnloadBoundary.valueOf(o.getString("boundary")),
        targetTank = TankId(o.getString("tank")),
        compositeSample = o.optString("composite").ifBlank { null }?.let(::SampleId),
        declaredBy = OperatorId(o.getString("by")),
        declaredAt = Instant.parse(o.getString("at")),
    )

    private fun dOverride(o: JSONObject) = SupervisorOverride(
        supervisor = OperatorId(o.getString("sup")),
        reason = o.getString("reason"),
        at = Instant.parse(o.getString("at")),
        scope = run {
            val scopeObj = o.getJSONObject("scope")
            scopeObj.keys().asSequence().associate { k ->
                val key: CompartmentCode? = if (k == "*") null else CompartmentCode(k)
                val codes = scopeObj.getJSONArray(k).items()
                    .map { FindingCode.valueOf(it as String) }.toSet()
                key to codes
            }
        },
    )

    private fun dAudit(o: JSONObject, fallbackSeq: Long) = AuditEvent(
        seq = o.optLong("seq", fallbackSeq),
        at = Instant.parse(o.getString("at")),
        actor = o.optString("actor").ifBlank { null }?.let(::OperatorId),
        action = o.getString("action"),
        detail = o.getString("detail"),
    )
}
