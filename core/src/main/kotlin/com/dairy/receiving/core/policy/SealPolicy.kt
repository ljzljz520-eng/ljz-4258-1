package com.dairy.receiving.core.policy

import com.dairy.receiving.core.model.*
import java.time.Instant

/**
 * 封签核对规则。权威顺序：NFC 读签 > 手写件。
 *  - 无 NFC 且手写不清/缺失 => 无法核验（阻断）
 *  - NFC 与装车单不符 => 换签嫌疑（阻断）
 *  - NFC 标签上的牧场/批次与预报不符 => 身份不符（阻断）
 *  - 手写不清但 NFC 通过 => 警告留痕，不阻断
 *  - 手抄与 NFC 不一致 => 警告
 */
object SealPolicy {

    fun evaluate(
        compartment: Compartment,
        binding: ExpectedSealBinding?,
        now: Instant,
    ): List<Finding> {
        val seal = compartment.seal ?: return emptyList()
        val out = mutableListOf<Finding>()
        fun f(code: FindingCode, detail: String) =
            Finding(code, compartment.code, detail, now)

        // 罐口标签的牧场身份（NFC NDEF 内容）与预报核对
        if (binding != null) {
            if (binding.farm != compartment.farm || binding.farmBatch != compartment.farmBatch) {
                out += f(FindingCode.FARM_MISMATCH,
                    "仓${compartment.code.value}: 标签 ${binding.farm.value}/${binding.farmBatch.value} " +
                        "!= 预报 ${compartment.farm.value}/${compartment.farmBatch.value}")
            }
        }

        val nfc = seal.nfc
        val written = seal.written

        when {
            nfc == null && (written == null || seal.writtenIllegible) ->
                out += f(FindingCode.SEAL_UNVERIFIED,
                    "仓${compartment.code.value}: 无 NFC 读签且手写封签不可辨认/缺失")
            nfc == null && written != null ->
                // 有手写但未读 NFC：仍可与装车单核对，但等级降为警告级证据
                if (written != seal.expected) {
                    out += f(FindingCode.SEAL_MISMATCH,
                        "仓${compartment.code.value}: 手抄封签 ${written.value} != 装车单 ${seal.expected.value}（无 NFC 佐证）")
                } else if (seal.writtenIllegible) {
                    out += f(FindingCode.SEAL_ILLEGIBLE, "仓${compartment.code.value}: 手填字迹不清")
                }
            nfc != null -> {
                if (nfc != seal.expected) {
                    out += f(FindingCode.SEAL_MISMATCH,
                        "仓${compartment.code.value}: NFC 封签 ${nfc.value} != 装车单 ${seal.expected.value}")
                }
                if (written != null && written != nfc) {
                    out += f(FindingCode.SEAL_TEXT_MISMATCH,
                        "仓${compartment.code.value}: 手抄 ${written.value} != NFC ${nfc.value}")
                }
                if (seal.writtenIllegible) {
                    out += f(FindingCode.SEAL_ILLEGIBLE,
                        "仓${compartment.code.value}: 手填不清，已按 NFC ${nfc.value} 复核")
                }
            }
        }
        return out
    }
}
