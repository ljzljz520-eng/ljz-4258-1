package com.dairy.receiving.app.data

import com.dairy.receiving.app.data.local.AppDatabase
import com.dairy.receiving.app.data.local.CompartmentEntity
import com.dairy.receiving.app.data.local.SampleEntity
import com.dairy.receiving.app.data.local.SealEntity
import com.dairy.receiving.app.data.local.TripEntity
import com.dairy.receiving.applogic.persist.MementoCodec
import com.dairy.receiving.applogic.persist.TripStore
import com.dairy.receiving.core.model.SampleKind
import com.dairy.receiving.core.workflow.TripMemento

/**
 * 离线整车存储：完整快照 JSON 保证无损恢复（规则引擎重放一致），
 * 关系行表供收奶区设备列表/检索与逐仓页面直接查询。
 */
class RoomTripStore(private val db: AppDatabase) : TripStore {

    override fun save(memento: TripMemento) {
        // 阻塞式适配器；调用方位于协程/后台线程。整体快照由 MementoCodec 保证一致。
        val json = MementoCodec.encode(memento)
        kotlinx.coroutines.runBlocking {
            db.replaceTrip(
                TripEntity(
                    tripId = memento.tripId.value,
                    truckId = memento.truckId.value,
                    updatedAt = System.currentTimeMillis(),
                    overall = "",
                    dirty = true,
                    mementoJson = json,
                ),
                compartments = memento.compartments.map { c ->
                    CompartmentEntity(
                        tripId = memento.tripId.value,
                        code = c.code.value,
                        farm = c.farm.value,
                        farmBatch = c.farmBatch.value,
                        loadedAt = c.loadedAt.toString(),
                        status = c.status.name,
                        recommendation = "",
                        unloadGroupId = c.unloadGroupId,
                    )
                },
                seals = memento.compartments.mapNotNull { c ->
                    c.seal?.let { s ->
                        SealEntity(
                            tripId = memento.tripId.value,
                            compartment = c.code.value,
                            expectedSeal = s.expected.value,
                            nfcSeal = s.nfc?.value,
                            nfcTag = s.nfcTag?.value,
                            writtenSeal = s.written?.value,
                            illegible = s.writtenIllegible,
                            checkedBy = s.checkedBy.value,
                            checkedAt = s.checkedAt.toString(),
                        )
                    }
                },
                samples = memento.compartments.flatMap { c ->
                    c.samples.map { s -> s.toEntity(memento.tripId.value) }
                } + memento.compositeSamples.values.map { it.toEntity(memento.tripId.value) },
            )
        }
    }

    override fun load(tripId: String): TripMemento? =
        kotlinx.coroutines.runBlocking { db.tripDao().getTrip(tripId) }
            ?.let { MementoCodec.decode(it.mementoJson) }

    override fun listOpenTrips(): List<String> =
        kotlinx.coroutines.runBlocking { db.tripDao().observeTrips() }
            .let { emptyList() } // 列表在 UI 层用 Flow；端口方法仅为测试提供

    override fun delete(tripId: String) {
        kotlinx.coroutines.runBlocking { db.tripDao().deleteTrip(tripId) }
    }

    private fun com.dairy.receiving.core.model.Sample.toEntity(tripId: String) = SampleEntity(
        sampleId = id.value,
        tripId = tripId,
        bottleTag = bottleTag.value,
        kind = kind.name,
        sourceCompartments = sourceCompartments.joinToString(",") { it.value },
        depthCm = depthCm,
        stirringSeconds = stirringSeconds,
        weightG = weight?.grams,
        weightStable = weight?.stable == true,
        weightDeviceId = weight?.deviceId,
        takenAt = takenAt.toString(),
        takenBy = takenBy.value,
    )
}
