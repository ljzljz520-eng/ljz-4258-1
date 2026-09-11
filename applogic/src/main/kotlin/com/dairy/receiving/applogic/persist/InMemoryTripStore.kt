package com.dairy.receiving.applogic.persist

import com.dairy.receiving.core.workflow.TripMemento

/** 测试与离线预览用的内存存储。 */
class InMemoryTripStore : TripStore {
    private val map = linkedMapOf<String, TripMemento>()
    override fun save(memento: TripMemento) { map[memento.tripId.value] = memento }
    override fun load(tripId: String) = map[tripId]
    override fun listOpenTrips() = map.keys.toList()
    override fun delete(tripId: String) { map.remove(tripId) }
}
