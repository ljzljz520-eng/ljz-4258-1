package com.dairy.receiving.core.workflow

import com.dairy.receiving.core.model.*

/**
 * 工作流完整快照（离线持久化单元）。Room 在 Android 侧把它拆成关系行；
 * 恢复后规则引擎可重放，历史审计、覆核、混卸声明原样保留。
 */
data class TripMemento(
    val tripId: TripId,
    val truckId: TruckId,
    val compartments: List<Compartment>,
    val config: ReceivingPolicyConfig,
    val sealBindings: List<ExpectedSealBinding>,
    val bottleBindings: Map<BottleTagId, SampleId>,
    val compositeSamples: Map<SampleId, Sample>,
    val declarations: List<UnloadDeclaration>,
    val overrides: List<SupervisorOverride>,
    val audit: List<AuditEvent>,
    val sampleSources: Map<SampleId, List<CompartmentCode>>,
)
