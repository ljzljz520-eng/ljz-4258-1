package com.dairy.receiving.core.model

/**
 * 时序/身份/代表性三类缺陷码。blocking=true 表示未决前不允许进入卸奶；
 * 主管可双签覆核（记录留痕），但系统自身从不放行或开阀。
 */
enum class FindingCode(
    val severity: FindingSeverity,
    val blocking: Boolean,
    val category: FindingCategory,
    val defaultMessage: String,
) {
    // —— 封签 / 身份 ——
    SEAL_ILLEGIBLE(FindingSeverity.WARNING, false, FindingCategory.IDENTITY,
        "封签号手写不清，须以 NFC 读签为准复核"),
    SEAL_TEXT_MISMATCH(FindingSeverity.WARNING, false, FindingCategory.IDENTITY,
        "手抄封签号与 NFC 签号不一致"),
    SEAL_MISMATCH(FindingSeverity.CRITICAL, true, FindingCategory.IDENTITY,
        "封签号与牧场装车记录不符，疑似换签/拆封"),
    SEAL_UNVERIFIED(FindingSeverity.CRITICAL, true, FindingCategory.IDENTITY,
        "封签无法核验（无 NFC 读签且手填不可辨认）"),
    FARM_MISMATCH(FindingSeverity.CRITICAL, true, FindingCategory.IDENTITY,
        "罐口标签牧场码/牧场批与预报不符"),
    RELOAD_DETECTED(FindingSeverity.CRITICAL, true, FindingCategory.IDENTITY,
        "检出途中二次装载：同一仓室存在两个牧场/批次的装车记录"),

    // —— 温度 / 时序 ——
    TEMP_UNSTABLE(FindingSeverity.WARNING, false, FindingCategory.TIMING,
        "BLE 温度计读数未稳定，不可作为判定值"),
    TEMP_HIGH_WARN(FindingSeverity.WARNING, false, FindingCategory.TIMING,
        "奶温高于预警线"),
    TEMP_HIGH_REJECT(FindingSeverity.CRITICAL, true, FindingCategory.TIMING,
        "奶温超过拒收线"),
    TEMP_LOG_GAP(FindingSeverity.WARNING, false, FindingCategory.TIMING,
        "车载温度记录缺段，途中冷链不可追溯"),
    TEMP_DIVERGENCE(FindingSeverity.WARNING, false, FindingCategory.TIMING,
        "探针实测与车载温度记录偏差超限"),
    TEMP_MISSING(FindingSeverity.WARNING, false, FindingCategory.TIMING,
        "卸前缺少稳定的探针温度记录"),

    // —— 搅拌 / 取样代表性 ——
    STIRRING_INSUFFICIENT(FindingSeverity.WARNING, false, FindingCategory.REPRESENTATIVENESS,
        "搅拌时间不足，不满足取样条件"),
    SAMPLE_DEPTH_INVALID(FindingSeverity.WARNING, false, FindingCategory.REPRESENTATIVENESS,
        "取样深度不在规定液位区间"),
    SAMPLE_WEIGHT_UNSTABLE(FindingSeverity.WARNING, false, FindingCategory.REPRESENTATIVENESS,
        "采样秤未稳定即取样"),
    SAMPLE_WEIGHT_MISSING(FindingSeverity.CRITICAL, true, FindingCategory.REPRESENTATIVENESS,
        "样品重量链缺失：未取得指定 BLE 采样秤读数"),
    SAMPLE_WEIGHT_WRONG_DEVICE(FindingSeverity.CRITICAL, true, FindingCategory.REPRESENTATIVENESS,
        "样品重量不是指定 BLE 采样秤产生，重量链不予承认"),
    SAMPLE_LABEL_MISMATCH(FindingSeverity.CRITICAL, true, FindingCategory.REPRESENTATIVENESS,
        "样瓶标签与本仓不符（混样贴错瓶）"),
    SAMPLE_AFTER_UNLOAD(FindingSeverity.CRITICAL, true, FindingCategory.REPRESENTATIVENESS,
        "先卸后取样：样品不具代表性"),
    SAMPLE_MISSING(FindingSeverity.CRITICAL, true, FindingCategory.REPRESENTATIVENESS,
        "卸前缺少本仓代表性个体样"),
    SCREENING_PENDING(FindingSeverity.WARNING, false, FindingCategory.TIMING,
        "快速筛查结果尚未发布"),

    // —— 混合卸载边界 ——
    COMPOSITE_TRACEABILITY_LOST(FindingSeverity.CRITICAL, true, FindingCategory.IDENTITY,
        "混合卸载将使异常仓失去身份：组分仓未全部留存个体样或存在未决异常"),

    // —— 实验室结果 ——
    ANTIBIOTIC_POSITIVE(FindingSeverity.CRITICAL, true, FindingCategory.IDENTITY,
        "抗生素/抑制物筛查阳性"),
    SUSPECT_ADULTERATION(FindingSeverity.CRITICAL, true, FindingCategory.IDENTITY,
        "冰点/密度异常，疑似掺水掺假"),
    PHYS_ABNORMAL(FindingSeverity.WARNING, false, FindingCategory.IDENTITY,
        "理化指标异常"),

    // —— 卸奶管线残留见证 ——
    PIPELINE_WITNESS_MISSING(FindingSeverity.CRITICAL, true, FindingCategory.PIPELINE,
        "卸奶管线连接未见证：缺清洁验收/连接时刻/前段冲洗液去向登记"),
    PIPELINE_CLEANING_EXPIRED(FindingSeverity.CRITICAL, true, FindingCategory.PIPELINE,
        "管线清洁状态过期：连接时刻晚于清洁验收有效期，首仓承担残留风险"),
    PIPELINE_FLUSH_TO_MILK(FindingSeverity.CRITICAL, true, FindingCategory.PIPELINE,
        "前段冲洗液冲入原奶罐：首仓被冲洗液污染"),
    PIPELINE_FLUSH_DESTINATION_UNKNOWN(FindingSeverity.WARNING, false, FindingCategory.PIPELINE,
        "前段冲洗液去向不明，无法排除进入原奶"),
    PIPELINE_FLUSH_BACKFLOW(FindingSeverity.CRITICAL, true, FindingCategory.PIPELINE,
        "冲洗液回流：前段冲洗液倒灌回槽车首仓"),
    PIPELINE_SHARED_MANIFOLD(FindingSeverity.WARNING, false, FindingCategory.PIPELINE,
        "与他车共用卸奶歧管：首仓接触前车管线滞留奶"),
    PIPELINE_HOSE_UNVERIFIED(FindingSeverity.CRITICAL, true, FindingCategory.PIPELINE,
        "临时更换的软管清洁未核验：暴露仓接触未核验软管"),
    PIPELINE_RESIDUAL_FIRST(FindingSeverity.INFO, false, FindingCategory.PIPELINE,
        "首仓保留管线前段残留影响（残留不在组分仓间分摊）"),
    PIPELINE_RESIDUAL_LAST(FindingSeverity.INFO, false, FindingCategory.PIPELINE,
        "末仓奶卸后滞留管线，身份保留至下一连接见证"),
    PIPELINE_HOSE_SWAPPED(FindingSeverity.INFO, false, FindingCategory.PIPELINE,
        "卸奶中途临时更换软管（清洁已核验），暴露仓留痕"),
    PARTIAL_UNLOAD(FindingSeverity.WARNING, false, FindingCategory.PIPELINE,
        "仓室只卸一部分即关阀：余奶身份保留在本仓"),
}

enum class FindingCategory { IDENTITY, TIMING, REPRESENTATIVENESS, PIPELINE }

data class Finding(
    val code: FindingCode,
    val compartment: CompartmentCode?,
    val detail: String,
    val detectedAt: TimePoint,
    val overriddenBy: SupervisorOverride? = null,
) {
    val open: Boolean get() = overriddenBy == null
}

data class SupervisorOverride(
    val supervisor: OperatorId,
    val reason: String,
    val at: TimePoint,
    /** 覆核范围：仓室(可空=整车级) -> 缺陷码集合 */
    val scope: Map<CompartmentCode?, Set<FindingCode>>,
) {
    val findingCodes: Set<FindingCode> get() = scope.values.flatten().toSet()
    fun covers(compartment: CompartmentCode?, code: FindingCode): Boolean =
        scope[compartment]?.contains(code) == true || scope[null]?.contains(code) == true
}
