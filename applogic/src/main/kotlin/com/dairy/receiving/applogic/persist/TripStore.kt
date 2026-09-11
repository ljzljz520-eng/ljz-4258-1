package com.dairy.receiving.applogic.persist

import com.dairy.receiving.core.workflow.TripMemento
import com.dairy.receiving.core.workflow.TripWorkflow
import java.time.Clock

/**
 * 离线单车存储端口。Android 实现为 Room（行表）+ JSON 列；
 * 测试/桌面实现可用内存或文件。断网期间收奶流程不阻塞。
 */
interface TripStore {
    fun save(memento: TripMemento)
    fun load(tripId: String): TripMemento?
    fun listOpenTrips(): List<String>
    fun delete(tripId: String)
}

/** 恢复工作流（规则引擎可在离线数据上重放）。 */
fun TripStore.restore(tripId: String, clock: Clock = Clock.systemUTC()): TripWorkflow? =
    load(tripId)?.let { TripWorkflow(it, clock) }
