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
}

enum class FindingCategory { IDENTITY, TIMING, REPRESENTATIVENESS }

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
