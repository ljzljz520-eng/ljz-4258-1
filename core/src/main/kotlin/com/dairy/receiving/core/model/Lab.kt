package com.dairy.receiving.core.model

/** 实验室理化 + 筛查结果，按样品发布；个体样结果回指具体仓室。 */
data class LabResult(
    val sampleId: SampleId,
    val fatPct: Double?,
    val proteinPct: Double?,
    val densityKgM3: Double?,
    val freezingPointC: Double?,
    val acidityT: Double?,
    val antibiotic: ScreenAssay,
    val adulteration: ScreenAssay,
    val publishedAt: TimePoint,
) {
    companion object {
        // 常见收奶限值（可按工厂配置覆盖）
        const val FAT_MIN = 3.1
        const val PROTEIN_MIN = 2.9
        const val DENSITY_MIN = 1027.0
        const val DENSITY_MAX = 1033.0
        // 掺水倾向：冰点高于 -0.500℃（接近 0）
        const val FREEZING_HIGH = -0.500
        const val FREEZING_LOW = -0.570
        const val ACIDITY_MIN = 12.0
        const val ACIDITY_MAX = 18.0
    }
}
