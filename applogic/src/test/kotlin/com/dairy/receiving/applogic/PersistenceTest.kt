package com.dairy.receiving.applogic

import com.dairy.receiving.applogic.persist.InMemoryTripStore
import com.dairy.receiving.applogic.persist.MementoCodec
import com.dairy.receiving.applogic.persist.restore
import com.dairy.receiving.core.demo.DemoScenarios
import com.dairy.receiving.core.model.FindingCode
import com.dairy.receiving.core.model.Recommendation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PersistenceTest {

    /** 全部五个异常场景：保存->JSON->恢复后，缺陷与建议必须完全一致。 */
    @Test
    fun `五场景离线快照往返后判定一致`() {
        val store = InMemoryTripStore()
        for ((name, factory) in DemoScenarios.all()) {
            val wf = factory()
            val before = wf.allFindings().map { it.compartment?.value to it.code }.sortedBy { "${it.first}#${it.second}" }
            val recBefore = wf.compartments.keys.associateWith { wf.recommendation(it) }

            store.save(wf.snapshot())
            val json = MementoCodec.encode(wf.snapshot())
            val decoded = MementoCodec.decode(json)
            store.save(decoded)

            val restored = store.restore(wf.tripId.value) ?: fail("恢复失败: $name")
            val after = restored.allFindings().map { it.compartment?.value to it.code }
                .sortedBy { "${it.first}#${it.second}" }
            val recAfter = restored.compartments.keys.associateWith { restored.recommendation(it) }

            assertEquals(before, after, "场景[$name]缺陷集合在持久化往返后变化")
            assertEquals(recBefore, recAfter, "场景[$name]建议在持久化往返后变化")
            assertEquals(wf.events.size, restored.events.size, "场景[$name]审计链丢失")
            assertEquals(wf.unloadDeclarations.size, restored.unloadDeclarations.size)
            assertEquals(wf.supervisorOverrides.size, restored.supervisorOverrides.size)
        }
    }

    @Test
    fun `恢复后的工作流仍可继续推进`() {
        val wf = DemoScenarios.truckLogGap()
        val store = InMemoryTripStore()
        store.save(wf.snapshot())
        val restored = store.restore(wf.tripId.value)!!
        val code = restored.compartments.keys.single()
        assertTrue(restored.findingsFor(code).any { it.code == FindingCode.TEMP_LOG_GAP })
        assertEquals(Recommendation.ACCEPT_WITH_NOTE, restored.recommendation(code))
        // 恢复后续操作，审计序号连续
        val seqBefore = restored.events.last().seq
        // 开阀硬前置：先声明独立卸奶边界，再登记人工开阀
        restored.declareUnload(com.dairy.receiving.core.model.UnloadDeclaration(
            "g-2", listOf(code), com.dairy.receiving.core.model.UnloadBoundary.SEPARATE,
            com.dairy.receiving.core.model.TankId("T-1"), null,
            com.dairy.receiving.core.model.OperatorId("recv-07"),
            java.time.Instant.now(java.time.Clock.systemUTC())))
        restored.confirmValveOpened(code, com.dairy.receiving.core.model.OperatorId("recv-07"))
        assertTrue(restored.events.last().seq > seqBefore)
    }
}
