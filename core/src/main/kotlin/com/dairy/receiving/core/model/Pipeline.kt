package com.dairy.receiving.core.model

/**
 * 卸奶管线残留见证的领域模型。
 *
 * 一辆车的一个卸奶组经一条软管接入一条管线/歧管：系统见证**清洁验收、
 * 连接时刻、前段冲洗液去向**三件事，并把管线影响只归属到**首仓**（接触
 * 管线前段残留的奶）与**末仓**（卸后滞留管线的奶）——绝不在组分仓之间
 * 平均分摊残留。
 */

/** 前段冲洗液去向。 */
enum class FlushDestination {
    RECLAIM,     // 单独回收（回收罐/回收线），未进入原奶
    WASTE,       // 废弃排放
    MILK_TANK,   // 冲入原奶罐 —— 残留进入产品，首仓被污染
    UNKNOWN,     // 去向不明，无法排除进入原奶
}

/** 管线清洁验收：连接管线前对清洁状态的核验，有有效期。 */
data class CleaningAcceptance(
    val pipeline: PipelineId,
    val method: String,            // CIP / 热水 / 酸碱循环等
    val acceptedBy: OperatorId,
    val acceptedAt: TimePoint,
    /** 清洁状态有效期：连接时刻晚于它即“清洁状态过期” */
    val validUntil: TimePoint,
)

/** 前段冲洗液去向登记（连接时刻一并见证）。 */
data class FlushRecord(
    val destination: FlushDestination,
    val volumeLiters: Double?,
    /** 冲洗液回流迹象：前段冲洗液倒灌回槽车首仓 */
    val backflowSuspected: Boolean,
    val recordedBy: OperatorId,
    val recordedAt: TimePoint,
)

/**
 * 车辆-管线连接见证。不可变留痕；同一组重复连接时以最新一条为准，
 * 历史连接全部保留在审计链中。
 *
 * @param compartments 计划卸奶顺序（= 卸奶边界声明的组分顺序），首仓 = first
 * @param sharedWithTruck 共用同一歧管的另一辆车（null = 独占管线）
 */
data class PipelineConnection(
    val groupId: String,
    val pipeline: PipelineId,
    val hose: HoseId,
    val compartments: List<CompartmentCode>,
    val cleaning: CleaningAcceptance,
    val flush: FlushRecord,
    val connectedBy: OperatorId,
    val connectedAt: TimePoint,
    val sharedWithTruck: TruckId? = null,
    val sharedWithTrip: TripId? = null,
)

/**
 * 软管临时更换留痕。更换后第一股奶接触的仓 = 暴露仓：
 * 更换时刻正在卸的仓，否则该连接的首仓。
 */
data class HoseSwap(
    val groupId: String,
    val oldHose: HoseId?,
    val newHose: HoseId,
    /** 更换软管的清洁是否已核验；未核验即使用 => 阻断暴露仓 */
    val cleanedVerified: Boolean,
    val reason: String,
    val swappedBy: OperatorId,
    val swappedAt: TimePoint,
)

/**
 * 管线残留归属（由规则引擎推导，不持久化）：
 * 首仓承担前段残留，末仓的奶卸后滞留管线；两者可同为一只仓
 * （例如首仓只卸一部分即停卸）。中间仓不受管线影响。
 */
data class PipelineExposure(
    val groupId: String,
    val pipeline: PipelineId,
    val firstCompartment: CompartmentCode,
    /** 已开始卸奶的仓中最后开卸者；尚未开卸时为 null */
    val lastCompartment: CompartmentCode?,
)
