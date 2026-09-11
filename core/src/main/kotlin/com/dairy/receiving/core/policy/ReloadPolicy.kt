package com.dairy.receiving.core.policy

import com.dairy.receiving.core.model.*
import java.time.Instant

/**
 * 中途补装：同一仓室出现第二个牧场/批（司机自报、封签二次记录、
 * 车载 GPS 停靠 + 装车时刻异常等证据）。无论是否混在同一仓，都必须挂起，
 * 因为“预报牧场批”身份已被污染，绝不能随混卸进入大罐。
 */
object ReloadPolicy {
    fun evaluate(c: Compartment, now: Instant): List<Finding> =
        c.secondaryLoads.map { sl ->
            Finding(
                FindingCode.RELOAD_DETECTED, c.code,
                "仓${c.code.value}: 检出途中补装 ${sl.farm.value}/${sl.farmBatch.value} " +
                    "@${sl.loadedAt}（证据: ${sl.evidence}），主批 " +
                    "${c.farm.value}/${c.farmBatch.value}",
                now,
            )
        }
}
