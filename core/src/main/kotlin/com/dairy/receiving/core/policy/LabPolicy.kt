package com.dairy.receiving.core.policy

import com.dairy.receiving.core.model.*
import java.time.Instant

/** 实验室结果评估：抗生素阳性/掺假为阻断，理化异常为警告。结果按个体样回指仓。 */
object LabPolicy {

    fun evaluate(c: Compartment, now: Instant): List<Finding> {
        val out = mutableListOf<Finding>()
        for ((sampleId, r) in c.labResults) {
            val tag = "仓${c.code.value} 样${sampleId.value}"
            if (r.antibiotic == ScreenAssay.POSITIVE) {
                out += Finding(FindingCode.ANTIBIOTIC_POSITIVE, c.code,
                    "$tag: 抗生素/抑制物筛查阳性", now)
            }
            if (r.adulteration == ScreenAssay.POSITIVE) {
                out += Finding(FindingCode.SUSPECT_ADULTERATION, c.code,
                    "$tag: 掺假筛查阳性", now)
            }
            val phys = mutableListOf<String>()
            r.fatPct?.let { if (it < LabResult.FAT_MIN) phys += "脂肪 ${it}%" }
            r.proteinPct?.let { if (it < LabResult.PROTEIN_MIN) phys += "蛋白 ${it}%" }
            r.densityKgM3?.let {
                if (it !in LabResult.DENSITY_MIN..LabResult.DENSITY_MAX) phys += "密度 ${it}"
            }
            r.freezingPointC?.let { fp ->
                if (fp > LabResult.FREEZING_HIGH) {
                    out += Finding(FindingCode.SUSPECT_ADULTERATION, c.code,
                        "$tag: 冰点 ${fp}℃ 偏高，疑似掺水", now)
                } else if (fp < LabResult.FREEZING_LOW) {
                    phys += "冰点 ${fp}℃"
                }
            }
            r.acidityT?.let {
                if (it !in LabResult.ACIDITY_MIN..LabResult.ACIDITY_MAX) phys += "酸度 ${it}°T"
            }
            if (phys.isNotEmpty()) {
                out += Finding(FindingCode.PHYS_ABNORMAL, c.code,
                    "$tag: ${phys.joinToString("、")}", now)
            }
        }
        return out
    }
}
